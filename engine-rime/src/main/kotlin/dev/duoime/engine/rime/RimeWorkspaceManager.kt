// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.engine.rime

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A single streamed file entry supplied to [RimeWorkspaceManager] from a `.zip` archive or SAF
 * document tree.
 */
public data class RimeImportEntry(
    public val relativePath: String,
    public val sizeBytes: Long = -1L,
    public val openStream: () -> InputStream
)

/**
 * Structured failure reasons for Rime workspace import, deploy, and reset operations.
 */
public enum class RimeImportFailureReason {
    EMPTY_ARCHIVE,
    NO_YAML_FILES,
    TOO_MANY_FILES,
    FILE_TOO_LARGE,
    TOTAL_SIZE_TOO_LARGE,
    UNSAFE_PATH,
    FORBIDDEN_FILE_TYPE,
    INVALID_YAML,
    IO_ERROR,
    DEPLOY_FAILED,
    MISSING_IMPORTED_SCHEMA
}

/**
 * Result of Phase 1 staging into an isolated temporary directory (before touching `userDir`).
 */
public sealed interface RimeStagingResult {
    public data class Success(
        public val stagedDir: File,
        public val fileCount: Int,
        public val totalBytes: Long,
        public val discoveredCustomSchemaIds: List<String>,
        public val synthesizedDefaultCustom: Boolean,
        public val hasUserDefaultCustom: Boolean = false
    ) : RimeStagingResult

    public data class Failure(
        public val reason: RimeImportFailureReason,
        public val detail: String
    ) : RimeStagingResult
}

/**
 * Result of a full two-phase import or workspace reset transaction.
 */
public sealed interface RimeImportResult {
    public data class Success(
        public val schemas: List<RimeSchemaInfo>,
        public val importedCustomSchemaIds: List<String>,
        public val fileCount: Int,
        public val totalBytes: Long
    ) : RimeImportResult

    public data class Failure(
        public val reason: RimeImportFailureReason,
        public val detail: String,
        public val rolledBack: Boolean
    ) : RimeImportResult
}

/**
 * Pure-JVM two-phase Rime workspace manager providing safe streamed staging, security/hygiene
 * guards, managed `default.custom.yaml` overlay synthesis, atomic backup/swap into `userDir`,
 * automatic rollback + re-deploy on failure, starter workspace reset, and fast disk schema
 * discovery.
 */
public object RimeWorkspaceManager {
    public const val MAX_IMPORT_FILES: Int = 2_000
    public const val MAX_SINGLE_FILE_BYTES: Long = 64L * 1024L * 1024L
    public const val MAX_TOTAL_IMPORT_BYTES: Long = 200L * 1024L * 1024L

    internal const val WORKSPACE_TX_MARKER_FILE: String = ".workspace_tx_in_progress"

    private const val COPY_BUFFER_BYTES: Int = 16 * 1024
    private const val USER_YAML_FILE: String = "user.yaml"
    private const val BUILD_DIR_NAME: String = "build"

    private val IGNORED_DIR_SEGMENTS: Set<String> = setOf(
        "__MACOSX",
        ".git",
        ".svn",
        ".hg"
    )

    private val IGNORED_FILE_NAMES: Set<String> = setOf(
        ".ds_store",
        "thumbs.db",
        "desktop.ini"
    )

    // `.lua` is intentionally permitted for `librime-lua` schema scripts (e.g. rime-ice / wanxiang /
    // moran) running offline inside DuoIME's zero-INTERNET app-private sandbox, while native
    // binaries, JVM/Android packages, and OS shell scripts remain blocked.
    private val FORBIDDEN_EXTENSIONS: Set<String> = setOf(
        "so", "a", "o", "dex", "odex", "vdex", "art", "apk", "jar", "class",
        "exe", "dll", "dylib", "sh", "bat", "cmd", "ps1", "elf", "com", "msi", "vbs", "js"
    )

    private val STANDARD_USER_DIR_ENTRIES: Set<String> = setOf(
        "user.yaml",
        "build",
        "installation.yaml",
        ".deploy_pending",
        "trash"
    )

    /**
     * Phase 1: validates and streams [entries] into an empty [stageDir], enforces size/path/type
     * bounds, unwraps a single top-level wrapper folder if present, validates staged YAML, and
     * applies DuoIME's managed `default.custom.yaml` overlay & `config_version` protection.
     */
    public fun stageEntries(entries: Sequence<RimeImportEntry>, stageDir: File): RimeStagingResult {
        if (stageDir.exists()) {
            stageDir.deleteRecursively()
        }
        if (!stageDir.mkdirs()) {
            return RimeStagingResult.Failure(RimeImportFailureReason.IO_ERROR, "Failed to create staging directory")
        }

        var fileCount = 0
        var totalBytes = 0L
        val buffer = ByteArray(COPY_BUFFER_BYTES)

        for (entry in entries) {
            val normalized = sanitizeRelativePath(entry.relativePath)
            if (normalized is PathCheck.Unsafe) {
                return RimeStagingResult.Failure(RimeImportFailureReason.UNSAFE_PATH, normalized.reason)
            }
            if (normalized is PathCheck.Skip) {
                continue
            }
            val relPath = (normalized as PathCheck.Accept).relativePath
            val forbiddenExt = findForbiddenExtension(relPath)
            if (forbiddenExt != null) {
                return RimeStagingResult.Failure(
                    RimeImportFailureReason.FORBIDDEN_FILE_TYPE,
                    "Disallowed file type '.$forbiddenExt': $relPath"
                )
            }
            if (fileCount >= MAX_IMPORT_FILES) {
                return RimeStagingResult.Failure(
                    RimeImportFailureReason.TOO_MANY_FILES,
                    "Import exceeds maximum of $MAX_IMPORT_FILES files"
                )
            }
            if (entry.sizeBytes > MAX_SINGLE_FILE_BYTES) {
                return RimeStagingResult.Failure(
                    RimeImportFailureReason.FILE_TOO_LARGE,
                    "File exceeds 64 MiB limit: $relPath"
                )
            }
            val streamResult = copyEntryBounded(entry, stageDir, relPath, totalBytes, buffer)
            if (streamResult is StreamOutcome.Failure) {
                return RimeStagingResult.Failure(streamResult.reason, streamResult.detail)
            }
            fileCount++
            totalBytes += (streamResult as StreamOutcome.Copied).bytesWritten
        }

        if (fileCount == 0) {
            return RimeStagingResult.Failure(RimeImportFailureReason.EMPTY_ARCHIVE, "No importable files found")
        }
        return finalizeStagedDirectory(stageDir, fileCount, totalBytes)
    }

    private fun finalizeStagedDirectory(stageDir: File, fileCount: Int, totalBytes: Long): RimeStagingResult {
        unwrapSingleTopDirectory(stageDir)
        stripStaleBuildDirectory(stageDir)

        val protectedOverride = findProtectedIncognitoOverride(stageDir)
        if (protectedOverride != null) {
            return RimeStagingResult.Failure(
                RimeImportFailureReason.UNSAFE_PATH,
                "Cannot override protected built-in incognito schema: $protectedOverride"
            )
        }
        if (!containsAnyYamlFile(stageDir)) {
            return RimeStagingResult.Failure(
                RimeImportFailureReason.NO_YAML_FILES,
                "Import contains no Rime .yaml configuration files"
            )
        }
        val yamlError = RimeYamlOverlay.validateStagedYamlFiles(stageDir)
        if (yamlError != null) {
            return RimeStagingResult.Failure(RimeImportFailureReason.INVALID_YAML, yamlError)
        }

        val overlay = RimeYamlOverlay.applyManagedOverlay(stageDir)
        return RimeStagingResult.Success(
            stagedDir = stageDir,
            fileCount = fileCount,
            totalBytes = totalBytes,
            discoveredCustomSchemaIds = overlay.discoveredCustomSchemaIds,
            synthesizedDefaultCustom = overlay.synthesizedDefaultCustom,
            hasUserDefaultCustom = overlay.hasUserDefaultCustom
        )
    }

    /**
     * Two-phase transactional import: stages [entries] into a sibling directory under
     * `workspaceRoot`, swaps atomically into `paths.userDir`, wipes + recreates `stagingDir` with
     * `.deploy_pending`, deploys via [deployer], and automatically rolls back + re-deploys the
     * previous `userDir` if deployment fails, returns empty, or omits expected imported schemas.
     */
    @Suppress("TooGenericExceptionCaught")
    public fun importEntries(
        paths: RimeWorkspacePaths,
        entries: Sequence<RimeImportEntry>,
        deployer: RimeWorkspaceDeployer
    ): RimeImportResult {
        val workspaceRoot = paths.userDir.parentFile
            ?: return RimeImportResult.Failure(RimeImportFailureReason.IO_ERROR, "Invalid userDir", rolledBack = false)
        val stageDir = File(workspaceRoot, ".import_stage_${System.nanoTime()}")

        return try {
            val staged = stageEntries(entries, stageDir)
            if (staged is RimeStagingResult.Failure) {
                return RimeImportResult.Failure(staged.reason, staged.detail, rolledBack = false)
            }
            commitStagedWorkspace(paths, staged as RimeStagingResult.Success, deployer)
        } catch (err: Throwable) {
            RimeImportResult.Failure(
                reason = RimeImportFailureReason.IO_ERROR,
                detail = err.message ?: err::class.simpleName.orEmpty(),
                rolledBack = false
            )
        } finally {
            if (stageDir.exists()) {
                stageDir.deleteRecursively()
            }
        }
    }

    /**
     * Phase 2 of a two-phase import: commits an already-staged directory (produced by
     * [stageEntries] on `Dispatchers.IO`) into `paths.userDir` with atomic backup/swap, wipes
     * `stagingDir` + writes `.deploy_pending`, deploys via [deployer], and rolls back + re-deploys
     * automatically on failure.
     */
    @Suppress("TooGenericExceptionCaught")
    public fun commitStagedWorkspace(
        paths: RimeWorkspacePaths,
        staged: RimeStagingResult.Success,
        deployer: RimeWorkspaceDeployer
    ): RimeImportResult {
        val workspaceRoot = paths.userDir.parentFile
            ?: return RimeImportResult.Failure(RimeImportFailureReason.IO_ERROR, "Invalid userDir", rolledBack = false)
        val backupDir = File(workspaceRoot, ".import_backup_${System.nanoTime()}")
        return try {
            preserveUserRuntimeState(paths.userDir, staged.stagedDir)
            commitSwapAndDeploy(paths, staged.stagedDir, backupDir, staged, deployer)
        } catch (err: Throwable) {
            val rolledBack = if (backupDir.exists()) {
                rollbackUserDirAndRedeploy(paths, backupDir, deployer, "rollback")
            } else {
                false
            }
            RimeImportResult.Failure(
                reason = RimeImportFailureReason.IO_ERROR,
                detail = err.message ?: err::class.simpleName.orEmpty(),
                rolledBack = rolledBack
            )
        } finally {
            if (staged.stagedDir.exists()) {
                staged.stagedDir.deleteRecursively()
            }
        }
    }

    /**
     * Resets `paths.userDir` back to a clean starter workspace (preserving a fresh minimal
     * `user.yaml`), wipes + recreates `stagingDir` + `.deploy_pending`, and re-deploys via
     * [deployer] with automatic rollback if deploy fails.
     */
    @Suppress("TooGenericExceptionCaught")
    public fun resetToStarterWorkspace(paths: RimeWorkspacePaths, deployer: RimeWorkspaceDeployer): RimeImportResult {
        val workspaceRoot = paths.userDir.parentFile
            ?: return RimeImportResult.Failure(RimeImportFailureReason.IO_ERROR, "Invalid userDir", rolledBack = false)
        val token = System.nanoTime()
        val freshDir = File(workspaceRoot, ".reset_stage_$token")
        val backupDir = File(workspaceRoot, ".reset_backup_$token")

        return try {
            freshDir.mkdirs()
            RimeWorkspaceInstaller.ensureMinimalUserYaml(freshDir)
            swapIntoUserDir(paths.userDir, freshDir, backupDir)
            RimeWorkspaceInstaller.prepareStagingForRedeploy(paths, "reset")
            val deployed = runCatching { deployer.deployAndListSchemas() }.getOrNull()
            if (deployed.isNullOrEmpty()) {
                val rolledBack = rollbackUserDirAndRedeploy(paths, backupDir, deployer, "reset_rollback")
                RimeImportResult.Failure(
                    reason = RimeImportFailureReason.DEPLOY_FAILED,
                    detail = "Starter workspace re-deploy failed",
                    rolledBack = rolledBack
                )
            } else {
                backupDir.deleteRecursively()
                deleteTxMarker(workspaceRoot)
                RimeImportResult.Success(
                    schemas = deployed,
                    importedCustomSchemaIds = emptyList(),
                    fileCount = 0,
                    totalBytes = 0L
                )
            }
        } catch (err: Throwable) {
            val rolledBack = if (backupDir.exists()) {
                rollbackUserDirAndRedeploy(paths, backupDir, deployer, "reset_rollback")
            } else {
                false
            }
            RimeImportResult.Failure(
                reason = RimeImportFailureReason.IO_ERROR,
                detail = err.message ?: err::class.simpleName.orEmpty(),
                rolledBack = rolledBack
            )
        } finally {
            if (freshDir.exists()) {
                freshDir.deleteRecursively()
            }
        }
    }

    /**
     * Prepares `stagingDir` for a forced recompile (wiping `userDir/build` + writing
     * `.deploy_pending`) and invokes [deployer].
     */
    public fun redeployWorkspace(paths: RimeWorkspacePaths, deployer: RimeWorkspaceDeployer): List<RimeSchemaInfo>? {
        RimeWorkspaceInstaller.prepareStagingForRedeploy(paths, "manual_redeploy")
        return deployer.deployAndListSchemas()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Fast pure-JVM disk scan discovering installed schemas from `sharedDir` and `userDir`
     * without starting `librime`.
     */
    public fun discoverInstalledSchemasFromDisk(
        paths: RimeWorkspacePaths,
        includeIncognito: Boolean = false
    ): List<RimeSchemaInfo> {
        val byId = LinkedHashMap<String, RimeSchemaInfo>()
        val orderedDirs = listOf(paths.sharedDir, paths.userDir)
        for (dir in orderedDirs) {
            for (file in RimeYamlOverlay.listTopLevelSchemaFiles(dir)) {
                val parsed = RimeYamlOverlay.parseSchemaYaml(file) ?: continue
                if (!includeIncognito && parsed.id.endsWith(RimeEngineMetadata.INCOGNITO_SUFFIX)) {
                    continue
                }
                byId[parsed.id] = RimeSchemaInfo(
                    id = parsed.id,
                    name = parsed.name,
                    isBuiltIn = parsed.id in RimeEngineMetadata.ALL_BUILT_IN_SCHEMA_IDS
                )
            }
        }
        val starterOrder = RimeEngineMetadata.BUILT_IN_SCHEMA_IDS.toList()
        val builtIns = starterOrder.mapNotNull { byId[it] } +
            byId.values.filter { it.isBuiltIn && it.id !in starterOrder }.sortedBy { it.id }
        val custom = byId.values.filter { !it.isBuiltIn }.sortedBy { it.id }
        return builtIns + custom
    }

    /**
     * Returns `true` if a directory name (such as `.git` or `__MACOSX`) is ignored during Rime
     * archive and SAF folder imports.
     */
    public fun isIgnoredImportDirectory(name: String): Boolean = name in IGNORED_DIR_SEGMENTS

    /**
     * Returns `true` if a file name (such as `.DS_Store`, `Thumbs.db`, or AppleDouble `._*`)
     * is ignored during Rime archive and SAF folder imports.
     */
    public fun isIgnoredImportFile(name: String): Boolean =
        name.lowercase() in IGNORED_FILE_NAMES || name.startsWith("._")

    /**
     * Returns `true` if `paths.userDir` contains user-imported configuration/schema/dictionary
     * files beyond DuoIME's standard runtime state (`user.yaml`, `build/`, `*.userdb*`).
     */
    public fun hasCustomUserFiles(paths: RimeWorkspacePaths): Boolean {
        val children = paths.userDir.listFiles() ?: return false
        return children.any { child ->
            val lower = child.name.lowercase()
            lower !in STANDARD_USER_DIR_ENTRIES &&
                !lower.endsWith(".userdb") &&
                !lower.endsWith(".userdb.txt")
        }
    }

    /**
     * Checks for a `.workspace_tx_in_progress` marker left behind if a previous import or reset
     * crashed mid-deploy (for example, native `SIGSEGV` or process kill). When a valid backup
     * directory is referenced, atomically restores it over `userDir`, prepares `stagingDir` for a
     * clean re-deploy, cleans up stale sibling stage directories, and deletes the marker.
     */
    internal fun recoverInterruptedTransactionIfNeeded(workspaceRoot: File, userDir: File, stagingDir: File): Boolean =
        WorkspaceTxRecovery.recoverInterruptedTransactionIfNeeded(
            workspaceRoot = workspaceRoot,
            userDir = userDir,
            stagingDir = stagingDir,
            moveDirectory = ::moveDirectory
        )

    private fun commitSwapAndDeploy(
        paths: RimeWorkspacePaths,
        stageDir: File,
        backupDir: File,
        staged: RimeStagingResult.Success,
        deployer: RimeWorkspaceDeployer
    ): RimeImportResult {
        swapIntoUserDir(paths.userDir, stageDir, backupDir)
        RimeWorkspaceInstaller.prepareStagingForRedeploy(paths, "import")

        val deployOutcome = runCatching { deployer.deployAndListSchemas() }
        val deployedSchemas = deployOutcome.getOrNull()
        val failure = validateDeployedSchemas(deployedSchemas, staged, deployOutcome.exceptionOrNull())
        if (failure != null) {
            val rolledBack = rollbackUserDirAndRedeploy(paths, backupDir, deployer, "rollback")
            return RimeImportResult.Failure(
                reason = failure.first,
                detail = failure.second,
                rolledBack = rolledBack
            )
        }

        backupDir.deleteRecursively()
        deleteTxMarker(paths.userDir.parentFile)
        val finalSchemas = deployedSchemas!!
        val deployedIds = finalSchemas.map { it.id }.toSet()
        val activeCustomIds = staged.discoveredCustomSchemaIds.filter { it in deployedIds }
            .ifEmpty { finalSchemas.filter { !it.isBuiltIn }.map { it.id } }
        return RimeImportResult.Success(
            schemas = finalSchemas,
            importedCustomSchemaIds = activeCustomIds,
            fileCount = staged.fileCount,
            totalBytes = staged.totalBytes
        )
    }

    private fun validateDeployedSchemas(
        deployed: List<RimeSchemaInfo>?,
        staged: RimeStagingResult.Success,
        deployError: Throwable?
    ): Pair<RimeImportFailureReason, String>? {
        if (deployError != null) {
            return RimeImportFailureReason.DEPLOY_FAILED to
                (deployError.message ?: deployError::class.simpleName.orEmpty())
        }
        if (deployed.isNullOrEmpty()) {
            return RimeImportFailureReason.DEPLOY_FAILED to "Rime deploy failed or produced zero schemas"
        }
        if (!staged.hasUserDefaultCustom && staged.discoveredCustomSchemaIds.isNotEmpty()) {
            val deployedIds = deployed.map { it.id }.toSet()
            val missing = staged.discoveredCustomSchemaIds.filter { it !in deployedIds }
            if (missing.isNotEmpty()) {
                return RimeImportFailureReason.MISSING_IMPORTED_SCHEMA to
                    "Imported schemas did not deploy cleanly: ${missing.joinToString(", ")}"
            }
        }
        return null
    }

    private fun swapIntoUserDir(userDir: File, stageDir: File, backupDir: File) {
        val workspaceRoot = userDir.parentFile
        if (backupDir.exists()) {
            backupDir.deleteRecursively()
        }
        if (userDir.exists()) {
            moveDirectory(userDir, backupDir)
        } else {
            backupDir.mkdirs()
        }
        WorkspaceTxRecovery.writeMarker(workspaceRoot, backupDir.name)
        try {
            moveDirectory(stageDir, userDir)
        } catch (err: IOException) {
            if (!userDir.exists() && backupDir.exists()) {
                val restored = runCatching { moveDirectory(backupDir, userDir) }.isSuccess
                if (restored) {
                    deleteTxMarker(workspaceRoot)
                }
            }
            throw err
        }
    }

    private fun rollbackUserDirAndRedeploy(
        paths: RimeWorkspacePaths,
        backupDir: File,
        deployer: RimeWorkspaceDeployer,
        reason: String
    ): Boolean {
        val restored = runCatching {
            if (paths.userDir.exists()) {
                paths.userDir.deleteRecursively()
            }
            if (backupDir.exists()) {
                moveDirectory(backupDir, paths.userDir)
            } else {
                paths.userDir.mkdirs()
                RimeWorkspaceInstaller.ensureMinimalUserYaml(paths.userDir)
            }
            RimeWorkspaceInstaller.prepareStagingForRedeploy(paths, reason)
            true
        }.getOrDefault(false)
        if (restored) {
            deleteTxMarker(paths.userDir.parentFile)
            runCatching { deployer.deployAndListSchemas() }
        }
        return restored
    }

    private fun deleteTxMarker(workspaceRoot: File?) {
        WorkspaceTxRecovery.deleteMarker(workspaceRoot)
    }

    private fun moveDirectory(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return
        } catch (_: IOException) {
            // Fall back to renameTo / recursive copy+delete if atomic move isn't supported.
        }
        if (source.renameTo(target)) return
        if (!source.copyRecursively(target, overwrite = true)) {
            throw IOException("Failed to move directory ${source.name} -> ${target.name}")
        }
        source.deleteRecursively()
    }

    private fun preserveUserRuntimeState(existingUserDir: File, stageDir: File) {
        val stagedUserYaml = File(stageDir, USER_YAML_FILE)
        if (!stagedUserYaml.isFile) {
            val existingUserYaml = File(existingUserDir, USER_YAML_FILE)
            if (existingUserYaml.isFile) {
                existingUserYaml.copyTo(stagedUserYaml, overwrite = true)
            } else {
                RimeWorkspaceInstaller.ensureMinimalUserYaml(stageDir)
            }
        }
        val existingEntries = existingUserDir.listFiles().orEmpty()
        for (child in existingEntries) {
            val lower = child.name.lowercase()
            val isUserDb = (child.isDirectory && lower.endsWith(".userdb")) ||
                (child.isFile && lower.endsWith(".userdb.txt"))
            if (!isUserDb) continue
            val target = File(stageDir, child.name)
            if (!target.exists()) {
                child.copyRecursively(target, overwrite = false)
            }
        }
    }

    private sealed interface PathCheck {
        data class Accept(val relativePath: String) : PathCheck

        data object Skip : PathCheck

        data class Unsafe(val reason: String) : PathCheck
    }

    private fun sanitizeRelativePath(rawPath: String): PathCheck {
        if (rawPath.isEmpty()) return PathCheck.Skip
        if (rawPath.indexOf('\u0000') >= 0 || rawPath.contains('\\')) {
            return PathCheck.Unsafe("Unsafe path separator or NUL byte: $rawPath")
        }
        if (rawPath.startsWith('/')) {
            return PathCheck.Unsafe("Absolute path is not allowed: $rawPath")
        }
        if (rawPath.endsWith('/')) {
            return PathCheck.Skip
        }
        val segments = rawPath.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            return PathCheck.Unsafe("Invalid path segment in: $rawPath")
        }
        if (segments.first().contains(':')) {
            return PathCheck.Unsafe("Drive prefix is not allowed: $rawPath")
        }
        if (segments.any(::isIgnoredImportDirectory)) {
            return PathCheck.Skip
        }
        if (isIgnoredImportFile(segments.last())) {
            return PathCheck.Skip
        }
        return PathCheck.Accept(segments.joinToString("/"))
    }

    private fun findForbiddenExtension(relativePath: String): String? {
        val fileName = relativePath.substringAfterLast('/')
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.lastIndex) return null
        val ext = fileName.substring(dot + 1).lowercase()
        return ext.takeIf { it in FORBIDDEN_EXTENSIONS }
    }

    private sealed interface StreamOutcome {
        data class Copied(val bytesWritten: Long) : StreamOutcome

        data class Failure(val reason: RimeImportFailureReason, val detail: String) : StreamOutcome
    }

    @Suppress("TooGenericExceptionCaught")
    private fun copyEntryBounded(
        entry: RimeImportEntry,
        stageDir: File,
        relPath: String,
        currentTotalBytes: Long,
        buffer: ByteArray
    ): StreamOutcome {
        val canonicalRoot = stageDir.canonicalFile
        val target = File(canonicalRoot, relPath).canonicalFile
        if (!target.path.startsWith(canonicalRoot.path + File.separator)) {
            return StreamOutcome.Failure(RimeImportFailureReason.UNSAFE_PATH, "Path escapes staging root: $relPath")
        }
        target.parentFile?.mkdirs()

        return try {
            entry.openStream().use { input ->
                target.outputStream().use { output ->
                    streamWithBounds(input, output, relPath, currentTotalBytes, buffer)
                }
            }
        } catch (err: Throwable) {
            StreamOutcome.Failure(
                RimeImportFailureReason.IO_ERROR,
                "Failed reading $relPath: ${err.message ?: err::class.simpleName.orEmpty()}"
            )
        }
    }

    private fun streamWithBounds(
        input: InputStream,
        output: java.io.OutputStream,
        relPath: String,
        currentTotalBytes: Long,
        buffer: ByteArray
    ): StreamOutcome {
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            written += read
            if (written > MAX_SINGLE_FILE_BYTES) {
                return StreamOutcome.Failure(
                    RimeImportFailureReason.FILE_TOO_LARGE,
                    "File exceeds 64 MiB limit while streaming: $relPath"
                )
            }
            if (currentTotalBytes + written > MAX_TOTAL_IMPORT_BYTES) {
                return StreamOutcome.Failure(
                    RimeImportFailureReason.TOTAL_SIZE_TOO_LARGE,
                    "Import exceeds 200 MiB total limit"
                )
            }
            output.write(buffer, 0, read)
        }
        return StreamOutcome.Copied(written)
    }

    private fun unwrapSingleTopDirectory(stageDir: File) {
        val topEntries = stageDir.listFiles().orEmpty()
        if (topEntries.size != 1 || !topEntries[0].isDirectory) return
        val wrapper = topEntries[0]
        val tempWrapper = File(stageDir.parentFile, "${stageDir.name}_unwrap_${System.nanoTime()}")
        moveDirectory(wrapper, tempWrapper)
        try {
            for (child in tempWrapper.listFiles().orEmpty()) {
                val dest = File(stageDir, child.name)
                if (child.isDirectory) {
                    moveDirectory(child, dest)
                } else {
                    Files.move(child.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } finally {
            tempWrapper.deleteRecursively()
        }
    }

    private fun stripStaleBuildDirectory(stageDir: File) {
        val buildDir = File(stageDir, BUILD_DIR_NAME)
        if (buildDir.exists()) {
            buildDir.deleteRecursively()
        }
    }

    private fun findProtectedIncognitoOverride(stageDir: File): String? {
        val protectedIds = RimeEngineMetadata.BUILT_IN_INCOGNITO_SCHEMA_IDS.mapTo(HashSet()) { it.lowercase() }
        val topFiles = stageDir.listFiles()?.filter { it.isFile }.orEmpty()
        for (file in topFiles) {
            val lower = file.name.lowercase()
            val matchesProtectedName = protectedIds.any { id ->
                lower == "$id.schema.yaml" || lower == "$id.custom.yaml" || lower == "$id.yaml"
            }
            if (matchesProtectedName) return file.name
            if (lower.endsWith(".schema.yaml")) {
                val parsedId = RimeYamlOverlay.parseSchemaYaml(file)?.id?.lowercase()
                if (parsedId != null && parsedId in protectedIds) {
                    return "${file.name} (schema_id: $parsedId)"
                }
            }
        }
        return null
    }

    private fun containsAnyYamlFile(dir: File): Boolean = dir.walkTopDown().any { file ->
        file.isFile &&
            (file.name.endsWith(".yaml", ignoreCase = true) || file.name.endsWith(".yml", ignoreCase = true))
    }
}

private object WorkspaceTxRecovery {
    private const val IMPORT_BACKUP_PREFIX: String = ".import_backup_"
    private const val RESET_BACKUP_PREFIX: String = ".reset_backup_"
    private val STALE_STAGE_PREFIXES: List<String> = listOf(
        ".import_stage_",
        ".reset_stage_",
        ".saf_stage_"
    )

    fun writeMarker(workspaceRoot: File?, backupDirName: String) {
        if (workspaceRoot == null) return
        File(workspaceRoot, RimeWorkspaceManager.WORKSPACE_TX_MARKER_FILE)
            .writeText("$backupDirName\n", Charsets.UTF_8)
    }

    fun deleteMarker(workspaceRoot: File?) {
        if (workspaceRoot == null) return
        val marker = File(workspaceRoot, RimeWorkspaceManager.WORKSPACE_TX_MARKER_FILE)
        if (marker.exists()) {
            marker.deleteRecursively()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun recoverInterruptedTransactionIfNeeded(
        workspaceRoot: File,
        userDir: File,
        stagingDir: File,
        moveDirectory: (File, File) -> Unit
    ): Boolean {
        val marker = File(workspaceRoot, RimeWorkspaceManager.WORKSPACE_TX_MARKER_FILE)
        if (!marker.exists()) return false

        return try {
            val backupName = runCatching { marker.readText(Charsets.UTF_8).trim() }.getOrNull().orEmpty()
            val backupDir = resolveValidatedBackupDir(workspaceRoot, backupName) ?: return false
            if (userDir.exists()) {
                userDir.deleteRecursively()
            }
            moveDirectory(backupDir, userDir)
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
            stagingDir.mkdirs()
            File(stagingDir, RimeWorkspaceInstaller.DEPLOY_PENDING_FILE)
                .writeText("crash_recovery\n", Charsets.UTF_8)
            cleanupStaleStageDirs(workspaceRoot)
            true
        } catch (_: Throwable) {
            false
        } finally {
            if (marker.exists()) {
                marker.deleteRecursively()
            }
        }
    }

    private fun resolveValidatedBackupDir(workspaceRoot: File, backupName: String): File? {
        if (!isValidBackupDirName(backupName)) return null
        val backupDir = File(workspaceRoot, backupName)
        if (!backupDir.isDirectory || Files.isSymbolicLink(backupDir.toPath())) return null
        val canonicalRoot = workspaceRoot.canonicalFile
        val canonicalBackup = backupDir.canonicalFile
        return backupDir.takeIf { canonicalBackup.parentFile == canonicalRoot }
    }

    private fun isValidBackupDirName(name: String): Boolean {
        if (name.isEmpty() || name == "." || name.contains("..")) return false
        if (name.contains('/') || name.contains('\\') || name.indexOf('\u0000') >= 0) return false
        return when {
            name.startsWith(IMPORT_BACKUP_PREFIX) -> name.length > IMPORT_BACKUP_PREFIX.length
            name.startsWith(RESET_BACKUP_PREFIX) -> name.length > RESET_BACKUP_PREFIX.length
            else -> false
        }
    }

    private fun cleanupStaleStageDirs(workspaceRoot: File) {
        for (sibling in workspaceRoot.listFiles().orEmpty()) {
            val isStaleStage = STALE_STAGE_PREFIXES.any { sibling.name.startsWith(it) }
            if (isStaleStage && sibling.isDirectory && !Files.isSymbolicLink(sibling.toPath())) {
                sibling.deleteRecursively()
            }
        }
    }
}

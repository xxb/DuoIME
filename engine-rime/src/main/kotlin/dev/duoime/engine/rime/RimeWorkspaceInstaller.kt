// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.engine.rime

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * App-private directory layout passed into `librime` `RimeTraits`.
 */
public data class RimeWorkspacePaths(
    public val sharedDir: File,
    public val userDir: File,
    public val prebuiltDir: File,
    public val stagingDir: File,
    public val assetsUpdated: Boolean = false
)

internal interface RimeAssetSource {
    fun list(relativeDir: String): List<String>

    fun open(relativePath: String): InputStream
}

private class AndroidRimeAssetSource(
    private val assetManager: AssetManager,
    private val rootPath: String
) : RimeAssetSource {
    override fun list(relativeDir: String): List<String> {
        val fullPath = if (relativeDir.isEmpty()) rootPath else "$rootPath/$relativeDir"
        return assetManager.list(fullPath)?.sorted().orEmpty()
    }

    override fun open(relativePath: String): InputStream = assetManager.open("$rootPath/$relativePath")
}

/**
 * Extracts DuoIME's bundled permissive Rime starter assets (`assets/duoime/rime/`) into
 * app-private storage (`filesDir/duoime/rime/{shared,user}`) without touching existing user data.
 */
public object RimeWorkspaceInstaller {
    private const val WORKSPACE_SUBDIR: String = "duoime/rime"
    private const val FINGERPRINT_FILE: String = ".starter_fingerprint"
    internal const val DEPLOY_PENDING_FILE: String = ".deploy_pending"
    private const val STARTER_MANIFEST_FILE: String = "starter_manifest.json"
    private const val DEFAULT_YAML_FILE: String = "default.yaml"
    private const val STARTER_DICT_FILE: String = "duoime_pinyin.dict.yaml"
    private const val USER_YAML_FILE: String = "user.yaml"

    /**
     * Ensures starter Rime configs/dicts are unpacked into app-private storage and up-to-date.
     * Performs blocking disk I/O and must never be called on the Android main thread.
     */
    public fun installStarterAssets(
        context: Context,
        workspaceRoot: File = File(context.applicationContext.filesDir, WORKSPACE_SUBDIR)
    ): RimeWorkspacePaths {
        val source = AndroidRimeAssetSource(
            assetManager = context.applicationContext.assets,
            rootPath = RimeEngineMetadata.ASSET_ROOT_PATH
        )
        return installFromSource(source = source, workspaceRoot = workspaceRoot)
    }

    internal fun installFromSource(source: RimeAssetSource, workspaceRoot: File): RimeWorkspacePaths {
        val sharedDir = File(workspaceRoot, "shared")
        val userDir = File(workspaceRoot, "user")
        val prebuiltDir = File(sharedDir, "build")
        val stagingDir = File(userDir, "build")
        sharedDir.mkdirs()
        userDir.mkdirs()
        prebuiltDir.mkdirs()
        stagingDir.mkdirs()

        val assetFiles = collectRelativeFiles(source, "")
        require(assetFiles.isNotEmpty()) {
            "No bundled Rime starter assets found under '${RimeEngineMetadata.ASSET_ROOT_PATH}'"
        }

        val expectedFingerprint = computeFingerprint(source, assetFiles)
        val fingerprintFile = File(sharedDir, FINGERPRINT_FILE)
        val isUpToDate = fingerprintFile.isFile &&
            fingerprintFile.readText(Charsets.UTF_8).trim() == expectedFingerprint &&
            File(sharedDir, DEFAULT_YAML_FILE).isFile &&
            File(sharedDir, STARTER_DICT_FILE).isFile

        val deployPendingFile = File(stagingDir, DEPLOY_PENDING_FILE)
        if (!isUpToDate) {
            extractAssetFiles(source, sharedDir, assetFiles)
            fingerprintFile.writeText("$expectedFingerprint\n", Charsets.UTF_8)
            deployPendingFile.writeText("$expectedFingerprint\n", Charsets.UTF_8)
        }

        val userYaml = File(userDir, USER_YAML_FILE)
        if (!userYaml.exists()) {
            userYaml.writeText("# DuoIME Rime user state\nvar: {}\n", Charsets.UTF_8)
        }

        val needsDeploy = !isUpToDate ||
            deployPendingFile.isFile ||
            !File(stagingDir, DEFAULT_YAML_FILE).isFile

        return RimeWorkspacePaths(
            sharedDir = sharedDir,
            userDir = userDir,
            prebuiltDir = prebuiltDir,
            stagingDir = stagingDir,
            assetsUpdated = needsDeploy
        )
    }

    internal fun markWorkspaceDeployed(paths: RimeWorkspacePaths) {
        val pendingFile = File(paths.stagingDir, DEPLOY_PENDING_FILE)
        if (pendingFile.exists()) {
            pendingFile.delete()
        }
    }

    private fun extractAssetFiles(source: RimeAssetSource, sharedDir: File, assetFiles: List<String>) {
        for (relPath in assetFiles) {
            val target = resolveSafeChild(sharedDir, relPath)
            target.parentFile?.mkdirs()
            source.open(relPath).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun collectRelativeFiles(source: RimeAssetSource, dir: String): List<String> {
        val out = ArrayList<String>()
        val children = source.list(dir)
        for (name in children) {
            if (name.isEmpty() || name == "." || name == "..") continue
            val relPath = if (dir.isEmpty()) name else "$dir/$name"
            val subChildren = source.list(relPath)
            if (subChildren.isNotEmpty()) {
                out.addAll(collectRelativeFiles(source, relPath))
            } else {
                out.add(relPath)
            }
        }
        out.sort()
        return out
    }

    private fun computeFingerprint(source: RimeAssetSource, assetFiles: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (STARTER_MANIFEST_FILE in assetFiles) {
            source.open(STARTER_MANIFEST_FILE).use { input ->
                digest.update(input.readBytes())
            }
        } else {
            for (relPath in assetFiles) {
                digest.update(relPath.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                source.open(relPath).use { input ->
                    digest.update(input.readBytes())
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun resolveSafeChild(parent: File, relativePath: String): File {
        val canonicalParent = parent.canonicalFile
        val child = File(canonicalParent, relativePath).canonicalFile
        require(child.path.startsWith(canonicalParent.path + File.separator)) {
            "Unsafe asset path escapes workspace root: $relativePath"
        }
        return child
    }
}

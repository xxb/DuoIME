// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.engine.rime

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RimeWorkspaceManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createWorkspace(): RimeWorkspacePaths {
        val root = tempFolder.newFolder("rime_ws_${System.nanoTime()}")
        val shared = File(root, "shared").apply { mkdirs() }
        val user = File(root, "user").apply { mkdirs() }
        val prebuilt = File(shared, "build").apply { mkdirs() }
        val staging = File(user, "build").apply { mkdirs() }
        File(shared, "duoime_pinyin_26.schema.yaml").writeText(
            """
            schema:
              schema_id: duoime_pinyin_26
              name: "DuoIME 26键拼音"
            """.trimIndent()
        )
        File(shared, "duoime_t9.schema.yaml").writeText(
            """
            schema:
              schema_id: duoime_t9
              name: "DuoIME 9键拼音"
            """.trimIndent()
        )
        File(shared, "duoime_pinyin_26_incognito.schema.yaml").writeText(
            """
            schema:
              schema_id: duoime_pinyin_26_incognito
              name: "DuoIME 26键拼音 (无痕)"
            """.trimIndent()
        )
        File(user, "user.yaml").writeText("var:\n  previously_selected_schema: duoime_pinyin_26\n")
        return RimeWorkspacePaths(shared, user, prebuilt, staging)
    }

    private fun entry(path: String, text: String): RimeImportEntry {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return RimeImportEntry(path, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
    }

    private fun sampleSchemaYaml(id: String, name: String): String = """
        schema:
          schema_id: $id
          name: "$name"
        speller:
          alphabet: zyxwvutsrqponmlkjihgfedcba
          delimiter: " '"
          algebra:
            - xform/([nl])v/${'$'}1ü/
    """.trimIndent() + "\n"

    @Test
    fun caseAUnwrapsTopDirFiltersJunkStripsBuildAndSynthesizesDefaultCustom() {
        val paths = createWorkspace()
        val entries = sequenceOf(
            entry("__MACOSX/._rime_ice.schema.yaml", "junk"),
            entry("rime-ice-main/.DS_Store", "junk"),
            entry("rime-ice-main/build/stale.prism.bin", "stale"),
            entry("rime-ice-main/rime_ice.schema.yaml", sampleSchemaYaml("rime_ice", "雾凇拼音")),
            entry("rime-ice-main/rime_ice_incognito.schema.yaml", sampleSchemaYaml("rime_ice_incognito", "雾凇无痕")),
            entry("rime-ice-main/rime_ice.dict.yaml", "name: rime_ice\n...\n你好\tni hao\t100\n"),
            entry("rime-ice-main/lua/date_translator.lua", "return function() end\n")
        )

        var sawPendingDuringDeploy = false
        var sawTxMarkerDuringDeploy = false
        val txMarker = File(paths.userDir.parentFile, RimeWorkspaceManager.WORKSPACE_TX_MARKER_FILE)
        val result = RimeWorkspaceManager.importEntries(paths, entries) {
            sawPendingDuringDeploy = File(paths.stagingDir, RimeWorkspaceInstaller.DEPLOY_PENDING_FILE).isFile
            sawTxMarkerDuringDeploy = txMarker.isFile &&
                txMarker.readText(Charsets.UTF_8).trim().startsWith(".import_backup_")
            assertFalse(
                "Stale build artifact must be wiped before deploy",
                File(paths.stagingDir, "stale.prism.bin").exists()
            )
            listOf(
                RimeSchemaInfo("duoime_pinyin_26", "DuoIME 26键拼音", isBuiltIn = true),
                RimeSchemaInfo("rime_ice", "雾凇拼音", isBuiltIn = false)
            )
        }

        assertTrue(sawPendingDuringDeploy)
        assertTrue("Transaction marker must exist during native deploy", sawTxMarkerDuringDeploy)
        assertFalse("Successful import must delete transaction marker", txMarker.exists())
        assertTrue("Expected Success, got $result", result is RimeImportResult.Success)
        val success = result as RimeImportResult.Success
        assertEquals(listOf("rime_ice"), success.importedCustomSchemaIds)
        assertTrue(File(paths.userDir, "rime_ice.schema.yaml").isFile)
        assertTrue(File(paths.userDir, "lua/date_translator.lua").isFile)
        assertTrue(
            "Existing user.yaml must be preserved",
            File(paths.userDir, "user.yaml").readText().contains("previously_selected_schema")
        )
        val defaultCustom = File(paths.userDir, "default.custom.yaml")
        assertTrue("Case A must synthesize default.custom.yaml", defaultCustom.isFile)
        val customText = defaultCustom.readText()
        assertTrue(customText.contains("\"schema_list/+\":"))
        assertTrue(customText.contains("- schema: rime_ice"))
        assertTrue(customText.contains("- schema: rime_ice_incognito"))
        assertTrue(RimeWorkspaceManager.hasCustomUserFiles(paths))

        // Fast disk schema discovery finds both starter and custom schemas
        val discovered = RimeWorkspaceManager.discoverInstalledSchemasFromDisk(paths)
        assertEquals(listOf("duoime_pinyin_26", "duoime_t9", "rime_ice"), discovered.map { it.id })
    }

    @Test
    fun caseBBumpsDeprecatedConfigVersionAndDeduplicatesStarterSchemas() {
        val paths = createWorkspace()
        val userDefaultYaml = """
            # Upstream Rime default.yaml
            config_version: "0.40"
            schema_list:
              - schema: rime_ice
              - schema: duoime_pinyin_26
        """.trimIndent() + "\n"
        val entries = sequenceOf(
            entry("default.yaml", userDefaultYaml),
            entry("rime_ice.schema.yaml", sampleSchemaYaml("rime_ice", "雾凇拼音")),
            entry("extra_custom.schema.yaml", sampleSchemaYaml("extra_custom", "Extra Custom"))
        )

        val result = RimeWorkspaceManager.importEntries(paths, entries) {
            listOf(
                RimeSchemaInfo("duoime_pinyin_26", "DuoIME 26键拼音", isBuiltIn = true),
                RimeSchemaInfo("rime_ice", "雾凇拼音", isBuiltIn = false),
                RimeSchemaInfo("extra_custom", "Extra Custom", isBuiltIn = false)
            )
        }
        assertTrue("Expected Success, got $result", result is RimeImportResult.Success)
        val stagedDefault = File(paths.userDir, "default.yaml").readText()
        assertTrue(
            "config_version 0.40 must be bumped to 1.0 to prevent TrashDeprecatedUserCopy",
            stagedDefault.contains("config_version: \"1.0\"")
        )
        assertFalse(stagedDefault.contains("0.40"))

        val defaultCustomText = File(paths.userDir, "default.custom.yaml").readText()
        assertFalse(
            "Starter schema already listed in user default.yaml must be deduplicated",
            defaultCustomText.contains("- schema: duoime_pinyin_26\n")
        )
        assertTrue("Missing starter schemas must be appended", defaultCustomText.contains("- schema: duoime_t9"))
        assertTrue(defaultCustomText.contains("- schema: duoime_pinyin_26_incognito"))
        assertFalse(
            "Schema already in user default.yaml must not duplicate",
            defaultCustomText.contains("- schema: rime_ice")
        )
        assertTrue("Unreferenced custom schema must be appended", defaultCustomText.contains("- schema: extra_custom"))
    }

    @Test
    fun caseCKeepsDefaultCustomVerbatimAndGuardsDefaultYamlCustomVersionSuffix() {
        val paths = createWorkspace()
        val userDefaultCustom = """
            patch:
              schema_list:
                - schema: rime_ice
        """.trimIndent() + "\n"
        val userDefaultWithCustomSuffix = """
            config_version: "1.0.custom.123"
            schema_list:
              - schema: rime_ice
        """.trimIndent() + "\n"
        val entries = sequenceOf(
            entry("default.yaml", userDefaultWithCustomSuffix),
            entry("default.custom.yaml", userDefaultCustom),
            entry("rime_ice.schema.yaml", sampleSchemaYaml("rime_ice", "雾凇拼音"))
        )

        val result = RimeWorkspaceManager.importEntries(paths, entries) {
            listOf(RimeSchemaInfo("rime_ice", "雾凇拼音", isBuiltIn = false))
        }
        assertTrue("Expected Success, got $result", result is RimeImportResult.Success)
        assertEquals(userDefaultCustom, File(paths.userDir, "default.custom.yaml").readText())
        assertTrue(
            "config_version ending with .custom. must be sanitized to 1.0",
            File(paths.userDir, "default.yaml").readText().contains("config_version: \"1.0\"")
        )
    }

    @Test
    fun securityGuardsRejectTraversalExecutablesBrokenYamlMissingYamlAndOversizedStreams() {
        val stageDir = tempFolder.newFolder("stage_guards")

        // Path traversal / zip-slip
        val traversal = RimeWorkspaceManager.stageEntries(
            sequenceOf(entry("../escape.yaml", "a: b\n")),
            stageDir
        )
        assertEquals(RimeImportFailureReason.UNSAFE_PATH, (traversal as RimeStagingResult.Failure).reason)

        // Backslash path
        val backslash = RimeWorkspaceManager.stageEntries(
            sequenceOf(entry("sub\\evil.yaml", "a: b\n")),
            stageDir
        )
        assertEquals(RimeImportFailureReason.UNSAFE_PATH, (backslash as RimeStagingResult.Failure).reason)

        // Forbidden executable extension
        val exec = RimeWorkspaceManager.stageEntries(
            sequenceOf(
                entry("rime_ice.schema.yaml", sampleSchemaYaml("rime_ice", "Ice")),
                entry("libevil.so", "ELF")
            ),
            stageDir
        )
        assertEquals(RimeImportFailureReason.FORBIDDEN_FILE_TYPE, (exec as RimeStagingResult.Failure).reason)

        // No YAML files
        val noYaml = RimeWorkspaceManager.stageEntries(
            sequenceOf(entry("readme.txt", "hello")),
            stageDir
        )
        assertEquals(RimeImportFailureReason.NO_YAML_FILES, (noYaml as RimeStagingResult.Failure).reason)

        // Broken default.custom.yaml (unbalanced bracket)
        val brokenCustom = RimeWorkspaceManager.stageEntries(
            sequenceOf(entry("default.custom.yaml", "patch: [unclosed\n")),
            stageDir
        )
        assertEquals(RimeImportFailureReason.INVALID_YAML, (brokenCustom as RimeStagingResult.Failure).reason)

        // Tab indentation in schema YAML
        val tabYaml = RimeWorkspaceManager.stageEntries(
            sequenceOf(entry("bad.schema.yaml", "schema:\n\tschema_id: bad\n")),
            stageDir
        )
        assertEquals(RimeImportFailureReason.INVALID_YAML, (tabYaml as RimeStagingResult.Failure).reason)

        // Too many files (> 2000)
        val tooMany = RimeWorkspaceManager.stageEntries(
            (1..(RimeWorkspaceManager.MAX_IMPORT_FILES + 1)).asSequence().map { idx ->
                entry("f_$idx.txt", "x")
            },
            stageDir
        )
        assertEquals(RimeImportFailureReason.TOO_MANY_FILES, (tooMany as RimeStagingResult.Failure).reason)

        // Declared oversized file (> 64 MiB)
        val oversizedDeclared = RimeWorkspaceManager.stageEntries(
            sequenceOf(
                RimeImportEntry("huge.yaml", RimeWorkspaceManager.MAX_SINGLE_FILE_BYTES + 1L) {
                    ByteArrayInputStream(ByteArray(0))
                }
            ),
            stageDir
        )
        assertEquals(RimeImportFailureReason.FILE_TOO_LARGE, (oversizedDeclared as RimeStagingResult.Failure).reason)

        // Streamed zip-bomb with unknown size (-1) exceeding 64 MiB limit
        val bombEntry = RimeImportEntry("bomb.yaml", sizeBytes = -1L) {
            object : InputStream() {
                private var remaining = RimeWorkspaceManager.MAX_SINGLE_FILE_BYTES + 32_768L
                override fun read(): Int = if (remaining-- > 0) 'a'.code else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0L) return -1
                    val count = minOf(len.toLong(), remaining).toInt()
                    b.fill('a'.code.toByte(), off, off + count)
                    remaining -= count
                    return count
                }
            }
        }
        val oversizedStreamed = RimeWorkspaceManager.stageEntries(sequenceOf(bombEntry), stageDir)
        assertEquals(RimeImportFailureReason.FILE_TOO_LARGE, (oversizedStreamed as RimeStagingResult.Failure).reason)
    }

    @Test
    fun deployFailureAndMissingImportedSchemaAtomicallyRollbackAndRedeployPreviousWorkspace() {
        val paths = createWorkspace()
        val existingCustom = File(paths.userDir, "previous_keep.yaml").apply {
            writeText("keep: true\n")
        }

        // 1) Deploy returns null -> rollback restores previous_keep.yaml and triggers rollback redeploy
        var deployCalls = 0
        val failResult = RimeWorkspaceManager.importEntries(
            paths,
            sequenceOf(entry("broken_pack.schema.yaml", sampleSchemaYaml("broken_pack", "Broken")))
        ) {
            deployCalls++
            if (deployCalls == 1) {
                null
            } else {
                assertTrue("Rollback must restore previous files before re-deploy", existingCustom.isFile)
                assertTrue(
                    "Rollback must recreate .deploy_pending before re-deploy",
                    File(paths.stagingDir, RimeWorkspaceInstaller.DEPLOY_PENDING_FILE).isFile
                )
                listOf(RimeSchemaInfo("duoime_pinyin_26", "DuoIME 26键拼音", isBuiltIn = true))
            }
        }
        assertTrue(failResult is RimeImportResult.Failure)
        val failure = failResult as RimeImportResult.Failure
        assertEquals(RimeImportFailureReason.DEPLOY_FAILED, failure.reason)
        assertTrue(failure.rolledBack)
        assertEquals(2, deployCalls)
        assertTrue(existingCustom.isFile)
        assertFalse(File(paths.userDir, "broken_pack.schema.yaml").exists())

        // 2) Case A deploy succeeds for starters only (omitting expected imported schema) -> MISSING_IMPORTED_SCHEMA + rollback
        var missingCalls = 0
        val missingResult = RimeWorkspaceManager.importEntries(
            paths,
            sequenceOf(entry("ghost.schema.yaml", sampleSchemaYaml("ghost", "Ghost")))
        ) {
            missingCalls++
            listOf(RimeSchemaInfo("duoime_pinyin_26", "DuoIME 26键拼音", isBuiltIn = true))
        }
        assertTrue(missingResult is RimeImportResult.Failure)
        assertEquals(
            RimeImportFailureReason.MISSING_IMPORTED_SCHEMA,
            (missingResult as RimeImportResult.Failure).reason
        )
        assertTrue(missingResult.rolledBack)
        assertEquals(2, missingCalls)
        assertTrue(existingCustom.isFile)
        assertFalse(
            "In-process rollback must delete transaction marker",
            File(paths.userDir.parentFile, RimeWorkspaceManager.WORKSPACE_TX_MARKER_FILE).exists()
        )
    }

    @Test
    fun resetToStarterWorkspaceClearsCustomFilesAndRollsBackOnDeployFailure() {
        val paths = createWorkspace()
        File(paths.userDir, "rime_ice.schema.yaml").writeText(sampleSchemaYaml("rime_ice", "雾凇拼音"))
        assertTrue(RimeWorkspaceManager.hasCustomUserFiles(paths))

        // Failed deploy on reset rolls back custom files
        var failCalls = 0
        val failedReset = RimeWorkspaceManager.resetToStarterWorkspace(paths) {
            failCalls++
            if (failCalls == 1) null else listOf(RimeSchemaInfo("rime_ice", "雾凇拼音", isBuiltIn = false))
        }
        assertTrue(failedReset is RimeImportResult.Failure)
        assertTrue((failedReset as RimeImportResult.Failure).rolledBack)
        assertTrue(RimeWorkspaceManager.hasCustomUserFiles(paths))

        // Successful reset wipes custom files, preserves clean user.yaml, and deploys starters
        val okReset = RimeWorkspaceManager.resetToStarterWorkspace(paths) {
            assertTrue(File(paths.stagingDir, RimeWorkspaceInstaller.DEPLOY_PENDING_FILE).isFile)
            listOf(RimeSchemaInfo("duoime_pinyin_26", "DuoIME 26键拼音", isBuiltIn = true))
        }
        assertTrue(okReset is RimeImportResult.Success)
        assertFalse(RimeWorkspaceManager.hasCustomUserFiles(paths))
        assertTrue(File(paths.userDir, "user.yaml").isFile)
        assertFalse(
            "Reset must not leave transaction marker behind",
            File(paths.userDir.parentFile, RimeWorkspaceManager.WORKSPACE_TX_MARKER_FILE).exists()
        )
    }

    @Test
    fun yamlOverlayHandlesDocumentStartHeaderUtf8BomAndCommentOnlyCustomTemplates() {
        val paths = createWorkspace()
        val defaultWithDocStart = "\uFEFF%YAML 1.2\n---\n# User config\nschema_list:\n  - schema: rime_ice\n"
        val bomSchemaYaml = "\uFEFF" + sampleSchemaYaml("rime_ice", "雾凇拼音")
        val commentOnlyTemplate = "# Weasel desktop template\n# patch:\n#   style/font_point: 14\n"

        val result = RimeWorkspaceManager.importEntries(
            paths,
            sequenceOf(
                entry("default.yaml", defaultWithDocStart),
                entry("rime_ice.schema.yaml", bomSchemaYaml),
                entry("weasel.custom.yaml", commentOnlyTemplate)
            )
        ) {
            listOf(
                RimeSchemaInfo("duoime_pinyin_26", "DuoIME 26键拼音", isBuiltIn = true),
                RimeSchemaInfo("rime_ice", "雾凇拼音", isBuiltIn = false)
            )
        }

        assertTrue(
            "Expected Success with BOM, --- header, and comment-only template, got $result",
            result is RimeImportResult.Success
        )
        val writtenDefault = File(paths.userDir, "default.yaml").readText(Charsets.UTF_8)
        assertFalse("UTF-8 BOM must be stripped from rewritten default.yaml", writtenDefault.startsWith("\uFEFF"))
        val docStartIdx = writtenDefault.indexOf("---")
        val versionIdx = writtenDefault.indexOf("config_version: \"1.0\"")
        val schemaListIdx = writtenDefault.indexOf("schema_list:")
        assertTrue(
            "config_version must be inserted AFTER --- and BEFORE schema_list:\n$writtenDefault",
            docStartIdx in 0 until versionIdx && versionIdx < schemaListIdx
        )

        // Disk schema discovery also handles UTF-8 BOM .schema.yaml cleanly
        val discovered = RimeWorkspaceManager.discoverInstalledSchemasFromDisk(paths)
        assertTrue(
            "Expected BOM schema rime_ice in $discovered",
            discovered.any { it.id == "rime_ice" && it.name == "雾凇拼音" }
        )

        // Non-comment garbage without mapping keys in *.custom.yaml is still rejected
        val garbageCustom = RimeWorkspaceManager.stageEntries(
            sequenceOf(entry("default.custom.yaml", "not a valid yaml mapping\n")),
            tempFolder.newFolder("garbage_custom_stage")
        )
        assertEquals(RimeImportFailureReason.INVALID_YAML, (garbageCustom as RimeStagingResult.Failure).reason)

        // Multi-line single- and double-quoted scalars are valid YAML; unclosed EOF quote is rejected
        val multilineQuotedSchema = """
            schema:
              schema_id: multi_quote
              name: "Multi
                Line # not a comment
                Schema"
              description: 'It''s valid across
                multiple lines'
        """.trimIndent() + "\n"
        val multilineStaged = RimeWorkspaceManager.stageEntries(
            sequenceOf(entry("multi_quote.schema.yaml", multilineQuotedSchema)),
            tempFolder.newFolder("multiline_quote_stage")
        )
        assertTrue(
            "Expected multi-line quoted YAML to pass, got $multilineStaged",
            multilineStaged is RimeStagingResult.Success
        )

        val unclosedMultiline = RimeWorkspaceManager.stageEntries(
            sequenceOf(
                entry("bad_quote.schema.yaml", "schema:\n  schema_id: bad\n  description: \"never closed\n  line2\n")
            ),
            tempFolder.newFolder("unclosed_quote_stage")
        )
        assertEquals(RimeImportFailureReason.INVALID_YAML, (unclosedMultiline as RimeStagingResult.Failure).reason)

        // Importing an override/patch for a built-in *_incognito schema is rejected fail-closed
        val protectedPatch = RimeWorkspaceManager.stageEntries(
            sequenceOf(
                entry("rime_ice.schema.yaml", sampleSchemaYaml("rime_ice", "雾凇拼音")),
                entry("duoime_pinyin_26_incognito.custom.yaml", "patch:\n  translator/enable_user_dict: true\n")
            ),
            tempFolder.newFolder("protected_incognito_stage")
        )
        assertEquals(RimeImportFailureReason.UNSAFE_PATH, (protectedPatch as RimeStagingResult.Failure).reason)

        // Spoofed filename declaring a protected built-in *_incognito schema_id is also rejected fail-closed
        val spoofedIncognito = RimeWorkspaceManager.stageEntries(
            sequenceOf(
                entry("evil.schema.yaml", sampleSchemaYaml("duoime_pinyin_26_incognito", "Spoofed Incognito"))
            ),
            tempFolder.newFolder("spoofed_incognito_stage")
        )
        assertEquals(RimeImportFailureReason.UNSAFE_PATH, (spoofedIncognito as RimeStagingResult.Failure).reason)

        // Case B with flow-style schema_list (single-line & multi-line) deduplicates listed schemas cleanly
        val flowDefaultYaml = """
            config_version: "1.0"
            schema_list: [
              { schema: rime_ice },
              { schema: duoime_pinyin_26 }
            ]
        """.trimIndent() + "\n"
        val flowStageDir = tempFolder.newFolder("flow_schema_list_stage")
        val flowStaged = RimeWorkspaceManager.stageEntries(
            sequenceOf(
                entry("default.yaml", flowDefaultYaml),
                entry("rime_ice.schema.yaml", sampleSchemaYaml("rime_ice", "雾凇拼音"))
            ),
            flowStageDir
        )
        assertTrue(
            "Expected Success for flow-style schema_list, got $flowStaged",
            flowStaged is RimeStagingResult.Success
        )
        val flowDefaultCustom = File(flowStageDir, "default.custom.yaml").readText()
        assertFalse(flowDefaultCustom.contains("- schema: rime_ice"))
        assertFalse(flowDefaultCustom.contains("- schema: duoime_pinyin_26\n"))
        assertTrue(flowDefaultCustom.contains("- schema: duoime_t9"))

        // Plain scalars containing embedded quotes (e.g. "Press ' to separate syllables" or embedded ") are valid YAML
        val plainScalarWithQuotes = """
            schema:
              schema_id: plain_quotes
              name: Plain Quote Schema
              description: Press ' to separate syllables and "space" to commit
              note: Unquoted trailing " mark inside plain scalar
        """.trimIndent() + "\n"
        val plainQuotesStaged = RimeWorkspaceManager.stageEntries(
            sequenceOf(entry("plain_quotes.schema.yaml", plainScalarWithQuotes)),
            tempFolder.newFolder("plain_quotes_stage")
        )
        assertTrue(
            "Expected plain scalar with embedded quotes to pass, got $plainQuotesStaged",
            plainQuotesStaged is RimeStagingResult.Success
        )
    }

    @Test
    fun importPreservesExistingUserDbLearningDirectoriesAndSnapshotFiles() {
        val paths = createWorkspace()
        val userDbDir = File(paths.userDir, "duoime_pinyin.userdb").apply { mkdirs() }
        File(userDbDir, "CURRENT").writeText("MANIFEST-000001\n")
        File(paths.userDir, "duoime_pinyin.userdb.txt").writeText("# Rime user dict snapshot\n你好\tni hao\t5\n")

        val result = RimeWorkspaceManager.importEntries(
            paths,
            sequenceOf(entry("rime_ice.schema.yaml", sampleSchemaYaml("rime_ice", "雾凇拼音")))
        ) {
            listOf(
                RimeSchemaInfo("duoime_pinyin_26", "DuoIME 26键拼音", isBuiltIn = true),
                RimeSchemaInfo("rime_ice", "雾凇拼音", isBuiltIn = false)
            )
        }
        assertTrue("Expected Success, got $result", result is RimeImportResult.Success)
        assertEquals("MANIFEST-000001\n", File(paths.userDir, "duoime_pinyin.userdb/CURRENT").readText())
        assertTrue(File(paths.userDir, "duoime_pinyin.userdb.txt").readText().contains("你好"))
    }

    @Test
    fun crashRecoveryRestoresBackupCleansStaleStagesAndRejectsUnsafeMarkers() {
        val paths = createWorkspace()
        val workspaceRoot = paths.userDir.parentFile!!
        val marker = File(workspaceRoot, RimeWorkspaceManager.WORKSPACE_TX_MARKER_FILE)

        // Fast path when no transaction marker exists
        assertFalse(
            RimeWorkspaceManager.recoverInterruptedTransactionIfNeeded(
                workspaceRoot = workspaceRoot,
                userDir = paths.userDir,
                stagingDir = paths.stagingDir
            )
        )

        // Simulate native crash mid-import: corrupt userDir + valid .import_backup_* + stale stages + marker
        File(paths.userDir, "corrupt_new.schema.yaml").writeText("broken: true\n")
        val importBackup = File(workspaceRoot, ".import_backup_1001").apply { mkdirs() }
        File(importBackup, "user.yaml").writeText("var:\n  restored_from: import_backup\n")
        File(importBackup, "previous_good.yaml").writeText("good: true\n")
        val staleImportStage = File(workspaceRoot, ".import_stage_1001").apply { mkdirs() }
        val staleResetStage = File(workspaceRoot, ".reset_stage_1002").apply { mkdirs() }
        val staleSafStage = File(workspaceRoot, ".saf_stage_1003").apply { mkdirs() }
        marker.writeText("${importBackup.name}\n", Charsets.UTF_8)

        assertTrue(
            RimeWorkspaceManager.recoverInterruptedTransactionIfNeeded(
                workspaceRoot = workspaceRoot,
                userDir = paths.userDir,
                stagingDir = paths.stagingDir
            )
        )
        assertFalse("Recovery must delete transaction marker", marker.exists())
        assertFalse("Backup dir must be moved back into userDir", importBackup.exists())
        assertFalse("Corrupt userDir file must be removed", File(paths.userDir, "corrupt_new.schema.yaml").exists())
        assertTrue("Previous good file must be restored", File(paths.userDir, "previous_good.yaml").isFile)
        assertTrue(
            File(paths.userDir, "user.yaml").readText(Charsets.UTF_8).contains("restored_from: import_backup")
        )
        assertEquals(
            "crash_recovery",
            File(paths.stagingDir, RimeWorkspaceInstaller.DEPLOY_PENDING_FILE).readText(Charsets.UTF_8).trim()
        )
        assertFalse(staleImportStage.exists())
        assertFalse(staleResetStage.exists())
        assertFalse(staleSafStage.exists())
        assertTrue("Unrelated shared directory must remain untouched", paths.sharedDir.isDirectory)

        // Simulate crash mid-reset with .reset_backup_*
        val resetBackup = File(workspaceRoot, ".reset_backup_2002").apply { mkdirs() }
        File(resetBackup, "user.yaml").writeText("var:\n  restored_from: reset_backup\n")
        marker.writeText("${resetBackup.name}\n", Charsets.UTF_8)
        assertTrue(
            RimeWorkspaceManager.recoverInterruptedTransactionIfNeeded(
                workspaceRoot = workspaceRoot,
                userDir = paths.userDir,
                stagingDir = paths.stagingDir
            )
        )
        assertFalse(marker.exists())
        assertTrue(
            File(paths.userDir, "user.yaml").readText(Charsets.UTF_8).contains("restored_from: reset_backup")
        )

        // Unsafe or missing marker contents are ignored, marker is deleted, and userDir is untouched
        val sentinelFile = File(paths.userDir, "keep_untouched.yaml").apply { writeText("keep: 1\n") }
        val badPayloads = listOf(
            "../escape",
            "shared",
            ".import_backup_../evil",
            ".import_backup_/sub",
            ".import_backup_\\sub",
            ".import_backup_",
            ".reset_backup_",
            "",
            "   ",
            ".import_backup_nonexistent"
        )
        for (payload in badPayloads) {
            marker.writeText("$payload\n", Charsets.UTF_8)
            assertFalse(
                "Unsafe/missing marker '$payload' must not trigger recovery",
                RimeWorkspaceManager.recoverInterruptedTransactionIfNeeded(
                    workspaceRoot = workspaceRoot,
                    userDir = paths.userDir,
                    stagingDir = paths.stagingDir
                )
            )
            assertFalse("Marker '$payload' must still be deleted to avoid loops", marker.exists())
            assertTrue("userDir must remain untouched for '$payload'", sentinelFile.isFile)
        }

        // Post-commit crash mid-backup cleanup: marker already cleared, half-deleted backup left behind.
        // Startup recovery must NOT restore the incomplete backup over the healthy deployed userDir.
        val halfDeletedBackup = File(workspaceRoot, ".import_backup_3003").apply { mkdirs() }
        File(halfDeletedBackup, "partial.yaml").writeText("incomplete: true\n")
        assertFalse(marker.exists())
        assertFalse(
            RimeWorkspaceManager.recoverInterruptedTransactionIfNeeded(
                workspaceRoot = workspaceRoot,
                userDir = paths.userDir,
                stagingDir = paths.stagingDir
            )
        )
        assertTrue("Healthy userDir must stay untouched when marker is already cleared", sentinelFile.isFile)
        assertFalse(File(paths.userDir, "partial.yaml").exists())
    }

    @Test
    fun recoverySerializesWithConcurrentAndReentrantActiveTransactions() {
        val paths = createWorkspace()
        val workspaceRoot = paths.userDir.parentFile!!
        val marker = File(workspaceRoot, RimeWorkspaceManager.WORKSPACE_TX_MARKER_FILE)
        val enteredDeploy = CountDownLatch(1)
        val releaseDeploy = CountDownLatch(1)
        val reentrantRecoveryResult = AtomicBoolean(true)
        val txResultRef = AtomicReference<RimeImportResult?>(null)
        val txErrorRef = AtomicReference<Throwable?>(null)

        val txThread = Thread {
            try {
                txResultRef.set(
                    RimeWorkspaceManager.importEntries(
                        paths,
                        sequenceOf(entry("live_pack.schema.yaml", sampleSchemaYaml("live_pack", "Live Pack")))
                    ) {
                        // Same-thread reentrant recovery during live deploy must be a no-op
                        reentrantRecoveryResult.set(
                            RimeWorkspaceManager.recoverInterruptedTransactionIfNeeded(
                                workspaceRoot = workspaceRoot,
                                userDir = paths.userDir,
                                stagingDir = paths.stagingDir
                            )
                        )
                        assertTrue("Marker must stay present during live deploy", marker.isFile)
                        enteredDeploy.countDown()
                        assertTrue(releaseDeploy.await(5, TimeUnit.SECONDS))
                        listOf(
                            RimeSchemaInfo("duoime_pinyin_26", "DuoIME 26键拼音", isBuiltIn = true),
                            RimeSchemaInfo("live_pack", "Live Pack", isBuiltIn = false)
                        )
                    }
                )
            } catch (err: Throwable) {
                txErrorRef.set(err)
            }
        }
        txThread.start()
        assertTrue("Transaction should reach deploy hook", enteredDeploy.await(5, TimeUnit.SECONDS))
        assertFalse("Reentrant recovery during active tx must return false", reentrantRecoveryResult.get())

        // Concurrent recovery from another thread (e.g., IME startup) must block until active tx completes
        val concurrentDone = CountDownLatch(1)
        val concurrentRecovered = AtomicBoolean(true)
        val recoveryThread = Thread {
            concurrentRecovered.set(
                RimeWorkspaceManager.recoverInterruptedTransactionIfNeeded(
                    workspaceRoot = workspaceRoot,
                    userDir = paths.userDir,
                    stagingDir = paths.stagingDir
                )
            )
            concurrentDone.countDown()
        }
        recoveryThread.start()

        assertFalse(
            "Concurrent recovery must wait on workspace lock while active deploy holds it",
            concurrentDone.await(150, TimeUnit.MILLISECONDS)
        )
        assertTrue("Active transaction marker must still be intact while blocked", marker.isFile)
        assertTrue(
            "New staged schema must still be in userDir during deploy",
            File(paths.userDir, "live_pack.schema.yaml").isFile
        )

        releaseDeploy.countDown()
        assertTrue(
            "Recovery thread should finish once active tx releases lock",
            concurrentDone.await(5, TimeUnit.SECONDS)
        )
        txThread.join(5_000)
        recoveryThread.join(5_000)

        assertEquals(null, txErrorRef.get())
        assertTrue("Expected Success, got ${txResultRef.get()}", txResultRef.get() is RimeImportResult.Success)
        assertFalse("Concurrent recovery after committed tx must return false", concurrentRecovered.get())
        assertFalse("Committed tx must clear transaction marker", marker.exists())
        assertTrue("Imported schema must survive intact", File(paths.userDir, "live_pack.schema.yaml").isFile)
    }
}

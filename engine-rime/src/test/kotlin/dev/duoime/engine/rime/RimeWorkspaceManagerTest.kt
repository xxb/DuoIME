// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.engine.rime

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
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
        val result = RimeWorkspaceManager.importEntries(paths, entries) {
            sawPendingDuringDeploy = File(paths.stagingDir, RimeWorkspaceInstaller.DEPLOY_PENDING_FILE).isFile
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
    }
}

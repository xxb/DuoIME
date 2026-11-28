// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.settings

import dev.duoime.engine.rime.RimeImportFailureReason
import dev.duoime.engine.rime.RimeImportResult
import dev.duoime.engine.rime.RimeSchemaInfo
import dev.duoime.engine.rime.RimeStagingResult
import dev.duoime.engine.rime.RimeWorkspaceDeployer
import dev.duoime.engine.rime.RimeWorkspacePaths
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RimeSafImporterAndCoordinatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun buildZipBytes(entries: Map<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun sampleSchema(id: String, name: String): String = """
        schema:
          schema_id: $id
          name: "$name"
    """.trimIndent() + "\n"

    private fun createWorkspacePaths(): RimeWorkspacePaths {
        val root = tempFolder.newFolder("coord_ws_${System.nanoTime()}")
        val shared = File(root, "shared").apply { mkdirs() }
        val user = File(root, "user").apply { mkdirs() }
        val prebuilt = File(shared, "build").apply { mkdirs() }
        val staging = File(user, "build").apply { mkdirs() }
        File(shared, "duoime_pinyin_26.schema.yaml").writeText(sampleSchema("duoime_pinyin_26", "DuoIME 26键拼音"))
        File(user, "user.yaml").writeText("var: {}\n")
        return RimeWorkspacePaths(shared, user, prebuilt, staging)
    }

    @Test
    fun stageZipStreamExtractsMultiEntryZipWithoutPrematurelyClosingStreamAndRejectsZipSlip() {
        val zipFile = File(tempFolder.root, "dynamic_rime_pack.zip")
        zipFile.writeBytes(
            buildZipBytes(
                linkedMapOf(
                    "rime-pack/rime_ice.schema.yaml" to sampleSchema("rime_ice", "雾凇拼音"),
                    "rime-pack/rime_ice.dict.yaml" to "name: rime_ice\n...\n你好\tni hao\n",
                    "rime-pack/lua/helper.lua" to "return {}\n"
                )
            )
        )
        val stageDir = File(tempFolder.root, "zip_stage_ok")
        val staged = zipFile.inputStream().use { RimeSafImporter.stageZipStream(it, stageDir) }
        assertTrue("Expected Success, got $staged", staged is RimeStagingResult.Success)
        val ok = staged as RimeStagingResult.Success
        assertEquals(3, ok.fileCount)
        assertEquals(listOf("rime_ice"), ok.discoveredCustomSchemaIds)
        assertTrue(File(stageDir, "rime_ice.schema.yaml").isFile)
        assertTrue(File(stageDir, "rime_ice.dict.yaml").isFile)
        assertTrue(File(stageDir, "lua/helper.lua").isFile)
        assertTrue(File(stageDir, "default.custom.yaml").isFile)

        // Zip-slip archive must fail cleanly
        val evilZip = buildZipBytes(mapOf("../escape.yaml" to "bad: true\n"))
        val evilResult = RimeSafImporter.stageZipStream(
            ByteArrayInputStream(evilZip),
            File(tempFolder.root, "zip_stage_evil")
        )
        assertEquals(RimeImportFailureReason.UNSAFE_PATH, (evilResult as RimeStagingResult.Failure).reason)
    }

    @Test
    fun stageTreeNodesHandlesNestedDirsCycleGuardDepthLimitAndUnsafeNames() {
        // 1) Nested tree with a directory cycle (child folder points back to root) finishes cleanly
        val cyclicSource = object : SafTreeNodeSource {
            private val files = mapOf(
                "f1" to sampleSchema("terra_pinyin", "地球拼音"),
                "f2" to "return 1\n"
            )

            override fun listChildren(parentDocumentId: String): List<SafDocumentNode> = when (parentDocumentId) {
                "root" -> listOf(
                    SafDocumentNode("f1", "terra_pinyin.schema.yaml", isDirectory = false),
                    SafDocumentNode("dir_lua", "lua", isDirectory = true)
                )
                "dir_lua" -> listOf(
                    SafDocumentNode("f2", "filter.lua", isDirectory = false),
                    SafDocumentNode("root", "cycle_back", isDirectory = true)
                )
                else -> emptyList()
            }

            override fun openStream(documentId: String): InputStream? =
                files[documentId]?.toByteArray(Charsets.UTF_8)?.let(::ByteArrayInputStream)
        }
        val stageDir = File(tempFolder.root, "tree_stage_ok")
        val staged = RimeSafImporter.stageTreeNodes("root", cyclicSource, stageDir)
        assertTrue("Expected Success despite cycle, got $staged", staged is RimeStagingResult.Success)
        assertEquals(listOf("terra_pinyin"), (staged as RimeStagingResult.Success).discoveredCustomSchemaIds)
        assertTrue(File(stageDir, "lua/filter.lua").isFile)

        // 2) Excessive nesting depth (> MAX_TREE_DEPTH) is rejected
        val deepSource = object : SafTreeNodeSource {
            override fun listChildren(parentDocumentId: String): List<SafDocumentNode> {
                val level = parentDocumentId.removePrefix("d").toIntOrNull() ?: 0
                return listOf(SafDocumentNode("d${level + 1}", "sub$level", isDirectory = true))
            }

            override fun openStream(documentId: String): InputStream? = null
        }
        val deepResult = RimeSafImporter.stageTreeNodes("d0", deepSource, File(tempFolder.root, "tree_stage_deep"))
        assertEquals(RimeImportFailureReason.UNSAFE_PATH, (deepResult as RimeStagingResult.Failure).reason)

        // 3) Unsafe display name with slash is rejected
        val unsafeNameSource = object : SafTreeNodeSource {
            override fun listChildren(parentDocumentId: String): List<SafDocumentNode> =
                listOf(SafDocumentNode("f1", "../evil.yaml", isDirectory = false))

            override fun openStream(documentId: String): InputStream? = ByteArrayInputStream(ByteArray(0))
        }
        val unsafeResult = RimeSafImporter.stageTreeNodes(
            "root",
            unsafeNameSource,
            File(tempFolder.root, "tree_stage_bad")
        )
        assertEquals(RimeImportFailureReason.UNSAFE_PATH, (unsafeResult as RimeStagingResult.Failure).reason)

        // 4) Ignored dirs/files (.git, __MACOSX, .DS_Store, ._*) are skipped BEFORE MAX_IMPORT_FILES / MAX_TREE_DEPTH
        val ignoredRepoSource = object : SafTreeNodeSource {
            override fun listChildren(parentDocumentId: String): List<SafDocumentNode> = when (parentDocumentId) {
                "root" -> listOf(
                    SafDocumentNode("schema1", "rime_ice.schema.yaml", isDirectory = false),
                    SafDocumentNode("ds_store", ".DS_Store", isDirectory = false),
                    SafDocumentNode("apple_double", "._rime_ice.schema.yaml", isDirectory = false),
                    SafDocumentNode("git_dir", ".git", isDirectory = true),
                    SafDocumentNode("macosx_dir", "__MACOSX", isDirectory = true)
                )
                "git_dir", "macosx_dir" -> error("Ignored directory '$parentDocumentId' must never be traversed")
                else -> emptyList()
            }

            override fun openStream(documentId: String): InputStream? = when (documentId) {
                "schema1" -> ByteArrayInputStream(sampleSchema("rime_ice", "雾凇拼音").toByteArray(Charsets.UTF_8))
                else -> error("Ignored file '$documentId' must never be opened")
            }
        }
        val ignoredRepoStage = File(tempFolder.root, "tree_stage_ignored")
        val ignoredRepoResult = RimeSafImporter.stageTreeNodes("root", ignoredRepoSource, ignoredRepoStage)
        assertTrue(
            "Expected Success skipping .git/__MACOSX/junk, got $ignoredRepoResult",
            ignoredRepoResult is RimeStagingResult.Success
        )
        assertEquals(1, (ignoredRepoResult as RimeStagingResult.Success).fileCount)
        assertEquals(listOf("rime_ice"), ignoredRepoResult.discoveredCustomSchemaIds)
    }

    @Test
    fun coordinatorEngineHandlesRefreshImportAutoSelectRollbackRedeployAndReset() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val paths = createWorkspacePaths()
        var liveSchemas: List<RimeSchemaInfo>? = null
        var nextDeploySchemas: List<RimeSchemaInfo>? = listOf(
            RimeSchemaInfo("duoime_pinyin_26", "DuoIME 26键拼音", isBuiltIn = true),
            RimeSchemaInfo("rime_ice", "雾凇拼音", isBuiltIn = false)
        )
        var selectedSchemaPref: String? = null
        var transactionCount = 0

        val host = object : RimeWorkspaceHost {
            override suspend fun ensureWorkspacePaths(): RimeWorkspacePaths = paths

            override suspend fun listLiveSchemas(): List<RimeSchemaInfo>? = liveSchemas

            override suspend fun <T> runTransaction(block: (RimeWorkspacePaths, RimeWorkspaceDeployer) -> T): T {
                transactionCount++
                return block(paths, RimeWorkspaceDeployer { nextDeploySchemas })
            }

            override fun selectSchemaPreference(schemaId: String?) {
                selectedSchemaPref = schemaId
            }
        }

        val coordinator = RimeWorkspaceCoordinatorEngine(ioDispatcher = dispatcher)

        // 1) Initial refresh falls back to disk discovery when liveSchemas is null
        coordinator.refreshSchemas(host)
        assertEquals(listOf("duoime_pinyin_26"), coordinator.uiState.value.schemas.map { it.id })
        assertFalse(coordinator.uiState.value.hasCustomFiles)

        // 2) Phase 1 staging failure never runs a native transaction
        val failedStage = coordinator.importStaged(host) { stageDir ->
            RimeSafImporter.stageZipStream(ByteArrayInputStream(buildZipBytes(mapOf("notes.txt" to "hi"))), stageDir)
        }
        assertTrue(failedStage is RimeImportResult.Failure)
        assertEquals(0, transactionCount)
        assertFalse(coordinator.uiState.value.isBusy)
        assertTrue(coordinator.uiState.value.feedback is RimeWorkspaceFeedback.Failed)

        // 3) Successful import commits, auto-selects rime_ice, and sets Imported feedback
        val packBytes = buildZipBytes(
            mapOf("rime_ice.schema.yaml" to sampleSchema("rime_ice", "雾凇拼音"))
        )
        val imported = coordinator.importStaged(host) { stageDir ->
            RimeSafImporter.stageZipStream(ByteArrayInputStream(packBytes), stageDir)
        }
        assertTrue("Expected Success, got $imported", imported is RimeImportResult.Success)
        assertEquals(1, transactionCount)
        assertEquals("rime_ice", selectedSchemaPref)
        assertTrue(coordinator.uiState.value.hasCustomFiles)
        assertEquals(
            RimeWorkspaceFeedback.Imported(customSchemaCount = 1, fileCount = 1),
            coordinator.uiState.value.feedback
        )

        // 4) Redeploy updates feedback & schema list
        assertTrue(coordinator.redeployWorkspace(host))
        assertEquals(RimeWorkspaceFeedback.Deployed, coordinator.uiState.value.feedback)

        // 5) Reset clears custom files, resets preference to null, and restores starter list
        nextDeploySchemas = listOf(RimeSchemaInfo("duoime_pinyin_26", "DuoIME 26键拼音", isBuiltIn = true))
        val resetResult = coordinator.resetWorkspace(host)
        assertTrue("Expected Reset Success, got $resetResult", resetResult is RimeImportResult.Success)
        assertNull(selectedSchemaPref)
        assertFalse(coordinator.uiState.value.hasCustomFiles)
        assertEquals(RimeWorkspaceFeedback.ResetComplete, coordinator.uiState.value.feedback)

        // 6) Live schemas take priority over disk scan on refresh
        liveSchemas = listOf(
            RimeSchemaInfo("duoime_pinyin_26", "DuoIME 26键拼音", isBuiltIn = true),
            RimeSchemaInfo("duoime_t9", "DuoIME 9键拼音", isBuiltIn = true)
        )
        coordinator.refreshSchemas(host)
        assertEquals(listOf("duoime_pinyin_26", "duoime_t9"), coordinator.uiState.value.schemas.map { it.id })
    }
}

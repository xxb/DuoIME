// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.engine.rime

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RimeBridgeAndWorkspaceTest {

    private class FakeRawRimeCalls(
        private val failLoad: Boolean = false,
        private val failInit: Boolean = false
    ) : RawRimeCalls {
        var initCount: Int = 0
        var finalizeCount: Int = 0
        var deployCount: Int = 0
        var nextSessionId: Long = 1L
        val activeSessions = LinkedHashSet<Long>()

        override fun ensureLoaded() {
            if (failLoad) throw UnsatisfiedLinkError("libduoime_rime.so not packaged")
        }

        override fun nativeInitialize(
            sharedDataDir: String,
            userDataDir: String,
            prebuiltDataDir: String?,
            stagingDir: String?,
            distributionVersion: String
        ): Boolean {
            initCount++
            return !failInit
        }

        override fun nativeFinalize() {
            finalizeCount++
        }

        override fun nativeDeployWorkspace(): Boolean {
            deployCount++
            return true
        }

        override fun nativeCreateSession(): Long {
            val id = nextSessionId++
            activeSessions.add(id)
            return id
        }

        override fun nativeDestroySession(sessionId: Long): Boolean = activeSessions.remove(sessionId)

        override fun nativeProcessKey(sessionId: Long, keysym: Int, mask: Int): Boolean = false

        override fun nativeGetCommitText(sessionId: Long): String? = null

        override fun nativeGetSnapshot(sessionId: Long): Array<Any?>? = null

        override fun nativeSelectCandidateOnCurrentPage(sessionId: Long, indexOnPage: Int): Boolean = false

        override fun nativeChangePage(sessionId: Long, backward: Boolean): Boolean = false

        override fun nativeSelectSchema(sessionId: Long, schemaId: String): Boolean = true

        override fun nativeGetCurrentSchema(sessionId: Long): String? = null

        override fun nativeGetOption(sessionId: Long, optionName: String): Boolean = false

        override fun nativeSetOption(sessionId: Long, optionName: String, value: Boolean) = Unit

        override fun nativeClearComposition(sessionId: Long) = Unit

        override fun nativeCommitComposition(sessionId: Long): Boolean = false

        override fun nativeGetSchemaList(): Array<String> = arrayOf("duoime_pinyin_26", "26-Key")

        override fun nativeGetVersion(): String = "1.18.0"
    }

    private class MemoryAssetSource(
        private val files: Map<String, ByteArray>
    ) : RimeAssetSource {
        var openCount: Int = 0

        override fun list(relativeDir: String): List<String> {
            val prefix = if (relativeDir.isEmpty()) "" else "$relativeDir/"
            val children = LinkedHashSet<String>()
            for (path in files.keys) {
                if (!path.startsWith(prefix)) continue
                val rest = path.removePrefix(prefix)
                if (rest.isEmpty()) continue
                val seg = rest.substringBefore('/')
                children.add(seg)
            }
            return children.sorted()
        }

        override fun open(relativePath: String): InputStream {
            openCount++
            val bytes = files[relativePath] ?: error("Missing asset $relativePath")
            return ByteArrayInputStream(bytes)
        }
    }

    private fun tempWorkspacePaths(): RimeWorkspacePaths {
        val root = Files.createTempDirectory("duoime-rime-ws-test").toFile()
        root.deleteOnExit()
        return RimeWorkspacePaths(
            sharedDir = File(root, "shared").apply { mkdirs() },
            userDir = File(root, "user").apply { mkdirs() },
            prebuiltDir = File(root, "shared/build").apply { mkdirs() },
            stagingDir = File(root, "user/build").apply { mkdirs() }
        )
    }

    @Test
    fun refCountedBridgePreventsPrematureFinalizeAcrossReloadOverlap() {
        val raw = FakeRawRimeCalls()
        val bridge = RefCountedRimeBridge(raw)
        val paths = tempWorkspacePaths()

        assertTrue(bridge.acquireRuntime(paths, "1.0"))
        assertEquals(1, raw.initCount)
        assertEquals(0, raw.finalizeCount)
        assertEquals(1, bridge.activeInstanceCount)
        assertTrue("Sole active instance may deploy workspace", bridge.deployWorkspace())
        assertEquals(1, raw.deployCount)

        // Simulate reloadEngine creating engine #2 BEFORE closing engine #1
        assertTrue(bridge.acquireRuntime(paths, "1.0"))
        assertEquals("Re-acquire on same workspace must not re-initialize", 1, raw.initCount)
        assertEquals(2, bridge.activeInstanceCount)
        assertFalse(
            "Concurrent overlap must reject deployWorkspace without invoking nativeDeployWorkspace",
            bridge.deployWorkspace()
        )
        assertEquals(1, raw.deployCount)

        // Closing engine #1 must NOT call nativeFinalize while engine #2 is still active
        bridge.releaseRuntime()
        assertEquals(0, raw.finalizeCount)
        assertEquals(1, bridge.activeInstanceCount)
        assertTrue("Sole remaining instance may deploy again", bridge.deployWorkspace())
        assertEquals(2, raw.deployCount)

        // Closing engine #2 drops refcount to 0 and calls nativeFinalize exactly once
        bridge.releaseRuntime()
        assertEquals(1, raw.finalizeCount)
        assertEquals(0, bridge.activeInstanceCount)
        assertFalse("Uninitialized bridge must reject deployWorkspace", bridge.deployWorkspace())
        assertEquals(2, raw.deployCount)

        // Extra release is safely idempotent
        bridge.releaseRuntime()
        assertEquals(1, raw.finalizeCount)

        // Re-acquiring after finalize cleanly initializes again
        assertTrue(bridge.acquireRuntime(paths, "1.0"))
        assertEquals(2, raw.initCount)
        assertEquals(1, bridge.activeInstanceCount)
        bridge.releaseRuntime()
        assertEquals(2, raw.finalizeCount)
    }

    @Test(expected = UnsatisfiedLinkError::class)
    fun missingNativeLibraryThrowsUnsatisfiedLinkErrorForFallback() {
        val bridge = RefCountedRimeBridge(FakeRawRimeCalls(failLoad = true))
        bridge.acquireRuntime(tempWorkspacePaths(), "1.0")
    }

    @Test
    fun failedNativeInitializeDoesNotLeakRefcount() {
        val raw = FakeRawRimeCalls(failInit = true)
        val bridge = RefCountedRimeBridge(raw)
        assertFalse(bridge.acquireRuntime(tempWorkspacePaths(), "1.0"))
        assertEquals(0, bridge.activeInstanceCount)
        assertEquals(0, raw.finalizeCount)
    }

    @Test
    fun snapshotAndSchemaListArrayParsersMatch14SlotJniContract() {
        assertNull(parseSnapshotArray(null))
        assertNull(parseSnapshotArray(arrayOf("too_short")))

        val raw = arrayOf<Any?>(
            "duoime_pinyin_26",
            "DuoIME 26键拼音",
            true,
            false,
            true,
            "ni'hao",
            "你好",
            1,
            false,
            2,
            arrayOf("你好", "𠀀测试😀"),
            arrayOf("nǐ hǎo", null),
            "nihao",
            6
        )
        val parsed = parseSnapshotArray(raw)
        assertNotNull(parsed)
        assertEquals("duoime_pinyin_26", parsed!!.schemaId)
        assertEquals("DuoIME 26键拼音", parsed.schemaName)
        assertTrue(parsed.isComposing)
        assertFalse(parsed.isAsciiMode)
        assertTrue(parsed.isTraditional)
        assertEquals("ni'hao", parsed.preedit)
        assertEquals("你好", parsed.commitTextPreview)
        assertEquals(1, parsed.pageIndex)
        assertFalse(parsed.isLastPage)
        assertEquals(2, parsed.highlightedIndex)
        assertEquals(listOf("你好", "𠀀测试😀"), parsed.candidateTexts)
        assertEquals(listOf("nǐ hǎo", null), parsed.candidateComments)
        assertEquals("nihao", parsed.rawInput)
        assertEquals(6, parsed.caretPos)

        assertEquals(
            listOf("duoime_pinyin_26" to "26-Key", "duoime_t9" to "T9"),
            parseSchemaListArray(arrayOf("duoime_pinyin_26", "26-Key", "duoime_t9", "T9"))
        )
    }

    @Test
    fun workspaceInstallerExtractsSkipsUnchangedAndPreservesUserData() {
        val root = Files.createTempDirectory("duoime-rime-install-test").toFile()
        root.deleteOnExit()

        val sourceV1 = MemoryAssetSource(
            mapOf(
                "default.yaml" to "config_version: '1.0'\n".toByteArray(),
                "duoime_pinyin.dict.yaml" to "name: duoime_pinyin\n".toByteArray(),
                "opencc/duoime_s2t.json" to "{\"name\":\"s2t\"}\n".toByteArray(),
                "opencc/duoime_s2t.txt" to "万\t萬\n".toByteArray(),
                "starter_manifest.json" to "{\"schema_version\":1,\"v\":1}\n".toByteArray()
            )
        )
        val paths = RimeWorkspaceInstaller.installFromSource(sourceV1, root)
        assertTrue(paths.assetsUpdated)
        assertTrue(File(paths.sharedDir, "default.yaml").isFile)
        assertTrue(File(paths.sharedDir, "opencc/duoime_s2t.txt").isFile)
        assertTrue(File(paths.userDir, "user.yaml").isFile)
        assertTrue(paths.prebuiltDir.isDirectory)
        assertTrue(paths.stagingDir.isDirectory)

        // Add a custom user file that must survive re-extraction
        val customUserFile = File(paths.userDir, "custom_user.yaml")
        customUserFile.writeText("custom: true\n")

        // Even with stagingDir/default.yaml present, pending deploy marker keeps assetsUpdated=true
        // until markWorkspaceDeployed() clears it after a successful native deploy.
        val stagedDefaultYaml = File(paths.stagingDir, "default.yaml")
        stagedDefaultYaml.writeText("deployed: true\n")
        assertTrue(File(paths.stagingDir, RimeWorkspaceInstaller.DEPLOY_PENDING_FILE).isFile)
        val opensBeforePendingCheck = sourceV1.openCount
        val stillPendingPaths = RimeWorkspaceInstaller.installFromSource(sourceV1, root)
        assertTrue(stillPendingPaths.assetsUpdated)
        assertEquals(opensBeforePendingCheck + 1, sourceV1.openCount)

        // Marking deployed clears .deploy_pending; next install skips copying & reports assetsUpdated=false
        RimeWorkspaceInstaller.markWorkspaceDeployed(paths)
        assertFalse(File(paths.stagingDir, RimeWorkspaceInstaller.DEPLOY_PENDING_FILE).exists())
        val opensBeforeSecond = sourceV1.openCount
        val secondPaths = RimeWorkspaceInstaller.installFromSource(sourceV1, root)
        assertFalse(secondPaths.assetsUpdated)
        assertEquals(
            "Only manifest fingerprint should be read when up-to-date",
            opensBeforeSecond + 1,
            sourceV1.openCount
        )

        // If stagingDir/default.yaml is missing, assetsUpdated=true triggers deploy even without recopying
        assertTrue(stagedDefaultYaml.delete())
        val opensBeforeMissingStaging = sourceV1.openCount
        val missingStagingPaths = RimeWorkspaceInstaller.installFromSource(sourceV1, root)
        assertTrue(missingStagingPaths.assetsUpdated)
        assertEquals(opensBeforeMissingStaging + 1, sourceV1.openCount)

        // Updated manifest triggers clean re-extraction & assetsUpdated=true even when staging default.yaml exists
        stagedDefaultYaml.writeText("deployed: true\n")
        val sourceV2 = MemoryAssetSource(
            mapOf(
                "default.yaml" to "config_version: '2.0'\n".toByteArray(),
                "duoime_pinyin.dict.yaml" to "name: duoime_pinyin\n".toByteArray(),
                "opencc/duoime_s2t.json" to "{\"name\":\"s2t\"}\n".toByteArray(),
                "opencc/duoime_s2t.txt" to "万\t萬\n".toByteArray(),
                "starter_manifest.json" to "{\"schema_version\":1,\"v\":2}\n".toByteArray()
            )
        )
        val v2Paths = RimeWorkspaceInstaller.installFromSource(sourceV2, root)
        assertTrue(v2Paths.assetsUpdated)
        assertEquals("config_version: '2.0'\n", File(paths.sharedDir, "default.yaml").readText())
        assertEquals("custom: true\n", customUserFile.readText())
    }

    @Test
    fun installFromSourceRecoversInterruptedTransactionAndTriggersRedeploy() {
        val root = Files.createTempDirectory("duoime-rime-crash-recover-test").toFile()
        root.deleteOnExit()

        val source = MemoryAssetSource(
            mapOf(
                "default.yaml" to "config_version: '1.0'\n".toByteArray(),
                "duoime_pinyin.dict.yaml" to "name: duoime_pinyin\n".toByteArray(),
                "starter_manifest.json" to "{\"schema_version\":1,\"v\":1}\n".toByteArray()
            )
        )
        val initial = RimeWorkspaceInstaller.installFromSource(source, root)
        File(initial.stagingDir, "default.yaml").writeText("deployed: true\n")
        RimeWorkspaceInstaller.markWorkspaceDeployed(initial)
        assertFalse(RimeWorkspaceInstaller.installFromSource(source, root).assetsUpdated)

        // Simulate native crash mid-import: corrupt userDir, backup present, stale stages, tx marker
        File(initial.userDir, "crashing_pack.schema.yaml").writeText("schema:\n  schema_id: crashing\n")
        val backupDir = File(root, ".import_backup_987654").apply { mkdirs() }
        File(backupDir, "user.yaml").writeText("var:\n  previously_selected_schema: duoime_pinyin_26\n")
        File(backupDir, "known_good.yaml").writeText("ok: true\n")
        val staleSafStage = File(root, ".saf_stage_987654").apply { mkdirs() }
        val marker = File(root, RimeWorkspaceManager.WORKSPACE_TX_MARKER_FILE)
        marker.writeText("${backupDir.name}\n", Charsets.UTF_8)

        val recovered = RimeWorkspaceInstaller.installFromSource(source, root)
        assertTrue("Recovered transaction must require redeploy (assetsUpdated=true)", recovered.assetsUpdated)
        assertTrue(File(recovered.userDir, "known_good.yaml").isFile)
        assertFalse(File(recovered.userDir, "crashing_pack.schema.yaml").exists())
        assertTrue(File(recovered.stagingDir, RimeWorkspaceInstaller.DEPLOY_PENDING_FILE).isFile)
        assertFalse(marker.exists())
        assertFalse(backupDir.exists())
        assertFalse(staleSafStage.exists())

        // Once redeployed cleanly, subsequent startup does not re-trigger recovery or deploy
        File(recovered.stagingDir, "default.yaml").writeText("deployed: true\n")
        RimeWorkspaceInstaller.markWorkspaceDeployed(recovered)
        assertFalse(RimeWorkspaceInstaller.installFromSource(source, root).assetsUpdated)
    }

    @Test
    fun offlineTransactionAcquiresDeploysClearsPendingAndAlwaysReleasesRuntime() {
        val raw = FakeRawRimeCalls()
        val bridge = RefCountedRimeBridge(raw)
        val paths = tempWorkspacePaths()
        val pendingFile = File(paths.stagingDir, RimeWorkspaceInstaller.DEPLOY_PENDING_FILE)

        val schemas = RimeNativeExecutor.runOfflineTransaction(
            bridge = bridge,
            paths = paths,
            distributionVersion = "0.1.0"
        ) { txPaths, deployer ->
            RimeWorkspaceInstaller.prepareStagingForRedeploy(txPaths, "offline_test")
            assertTrue(pendingFile.isFile)
            deployer.deployAndListSchemas()
        }

        assertEquals(1, raw.initCount)
        assertEquals(1, raw.deployCount)
        assertEquals(1, raw.finalizeCount)
        assertEquals(0, bridge.activeInstanceCount)
        assertFalse(pendingFile.exists())
        assertEquals(listOf(RimeSchemaInfo("duoime_pinyin_26", "26-Key", isBuiltIn = true)), schemas)

        // Exception inside block still releases runtime cleanly
        val err = runCatching {
            RimeNativeExecutor.runOfflineTransaction<Unit>(
                bridge = bridge,
                paths = paths,
                distributionVersion = "0.1.0"
            ) { _, _ -> error("offline boom") }
        }.exceptionOrNull()
        assertTrue(err is IllegalStateException)
        assertEquals(2, raw.initCount)
        assertEquals(2, raw.finalizeCount)
        assertEquals(0, bridge.activeInstanceCount)

        // When a live session already holds refcount > 0, acquireExclusiveRuntime rejects without running block
        assertTrue(bridge.acquireRuntime(paths, "0.1.0"))
        assertEquals(1, bridge.activeInstanceCount)
        var blockExecuted = false
        val busyErr = runCatching {
            RimeNativeExecutor.runOfflineTransaction<Unit>(
                bridge = bridge,
                paths = paths,
                distributionVersion = "0.1.0"
            ) { _, _ ->
                blockExecuted = true
            }
        }.exceptionOrNull()
        assertTrue("Expected RimeWorkspaceBusyException, got $busyErr", busyErr is RimeWorkspaceBusyException)
        assertFalse("Offline block must NEVER execute while live runtime is active", blockExecuted)
        assertEquals("Refcount must remain unchanged at 1", 1, bridge.activeInstanceCount)
        bridge.releaseRuntime()
        assertEquals(0, bridge.activeInstanceCount)

        // Exclusive lease blocks concurrent acquireRuntime until releaseRuntime() clears the lease
        assertTrue(bridge.acquireExclusiveRuntime(paths, "0.1.0"))
        assertEquals(1, bridge.activeInstanceCount)
        assertFalse(
            "Concurrent acquireRuntime must be blocked while exclusiveLease is active",
            bridge.acquireRuntime(paths, "0.1.0")
        )
        assertEquals(1, bridge.activeInstanceCount)
        bridge.releaseRuntime()
        assertEquals(0, bridge.activeInstanceCount)
        assertTrue(
            "acquireRuntime succeeds again once exclusiveLease is released",
            bridge.acquireRuntime(paths, "0.1.0")
        )
        bridge.releaseRuntime()
        assertEquals(0, bridge.activeInstanceCount)
    }
}

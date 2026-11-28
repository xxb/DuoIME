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

        override fun nativeDeployWorkspace(): Boolean = true

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

        // Simulate reloadEngine creating engine #2 BEFORE closing engine #1
        assertTrue(bridge.acquireRuntime(paths, "1.0"))
        assertEquals("Re-acquire on same workspace must not re-initialize", 1, raw.initCount)
        assertEquals(2, bridge.activeInstanceCount)

        // Closing engine #1 must NOT call nativeFinalize while engine #2 is still active
        bridge.releaseRuntime()
        assertEquals(0, raw.finalizeCount)
        assertEquals(1, bridge.activeInstanceCount)

        // Closing engine #2 drops refcount to 0 and calls nativeFinalize exactly once
        bridge.releaseRuntime()
        assertEquals(1, raw.finalizeCount)
        assertEquals(0, bridge.activeInstanceCount)

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

        // Once stagingDir/default.yaml exists, same fingerprint skips copying & reports assetsUpdated=false
        val stagedDefaultYaml = File(paths.stagingDir, "default.yaml")
        stagedDefaultYaml.writeText("deployed: true\n")
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
}

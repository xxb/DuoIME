// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.engine.rime

import dev.duoime.core.engine.EngineKeyEvent
import dev.duoime.core.engine.EngineOptions
import dev.duoime.core.engine.EngineSchemas
import dev.duoime.core.layout.KeyCodes
import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyProcessResult
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RimeInputEngineTest {

    private class FakeRimeNativeBridge(
        private val failAcquire: Boolean = false,
        private val failDeploy: Boolean = false
    ) : RimeNativeBridge {
        var acquireCount: Int = 0
        var releaseCount: Int = 0
        var deployCount: Int = 0
        var nextSession: Long = 1L
        val destroyedSessions = mutableListOf<Long>()
        val selectedSchemas = mutableListOf<String>()
        val setOptionsLog = mutableListOf<Pair<String, Boolean>>()

        private var currentSchema: String = RimeEngineMetadata.SCHEMA_PINYIN_26
        private val options = mutableMapOf<String, Boolean>()
        private val input = StringBuilder()
        private var pageIndex: Int = 0
        private var pendingCommit: String? = null
        private var confirmedPrefix: String = ""
        private var partialCommitMode: Boolean = false

        private val pagedDict: Map<String, List<List<String>>> = mapOf(
            "nihao" to listOf(
                listOf("你好", "拟好", "尼浩"),
                listOf("泥嚎", "你号")
            ),
            "nihc" to listOf(listOf("你好")),
            "6262" to listOf(listOf("妈妈", "哪哪", "妈")),
            "ma'62" to listOf(listOf("妈妈", "妈", "马鞍")),
            "ma'ma" to listOf(listOf("妈妈", "骂骂")),
            "ma'6" to listOf(listOf("妈", "麻")),
            "62" to listOf(listOf("妈", "马", "那")),
            "ma" to listOf(listOf("妈", "马"))
        )

        private val s2tMap: Map<String, String> = mapOf(
            "妈妈" to "媽媽",
            "妈" to "媽",
            "马" to "馬",
            "你好" to "你好",
            "拟好" to "擬好"
        )

        fun enableImmediatePartialCommitMode(enabled: Boolean) {
            partialCommitMode = enabled
        }

        override fun acquireRuntime(paths: RimeWorkspacePaths, distributionVersion: String): Boolean {
            acquireCount++
            if (failAcquire) throw UnsatisfiedLinkError("Simulated missing libduoime_rime.so")
            return true
        }

        override fun releaseRuntime() {
            releaseCount++
        }

        override fun deployWorkspace(): Boolean {
            deployCount++
            return !failDeploy
        }

        override fun createSession(): Long = nextSession++

        override fun destroySession(sessionId: Long): Boolean {
            destroyedSessions.add(sessionId)
            return true
        }

        override fun processKey(sessionId: Long, keysym: Int, mask: Int): Boolean {
            if (keysym == KeyCodes.KEYSYM_BACKSPACE) {
                if (confirmedPrefix.isNotEmpty()) {
                    confirmedPrefix = ""
                    return true
                }
                if (input.isEmpty()) return false
                input.deleteCharAt(input.lastIndex)
                pageIndex = 0
                return true
            }
            if (keysym == KeyCodes.KEYSYM_SPACE) {
                if (input.isEmpty()) return false
                return selectCandidateOnCurrentPage(sessionId, 0)
            }
            if (keysym == KeyCodes.KEYSYM_RETURN) {
                if (input.isEmpty()) return false
                pendingCommit = input.toString()
                clearComposition(sessionId)
                return true
            }
            if (keysym == KeyCodes.KEYSYM_ESCAPE) {
                if (input.isEmpty()) return false
                clearComposition(sessionId)
                return true
            }
            val ch = KeyCodes.charForKeysym(keysym) ?: return false
            if (ch in 'a'..'z' || ch in '2'..'9' || ch == '\'') {
                input.append(ch)
                pageIndex = 0
                return true
            }
            return false
        }

        override fun getCommitText(sessionId: Long): String? {
            val out = pendingCommit
            pendingCommit = null
            return out
        }

        override fun getSnapshot(sessionId: Long): RimeNativeSnapshot {
            val raw = input.toString()
            val pages = resolvePages(raw)
            val isComposing = raw.isNotEmpty()
            val safePage = if (pages.isEmpty()) 0 else pageIndex.coerceIn(0, pages.lastIndex)
            val isTrad = options[EngineOptions.TRADITIONALIZATION] == true
            val pageCandidates = pages.getOrNull(safePage).orEmpty().map { cand ->
                if (isTrad) s2tMap[cand] ?: cand else cand
            }
            val preeditDisplay = if (confirmedPrefix.isNotEmpty()) {
                val tail = raw.substringAfter('\'', raw)
                "$confirmedPrefix$tail"
            } else {
                raw
            }
            return RimeNativeSnapshot(
                schemaId = currentSchema,
                schemaName = currentSchema,
                isComposing = isComposing,
                isAsciiMode = options[EngineOptions.ASCII_MODE] == true,
                isTraditional = isTrad,
                preedit = preeditDisplay,
                commitTextPreview = pageCandidates.firstOrNull().orEmpty(),
                pageIndex = safePage,
                isLastPage = pages.isEmpty() || safePage >= pages.lastIndex,
                highlightedIndex = 0,
                candidateTexts = pageCandidates,
                candidateComments = List(pageCandidates.size) { null },
                rawInput = raw,
                caretPos = raw.length
            )
        }

        override fun selectCandidateOnCurrentPage(sessionId: Long, indexOnPage: Int): Boolean {
            val raw = input.toString()
            val pages = resolvePages(raw)
            val list = pages.getOrNull(pageIndex) ?: return false
            val base = list.getOrNull(indexOnPage) ?: return false
            val isTrad = options[EngineOptions.TRADITIONALIZATION] == true
            val text = if (isTrad) s2tMap[base] ?: base else base
            // Single-char "妈" on 2-syllable input demonstrates partial commit vs in-composition confirm
            if (confirmedPrefix.isEmpty() && base == "妈" && (raw == "ma'62" || raw == "6262")) {
                if (partialCommitMode) {
                    pendingCommit = text
                    input.clear()
                    input.append("62")
                    pageIndex = 0
                } else {
                    confirmedPrefix = text
                    pageIndex = 0
                }
                return true
            }
            pendingCommit = confirmedPrefix + text
            clearComposition(sessionId)
            return true
        }

        override fun changePage(sessionId: Long, backward: Boolean): Boolean {
            val pages = resolvePages(input.toString())
            if (pages.size <= 1) return false
            val next = if (backward) pageIndex - 1 else pageIndex + 1
            if (next !in pages.indices) return false
            pageIndex = next
            return true
        }

        override fun selectSchema(sessionId: Long, schemaId: String): Boolean {
            if (schemaId == "unsupported_schema") return false
            currentSchema = schemaId
            selectedSchemas.add(schemaId)
            // Mirror librime behavior: switching schema resets per-schema switches until re-applied
            options[EngineOptions.TRADITIONALIZATION] = false
            clearComposition(sessionId)
            return true
        }

        override fun getCurrentSchema(sessionId: Long): String = currentSchema

        override fun getOption(sessionId: Long, optionName: String): Boolean = options[optionName] == true

        override fun setOption(sessionId: Long, optionName: String, value: Boolean) {
            options[optionName] = value
            setOptionsLog.add(optionName to value)
        }

        override fun clearComposition(sessionId: Long) {
            input.clear()
            confirmedPrefix = ""
            pageIndex = 0
        }

        override fun commitComposition(sessionId: Long): Boolean = selectCandidateOnCurrentPage(sessionId, 0)

        override fun getSchemaList(): List<Pair<String, String>> = listOf(
            RimeEngineMetadata.SCHEMA_PINYIN_26 to "DuoIME 26键拼音",
            RimeEngineMetadata.SCHEMA_T9_PINYIN to "DuoIME 9键拼音",
            RimeEngineMetadata.SCHEMA_FLYPY to "DuoIME 小鹤双拼"
        )

        override fun getVersion(): String = "1.18.0"

        private fun resolvePages(raw: String): List<List<String>> {
            if (raw.isEmpty()) return emptyList()
            if (confirmedPrefix.isNotEmpty()) {
                return pagedDict["62"] ?: listOf(listOf(raw))
            }
            return pagedDict[raw] ?: listOf(listOf(raw))
        }
    }

    private fun testPaths(assetsUpdated: Boolean = false, withStagingDefaultYaml: Boolean = true): RimeWorkspacePaths {
        val root = Files.createTempDirectory("duoime-rime-engine-test").toFile()
        root.deleteOnExit()
        val stagingDir = File(root, "user/build").apply { mkdirs() }
        if (withStagingDefaultYaml) {
            File(stagingDir, "default.yaml").writeText("deployed: true\n")
        }
        return RimeWorkspacePaths(
            sharedDir = File(root, "shared"),
            userDir = File(root, "user"),
            prebuiltDir = File(root, "shared/build"),
            stagingDir = stagingDir,
            assetsUpdated = assetsUpdated
        )
    }

    private fun createEngine(
        bridge: FakeRimeNativeBridge,
        initialSchemaId: String = EngineSchemas.PINYIN_26,
        initialVariant: ChineseVariant = ChineseVariant.SIMPLIFIED,
        initialIncognito: Boolean = false,
        paths: RimeWorkspacePaths = testPaths()
    ): RimeInputEngine = RimeInputEngine(
        bridge = bridge,
        paths = paths,
        distributionVersion = "0.1.0",
        initialConfig = RimeInitialConfig(
            schemaId = initialSchemaId,
            variant = initialVariant,
            incognito = initialIncognito
        ),
        runNative = { block -> block() }
    )

    private fun typeChars(engine: RimeInputEngine, text: String) {
        for (ch in text) {
            engine.onKey(EngineKeyEvent.fromChar(ch))
        }
    }

    @Test
    fun pinyin26FlypyEnglishAndBidirectionalPagingWorkEndToEnd() {
        val bridge = FakeRimeNativeBridge()
        val engine = createEngine(bridge)
        assertEquals("1.18.0", engine.rimeVersion)
        assertEquals(EngineSchemas.PINYIN_26, engine.state.value.schemaId)
        assertEquals(InputMode.PINYIN_26, engine.state.value.mode)

        // 26-key Pinyin + bidirectional paging (forward then back to page 0)
        typeChars(engine, "nihao")
        val page0 = engine.state.value
        assertTrue(page0.isComposing)
        assertEquals(0, page0.pageIndex)
        assertFalse(page0.hasPrevPage)
        assertTrue(page0.hasNextPage)
        assertEquals(listOf("你好", "拟好", "尼浩"), page0.candidates.map { it.text })

        assertTrue(engine.page(forward = true))
        val page1 = engine.state.value
        assertEquals(1, page1.pageIndex)
        assertTrue(page1.hasPrevPage)
        assertFalse(page1.hasNextPage)
        assertEquals(listOf("泥嚎", "你号"), page1.candidates.map { it.text })
        assertFalse("Paging past last page must return false", engine.page(forward = true))

        assertTrue(engine.page(forward = false))
        assertEquals(0, engine.state.value.pageIndex)
        assertEquals(KeyProcessResult.CommitText("你好"), engine.selectCandidate(0))
        assertFalse(engine.state.value.isComposing)

        // Xiaohe Flypy schema
        assertTrue(engine.selectSchema(EngineSchemas.DOUBLE_PINYIN_FLYPY))
        assertEquals(RimeEngineMetadata.SCHEMA_FLYPY, bridge.selectedSchemas.last())
        assertEquals(EngineSchemas.DOUBLE_PINYIN_FLYPY, engine.state.value.schemaId)
        typeChars(engine, "nihc")
        assertEquals(KeyProcessResult.CommitText("你好"), engine.onKey(EngineKeyEvent(KeyCodes.KEYSYM_SPACE)))

        // English schema passes through cleanly
        assertTrue(engine.selectSchema(EngineSchemas.ENGLISH))
        assertEquals(InputMode.ENGLISH, engine.state.value.mode)
        assertEquals(KeyProcessResult.PassThrough, engine.onKey(EngineKeyEvent.fromChar('a')))

        engine.close()
        assertEquals(1, bridge.releaseCount)
        assertEquals(listOf(1L), bridge.destroyedSessions)
    }

    @Test
    fun t9SyllableCoordinatorHandles6262ReduplicationUnlockBackspaceAndPartialCommit() {
        val bridge = FakeRimeNativeBridge()
        val engine = createEngine(bridge, initialSchemaId = EngineSchemas.T9_PINYIN)
        assertEquals(InputMode.PINYIN_T9, engine.state.value.mode)
        assertEquals(EngineSchemas.T9_PINYIN, engine.state.value.schemaId)

        // Type 6262 -> rail offers "ma"
        typeChars(engine, "6262")
        val initialSnap = engine.state.value
        assertTrue(initialSnap.isComposing)
        assertTrue(initialSnap.lockedSyllables.isEmpty())
        val firstMaIdx = initialSnap.syllables.indexOf("ma")
        assertTrue("Expected 'ma' in 6262 options: ${initialSnap.syllables}", firstMaIdx >= 0)

        // 1st lock: "ma" -> replays "ma'62", offers second "ma"
        assertTrue(engine.selectSyllable(firstMaIdx))
        val afterLock1 = engine.state.value
        assertEquals(listOf("ma"), afterLock1.lockedSyllables)
        assertEquals("ma'62", afterLock1.preedit)
        val secondMaIdx = afterLock1.syllables.indexOf("ma")
        assertTrue("Expected second 'ma' option: ${afterLock1.syllables}", secondMaIdx >= 0)

        // 2nd lock: ["ma", "ma"] -> replays "ma'ma", yields "妈妈"
        assertTrue(engine.selectSyllable(secondMaIdx))
        val afterLock2 = engine.state.value
        assertEquals(listOf("ma", "ma"), afterLock2.lockedSyllables)
        assertEquals("ma'ma", afterLock2.preedit)
        assertTrue("妈妈" in afterLock2.candidates.map { it.text })

        // Backspace trims second "ma" lock while preserving first "ma" lock ("ma'6")
        assertEquals(KeyProcessResult.Consumed, engine.onKey(EngineKeyEvent(KeyCodes.KEYSYM_BACKSPACE)))
        assertEquals(listOf("ma"), engine.state.value.lockedSyllables)
        assertEquals("ma'6", engine.state.value.preedit)

        // Re-append '2' and test partial & full unlock
        typeChars(engine, "2")
        assertTrue(engine.selectSyllable(engine.state.value.syllables.indexOf("ma")))
        assertEquals(listOf("ma", "ma"), engine.state.value.lockedSyllables)

        assertTrue(engine.unlockSyllablesFrom(1))
        assertEquals(listOf("ma"), engine.state.value.lockedSyllables)
        assertEquals("ma'62", engine.state.value.preedit)

        assertTrue(engine.unlockSyllablesFrom(0))
        assertTrue(engine.state.value.lockedSyllables.isEmpty())
        assertEquals("6262", engine.state.value.preedit)

        // Partial commit mode: select single char "妈" -> commits "妈", re-syncs remaining "62"
        bridge.enableImmediatePartialCommitMode(true)
        assertTrue(engine.selectSyllable(engine.state.value.syllables.indexOf("ma")))
        assertEquals(KeyProcessResult.CommitText("妈"), engine.selectCandidate(1))
        val afterPartial = engine.state.value
        assertTrue(afterPartial.isComposing)
        assertTrue(afterPartial.lockedSyllables.isEmpty())
        assertTrue("ma" in afterPartial.syllables)
        assertEquals(KeyProcessResult.CommitText("妈"), engine.selectCandidate(0))
        assertFalse(engine.state.value.isComposing)

        // In-composition confirmed prefix mode: does not clobber confirmed "妈" when composing
        bridge.enableImmediatePartialCommitMode(false)
        typeChars(engine, "6262")
        assertTrue(engine.selectSyllable(engine.state.value.syllables.indexOf("ma")))
        assertEquals(KeyProcessResult.Consumed, engine.selectCandidate(1))
        assertTrue(engine.state.value.isComposing)
        assertTrue(engine.state.value.preedit.startsWith("妈"))
        assertFalse("Rail lock must defer while native confirmed prefix is active", engine.selectSyllable(0))
        assertEquals(KeyProcessResult.CommitText("妈妈"), engine.selectCandidate(0))
        assertFalse(engine.state.value.isComposing)

        engine.close()
    }

    @Test
    fun incognitoAndDisableUserDictSwapToCompanionSchemaPreservingLogicalIdAndTraditionalization() {
        val bridge = FakeRimeNativeBridge()
        val engine = createEngine(bridge, initialVariant = ChineseVariant.TRADITIONAL)

        assertEquals(RimeEngineMetadata.SCHEMA_PINYIN_26, bridge.selectedSchemas.last())
        assertEquals(EngineSchemas.PINYIN_26, engine.state.value.schemaId)
        assertEquals(ChineseVariant.TRADITIONAL, engine.state.value.variant)

        // Enabling INCOGNITO swaps native schema to *_incognito while preserving logical schemaId & S/T
        engine.setOption(EngineOptions.INCOGNITO, true)
        assertEquals("duoime_pinyin_26_incognito", bridge.selectedSchemas.last())
        assertEquals(EngineSchemas.PINYIN_26, engine.state.value.schemaId)
        assertEquals(ChineseVariant.TRADITIONAL, engine.state.value.variant)

        // Switching to T9 while incognito selects duoime_t9_incognito & preserves S/T output ("媽媽")
        assertTrue(engine.selectSchema(EngineSchemas.T9_PINYIN))
        assertEquals("duoime_t9_incognito", bridge.selectedSchemas.last())
        assertEquals(EngineSchemas.T9_PINYIN, engine.state.value.schemaId)
        typeChars(engine, "6262")
        assertEquals("媽媽", engine.state.value.candidates.first().text)

        // Turning off INCOGNITO while DISABLE_USER_DICT=true stays on *_incognito
        engine.setOption(EngineOptions.DISABLE_USER_DICT, true)
        engine.setOption(EngineOptions.INCOGNITO, false)
        assertEquals("duoime_t9_incognito", bridge.getCurrentSchema(1L))

        // Turning off both returns to regular duoime_t9
        engine.setOption(EngineOptions.DISABLE_USER_DICT, false)
        assertEquals("duoime_t9", bridge.selectedSchemas.last())
        assertEquals(EngineSchemas.T9_PINYIN, engine.state.value.schemaId)

        // Custom M2-2 schema degrades gracefully with empty T9 rail
        assertTrue(engine.selectSchema("custom_user_schema"))
        assertEquals("custom_user_schema", engine.state.value.schemaId)
        typeChars(engine, "6262")
        assertTrue(engine.state.value.syllables.isEmpty())
        assertTrue(engine.state.value.lockedSyllables.isEmpty())

        engine.close()
    }

    @Test(expected = UnsatisfiedLinkError::class)
    fun missingNativeLibraryPropagatesFromRimeInputEngineInit() {
        createEngine(FakeRimeNativeBridge(failAcquire = true))
    }

    @Test
    fun t9CompletionDigitsInsertBeforeDelimiterPreservingFollowingSyllable() {
        val bridge = FakeRimeNativeBridge()
        val engine = createEngine(bridge, initialSchemaId = EngineSchemas.T9_PINYIN)

        // Enter incomplete first syllable "6", delimiter "'", then second syllable "62" -> "6'62"
        typeChars(engine, "6'62")
        val options = engine.state.value.syllables
        val niIdx = options.indexOf("ni")
        assertTrue("Expected completion 'ni' for 6'62: $options", niIdx >= 0)

        // Selecting "ni" must insert missing '4' before '\'' -> "ni'62", NOT "6'624"
        assertTrue(engine.selectSyllable(niIdx))
        assertEquals(listOf("ni"), engine.state.value.lockedSyllables)
        assertEquals("ni'62", engine.state.value.preedit)

        // Following syllable "62" remains intact and lockable as "ma" -> "ni'ma"
        val maIdx = engine.state.value.syllables.indexOf("ma")
        assertTrue("Expected 'ma' for trailing 62: ${engine.state.value.syllables}", maIdx >= 0)
        assertTrue(engine.selectSyllable(maIdx))
        assertEquals(listOf("ni", "ma"), engine.state.value.lockedSyllables)
        assertEquals("ni'ma", engine.state.value.preedit)

        engine.close()
    }

    @Test
    fun unavailableInitialSchemaRejectsInitializationAndReleasesResources() {
        val bridge = FakeRimeNativeBridge()
        val error = runCatching {
            createEngine(bridge, initialSchemaId = "unsupported_schema")
        }.exceptionOrNull()
        assertTrue(
            "Expected IllegalStateException when initial schema selection fails, got: $error",
            error is IllegalStateException
        )
        assertEquals(listOf(1L), bridge.destroyedSessions)
        assertEquals(1, bridge.acquireCount)
        assertEquals(1, bridge.releaseCount)
    }

    @Test
    fun negativeArm64TaggedSessionIdWorksAcrossFullLifecycleAndFailureCleanup() {
        val taggedSessionId = 0xb4000072a1c34000uL.toLong()
        assertTrue("ARM64 Scudo tagged pointer must be negative as signed Long", taggedSessionId < 0L)

        val bridge = FakeRimeNativeBridge().apply { nextSession = taggedSessionId }
        val engine = createEngine(bridge)
        typeChars(engine, "nihao")
        assertTrue(engine.state.value.isComposing)
        assertTrue(engine.page(forward = true))
        assertTrue(engine.page(forward = false))
        assertEquals(KeyProcessResult.CommitText("你好"), engine.selectCandidate(0))

        assertTrue(engine.selectSchema(EngineSchemas.DOUBLE_PINYIN_FLYPY))
        assertTrue(engine.selectSchema(EngineSchemas.T9_PINYIN))
        typeChars(engine, "6262")
        assertTrue(engine.selectSyllable(engine.state.value.syllables.indexOf("ma")))
        assertTrue(engine.unlockSyllablesFrom(0))
        engine.setOption(EngineOptions.TRADITIONALIZATION, true)
        engine.reset()
        assertTrue(engine.deployWorkspace())

        engine.close()
        assertEquals(listOf(taggedSessionId), bridge.destroyedSessions)
        assertEquals(1, bridge.releaseCount)

        // Initial schema selection failure with negative tagged ID must destroySession(taggedId) & release runtime
        val failBridge = FakeRimeNativeBridge().apply { nextSession = taggedSessionId }
        val err = runCatching {
            createEngine(failBridge, initialSchemaId = "unsupported_schema")
        }.exceptionOrNull()
        assertTrue("Expected IllegalStateException, got $err", err is IllegalStateException)
        assertEquals(listOf(taggedSessionId), failBridge.destroyedSessions)
        assertEquals(1, failBridge.acquireCount)
        assertEquals(1, failBridge.releaseCount)
    }

    @Test
    fun initDeploysWorkspaceWhenAssetsUpdatedOrStagingDefaultYamlMissing() {
        val upToDateBridge = FakeRimeNativeBridge()
        createEngine(
            bridge = upToDateBridge,
            paths = testPaths(assetsUpdated = false, withStagingDefaultYaml = true)
        ).close()
        assertEquals(0, upToDateBridge.deployCount)

        val updatedAssetsBridge = FakeRimeNativeBridge()
        createEngine(
            bridge = updatedAssetsBridge,
            paths = testPaths(assetsUpdated = true, withStagingDefaultYaml = true)
        ).close()
        assertEquals(1, updatedAssetsBridge.deployCount)

        val missingStagingBridge = FakeRimeNativeBridge()
        createEngine(
            bridge = missingStagingBridge,
            paths = testPaths(assetsUpdated = false, withStagingDefaultYaml = false)
        ).close()
        assertEquals(1, missingStagingBridge.deployCount)

        val failingDeployBridge = FakeRimeNativeBridge(failDeploy = true)
        val deployErr = runCatching {
            createEngine(
                bridge = failingDeployBridge,
                paths = testPaths(assetsUpdated = true, withStagingDefaultYaml = false)
            )
        }.exceptionOrNull()
        assertTrue(
            "Expected IllegalStateException on deploy failure, got $deployErr",
            deployErr is IllegalStateException
        )
        assertEquals(1, failingDeployBridge.deployCount)
        assertEquals(1, failingDeployBridge.acquireCount)
        assertEquals(1, failingDeployBridge.releaseCount)
        assertTrue(failingDeployBridge.destroyedSessions.isEmpty())
    }
}

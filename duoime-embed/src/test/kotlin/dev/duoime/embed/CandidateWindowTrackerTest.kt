// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.embed

import dev.duoime.core.engine.EngineKeyEvent
import dev.duoime.core.engine.InputEngine
import dev.duoime.core.engine.InputSink
import dev.duoime.core.layout.KeyCodes
import dev.duoime.core.model.CandidateItem
import dev.duoime.core.model.ChineseScheme
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.KeyProcessResult
import dev.duoime.core.model.KeyboardPanel
import dev.duoime.core.model.Language
import dev.duoime.core.model.SinkCapabilities
import dev.duoime.engine.lite.LiteInputEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CandidateWindowTrackerTest {

    private class RecordingSink : InputSink {
        override val capabilities: SinkCapabilities = SinkCapabilities.STANDARD_IME
        val events = mutableListOf<String>()

        override fun commitText(text: CharSequence) {
            events.add("commit:$text")
        }

        override fun setComposing(text: CharSequence?) {
            events.add("compose:${text ?: "<clear>"}")
        }

        override fun deleteBackward(count: Int) {
            events.add("delete:$count")
        }

        override fun sendKey(keyCode: Int, metaState: Int) {
            events.add("key:$keyCode:$metaState")
        }

        override fun performEditorAction(action: Int) {
            events.add("action:$action")
        }

        override fun sendHostCommand(id: String, payload: String): Boolean {
            events.add("host:$id:$payload")
            return true
        }
    }

    private fun typeChars(controller: KeyboardController, text: String) {
        for (ch in text) {
            controller.dispatchKeyAction(KeyAction.Engine(KeyCodes.keysymForChar(ch)))
        }
    }

    @Test
    fun trackerAutoAppendsMultiPageCandidatesPreservesPage0ForSpaceAndSelectsCrossPage() {
        val engine = LiteInputEngine.createDefault()
        val tracker = CandidateWindowTracker()

        engine.onKey(dev.duoime.core.engine.EngineKeyEvent.fromChar('y'))
        engine.onKey(dev.duoime.core.engine.EngineKeyEvent.fromChar('i'))
        tracker.syncWithSnapshot(engine.state.value)

        val page0Count = tracker.items.size
        assertTrue("Expected non-empty page 0 candidates for 'yi'", page0Count > 0)
        assertTrue("Expected more pages for 'yi'", tracker.hasMore)
        val primaryText = tracker.items[0].text

        // Load page 1 -> appends new items AND keeps engine on page 0
        assertTrue(tracker.loadMore(engine))
        assertEquals("Engine must remain on page 0 after loadMore", 0, engine.state.value.pageIndex)
        assertTrue("Page 1 must expand window candidates", tracker.items.size > page0Count)
        assertEquals(1, tracker.items[page0Count].pageIndex)

        val page1TargetIdx = page0Count
        val page1TargetText = tracker.items[page1TargetIdx].text

        // Load page 2 as well, then select an item from page 1 cleanly
        if (tracker.hasMore) {
            assertTrue(tracker.loadMore(engine))
            assertEquals(0, engine.state.value.pageIndex)
        }
        val commitResult = tracker.selectWindowCandidate(engine, page1TargetIdx)
        assertEquals(dev.duoime.core.model.KeyProcessResult.CommitText(page1TargetText), commitResult)
        assertFalse(engine.state.value.isComposing)
        assertTrue(tracker.items.isEmpty())

        // Re-compose 'yi', load page 1, and press Space -> must still commit page 0's primaryText
        engine.onKey(dev.duoime.core.engine.EngineKeyEvent.fromChar('y'))
        engine.onKey(dev.duoime.core.engine.EngineKeyEvent.fromChar('i'))
        tracker.syncWithSnapshot(engine.state.value)
        assertTrue(tracker.loadMore(engine))
        val spaceRes = engine.onKey(dev.duoime.core.engine.EngineKeyEvent(KeyCodes.KEYSYM_SPACE))
        assertEquals(dev.duoime.core.model.KeyProcessResult.CommitText(primaryText), spaceRes)
    }

    @Test
    fun controllerWindowSelectionCommitPanelSymbolKeepOpenAndT9ComposingLayoutWorkEndToEnd() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = RecordingSink()
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        controller.attachSink(sink)
        advanceUntilIdle()

        // 1) Multi-page window append & cross-page selection via KeyboardController
        typeChars(controller, "yi")
        advanceUntilIdle()
        val initialSize = controller.uiState.value.windowCandidates.size
        assertTrue(initialSize > 0)
        assertTrue(controller.uiState.value.hasMoreWindowCandidates)

        controller.loadMoreCandidates()
        advanceUntilIdle()
        val expanded = controller.uiState.value.windowCandidates
        assertTrue(expanded.size > initialSize)
        val page1Candidate = expanded[initialSize]
        assertEquals(1, page1Candidate.pageIndex)

        sink.events.clear()
        controller.selectWindowCandidate(page1Candidate.globalIndex)
        advanceUntilIdle()
        assertEquals(listOf("commit:${page1Candidate.text}", "compose:<clear>"), sink.events)
        assertTrue(controller.uiState.value.windowCandidates.isEmpty())

        // 2) CommitPanelSymbol: keepOpen = true keeps SYMBOL panel open; keepOpen = false closes unlocked panel
        typeChars(controller, "nihao")
        advanceUntilIdle()
        controller.openPanel(KeyboardPanel.SYMBOL)
        advanceUntilIdle()
        assertEquals(KeyboardPanel.SYMBOL, controller.uiState.value.derived.panel)
        assertFalse(controller.uiState.value.derived.panelLocked)
        sink.events.clear()

        controller.dispatchKeyAction(KeyAction.CommitPanelSymbol("★", keepOpen = true))
        advanceUntilIdle()
        assertEquals(listOf("commit:你好", "compose:<clear>", "commit:★"), sink.events)
        assertEquals(
            "Long-press CommitPanelSymbol(keepOpen=true) must keep SYMBOL panel open",
            KeyboardPanel.SYMBOL,
            controller.uiState.value.derived.panel
        )

        sink.events.clear()
        controller.dispatchKeyAction(KeyAction.CommitPanelSymbol("☆", keepOpen = false))
        advanceUntilIdle()
        assertEquals(listOf("commit:☆"), sink.events)
        assertEquals(KeyboardPanel.PRIMARY, controller.uiState.value.derived.panel)

        // 3) Tapping 9 from EN (SelectChineseScheme(PINYIN_T9)) switches to CHINESE + T9; composing updates T9 keys
        controller.setLanguage(Language.ENGLISH)
        advanceUntilIdle()
        assertEquals(InputMode.ENGLISH, controller.uiState.value.activeLayout.mode)

        controller.dispatchKeyAction(KeyAction.SelectChineseScheme(ChineseScheme.PINYIN_T9))
        advanceUntilIdle()
        assertEquals(Language.CHINESE, controller.uiState.value.derived.effectiveLanguage)
        assertEquals(InputMode.PINYIN_T9, controller.uiState.value.activeLayout.mode)
        assertTrue(controller.uiState.value.activeLayout.id.startsWith("t9_idle"))

        typeChars(controller, "64")
        advanceUntilIdle()
        assertTrue(controller.uiState.value.snapshot.isComposing)
        assertTrue(controller.uiState.value.activeLayout.id.startsWith("t9_comp"))
        assertTrue(
            controller.uiState.value.activeLayout.rows[1].keys.any { it.action == KeyAction.ClearPreedit }
        )
        controller.close()
    }

    @Test
    fun loadMorePublishesHasMoreFalseWhenTrailingPageIsExhausted() = runTest {
        val failingPageEngine = object : InputEngine {
            private val _state = MutableStateFlow(
                EngineSnapshot(
                    preedit = "yi",
                    composingText = "yi",
                    candidates = listOf(
                        CandidateItem(text = "一", indexOnPage = 0),
                        CandidateItem(text = "以", indexOnPage = 1)
                    ),
                    pageIndex = 0,
                    hasNextPage = true,
                    isComposing = true
                )
            )
            override val state: StateFlow<EngineSnapshot> = _state

            override fun onKey(event: EngineKeyEvent): KeyProcessResult = KeyProcessResult.Consumed

            override fun selectCandidate(indexOnPage: Int): KeyProcessResult = KeyProcessResult.PassThrough

            override fun page(forward: Boolean): Boolean = false

            override fun setOption(name: String, on: Boolean) = Unit

            override fun selectSchema(id: String): Boolean = true

            override fun reset() {
                _state.value = EngineSnapshot.EMPTY
            }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { failingPageEngine }
        )
        advanceUntilIdle()
        assertTrue(controller.uiState.value.hasMoreWindowCandidates)

        // When page(true) returns false, loadMore sets hasMore=false and controller must publish that transition
        controller.loadMoreCandidates()
        advanceUntilIdle()
        assertFalse(
            "Stale hasMoreWindowCandidates=true must clear when loadMore exhausts pages",
            controller.uiState.value.hasMoreWindowCandidates
        )
        controller.close()
    }
}

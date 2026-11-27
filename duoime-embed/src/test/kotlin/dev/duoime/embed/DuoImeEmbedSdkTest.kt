// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.embed

import dev.duoime.core.layout.KeyCodes
import dev.duoime.core.model.CandidatePaging
import dev.duoime.core.model.EditorContext
import dev.duoime.core.model.FieldKind
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.KeyboardPanel
import dev.duoime.core.model.LangKeyPlacement
import dev.duoime.core.model.QuickPin
import dev.duoime.core.model.SinkCapabilities
import dev.duoime.core.model.SymbolTabId
import dev.duoime.core.protocol.ExtensionConfig
import dev.duoime.engine.lite.LiteInputEngine
import dev.duoime.ui.theme.DuoThemePreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DuoImeEmbedSdkTest {

    private class RecordingEmbedBridge(
        var selectionSnapshot: EmbedSelectionSnapshot? = null
    ) : EmbedConnectionBridge {
        val calls = mutableListOf<String>()
        var readSelectionCount: Int = 0

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            calls += "commit:$text:$newCursorPosition"
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            calls += "compose:$text:$newCursorPosition"
            return true
        }

        override fun finishComposingText(): Boolean {
            calls += "finish"
            return true
        }

        override fun sendDownUpKeyEvent(keyCode: Int, metaState: Int): Boolean {
            calls += "key:$keyCode:$metaState"
            return true
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            calls += "action:$editorAction"
            return true
        }

        override fun performPrivateCommand(action: String, payload: String): Boolean {
            calls += "cmd:$action:$payload"
            return true
        }

        override fun readSelectionSnapshot(): EmbedSelectionSnapshot? {
            readSelectionCount++
            return selectionSnapshot
        }
    }

    @Test
    fun embedInputConnectionSinkHandlesComposingCommitDeleteKeysAndDisconnectCleanup() {
        val bridge = RecordingEmbedBridge()
        val sink = EmbedInputConnectionSink(connectionProvider = { bridge })

        sink.setComposing("ni")
        sink.setComposing(null)
        sink.setComposing("hao")
        sink.commitText("好")
        // Already committed -> disconnect must NOT send redundant finishComposingText
        sink.finishComposingOnDisconnect()

        sink.setComposing("shi")
        sink.finishComposingOnDisconnect()

        sink.deleteBackward(2)
        sink.sendKey(KeyCodes.KEYCODE_ENTER, 0)
        sink.performEditorAction(EditorContext.IME_ACTION_SEND)
        assertTrue(sink.sendHostCommand("esc", "1"))

        assertEquals(
            listOf(
                "compose:ni:1",
                "compose::1",
                "finish",
                "compose:hao:1",
                "commit:好:1",
                "compose:shi:1",
                "finish",
                "key:${KeyCodes.KEYCODE_DEL}:0",
                "key:${KeyCodes.KEYCODE_DEL}:0",
                "key:${KeyCodes.KEYCODE_ENTER}:0",
                "action:${EditorContext.IME_ACTION_SEND}",
                "cmd:${EmbedInputConnectionSink.PRIVATE_CMD_PREFIX}esc:1"
            ),
            bridge.calls
        )

        // Terminal capabilities suppress setComposing
        bridge.calls.clear()
        sink.updateCapabilities(SinkCapabilities.TERMINAL)
        sink.setComposing("ls")
        sink.finishComposingOnDisconnect()
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun chromePolicyAndHostChromeResolveCapabilitiesAndEinkPagedDefault() {
        assertEquals(listOf(QuickPin.SCHEME_SWITCH), ChromePolicy.EMBED_DEFAULT.pins)
        assertEquals(listOf(QuickPin.SCHEME_SWITCH, QuickPin.SWAP_SCREEN), ChromePolicy.APP_DEFAULT.pins)

        val darkPolicy = ChromePolicy(themePreset = DuoThemePreset.DARK)
        val einkPolicy = ChromePolicy(themePreset = DuoThemePreset.EINK)
        val explicitScrollOnEink = ChromePolicy(
            themePreset = DuoThemePreset.EINK,
            candidatePaging = CandidatePaging.SCROLL
        )

        assertEquals(CandidatePaging.SCROLL, darkPolicy.resolvedCandidatePaging(derivedOverride = null))
        assertEquals(CandidatePaging.PAGED, darkPolicy.resolvedCandidatePaging(derivedOverride = CandidatePaging.PAGED))
        assertEquals(CandidatePaging.PAGED, einkPolicy.resolvedCandidatePaging(derivedOverride = null))
        assertEquals(
            CandidatePaging.SCROLL,
            einkPolicy.resolvedCandidatePaging(derivedOverride = CandidatePaging.SCROLL)
        )
        assertEquals(
            CandidatePaging.SCROLL,
            explicitScrollOnEink.resolvedCandidatePaging(derivedOverride = CandidatePaging.PAGED)
        )

        assertFalse(HostChrome.NONE.canSwapScreen)
        assertFalse(HostChrome.NONE.canSwitchIme)
        assertFalse(HostChrome.NONE.showDismiss)

        val partial = HostChrome(onDismiss = {})
        assertFalse(partial.canSwapScreen)
        assertFalse(partial.canSwitchIme)
        assertTrue(partial.showDismiss)

        val full = HostChrome(onSwapScreen = {}, onSwitchIme = {}, onDismiss = {})
        assertTrue(full.canSwapScreen)
        assertTrue(full.canSwitchIme)
        assertTrue(full.showDismiss)
    }

    @Test
    fun updateHostPolicySetsDefaultsWithoutOverridingExplicitEditorExtensionOverrides() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        advanceUntilIdle()

        // Host policy sets HOST lang key placement and CLI default symbol tab
        controller.updateHostPolicy(
            langKeyPlacement = LangKeyPlacement.HOST,
            initialSymbolTab = SymbolTabId.CLI
        )
        advanceUntilIdle()
        assertEquals(LangKeyPlacement.HOST, controller.uiState.value.derived.langKeyPlacement)
        assertEquals(SymbolTabId.CLI, controller.uiState.value.derived.symbolTab)
        assertTrue(
            "HOST placement must omit ToggleLanguage on QWERTY",
            controller.uiState.value.activeLayout.rows.flattenKeys().none { it.action == KeyAction.ToggleLanguage }
        )

        // Opening SYMBOL panel picks up host policy's initialSymbolTab
        controller.openPanel(KeyboardPanel.SYMBOL)
        advanceUntilIdle()
        assertEquals(SymbolTabId.CLI, controller.uiState.value.derived.symbolTab)
        controller.closePanel()

        // Explicit dev.duoime.ext.v1 EditorInfo overrides MUST win over host policy
        controller.updateEditorContext(
            EditorContext(
                packageName = "dev.example.host",
                fieldKind = FieldKind.TEXT,
                extension = ExtensionConfig(
                    langKeyPlacement = LangKeyPlacement.GRID,
                    defaultSymbolTab = SymbolTabId.PAIR
                )
            )
        )
        advanceUntilIdle()
        assertEquals(LangKeyPlacement.GRID, controller.uiState.value.derived.langKeyPlacement)
        assertEquals(SymbolTabId.PAIR, controller.uiState.value.derived.symbolTab)
        assertTrue(
            controller.uiState.value.activeLayout.rows.flattenKeys().any { it.action == KeyAction.ToggleLanguage }
        )

        // Explicit extension defaultSymbolTab=COMMON also wins over hostPolicy initialSymbolTab=CLI
        controller.updateEditorContext(
            EditorContext(
                packageName = "dev.example.host2",
                fieldKind = FieldKind.TEXT,
                extension = ExtensionConfig(defaultSymbolTab = SymbolTabId.COMMON)
            )
        )
        advanceUntilIdle()
        assertEquals(SymbolTabId.COMMON, controller.uiState.value.derived.symbolTab)
        assertEquals(LangKeyPlacement.HOST, controller.uiState.value.derived.langKeyPlacement)
        controller.close()
    }

    @Test
    fun interceptDuoTextInputSessionDelegatesWhenDisabledAndCleansUpOnCancelWhenEnabled() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        val imeState = DuoImeState(controller)
        advanceUntilIdle()

        // 1) enabled = false delegates directly to nextHandler without activating session
        var delegated = false
        var bindingCreated = false
        val disabledJob = launch {
            try {
                interceptDuoTextInputSession(
                    enabled = false,
                    state = imeState,
                    createBinding = {
                        bindingCreated = true
                        error("Must not create binding when disabled")
                    },
                    delegateToNext = {
                        delegated = true
                        throw CancellationException("delegated-done")
                    }
                )
            } catch (_: CancellationException) {
                // Expected termination from test delegate
            }
        }
        runCurrent()
        assertTrue(delegated)
        assertFalse(bindingCreated)
        assertFalse(imeState.isInputSessionActive.value)
        assertNull(imeState.activeSink)
        disabledJob.cancel()

        // 2) enabled = true binds sink & EditorContext, tracks active session, flushes & detaches on cancel
        val bridge = RecordingEmbedBridge()
        val sink = EmbedInputConnectionSink(connectionProvider = { bridge })
        val editorCtx = EmbedEditorInfoMapper.buildEditorContext(
            packageName = "dev.example.embed",
            inputType = EmbedEditorInfoMapper.TYPE_CLASS_TEXT,
            imeOptions = EditorContext.IME_ACTION_GO
        )
        val sessionJob = launch {
            interceptDuoTextInputSession(
                enabled = true,
                state = imeState,
                createBinding = { TextInputSessionBinding(editorCtx, sink) },
                delegateToNext = { error("Must not delegate when enabled") }
            )
        }
        runCurrent()
        advanceUntilIdle()

        assertTrue(imeState.isInputSessionActive.value)
        assertEquals(sink, imeState.activeSink)
        assertEquals(FieldKind.TEXT, imeState.uiState.value.derived.fieldKind)

        // Drive real Chinese composition through controller, then cancel intercepted session ->
        // bridge receives finishComposingText AND engine/UI composing state is cleanly reset!
        controller.dispatchKeyAction(KeyAction.Engine('n'.code))
        runCurrent()
        assertTrue(imeState.uiState.value.snapshot.isComposing)
        assertTrue(imeState.uiState.value.snapshot.preedit.isNotEmpty())

        sessionJob.cancel()
        advanceUntilIdle()

        assertFalse(imeState.isInputSessionActive.value)
        assertNull(imeState.activeSink)
        assertFalse(
            "Disconnect must reset engine composing state so no ghost candidates remain",
            imeState.uiState.value.snapshot.isComposing
        )
        assertEquals("", imeState.uiState.value.snapshot.preedit)
        assertTrue(imeState.uiState.value.snapshot.candidates.isEmpty())
        assertTrue("Expected finishComposingText on disconnect, got ${bridge.calls}", "finish" in bridge.calls)
        imeState.close()
    }

    @Test
    fun embedSelectionTrackerObservedSelectionToleratesComposeAsyncLagAndDetectsExternalChanges() {
        var nowMs = 1_000L
        val tracker = EmbedSelectionTracker(clockMs = { nowMs })
        tracker.onStartInput(initialSelStart = 0, initialSelEnd = 0)

        // Prediction queued for "n"; Compose hasn't flushed the frame yet so baseline (0,0) is read
        tracker.recordSetComposing("n")
        assertEquals(1, tracker.pendingPredictionCount)
        nowMs += 10L
        assertFalse(
            "Pre-frame baseline read during Compose async lag must not false-reset",
            tracker.onObservedSelection(isComposing = true, selStart = 0, selEnd = 0)
        )
        assertEquals(1, tracker.pendingPredictionCount)

        // Frame flushes "n" -> (1,1) matches queued prediction and preserves compStart=0..1
        nowMs += 15L
        assertFalse(
            "Flushed composing prediction must match and drain cleanly",
            tracker.onObservedSelection(isComposing = true, selStart = 1, selEnd = 1)
        )
        assertEquals(0, tracker.pendingPredictionCount)

        // Next composing letter replaces 0..1 with "ni" -> predicts (2,2, comp=0..2)
        nowMs += 15L
        tracker.recordSetComposing("ni")
        assertFalse(
            "Pre-frame lag reading previous baseline (1,1) must not false-reset",
            tracker.onObservedSelection(isComposing = true, selStart = 1, selEnd = 1)
        )
        nowMs += 15L
        assertFalse(
            "Flushed composing prediction (2,2) must confirm compStart preservation",
            tracker.onObservedSelection(isComposing = true, selStart = 2, selEnd = 2)
        )
        assertEquals(0, tracker.pendingPredictionCount)

        // External cursor jump to (0,0) while composing -> reset immediately
        nowMs += 20L
        assertTrue(
            "External cursor move while composing must trigger reset",
            tracker.onObservedSelection(isComposing = true, selStart = 0, selEnd = 0)
        )
        assertEquals(0, tracker.pendingPredictionCount)

        // Negative indices are ignored safely without corrupting known baseline
        assertFalse(tracker.onObservedSelection(isComposing = true, selStart = -1, selEnd = -1))

        // Unknown baseline re-baselines first without false reset, then detects external selection
        tracker.onStartInput(initialSelStart = -1, initialSelEnd = -1)
        assertFalse(tracker.isBaselineKnown)
        assertFalse(tracker.onObservedSelection(isComposing = true, selStart = 4, selEnd = 4))
        assertTrue(tracker.isBaselineKnown)
        assertTrue(tracker.onObservedSelection(isComposing = true, selStart = 1, selEnd = 3))
    }

    @Test
    fun interceptDuoTextInputSessionMonitorResetsOnExternalMoveSelectionAndClearWithoutStaleWrites() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        val imeState = DuoImeState(controller)
        advanceUntilIdle()

        val bridge = RecordingEmbedBridge(selectionSnapshot = EmbedSelectionSnapshot(0, 0))
        val sink = EmbedInputConnectionSink(
            connectionProvider = { bridge },
            clockMs = { testScheduler.currentTime }
        ).apply {
            seedInitialSelection(initialSelStart = 0, initialSelEnd = 0)
        }
        val editorCtx = EmbedEditorInfoMapper.buildEditorContext(
            packageName = "dev.example.embed",
            inputType = EmbedEditorInfoMapper.TYPE_CLASS_TEXT,
            imeOptions = EditorContext.IME_ACTION_DONE
        )
        val sessionJob = launch {
            interceptDuoTextInputSession(
                enabled = true,
                state = imeState,
                createBinding = { TextInputSessionBinding(editorCtx, sink) },
                delegateToNext = { error("Must not delegate when enabled") },
                selectionPollIntervalMs = 50L
            )
        }
        runCurrent()

        // Completely dormant while idle: advancing virtual time triggers zero selection polls
        advanceTimeBy(250L)
        runCurrent()
        assertEquals("Monitor must stay dormant while idle", 0, bridge.readSelectionCount)

        // 1) External cursor move mid-composition
        controller.dispatchKeyAction(KeyAction.Engine('n'.code))
        controller.dispatchKeyAction(KeyAction.Engine('i'.code))
        runCurrent()
        assertTrue(imeState.uiState.value.snapshot.isComposing)
        // Compose async lag: first poll still sees (0,0) -> NO false reset
        advanceTimeBy(50L)
        runCurrent()
        assertTrue("Async lag before flush must not reset composition", imeState.uiState.value.snapshot.isComposing)
        // Compose flushes "ni" -> (2,2)
        bridge.selectionSnapshot = EmbedSelectionSnapshot(2, 2)
        advanceTimeBy(50L)
        runCurrent()
        assertTrue(imeState.uiState.value.snapshot.isComposing)
        assertTrue(imeState.uiState.value.snapshot.candidates.isNotEmpty())

        bridge.calls.clear()
        bridge.selectionSnapshot = EmbedSelectionSnapshot(0, 0)
        advanceTimeBy(50L)
        runCurrent()
        assertFalse(imeState.uiState.value.snapshot.isComposing)
        assertEquals("", imeState.uiState.value.snapshot.preedit)
        assertTrue(imeState.uiState.value.snapshot.candidates.isEmpty())
        assertTrue(
            "External cursor move must not emit stale finish/setComposing: ${bridge.calls}",
            bridge.calls.isEmpty()
        )
        advanceUntilIdle()

        // 2) External range selection mid-composition (also verifies idle cursor move re-baselining)
        bridge.selectionSnapshot = EmbedSelectionSnapshot(6, 6)
        controller.dispatchKeyAction(KeyAction.Engine('n'.code))
        controller.dispatchKeyAction(KeyAction.Engine('i'.code))
        runCurrent()
        bridge.selectionSnapshot = EmbedSelectionSnapshot(8, 8)
        advanceTimeBy(50L)
        runCurrent()
        assertTrue(imeState.uiState.value.snapshot.isComposing)

        bridge.calls.clear()
        bridge.selectionSnapshot = EmbedSelectionSnapshot(0, 4)
        advanceTimeBy(50L)
        runCurrent()
        assertFalse(imeState.uiState.value.snapshot.isComposing)
        assertEquals("", imeState.uiState.value.snapshot.preedit)
        assertTrue(imeState.uiState.value.snapshot.candidates.isEmpty())
        assertTrue("External selection must not emit stale writes: ${bridge.calls}", bridge.calls.isEmpty())
        advanceUntilIdle()

        // 3) External field clear mid-composition
        bridge.selectionSnapshot = EmbedSelectionSnapshot(6, 6)
        controller.dispatchKeyAction(KeyAction.Engine('n'.code))
        controller.dispatchKeyAction(KeyAction.Engine('i'.code))
        runCurrent()
        bridge.selectionSnapshot = EmbedSelectionSnapshot(8, 8)
        advanceTimeBy(50L)
        runCurrent()
        assertTrue(imeState.uiState.value.snapshot.isComposing)

        bridge.calls.clear()
        bridge.selectionSnapshot = EmbedSelectionSnapshot(0, 0)
        advanceTimeBy(50L)
        runCurrent()
        assertFalse(imeState.uiState.value.snapshot.isComposing)
        assertEquals("", imeState.uiState.value.snapshot.preedit)
        assertTrue(imeState.uiState.value.snapshot.candidates.isEmpty())
        assertTrue("External clear must not emit stale writes: ${bridge.calls}", bridge.calls.isEmpty())

        sessionJob.cancel()
        advanceUntilIdle()
        imeState.close()
    }

    @Test
    fun subIntervalSyncSessionSelectionBeforeCandidateTapPreventsStaleCommitAtMovedCursor() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        val imeState = DuoImeState(controller)
        advanceUntilIdle()

        val bridge = RecordingEmbedBridge(selectionSnapshot = EmbedSelectionSnapshot(0, 0))
        val sink = EmbedInputConnectionSink(
            connectionProvider = { bridge },
            clockMs = { testScheduler.currentTime }
        ).apply {
            seedInitialSelection()
        }
        val editorCtx = EmbedEditorInfoMapper.buildEditorContext(
            packageName = "dev.example.embed",
            inputType = EmbedEditorInfoMapper.TYPE_CLASS_TEXT,
            imeOptions = EditorContext.IME_ACTION_DONE
        )
        val sessionJob = launch {
            interceptDuoTextInputSession(
                enabled = true,
                state = imeState,
                createBinding = { TextInputSessionBinding(editorCtx, sink) },
                delegateToNext = { error("Must not delegate when enabled") },
                selectionPollIntervalMs = 50L
            )
        }
        runCurrent()

        controller.dispatchKeyAction(KeyAction.Engine('n'.code))
        controller.dispatchKeyAction(KeyAction.Engine('i'.code))
        runCurrent()
        bridge.selectionSnapshot = EmbedSelectionSnapshot(2, 2)
        advanceTimeBy(50L)
        runCurrent()
        assertTrue(imeState.uiState.value.snapshot.isComposing)
        assertTrue(imeState.uiState.value.snapshot.candidates.isNotEmpty())

        // User moves cursor to (0,0) and taps candidate #0 only 10ms later (before 50ms monitor tick)
        bridge.calls.clear()
        bridge.selectionSnapshot = EmbedSelectionSnapshot(0, 0)
        advanceTimeBy(10L)
        val wasReset = imeState.syncSessionSelectionIfComposing()
        if (!wasReset) {
            controller.selectCandidate(0)
        }
        runCurrent()

        assertTrue("Sub-interval pre-dispatch check must detect moved cursor immediately", wasReset)
        assertFalse(imeState.uiState.value.snapshot.isComposing)
        assertEquals("", imeState.uiState.value.snapshot.preedit)
        assertTrue(imeState.uiState.value.snapshot.candidates.isEmpty())
        assertTrue(
            "Sub-interval reset must not commit stale candidate or emit stale finish/compose: ${bridge.calls}",
            bridge.calls.isEmpty()
        )

        // Subsequent keystroke right after sub-interval cursor jump starts fresh composition at (0,0)
        controller.dispatchKeyAction(KeyAction.Engine('n'.code))
        controller.dispatchKeyAction(KeyAction.Engine('i'.code))
        runCurrent()
        bridge.selectionSnapshot = EmbedSelectionSnapshot(2, 2)
        advanceTimeBy(50L)
        runCurrent()
        bridge.calls.clear()
        bridge.selectionSnapshot = EmbedSelectionSnapshot(0, 0)
        advanceTimeBy(10L)
        assertTrue(imeState.syncSessionSelectionIfComposing())
        controller.dispatchKeyAction(KeyAction.Engine('a'.code))
        runCurrent()

        assertTrue(imeState.uiState.value.snapshot.isComposing)
        assertEquals("a", imeState.uiState.value.snapshot.preedit)
        assertEquals(listOf("compose:a:1"), bridge.calls)

        sessionJob.cancel()
        advanceUntilIdle()
        imeState.close()
    }

    private fun List<dev.duoime.core.layout.KeyRowSpec>.flattenKeys() = flatMap { it.keys }
}

// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.embed

import dev.duoime.core.engine.EngineKeyEvent
import dev.duoime.core.engine.InputEngine
import dev.duoime.core.engine.InputSink
import dev.duoime.core.layout.KeyCodes
import dev.duoime.core.model.EditorContext
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.KeyProcessResult
import dev.duoime.core.model.SinkCapabilities
import dev.duoime.core.protocol.ExtensionActionType
import dev.duoime.core.protocol.ExtensionToolbarButton
import dev.duoime.engine.lite.LiteInputEngine
import dev.duoime.engine.lite.UserLearningStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardControllerTest {

    private class RecordingInputSink(
        override val capabilities: SinkCapabilities = SinkCapabilities.STANDARD_IME
    ) : InputSink {
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

    private class FlakyFakeEngine(private var failNextKey: Boolean = true) : InputEngine {
        private val _state = MutableStateFlow(EngineSnapshot.EMPTY)
        override val state: StateFlow<EngineSnapshot> = _state
        var resetCount: Int = 0
        val selectedSchemas = mutableListOf<String>()

        override fun onKey(event: EngineKeyEvent): KeyProcessResult {
            if (failNextKey) {
                failNextKey = false
                error("Simulated engine crash")
            }
            val ch = KeyCodes.charForKeysym(event.keysym) ?: return KeyProcessResult.PassThrough
            return KeyProcessResult.CommitText(ch.toString())
        }

        override fun selectCandidate(indexOnPage: Int): KeyProcessResult = KeyProcessResult.PassThrough

        override fun page(forward: Boolean): Boolean = false

        override fun setOption(name: String, on: Boolean) = Unit

        override fun selectSchema(id: String): Boolean {
            selectedSchemas.add(id)
            return true
        }

        override fun reset() {
            resetCount++
            _state.value = EngineSnapshot.EMPTY
        }
    }

    private class StickyComposingPassThroughEngine(
        private val initialComposing: String = "raw"
    ) : InputEngine {
        private val _state = MutableStateFlow(
            EngineSnapshot(
                preedit = initialComposing,
                composingText = initialComposing,
                isComposing = true
            )
        )
        override val state: StateFlow<EngineSnapshot> = _state

        override fun onKey(event: EngineKeyEvent): KeyProcessResult = KeyProcessResult.PassThrough

        override fun selectCandidate(indexOnPage: Int): KeyProcessResult = KeyProcessResult.PassThrough

        override fun page(forward: Boolean): Boolean = false

        override fun setOption(name: String, on: Boolean) = Unit

        override fun selectSchema(id: String): Boolean = true

        override fun reset() {
            _state.value = EngineSnapshot.EMPTY
        }
    }

    private fun typeEngineChars(controller: KeyboardController, text: String) {
        for (ch in text) {
            controller.dispatchKeyAction(KeyAction.Engine(KeyCodes.keysymForChar(ch)))
        }
    }

    @Test
    fun partialCommitAppliesCommitTextStrictlyBeforeSetComposing() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = RecordingInputSink(SinkCapabilities.STANDARD_IME)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        controller.attachSink(sink)
        advanceUntilIdle()
        assertTrue(controller.uiState.value.isEngineReady)

        typeEngineChars(controller, "nihaoshijie")
        advanceUntilIdle()
        sink.events.clear()

        val partialIdx = controller.uiState.value.snapshot.candidates.indexOfFirst { it.text == "你好" }
        assertTrue("Expected partial candidate 你好", partialIdx >= 0)

        controller.selectCandidate(partialIdx)
        advanceUntilIdle()

        // Strict ordering: CommitText("你好") MUST precede setComposing("shi'jie")
        assertEquals(listOf("commit:你好", "compose:shi'jie"), sink.events)
        assertTrue(controller.uiState.value.snapshot.isComposing)

        sink.events.clear()
        controller.dispatchKeyAction(KeyAction.Space)
        advanceUntilIdle()
        assertEquals(listOf("commit:世界", "compose:<clear>"), sink.events)
        assertFalse(controller.uiState.value.snapshot.isComposing)
        controller.close()
    }

    @Test
    fun terminalSinkDisablesComposingAndReceivesRawEnterKey() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = RecordingInputSink(SinkCapabilities.TERMINAL)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        controller.attachSink(sink)
        advanceUntilIdle()

        typeEngineChars(controller, "nihao")
        controller.dispatchKeyAction(KeyAction.Space)
        controller.dispatchKeyAction(KeyAction.Enter)
        advanceUntilIdle()

        assertTrue("Terminal sink must never receive setComposing", sink.events.none { it.startsWith("compose:") })
        assertEquals(listOf("commit:你好", "key:${KeyCodes.KEYCODE_ENTER}:0"), sink.events)
        controller.close()
    }

    @Test
    fun enterBehaviorMasksImeOptionsAndSupportsNewlineVsEditorAction() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = RecordingInputSink(SinkCapabilities.STANDARD_IME)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        controller.attachSink(sink)
        advanceUntilIdle()

        // Multiline with NO_ENTER_ACTION must send KEYCODE_ENTER (newline), not performEditorAction
        controller.updateEditorContext(
            EditorContext(
                inputType = EditorContext.TYPE_CLASS_TEXT or EditorContext.TYPE_TEXT_FLAG_MULTI_LINE,
                imeOptions = EditorContext.IME_ACTION_SEND or EditorContext.IME_FLAG_NO_ENTER_ACTION
            )
        )
        advanceUntilIdle()
        sink.events.clear()
        controller.dispatchKeyAction(KeyAction.Enter)
        advanceUntilIdle()
        assertEquals(listOf("key:${KeyCodes.KEYCODE_ENTER}:0"), sink.events)

        // Search box with high-bit flags must invoke performEditorAction with masked IME_ACTION_SEARCH
        controller.updateEditorContext(
            EditorContext(
                inputType = EditorContext.TYPE_CLASS_TEXT,
                imeOptions = EditorContext.IME_ACTION_SEARCH or EditorContext.IME_FLAG_NO_EXTRACT_UI
            )
        )
        advanceUntilIdle()
        sink.events.clear()
        controller.dispatchKeyAction(KeyAction.Enter)
        advanceUntilIdle()
        assertEquals(listOf("action:${EditorContext.IME_ACTION_SEARCH}"), sink.events)
        controller.close()
    }

    @Test
    fun t9SelectSyllableAndIncognitoLearningSuppressionWorkThroughController() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val learningStore = UserLearningStore()
        val sink = RecordingInputSink()
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault(learningStore = learningStore) }
        )
        controller.attachSink(sink)
        advanceUntilIdle()

        // Enable incognito via EditorContext -> learning store must stay empty after commit
        controller.updateEditorContext(EditorContext(packageName = "dev.example.app", incognito = true))
        controller.dispatchKeyAction(KeyAction.SwitchMode(InputMode.PINYIN_T9))
        typeEngineChars(controller, "326426")
        advanceUntilIdle()

        val danIdx = controller.uiState.value.snapshot.syllables.indexOf("dan")
        assertTrue("Expected 'dan' T9 syllable option", danIdx >= 0)
        controller.selectSyllable(danIdx)
        advanceUntilIdle()
        assertTrue(controller.uiState.value.snapshot.preedit.startsWith("dan'"))
        assertTrue("蛋糕" in controller.uiState.value.snapshot.candidates.map { it.text })

        // Clearing syllable lock (-1) restores unfiltered options
        controller.selectSyllable(-1)
        advanceUntilIdle()
        assertTrue("fang" in controller.uiState.value.snapshot.syllables)

        controller.dispatchKeyAction(KeyAction.Space)
        advanceUntilIdle()
        assertEquals(0L, learningStore.getBoost("fang an", "方案"))
        assertEquals(0L, learningStore.getBoost("dan gao", "蛋糕"))
        controller.close()
    }

    @Test
    fun passwordEditorForcesEnglishModeSuppressesComposingAndRestoresModeOnExit() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = RecordingInputSink(SinkCapabilities.STANDARD_IME)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        controller.attachSink(sink)
        advanceUntilIdle()
        assertEquals(InputMode.PINYIN_26, controller.uiState.value.activeLayout.mode)

        controller.updateEditorContext(EditorContext(packageName = "dev.example.app", isPassword = true))
        advanceUntilIdle()
        assertEquals(InputMode.ENGLISH, controller.uiState.value.activeLayout.mode)

        sink.events.clear()
        controller.dispatchKeyAction(KeyAction.Text("p"))
        advanceUntilIdle()
        assertEquals(listOf("commit:p"), sink.events)
        assertFalse(controller.uiState.value.snapshot.isComposing)

        // Leaving password field restores previous Pinyin mode
        controller.updateEditorContext(EditorContext(packageName = "dev.example.app", isPassword = false))
        advanceUntilIdle()
        assertEquals(InputMode.PINYIN_26, controller.uiState.value.activeLayout.mode)
        controller.close()
    }

    @Test
    fun failingCustomFactoryFallsBackToLiteAndThrowingEngineEventRecoversCleanly() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = RecordingInputSink(SinkCapabilities.STANDARD_IME)

        // 1) Custom engine factory fails -> publishes engineError and falls back to :engine-lite
        val fallbackController = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { error("Rime native init failure") }
        )
        fallbackController.attachSink(sink)
        advanceUntilIdle()
        assertTrue("Fallback engine should become ready", fallbackController.uiState.value.isEngineReady)
        assertEquals(EngineIssueKind.PERSISTENT, fallbackController.uiState.value.engineIssue?.kind)

        typeEngineChars(fallbackController, "nihao")
        fallbackController.dispatchKeyAction(KeyAction.Space)
        advanceUntilIdle()
        assertTrue("Fallback engine must still type Chinese", "commit:你好" in sink.events)
        assertEquals(
            "Persistent init fallback issue must survive typing until dismissed",
            EngineIssueKind.PERSISTENT,
            fallbackController.uiState.value.engineIssue?.kind
        )
        fallbackController.dismissEngineError()
        advanceUntilIdle()
        assertNull(fallbackController.uiState.value.engineIssue)
        fallbackController.close()

        // 2) Throwing event inside actor -> sets TRANSIENT issue, resets engine, clears after next good key
        val flakyEngine = FlakyFakeEngine(failNextKey = true)
        val flakySink = RecordingInputSink(SinkCapabilities.STANDARD_IME)
        val customSchemas = mapOf(
            InputMode.PINYIN_26 to "rime_ice",
            InputMode.PINYIN_T9 to "rime_t9",
            InputMode.ENGLISH to "rime_en"
        )
        val recoveryController = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(
                engineDispatcher = dispatcher,
                sinkDispatcher = dispatcher,
                modeSchemaIds = customSchemas
            ),
            engineFactory = { flakyEngine }
        )
        recoveryController.attachSink(flakySink)
        advanceUntilIdle()

        recoveryController.dispatchKeyAction(KeyAction.Engine(KeyCodes.keysymForChar('a')))
        advanceUntilIdle()
        assertEquals(1, flakyEngine.resetCount)
        assertEquals(EngineIssueKind.TRANSIENT, recoveryController.uiState.value.engineIssue?.kind)
        assertTrue("Composing cleared on recovery", "compose:<clear>" in flakySink.events)

        recoveryController.dispatchKeyAction(KeyAction.Engine(KeyCodes.keysymForChar('b')))
        recoveryController.dispatchKeyAction(KeyAction.SwitchMode(InputMode.PINYIN_T9))
        advanceUntilIdle()

        assertNull(
            "Transient issue must auto-clear after a successful key",
            recoveryController.uiState.value.engineIssue
        )
        assertTrue("Actor survived and processed next key", "commit:b" in flakySink.events)
        assertEquals(listOf("rime_t9"), flakyEngine.selectedSchemas)
        recoveryController.close()
    }

    @Test
    fun asyncPrewarmStaysDisabledUntilReadyAndFifoOrderHoldsAcrossBursts() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engineGate = CompletableDeferred<LiteInputEngine>()
        val sink = RecordingInputSink(SinkCapabilities.TERMINAL)
        var swaps = 0
        val recordedHeights = mutableListOf<Float>()

        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(
                engineDispatcher = dispatcher,
                sinkDispatcher = dispatcher,
                initialHeightFraction = 0.60f
            ),
            engineFactory = { engineGate.await() },
            hostCallbacks = KeyboardHostCallbacks(
                onSwapScreenRequested = { swaps++ },
                onHeightFractionChanged = { recordedHeights.add(it) }
            )
        )
        controller.attachSink(sink)
        assertFalse(controller.uiState.value.isEngineReady)

        engineGate.complete(LiteInputEngine.createDefault())
        advanceUntilIdle()
        assertTrue(controller.uiState.value.isEngineReady)

        // Rapid FIFO burst in English mode
        controller.dispatchKeyAction(KeyAction.SwitchMode(InputMode.ENGLISH))
        for (i in 0 until 25) {
            controller.dispatchKeyAction(KeyAction.Text("k$i"))
        }
        controller.dispatchExtensionButton(
            ExtensionToolbarButton(id = "esc", label = "ESC", actionType = ExtensionActionType.SEND_KEY, value = "111")
        )
        controller.dispatchExtensionButton(
            ExtensionToolbarButton(
                id = "board",
                label = "Board",
                actionType = ExtensionActionType.HOST_COMMAND,
                value = "open"
            )
        )
        controller.dispatchKeyAction(KeyAction.SwapScreen)
        controller.dispatchKeyAction(KeyAction.CycleHeight)
        controller.dispatchKeyAction(KeyAction.CycleHeight)
        controller.dispatchKeyAction(KeyAction.CycleHeight)
        advanceUntilIdle()

        val expectedCommits = (0 until 25).map { "commit:k$it" } + listOf("key:111:0", "host:board:open")
        assertEquals(expectedCommits, sink.events)
        assertEquals(1, swaps)
        assertEquals(listOf(0.75f, 0.90f, 0.60f), recordedHeights)
        controller.close()
    }

    @Test
    fun totalEngineAndFallbackFailureInstallsPassThroughEngineAndStaysUsable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = RecordingInputSink(SinkCapabilities.STANDARD_IME)
        var swaps = 0

        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { error("Primary engine failed") },
            fallbackEngineFactory = { error("Fallback engine also failed") },
            hostCallbacks = KeyboardHostCallbacks(onSwapScreenRequested = { swaps++ })
        )
        controller.attachSink(sink)
        advanceUntilIdle()

        assertTrue("Keyboard must stay enabled via PassThroughEngine", controller.uiState.value.isEngineReady)
        assertEquals(EngineIssueKind.PERSISTENT, controller.uiState.value.engineIssue?.kind)
        assertEquals(InputMode.ENGLISH, controller.uiState.value.activeLayout.mode)

        controller.dispatchKeyAction(KeyAction.Text("h"))
        controller.dispatchKeyAction(KeyAction.Space)
        controller.dispatchKeyAction(KeyAction.Backspace)
        controller.dispatchKeyAction(KeyAction.Enter)
        controller.dispatchKeyAction(KeyAction.SwapScreen)
        advanceUntilIdle()

        assertEquals(
            listOf("commit:h", "commit: ", "delete:1", "key:${KeyCodes.KEYCODE_ENTER}:0"),
            sink.events
        )
        assertEquals(1, swaps)
        controller.close()
    }

    @Test
    fun chordMidPreeditDiscardsComposingWhileNonChordPassThroughCommitsRaw() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = RecordingInputSink(SinkCapabilities.STANDARD_IME)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        controller.attachSink(sink)
        advanceUntilIdle()

        // Empty backspace deletes 1 char cleanly without spurious composing clear
        controller.dispatchKeyAction(KeyAction.Backspace)
        advanceUntilIdle()
        assertEquals(listOf("delete:1"), sink.events)

        // Compose "nihao", then dispatch a Ctrl+C chord -> discards preedit without committing raw text
        sink.events.clear()
        typeEngineChars(controller, "nihao")
        advanceUntilIdle()
        assertTrue(controller.uiState.value.snapshot.isComposing)
        sink.events.clear()

        controller.dispatchKeyAction(
            KeyAction.Engine(keysym = KeyCodes.keysymForChar('c'), mask = KeyCodes.MASK_CONTROL)
        )
        advanceUntilIdle()

        assertFalse(
            "Preedit must be reset after mid-preedit chord PassThrough",
            controller.uiState.value.snapshot.isComposing
        )
        assertEquals(listOf("compose:<clear>"), sink.events)
        controller.close()

        // Non-chord PassThrough while composing commits raw preedit, clears span, then commits character
        val stickySink = RecordingInputSink(SinkCapabilities.STANDARD_IME)
        val stickyController = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { StickyComposingPassThroughEngine("ni'hao") }
        )
        stickyController.attachSink(stickySink)
        advanceUntilIdle()
        stickyController.dispatchKeyAction(KeyAction.Engine(keysym = KeyCodes.keysymForChar('!')))
        advanceUntilIdle()

        assertFalse(stickyController.uiState.value.snapshot.isComposing)
        assertEquals(listOf("commit:ni'hao", "compose:<clear>", "commit:!"), stickySink.events)
        stickyController.close()
    }

    @Test
    fun directTextFlushesCompositionEvenWhenSpaceReturnsPassThroughAndErrorDismissResetsOnNewError() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = RecordingInputSink(SinkCapabilities.STANDARD_IME)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { StickyComposingPassThroughEngine("raw") }
        )
        controller.attachSink(sink)
        advanceUntilIdle()

        controller.dispatchKeyAction(KeyAction.Text("!"))
        advanceUntilIdle()
        assertFalse(controller.uiState.value.snapshot.isComposing)
        assertEquals(listOf("commit:raw", "compose:<clear>", "commit:!"), sink.events)
        controller.close()

        // Persistent issue survives typing and clears on dismiss; subsequent runtime failure surfaces TRANSIENT
        val flaky = FlakyFakeEngine(failNextKey = true)
        val errController = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { error("Primary init boom") },
            fallbackEngineFactory = { flaky }
        )
        errController.attachSink(RecordingInputSink())
        advanceUntilIdle()
        assertEquals(EngineIssueKind.PERSISTENT, errController.uiState.value.engineIssue?.kind)

        errController.dismissEngineError()
        advanceUntilIdle()
        assertNull(errController.uiState.value.engineIssue)

        errController.dispatchKeyAction(KeyAction.Engine(KeyCodes.keysymForChar('a')))
        advanceUntilIdle()
        assertEquals(EngineIssueKind.TRANSIENT, errController.uiState.value.engineIssue?.kind)

        errController.dispatchKeyAction(KeyAction.Engine(KeyCodes.keysymForChar('b')))
        advanceUntilIdle()
        assertNull(errController.uiState.value.engineIssue)
        errController.close()
    }

    @Test
    fun symbolsModeToggleRestoresLastNonSymbolMode() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = RecordingInputSink(SinkCapabilities.STANDARD_IME)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        controller.attachSink(sink)
        advanceUntilIdle()

        // T9 -> SYMBOLS -> SYMBOLS toggle restores T9
        controller.dispatchKeyAction(KeyAction.SwitchMode(InputMode.PINYIN_T9))
        advanceUntilIdle()
        assertEquals(InputMode.PINYIN_T9, controller.uiState.value.activeLayout.mode)

        controller.dispatchKeyAction(KeyAction.SwitchMode(InputMode.SYMBOLS))
        advanceUntilIdle()
        assertEquals(InputMode.SYMBOLS, controller.uiState.value.activeLayout.mode)

        controller.dispatchKeyAction(KeyAction.SwitchMode(InputMode.SYMBOLS))
        advanceUntilIdle()
        assertEquals(InputMode.PINYIN_T9, controller.uiState.value.activeLayout.mode)

        // ENGLISH -> SYMBOLS -> SYMBOLS toggle restores ENGLISH
        controller.dispatchKeyAction(KeyAction.SwitchMode(InputMode.ENGLISH))
        controller.dispatchKeyAction(KeyAction.SwitchMode(InputMode.SYMBOLS))
        controller.dispatchKeyAction(KeyAction.SwitchMode(InputMode.SYMBOLS))
        advanceUntilIdle()
        assertEquals(InputMode.ENGLISH, controller.uiState.value.activeLayout.mode)
        controller.close()
    }
}

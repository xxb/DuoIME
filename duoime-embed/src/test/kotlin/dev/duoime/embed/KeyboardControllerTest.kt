// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.embed

import dev.duoime.core.engine.EngineKeyEvent
import dev.duoime.core.engine.EngineOptions
import dev.duoime.core.engine.EngineSchemas
import dev.duoime.core.engine.InputEngine
import dev.duoime.core.engine.InputSink
import dev.duoime.core.layout.KeyCodes
import dev.duoime.core.model.ChineseScheme
import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.EditorContext
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.FeedbackZone
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.KeyProcessResult
import dev.duoime.core.model.KeyboardPanel
import dev.duoime.core.model.KeyboardPrefs
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

    private class FlakyFakeEngine(
        private var failNextKey: Boolean = true,
        initialSchemaId: String = "pinyin_26"
    ) : InputEngine {
        private val _state = MutableStateFlow(EngineSnapshot(schemaId = initialSchemaId))
        override val state: StateFlow<EngineSnapshot> = _state
        var resetCount: Int = 0
        var closeCount: Int = 0
        val selectedSchemas = mutableListOf<String>()
        val appliedOptions = mutableMapOf<String, Boolean>()

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

        override fun setOption(name: String, on: Boolean) {
            appliedOptions[name] = on
        }

        override fun selectSchema(id: String): Boolean {
            selectedSchemas.add(id)
            _state.value = _state.value.copy(schemaId = id)
            return true
        }

        override fun reset() {
            resetCount++
            _state.value = _state.value.copy(preedit = "", composingText = "", isComposing = false)
        }

        override fun close() {
            closeCount++
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
    fun t9ProgressiveLockingHandlesReduplicatedSyllablesAndPartialUnlock() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = RecordingInputSink()
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        controller.attachSink(sink)
        advanceUntilIdle()

        controller.dispatchKeyAction(KeyAction.SwitchMode(InputMode.PINYIN_T9))
        typeEngineChars(controller, "6262")
        advanceUntilIdle()

        // Step 1: lock first "ma" -> lockedSyllables = ["ma"], remaining "62" still offers "ma" with selectedIndex=-1
        val firstMaIdx = controller.uiState.value.snapshot.syllables.indexOf("ma")
        assertTrue("Expected 'ma' in initial 6262 syllables", firstMaIdx >= 0)
        controller.selectSyllable(firstMaIdx)
        advanceUntilIdle()
        val afterFirstLock = controller.uiState.value.snapshot
        assertEquals(listOf("ma"), afterFirstLock.lockedSyllables)
        assertEquals(-1, afterFirstLock.selectedSyllableIndex)
        val secondMaIdx = afterFirstLock.syllables.indexOf("ma")
        assertTrue("Expected second 'ma' option for remaining 62", secondMaIdx >= 0)

        // Step 2: lock second "ma" -> lockedSyllables = ["ma", "ma"] and produces "妈妈"
        controller.selectSyllable(secondMaIdx)
        advanceUntilIdle()
        val afterSecondLock = controller.uiState.value.snapshot
        assertEquals(listOf("ma", "ma"), afterSecondLock.lockedSyllables)
        assertTrue(
            "Expected '妈妈' after locking ['ma', 'ma'], got ${afterSecondLock.candidates.map { it.text }}",
            "妈妈" in afterSecondLock.candidates.map { it.text }
        )

        // Step 3: partial unlock from index 1 keeps first "ma" locked and re-exposes second options
        controller.unlockSyllablesFrom(1)
        advanceUntilIdle()
        assertEquals(listOf("ma"), controller.uiState.value.snapshot.lockedSyllables)
        assertTrue("ma" in controller.uiState.value.snapshot.syllables)

        // Step 4: unlock from index 0 via KeyAction.UnlockSyllablesFrom clears all syllable locks
        controller.dispatchKeyAction(KeyAction.UnlockSyllablesFrom(0))
        advanceUntilIdle()
        assertTrue(controller.uiState.value.snapshot.lockedSyllables.isEmpty())
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
        assertEquals(listOf("rime_ice", "rime_t9"), flakyEngine.selectedSchemas)
        recoveryController.close()
        advanceUntilIdle()
        assertEquals(1, flakyEngine.closeCount)
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

        // Persistent fallback issue survives both transient runtime recovery AND subsequent good keys
        val flaky = FlakyFakeEngine(failNextKey = true)
        val errController = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { error("Primary init boom") },
            fallbackEngineFactory = { flaky }
        )
        errController.attachSink(RecordingInputSink())
        advanceUntilIdle()
        val persistentTag = errController.uiState.value.engineIssue?.tag
        assertEquals(EngineIssueKind.PERSISTENT, errController.uiState.value.engineIssue?.kind)

        // Throwing key on fallback engine must NOT downgrade PERSISTENT to TRANSIENT
        errController.dispatchKeyAction(KeyAction.Engine(KeyCodes.keysymForChar('a')))
        advanceUntilIdle()
        assertEquals(EngineIssueKind.PERSISTENT, errController.uiState.value.engineIssue?.kind)
        assertEquals(persistentTag, errController.uiState.value.engineIssue?.tag)

        // Subsequent good key must still preserve PERSISTENT until explicit dismiss
        errController.dispatchKeyAction(KeyAction.Engine(KeyCodes.keysymForChar('b')))
        advanceUntilIdle()
        assertEquals(EngineIssueKind.PERSISTENT, errController.uiState.value.engineIssue?.kind)

        errController.dismissEngineError()
        advanceUntilIdle()
        assertNull(errController.uiState.value.engineIssue)
        errController.close()

        // Both primary and fallback init failure reports both error tags in PERSISTENT tag
        val doubleFailController = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { throw IllegalStateException("primary") },
            fallbackEngineFactory = { throw IllegalArgumentException("fallback") }
        )
        advanceUntilIdle()
        assertEquals(EngineIssueKind.PERSISTENT, doubleFailController.uiState.value.engineIssue?.kind)
        assertEquals(
            "Plain: IllegalStateException/IllegalArgumentException",
            doubleFailController.uiState.value.engineIssue?.tag
        )
        doubleFailController.close()
    }

    @Test
    fun symbolsPanelDoubleTapRestoresBasePrimaryMode() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = RecordingInputSink(SinkCapabilities.STANDARD_IME)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        controller.attachSink(sink)
        advanceUntilIdle()

        // T9 -> SYMBOL -> SYMBOL double-tap restores T9
        controller.dispatchKeyAction(KeyAction.SwitchMode(InputMode.PINYIN_T9))
        advanceUntilIdle()
        assertEquals(InputMode.PINYIN_T9, controller.uiState.value.activeLayout.mode)
        assertEquals(KeyboardPanel.PRIMARY, controller.uiState.value.activeLayout.panel)

        controller.dispatchKeyAction(KeyAction.OpenPanel(KeyboardPanel.SYMBOL))
        advanceUntilIdle()
        assertEquals(KeyboardPanel.SYMBOL, controller.uiState.value.activeLayout.panel)

        controller.dispatchKeyAction(KeyAction.OpenPanel(KeyboardPanel.SYMBOL))
        advanceUntilIdle()
        assertEquals(KeyboardPanel.PRIMARY, controller.uiState.value.activeLayout.panel)
        assertEquals(InputMode.PINYIN_T9, controller.uiState.value.activeLayout.mode)

        // ENGLISH -> SYMBOL -> SYMBOL double-tap restores ENGLISH
        controller.dispatchKeyAction(KeyAction.SwitchMode(InputMode.ENGLISH))
        controller.dispatchKeyAction(KeyAction.OpenPanel(KeyboardPanel.SYMBOL))
        controller.dispatchKeyAction(KeyAction.OpenPanel(KeyboardPanel.SYMBOL))
        advanceUntilIdle()
        assertEquals(KeyboardPanel.PRIMARY, controller.uiState.value.activeLayout.panel)
        assertEquals(InputMode.ENGLISH, controller.uiState.value.activeLayout.mode)
        controller.close()
    }

    @Test
    fun onKeyRepeatInvokesRepeatFeedbackCallbackIndependentlyFromPressDown() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pressKeys = mutableListOf<FeedbackKey>()
        val repeatKeys = mutableListOf<FeedbackKey>()
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() },
            hostCallbacks = KeyboardHostCallbacks(
                onFeedbackKey = { pressKeys.add(it) },
                onRepeatFeedbackKey = { repeatKeys.add(it) }
            )
        )
        advanceUntilIdle()

        val letterKey = FeedbackKey(KeyAction.Engine(KeyCodes.keysymForChar('a')), "a", FeedbackZone.LETTER)
        val backspaceKey = FeedbackKey(KeyAction.Backspace, "⌫", FeedbackZone.BACKSPACE)

        controller.onKeyPressDown(letterKey)
        assertEquals(listOf(letterKey), pressKeys)
        assertTrue(repeatKeys.isEmpty())

        controller.onKeyRepeat(backspaceKey)
        assertEquals(listOf(letterKey), pressKeys)
        assertEquals(listOf(backspaceKey), repeatKeys)
        controller.close()
    }

    @Test
    fun reloadEngineClosesPreviousReappliesStateAndSupportsCustomChineseSchemeSchemas() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = FlakyFakeEngine(failNextKey = false, initialSchemaId = "uninitialized")
        val second = FlakyFakeEngine(failNextKey = false, initialSchemaId = "uninitialized")
        val customFlypySchemas = mapOf(ChineseScheme.XIAOHE_SHUANGPIN to "custom_flypy")

        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(
                engineDispatcher = dispatcher,
                sinkDispatcher = dispatcher,
                chineseSchemeSchemaIds = customFlypySchemas,
                initialPrefs = KeyboardPrefs(
                    chineseScheme = ChineseScheme.XIAOHE_SHUANGPIN,
                    variant = ChineseVariant.TRADITIONAL
                )
            ),
            engineFactory = { first },
            fallbackEngineFactory = { second }
        )
        advanceUntilIdle()

        assertEquals(listOf("custom_flypy"), first.selectedSchemas)
        assertEquals(true, first.appliedOptions[EngineOptions.TRADITIONALIZATION])
        assertFalse(controller.uiState.value.isUsingFallbackEngine)

        // Reload to second engine: first closes cleanly, second gets schema + variant + privacy options
        controller.updateEditorContext(EditorContext(packageName = "dev.example.notes", incognito = true))
        advanceUntilIdle()
        controller.reloadEngine(
            engineFactory = { second },
            fallbackEngineFactory = { LiteInputEngine.createDefault() },
            chineseSchemeSchemaIds = customFlypySchemas
        )
        advanceUntilIdle()

        assertEquals(1, first.closeCount)
        assertEquals(0, second.closeCount)
        assertEquals(listOf("custom_flypy"), second.selectedSchemas)
        assertEquals(true, second.appliedOptions[EngineOptions.TRADITIONALIZATION])
        assertEquals(true, second.appliedOptions[EngineOptions.INCOGNITO])
        assertFalse(controller.uiState.value.isUsingFallbackEngine)

        // Reload with failing primary falls back cleanly and marks isUsingFallbackEngine=true
        controller.reloadEngine(
            engineFactory = { error("Rime native unavailable") },
            fallbackEngineFactory = { LiteInputEngine.createDefault() }
        )
        advanceUntilIdle()
        assertEquals(1, second.closeCount)
        assertTrue(controller.uiState.value.isUsingFallbackEngine)
        assertEquals(EngineIssueKind.PERSISTENT, controller.uiState.value.engineIssue?.kind)

        controller.close()
        advanceUntilIdle()
    }

    @Test
    fun reloadEngineRecoversFromSinkAndReplacementInitFailuresWithoutTerminatingActor() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val initial = FlakyFakeEngine(failNextKey = false)
        var failNextSetComposing = true
        val flakySink = object : InputSink {
            override val capabilities: SinkCapabilities = SinkCapabilities.STANDARD_IME
            val commits = mutableListOf<String>()

            override fun commitText(text: CharSequence) {
                commits.add(text.toString())
            }

            override fun setComposing(text: CharSequence?) {
                if (failNextSetComposing) {
                    failNextSetComposing = false
                    throw IllegalStateException("Transient sink setComposing failure")
                }
            }

            override fun deleteBackward(count: Int) = Unit

            override fun sendKey(keyCode: Int, metaState: Int) = Unit

            override fun performEditorAction(actionId: Int) = Unit
        }
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { initial }
        )
        controller.attachSink(flakySink)
        advanceUntilIdle()

        // 1) Sink throws on setComposing(null) during reload -> reload still completes & actor stays alive
        val second = FlakyFakeEngine(failNextKey = false)
        controller.reloadEngine(engineFactory = { second }, fallbackEngineFactory = null)
        advanceUntilIdle()
        assertEquals(1, initial.closeCount)
        assertEquals(0, second.closeCount)

        // 2) Replacement engine throws during setOption in applyBootstrappedEngine -> broken replacement
        //    is closed, previous engine (`second`) is retained, issue published, and actor stays alive
        val brokenReplacement = object : InputEngine {
            var closed = 0
            override val state: StateFlow<EngineSnapshot> = MutableStateFlow(EngineSnapshot.EMPTY)

            override fun onKey(event: EngineKeyEvent): KeyProcessResult = KeyProcessResult.PassThrough

            override fun selectCandidate(indexOnPage: Int): KeyProcessResult = KeyProcessResult.PassThrough

            override fun page(forward: Boolean): Boolean = false

            override fun setOption(name: String, on: Boolean) {
                throw IllegalStateException("Replacement setOption failure")
            }

            override fun selectSchema(id: String): Boolean = true

            override fun reset() = Unit

            override fun close() {
                closed++
            }
        }
        controller.reloadEngine(engineFactory = { brokenReplacement }, fallbackEngineFactory = null)
        advanceUntilIdle()

        assertEquals(1, brokenReplacement.closed)
        assertEquals(0, second.closeCount)
        assertEquals(EngineIssueKind.PERSISTENT, controller.uiState.value.engineIssue?.kind)
        assertTrue(controller.uiState.value.engineIssue?.tag?.startsWith("Reload:") == true)
        assertTrue(controller.uiState.value.isEngineReady)

        // Actor is still alive and processes subsequent keys + reloads cleanly
        controller.dispatchKeyAction(KeyAction.Text("ok"))
        advanceUntilIdle()
        assertEquals(listOf("ok"), flakySink.commits)

        val third = FlakyFakeEngine(failNextKey = false)
        controller.reloadEngine(engineFactory = { third }, fallbackEngineFactory = null)
        advanceUntilIdle()
        assertEquals(1, second.closeCount)
        assertEquals(0, third.closeCount)

        controller.close()
        advanceUntilIdle()
        assertEquals(1, third.closeCount)
    }

    @Test
    fun restartingEditorPrivacySwapClearsHostSinkComposingSpan() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = RecordingInputSink(SinkCapabilities.STANDARD_IME)
        val privacyResettingEngine = object : InputEngine {
            private var incognito = false
            private val _state = MutableStateFlow(EngineSnapshot(schemaId = EngineSchemas.PINYIN_26))
            override val state: StateFlow<EngineSnapshot> = _state

            override fun onKey(event: EngineKeyEvent): KeyProcessResult {
                _state.value = EngineSnapshot(
                    mode = InputMode.PINYIN_26,
                    schemaId = EngineSchemas.PINYIN_26,
                    preedit = "nihao",
                    composingText = "nihao",
                    isComposing = true
                )
                return KeyProcessResult.Consumed
            }

            override fun selectCandidate(indexOnPage: Int): KeyProcessResult = KeyProcessResult.PassThrough

            override fun page(forward: Boolean): Boolean = false

            override fun setOption(name: String, on: Boolean) {
                if (name == EngineOptions.INCOGNITO && on != incognito) {
                    incognito = on
                    // Mirror RimeInputEngine swapping to *_incognito companion schema and clearing composition
                    _state.value = EngineSnapshot(
                        mode = InputMode.PINYIN_26,
                        schemaId = EngineSchemas.PINYIN_26,
                        isComposing = false
                    )
                }
            }

            override fun selectSchema(id: String): Boolean = true

            override fun reset() {
                _state.value = _state.value.copy(preedit = "", composingText = "", isComposing = false)
            }
        }
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { privacyResettingEngine }
        )
        controller.attachSink(sink)
        advanceUntilIdle()

        controller.dispatchKeyAction(KeyAction.Engine(KeyCodes.keysymForChar('n')))
        advanceUntilIdle()
        assertTrue(controller.uiState.value.snapshot.isComposing)
        assertTrue("compose:nihao" in sink.events)
        sink.events.clear()

        // Toggle incognito live under restarting=true with unchanged logical schema
        controller.updateEditorContext(
            EditorContext(packageName = "dev.example.notes", incognito = true),
            restarting = true
        )
        advanceUntilIdle()

        assertFalse(controller.uiState.value.snapshot.isComposing)
        assertEquals(listOf("compose:<clear>"), sink.events)
        controller.close()
        advanceUntilIdle()
    }
}

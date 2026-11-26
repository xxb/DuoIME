// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.embed

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.duoime.core.engine.EngineKeyEvent
import dev.duoime.core.engine.EngineOptions
import dev.duoime.core.engine.EngineSchemas
import dev.duoime.core.engine.InputEngine
import dev.duoime.core.engine.InputSink
import dev.duoime.core.engine.PassThroughEngine
import dev.duoime.core.layout.BuiltInLayouts
import dev.duoime.core.layout.KeyCodes
import dev.duoime.core.layout.LayoutSpec
import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.EditorContext
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.core.model.EnterBehavior
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.KeyProcessResult
import dev.duoime.core.policy.DisplayPlacementPolicy
import dev.duoime.core.protocol.ExtensionActionType
import dev.duoime.core.protocol.ExtensionToolbarButton
import dev.duoime.engine.lite.LiteInputEngine
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lifecycle classification for engine warnings surfaced in [KeyboardUiState.engineIssue].
 */
enum class EngineIssueKind {
    /** Per-event exception recovery; clears automatically on the next successful input event. */
    TRANSIENT,

    /** Init-time engine fallback (e.g. Lite or PassThroughEngine); stays until explicitly dismissed. */
    PERSISTENT
}

/**
 * Structured engine status issue surfaced to the toolbar warning chip.
 */
@Immutable
data class EngineIssue(
    val kind: EngineIssueKind,
    val tag: String
)

/**
 * Immutable UI state hoisted out of Compose so display swaps can rebuild `ComposeView` cheaply
 * without losing composition, layout mode, or height settings.
 */
data class KeyboardUiState(
    val isEngineReady: Boolean = false,
    val engineIssue: EngineIssue? = null,
    val snapshot: EngineSnapshot = EngineSnapshot.EMPTY,
    val activeLayout: LayoutSpec = BuiltInLayouts.qwerty(),
    val shifted: Boolean = false,
    val includeNumberRow: Boolean = false,
    val heightFraction: Float = DisplayPlacementPolicy.DEFAULT_HEIGHT_FRACTION,
    val isOverlayMode: Boolean = false,
    val editorContext: EditorContext = EditorContext()
)

/**
 * Configuration for [KeyboardController] dispatchers, backend schema mapping, and initial
 * presentation state.
 *
 * **Threading:** [sinkDispatcher] defaults to [Dispatchers.Main.immediate] because Android
 * `InputConnection` and host sinks expect main-thread invocation. Pure-JVM callers and unit tests
 * must override [sinkDispatcher] explicitly (e.g. with a `TestDispatcher`).
 */
data class KeyboardControllerConfig(
    val engineDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val sinkDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    val initialHeightFraction: Float = DisplayPlacementPolicy.DEFAULT_HEIGHT_FRACTION,
    val initialOverlayMode: Boolean = false,
    val modeSchemaIds: Map<InputMode, String> = EngineSchemas.DEFAULT_MODE_MAP
)

/**
 * Host-level callbacks invoked by [KeyboardController] for system/window-level actions.
 */
data class KeyboardHostCallbacks(
    val onSwapScreenRequested: () -> Unit = {},
    val onSwitchImeRequested: () -> Unit = {},
    val onDismissRequested: () -> Unit = {},
    val onHeightFractionChanged: (Float) -> Unit = {},
    val onFeedbackKey: (FeedbackKey) -> Unit = {}
)

/**
 * Hoisted, single-actor keyboard controller shared by `:app` (`InputMethodService`) and external
 * Compose embedders.
 *
 * - Builds [InputEngine] asynchronously off the main thread via [engineFactory] on
 *   `config.engineDispatcher`, exposing `isEngineReady = false` until ready so the first show
 *   never blocks or janks UI. If [engineFactory] fails, falls back to [fallbackEngineFactory]
 *   (defaulting to `:engine-lite`); if both fail, installs [PassThroughEngine] with `engineError`
 *   so direct English typing, Backspace, Space, Enter, and host controls never die.
 * - Serializes every input event through a single FIFO actor channel, confining all [InputEngine]
 *   mutations sequentially, catching unexpected engine exceptions per event (resetting engine and
 *   clearing composing without logging any typed text), and dispatching [InputSink] mutations
 *   strictly in order (`commitText` before `setComposing`) on `config.sinkDispatcher`.
 */
@Stable
class KeyboardController(
    scope: CoroutineScope,
    private val config: KeyboardControllerConfig = KeyboardControllerConfig(),
    private val engineFactory: suspend () -> InputEngine = DEFAULT_ENGINE_FACTORY,
    private val hostCallbacks: KeyboardHostCallbacks = KeyboardHostCallbacks(),
    private val fallbackEngineFactory: (suspend () -> InputEngine)? = DEFAULT_ENGINE_FACTORY
) {
    private sealed interface ControllerEvent {
        data class Action(val action: KeyAction) : ControllerEvent

        data class SelectCandidate(val indexOnPage: Int) : ControllerEvent

        data class SelectSyllable(val index: Int) : ControllerEvent

        data class Page(val forward: Boolean) : ControllerEvent

        data class ExtensionButton(val button: ExtensionToolbarButton) : ControllerEvent

        data class EditorChanged(val context: EditorContext) : ControllerEvent

        data class OverlayModeChanged(val isOverlay: Boolean) : ControllerEvent

        data class NumberRowChanged(val includeNumberRow: Boolean) : ControllerEvent

        data object DismissEngineError : ControllerEvent

        data object Reset : ControllerEvent
    }

    private val activeSinkRef = AtomicReference<InputSink?>(null)
    private val eventChannel = Channel<ControllerEvent>(Channel.UNLIMITED)
    private val actorJob: Job

    private var activeSchemaIds: Map<InputMode, String> = config.modeSchemaIds
    private var engineIssue: EngineIssue? = null
    private var uiMode: InputMode = InputMode.PINYIN_26
    private var lastNonSymbolMode: InputMode = InputMode.PINYIN_26
    private var prePasswordMode: InputMode? = null
    private var shifted: Boolean = false
    private var includeNumberRow: Boolean = false
    private var heightFraction: Float = DisplayPlacementPolicy.clampHeightFraction(config.initialHeightFraction)
    private var isOverlayMode: Boolean = config.initialOverlayMode
    private var editorContext: EditorContext = EditorContext()

    private val _uiState = MutableStateFlow(
        KeyboardUiState(
            isEngineReady = false,
            engineIssue = null,
            snapshot = EngineSnapshot.EMPTY,
            activeLayout = resolveLayout(uiMode),
            shifted = shifted,
            includeNumberRow = includeNumberRow,
            heightFraction = heightFraction,
            isOverlayMode = isOverlayMode,
            editorContext = editorContext
        )
    )
    val uiState: StateFlow<KeyboardUiState> = _uiState.asStateFlow()

    init {
        actorJob = scope.launch(config.engineDispatcher) {
            val engine = initializeEngine()
            applyPrivacyOptions(engine, editorContext)
            publishState(engine)
            for (event in eventChannel) {
                processEventSafely(engine, event)
            }
        }
    }

    fun attachSink(sink: InputSink) {
        activeSinkRef.set(sink)
    }

    fun detachSink(sink: InputSink) {
        activeSinkRef.compareAndSet(sink, null)
    }

    fun onKeyPressDown(feedbackKey: FeedbackKey) {
        hostCallbacks.onFeedbackKey(feedbackKey)
    }

    fun dispatchKeyAction(action: KeyAction) {
        eventChannel.trySend(ControllerEvent.Action(action))
    }

    fun selectCandidate(indexOnPage: Int) {
        eventChannel.trySend(ControllerEvent.SelectCandidate(indexOnPage))
    }

    fun selectSyllable(index: Int) {
        eventChannel.trySend(ControllerEvent.SelectSyllable(index))
    }

    fun pageCandidates(forward: Boolean) {
        eventChannel.trySend(ControllerEvent.Page(forward))
    }

    fun dispatchExtensionButton(button: ExtensionToolbarButton) {
        eventChannel.trySend(ControllerEvent.ExtensionButton(button))
    }

    fun updateEditorContext(context: EditorContext) {
        eventChannel.trySend(ControllerEvent.EditorChanged(context))
    }

    fun updateOverlayMode(isOverlay: Boolean) {
        eventChannel.trySend(ControllerEvent.OverlayModeChanged(isOverlay))
    }

    fun setIncludeNumberRow(enabled: Boolean) {
        eventChannel.trySend(ControllerEvent.NumberRowChanged(enabled))
    }

    fun dismissEngineError() {
        eventChannel.trySend(ControllerEvent.DismissEngineError)
    }

    fun reset() {
        eventChannel.trySend(ControllerEvent.Reset)
    }

    fun close() {
        eventChannel.close()
        actorJob.cancel()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun initializeEngine(): InputEngine {
        val primaryError = try {
            return engineFactory()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            error
        }

        val primaryTag = errorTag(primaryError)
        val fallback = fallbackEngineFactory
        if (fallback != null && fallback !== engineFactory) {
            try {
                val fallbackEngine = fallback()
                activeSchemaIds = EngineSchemas.DEFAULT_MODE_MAP
                engineIssue = EngineIssue(EngineIssueKind.PERSISTENT, "Fallback: $primaryTag")
                return fallbackEngine
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fallbackError: Throwable) {
                engineIssue = EngineIssue(
                    EngineIssueKind.PERSISTENT,
                    "Plain: $primaryTag/${errorTag(fallbackError)}"
                )
                uiMode = InputMode.ENGLISH
                lastNonSymbolMode = InputMode.ENGLISH
                return PassThroughEngine()
            }
        }

        engineIssue = EngineIssue(EngineIssueKind.PERSISTENT, "Plain: $primaryTag")
        uiMode = InputMode.ENGLISH
        lastNonSymbolMode = InputMode.ENGLISH
        return PassThroughEngine()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun processEventSafely(engine: InputEngine, event: ControllerEvent) {
        try {
            processEvent(engine, event)
            if (engineIssue?.kind == EngineIssueKind.TRANSIENT && isEngineInputEvent(event)) {
                engineIssue = null
                publishState(engine)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            recoverFromEventFailure(engine, error)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun recoverFromEventFailure(engine: InputEngine, error: Throwable) {
        if (engineIssue?.kind != EngineIssueKind.PERSISTENT) {
            engineIssue = EngineIssue(EngineIssueKind.TRANSIENT, "Recovered: ${errorTag(error)}")
        }
        try {
            engine.reset()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Engine reset failed during recovery; keep actor alive and clear sink composing state.
        }
        try {
            syncComposingOnly(EngineSnapshot.EMPTY)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Best-effort composing cleanup during recovery.
        }
        publishState(engine)
    }

    private fun isEngineInputEvent(event: ControllerEvent): Boolean = when (event) {
        is ControllerEvent.Action,
        is ControllerEvent.SelectCandidate,
        is ControllerEvent.SelectSyllable,
        is ControllerEvent.Page,
        is ControllerEvent.ExtensionButton -> true
        else -> false
    }

    private suspend fun processEvent(engine: InputEngine, event: ControllerEvent) {
        when (event) {
            is ControllerEvent.Action -> handleKeyAction(engine, event.action)
            is ControllerEvent.SelectCandidate -> handleSelectCandidate(engine, event.indexOnPage)
            is ControllerEvent.SelectSyllable -> {
                if (engine.selectSyllable(event.index)) {
                    syncComposingOnly(engine.state.value)
                    publishState(engine)
                }
            }
            is ControllerEvent.Page -> {
                if (engine.page(event.forward)) {
                    publishState(engine)
                }
            }
            is ControllerEvent.ExtensionButton -> handleExtensionButton(engine, event.button)
            is ControllerEvent.EditorChanged -> handleEditorChanged(engine, event.context)
            is ControllerEvent.OverlayModeChanged -> {
                isOverlayMode = event.isOverlay
                publishState(engine)
            }
            is ControllerEvent.NumberRowChanged -> {
                includeNumberRow = event.includeNumberRow
                publishState(engine)
            }
            is ControllerEvent.DismissEngineError -> {
                engineIssue = null
                publishState(engine)
            }
            is ControllerEvent.Reset -> {
                engine.reset()
                syncComposingOnly(engine.state.value)
                publishState(engine)
            }
        }
    }

    private suspend fun handleEditorChanged(engine: InputEngine, context: EditorContext) {
        val wasPassword = editorContext.isPassword
        editorContext = context
        engine.reset()
        applyPrivacyOptions(engine, context)
        if (context.isPassword) {
            if (!wasPassword) {
                prePasswordMode = uiMode
            }
            applyMode(engine, InputMode.ENGLISH, rememberNonSymbol = false)
        } else if (wasPassword) {
            val restore = prePasswordMode
            prePasswordMode = null
            if (restore != null) {
                applyMode(engine, restore, rememberNonSymbol = false)
            }
        }
        syncComposingOnly(engine.state.value)
        publishState(engine)
    }

    private suspend fun handleKeyAction(engine: InputEngine, action: KeyAction) {
        when (action) {
            is KeyAction.Text -> handleDirectText(engine, action.text)
            is KeyAction.Code -> withSink { it.sendKey(action.keyCode, action.metaState) }
            is KeyAction.Engine -> handleEngineKey(engine, EngineKeyEvent(action.keysym, action.mask))
            is KeyAction.Custom -> withSink { it.sendHostCommand(action.id, "") }
            is KeyAction.Backspace -> handleBackspace(engine)
            is KeyAction.Enter -> handleEnter(engine)
            is KeyAction.Space -> handleSpace(engine)
            is KeyAction.Shift -> {
                shifted = !shifted
                publishState(engine)
            }
            is KeyAction.SwitchMode -> handleSwitchMode(engine, action.targetMode)
            is KeyAction.SwitchVariant -> {
                val nextTraditional = engine.state.value.variant != ChineseVariant.TRADITIONAL
                engine.setOption(EngineOptions.TRADITIONALIZATION, nextTraditional)
                publishState(engine)
            }
            else -> handleHostKeyAction(engine, action)
        }
    }

    private suspend fun handleHostKeyAction(engine: InputEngine, action: KeyAction) {
        when (action) {
            is KeyAction.SwapScreen -> withContext(config.sinkDispatcher) { hostCallbacks.onSwapScreenRequested() }
            is KeyAction.CycleHeight -> cycleHeightPreset(engine)
            is KeyAction.ToggleToolbar -> Unit
            is KeyAction.SwitchIme -> withContext(config.sinkDispatcher) { hostCallbacks.onSwitchImeRequested() }
            is KeyAction.Dismiss -> withContext(config.sinkDispatcher) { hostCallbacks.onDismissRequested() }
            else -> Unit
        }
    }

    private suspend fun handleDirectText(engine: InputEngine, text: String) {
        flushComposition(engine)
        withSink { it.commitText(text) }
        if (shifted && uiMode == InputMode.ENGLISH) {
            shifted = false
        }
        publishState(engine)
    }

    private suspend fun flushComposition(engine: InputEngine) {
        if (!engine.state.value.isComposing) return
        val spaceResult = engine.onKey(EngineKeyEvent(KeyCodes.KEYSYM_SPACE))
        if (spaceResult is KeyProcessResult.CommitText) {
            applyEngineResultToSink(spaceResult, engine.state.value)
        }
        val after = engine.state.value
        if (after.isComposing) {
            val rawComposing = after.composingText
            engine.reset()
            withSink { sink ->
                if (rawComposing.isNotEmpty()) {
                    sink.commitText(rawComposing)
                }
                if (sink.capabilities.composing) {
                    sink.setComposing(null)
                }
            }
        } else if (spaceResult !is KeyProcessResult.CommitText) {
            syncComposingOnly(after)
        }
    }

    private suspend fun handleEngineKey(engine: InputEngine, event: EngineKeyEvent) {
        val wasComposing = engine.state.value.isComposing
        val isChord = (event.mask and CHORD_MODIFIER_MASK) != 0
        val result = engine.onKey(event)
        when (result) {
            is KeyProcessResult.PassThrough -> applyPassThrough(
                engine = engine,
                wasComposing = wasComposing,
                isChord = isChord
            ) { sink ->
                if (!isChord) {
                    KeyCodes.charForKeysym(event.keysym)?.let { ch ->
                        if (!Character.isISOControl(ch)) {
                            sink.commitText(ch.toString())
                        }
                    }
                }
            }
            else -> applyEngineResultToSink(result, engine.state.value)
        }
        publishState(engine)
    }

    private suspend fun handleBackspace(engine: InputEngine) {
        val wasComposing = engine.state.value.isComposing
        val result = engine.onKey(EngineKeyEvent(KeyCodes.KEYSYM_BACKSPACE))
        when (result) {
            is KeyProcessResult.PassThrough -> applyPassThrough(engine, wasComposing) { sink ->
                sink.deleteBackward(1)
            }
            else -> applyEngineResultToSink(result, engine.state.value)
        }
        publishState(engine)
    }

    private suspend fun handleSpace(engine: InputEngine) {
        val wasComposing = engine.state.value.isComposing
        val result = engine.onKey(EngineKeyEvent(KeyCodes.KEYSYM_SPACE))
        when (result) {
            is KeyProcessResult.PassThrough -> applyPassThrough(engine, wasComposing) { sink ->
                sink.commitText(" ")
            }
            else -> applyEngineResultToSink(result, engine.state.value)
        }
        publishState(engine)
    }

    private suspend fun handleEnter(engine: InputEngine) {
        val wasComposing = engine.state.value.isComposing
        val result = engine.onKey(EngineKeyEvent(KeyCodes.KEYSYM_RETURN))
        when (result) {
            is KeyProcessResult.PassThrough -> applyPassThrough(engine, wasComposing) { sink ->
                if (sink.capabilities.rawKeys) {
                    sink.sendKey(KeyCodes.KEYCODE_ENTER)
                } else {
                    when (val behavior = editorContext.resolveEnterBehavior()) {
                        EnterBehavior.Newline -> sink.sendKey(KeyCodes.KEYCODE_ENTER)
                        is EnterBehavior.EditorAction -> sink.performEditorAction(behavior.actionId)
                    }
                }
            }
            else -> applyEngineResultToSink(result, engine.state.value)
        }
        publishState(engine)
    }

    /**
     * Unified handler for [KeyProcessResult.PassThrough]:
     * - For a `CONTROL`/`ALT`/`SUPER` chord mid-preedit, discards the preedit (`engine.reset()` +
     *   `setComposing(null)`) and commits nothing so editor shortcuts act on clean text.
     * - For a non-chord declined key mid-preedit, commits the raw `composingText`, resets the engine,
     *   and clears the composing span so [defaultAction] never executes inside a live span.
     * - If composition cleared during the declined key, clears any stale sink composing span first.
     */
    private suspend fun applyPassThrough(
        engine: InputEngine,
        wasComposing: Boolean,
        isChord: Boolean = false,
        defaultAction: (InputSink) -> Unit
    ) {
        val snap = engine.state.value
        if (snap.isComposing) {
            val rawComposing = snap.composingText
            engine.reset()
            withSink { sink ->
                if (!isChord && rawComposing.isNotEmpty()) {
                    sink.commitText(rawComposing)
                }
                if (sink.capabilities.composing) {
                    sink.setComposing(null)
                }
                defaultAction(sink)
            }
        } else {
            withSink { sink ->
                if (wasComposing && sink.capabilities.composing) {
                    sink.setComposing(null)
                }
                defaultAction(sink)
            }
        }
    }

    private suspend fun handleSelectCandidate(engine: InputEngine, indexOnPage: Int) {
        val result = engine.selectCandidate(indexOnPage)
        val snap = engine.state.value
        if (result !is KeyProcessResult.PassThrough) {
            applyEngineResultToSink(result, snap)
        }
        publishState(engine)
    }

    private suspend fun handleSwitchMode(engine: InputEngine, targetMode: InputMode) {
        val resolvedMode = if (targetMode == InputMode.SYMBOLS && uiMode == InputMode.SYMBOLS) {
            lastNonSymbolMode
        } else {
            targetMode
        }
        applyMode(engine, resolvedMode, rememberNonSymbol = true)
        syncComposingOnly(engine.state.value)
        publishState(engine)
    }

    private fun applyMode(engine: InputEngine, targetMode: InputMode, rememberNonSymbol: Boolean) {
        uiMode = targetMode
        shifted = false
        if (targetMode != InputMode.SYMBOLS) {
            if (rememberNonSymbol) {
                lastNonSymbolMode = targetMode
            }
            val schemaId = activeSchemaIds[targetMode]
            if (schemaId != null) {
                engine.selectSchema(schemaId)
            }
        } else {
            engine.reset()
        }
    }

    private suspend fun handleExtensionButton(engine: InputEngine, button: ExtensionToolbarButton) {
        when (button.actionType) {
            ExtensionActionType.SEND_TEXT -> handleDirectText(engine, button.value)
            ExtensionActionType.SEND_KEY -> {
                val keyCode = button.value.toIntOrNull() ?: return
                withSink { it.sendKey(keyCode) }
            }
            ExtensionActionType.HOST_COMMAND -> withSink { it.sendHostCommand(button.id, button.value) }
        }
    }

    private suspend fun cycleHeightPreset(engine: InputEngine) {
        val currentIdx = HEIGHT_PRESETS.indexOfFirst { kotlin.math.abs(it - heightFraction) < 0.02f }
        heightFraction = HEIGHT_PRESETS[(currentIdx + 1) % HEIGHT_PRESETS.size]
        publishState(engine)
        withContext(config.sinkDispatcher) {
            hostCallbacks.onHeightFractionChanged(heightFraction)
        }
    }

    /**
     * Enforces `:core` [InputEngine] ordering strictly:
     * 1. Apply [KeyProcessResult.CommitText] FIRST
     * 2. Apply `setComposing` from the updated [snapshot] SECOND (only when `sink.capabilities.composing` is true).
     */
    private suspend fun applyEngineResultToSink(result: KeyProcessResult, snapshot: EngineSnapshot) {
        withSink { sink ->
            if (result is KeyProcessResult.CommitText) {
                sink.commitText(result.text)
            }
            if (sink.capabilities.composing) {
                sink.setComposing(snapshot.composingText.takeIf { snapshot.isComposing })
            }
        }
    }

    private suspend fun syncComposingOnly(snapshot: EngineSnapshot) {
        withSink { sink ->
            if (sink.capabilities.composing) {
                sink.setComposing(snapshot.composingText.takeIf { snapshot.isComposing })
            }
        }
    }

    private fun applyPrivacyOptions(engine: InputEngine, context: EditorContext) {
        val noLearning = context.incognito || context.isPassword
        engine.setOption(EngineOptions.INCOGNITO, noLearning)
    }

    private suspend inline fun withSink(crossinline block: (InputSink) -> Unit) {
        val sink = activeSinkRef.get() ?: return
        withContext(config.sinkDispatcher) {
            block(sink)
        }
    }

    private fun publishState(engine: InputEngine) {
        _uiState.value = KeyboardUiState(
            isEngineReady = true,
            engineIssue = engineIssue,
            snapshot = engine.state.value,
            activeLayout = resolveLayout(uiMode),
            shifted = shifted,
            includeNumberRow = includeNumberRow,
            heightFraction = heightFraction,
            isOverlayMode = isOverlayMode,
            editorContext = editorContext
        )
    }

    private fun resolveLayout(mode: InputMode): LayoutSpec = when (mode) {
        InputMode.PINYIN_26 -> BuiltInLayouts.qwerty(
            includeNumberRow = includeNumberRow,
            shifted = shifted,
            englishMode = false
        )
        InputMode.ENGLISH -> BuiltInLayouts.qwerty(
            includeNumberRow = includeNumberRow,
            shifted = shifted,
            englishMode = true
        )
        InputMode.PINYIN_T9 -> BuiltInLayouts.t9()
        InputMode.SYMBOLS -> BuiltInLayouts.symbolsV0()
    }

    private fun errorTag(error: Throwable): String = error::class.simpleName ?: "Throwable"

    companion object {
        private const val CHORD_MODIFIER_MASK: Int =
            KeyCodes.MASK_CONTROL or KeyCodes.MASK_ALT or KeyCodes.MASK_SUPER

        val HEIGHT_PRESETS: List<Float> = listOf(0.60f, 0.75f, 0.90f)
        val DEFAULT_ENGINE_FACTORY: suspend () -> InputEngine = { LiteInputEngine.createDefault() }
    }
}

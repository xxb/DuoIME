// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.embed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import dev.duoime.core.engine.InputSink
import dev.duoime.core.model.EditorContext
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hoisted, Compose-friendly state holder pairing a [KeyboardController] with active embedded
 * text-input session tracking (`isInputSessionActive`) for [DuoImeTextInputHost] and
 * [DuoImeKeyboard].
 */
@Stable
class DuoImeState(val controller: KeyboardController) {
    private val _isInputSessionActive = MutableStateFlow(false)
    private val activeSinkRef = AtomicReference<InputSink?>(null)

    val uiState: StateFlow<KeyboardUiState>
        get() = controller.uiState

    val isInputSessionActive: StateFlow<Boolean> = _isInputSessionActive.asStateFlow()

    internal val activeSink: InputSink?
        get() = activeSinkRef.get()

    internal fun attachSessionSink(sink: InputSink, context: EditorContext, restarting: Boolean = false) {
        activeSinkRef.set(sink)
        controller.attachSink(sink)
        controller.updateEditorContext(context, restarting = restarting)
        _isInputSessionActive.value = true
    }

    internal fun detachSessionSink(sink: InputSink) {
        if (activeSinkRef.compareAndSet(sink, null)) {
            controller.detachSink(sink)
            controller.reset()
            _isInputSessionActive.value = false
        }
    }

    /**
     * Checks the bound [EmbedInputConnectionSink] for external cursor/selection changes while
     * composing and resets [controller] immediately on divergence.
     */
    internal fun syncSessionSelectionIfComposing(): Boolean {
        val sink = activeSinkRef.get() as? EmbedInputConnectionSink ?: return false
        val isComposing = controller.uiState.value.snapshot.isComposing || sink.hasActiveComposing
        if (!isComposing) return false
        if (sink.syncExternalSelection(isComposing = true)) {
            controller.reset()
            return true
        }
        return false
    }

    fun close() {
        val sink = activeSinkRef.getAndSet(null)
        (sink as? EmbedInputConnectionSink)?.finishComposingOnDisconnect()
        if (sink != null) {
            controller.detachSink(sink)
        }
        _isInputSessionActive.value = false
        controller.close()
    }
}

/**
 * Remembers and manages the lifecycle of a [DuoImeState] backed by [KeyboardController], tearing
 * down the actor cleanly when leaving composition.
 *
 * [config] sets the initial actor configuration at first composition, while [hostCallbacks] is
 * forwarded live via [rememberUpdatedState] so recomposing with updated lambdas takes effect
 * immediately without recreating the controller.
 */
@Composable
fun rememberDuoImeState(
    config: KeyboardControllerConfig = KeyboardControllerConfig(),
    hostCallbacks: KeyboardHostCallbacks = KeyboardHostCallbacks()
): DuoImeState {
    val scope = rememberCoroutineScope()
    val currentHostCallbacks = rememberUpdatedState(hostCallbacks)
    val state = remember(scope) {
        DuoImeState(
            controller = KeyboardController(
                scope = scope,
                config = config,
                hostCallbacks = KeyboardHostCallbacks(
                    onSwapScreenRequested = { currentHostCallbacks.value.onSwapScreenRequested() },
                    onSwitchImeRequested = { currentHostCallbacks.value.onSwitchImeRequested() },
                    onDismissRequested = { currentHostCallbacks.value.onDismissRequested() },
                    onHeightFractionChanged = { fraction ->
                        currentHostCallbacks.value.onHeightFractionChanged(fraction)
                    },
                    onPrefsChanged = { prefs -> currentHostCallbacks.value.onPrefsChanged(prefs) },
                    onFeedbackKey = { key -> currentHostCallbacks.value.onFeedbackKey(key) },
                    onRepeatFeedbackKey = { key -> currentHostCallbacks.value.onRepeatFeedbackKey(key) }
                )
            )
        )
    }
    DisposableEffect(state) {
        onDispose { state.close() }
    }
    return state
}

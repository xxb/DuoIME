// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.embed

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import dev.duoime.core.model.EditorContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val DEFAULT_SELECTION_POLL_INTERVAL_MS: Long = 50L

internal data class TextInputSessionBinding(
    val editorContext: EditorContext,
    val sink: EmbedInputConnectionSink
)

/**
 * Pure-JVM testable session driver for [DuoImeTextInputHost]:
 * - When [enabled] is `false`, delegates cleanly to [delegateToNext] (platform IME).
 * - When [enabled] is `true`, binds the intercepted [EmbedInputConnectionSink] and [EditorContext]
 *   onto [state], sets `isInputSessionActive = true`, runs a dormant-while-idle selection monitor
 *   while composing, suspends until cancelled, and always cancels the monitor before flushing
 *   composing text + detaching the sink in `finally`.
 */
internal suspend fun interceptDuoTextInputSession(
    enabled: Boolean,
    state: DuoImeState,
    createBinding: () -> TextInputSessionBinding,
    delegateToNext: suspend () -> Nothing,
    selectionPollIntervalMs: Long = DEFAULT_SELECTION_POLL_INTERVAL_MS
): Nothing {
    if (!enabled) {
        delegateToNext()
    }
    val binding = createBinding()
    state.attachSessionSink(sink = binding.sink, context = binding.editorContext)
    try {
        coroutineScope {
            val monitorJob = launch {
                monitorSessionSelectionWhileComposing(
                    state = state,
                    sink = binding.sink,
                    pollIntervalMs = selectionPollIntervalMs
                )
            }
            try {
                awaitCancellation()
            } finally {
                monitorJob.cancel()
            }
        }
    } finally {
        binding.sink.finishComposingOnDisconnect()
        state.detachSessionSink(binding.sink)
    }
}

private suspend fun monitorSessionSelectionWhileComposing(
    state: DuoImeState,
    sink: EmbedInputConnectionSink,
    pollIntervalMs: Long
) {
    val intervalMs = pollIntervalMs.coerceAtLeast(1L)
    while (currentCoroutineContext().isActive) {
        state.uiState.first { it.snapshot.isComposing || sink.hasActiveComposing }
        while (currentCoroutineContext().isActive &&
            (state.uiState.value.snapshot.isComposing || sink.hasActiveComposing)
        ) {
            delay(intervalMs)
            state.syncSessionSelectionIfComposing()
        }
    }
}

/**
 * Intercepts Compose text-field input sessions within [content] and routes them directly into
 * [state]'s embedded [KeyboardController] without invoking the Android system IME.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DuoImeTextInputHost(state: DuoImeState, enabled: Boolean = true, content: @Composable () -> Unit) {
    val interceptor = remember(state, enabled) {
        PlatformTextInputInterceptor { request, nextHandler ->
            interceptDuoTextInputSession(
                enabled = enabled,
                state = state,
                createBinding = {
                    val editorInfo = EditorInfo()
                    val inputConnection = request.createInputConnection(editorInfo)
                    val capabilities = EmbedEditorInfoMapper.resolveSinkCapabilities(editorInfo)
                    val context = EmbedEditorInfoMapper.fromEditorInfo(editorInfo, userIncognito = false)
                    val sink = EmbedInputConnectionSink(inputConnection, capabilities).apply {
                        seedInitialSelection(
                            initialSelStart = editorInfo.initialSelStart,
                            initialSelEnd = editorInfo.initialSelEnd
                        )
                    }
                    TextInputSessionBinding(
                        editorContext = context,
                        sink = sink
                    )
                },
                delegateToNext = { nextHandler.startInputMethod(request) }
            )
        }
    }
    InterceptPlatformTextInput(
        interceptor = interceptor,
        content = content
    )
}

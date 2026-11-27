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

internal data class TextInputSessionBinding(
    val editorContext: EditorContext,
    val sink: EmbedInputConnectionSink
)

/**
 * Pure-JVM testable session driver for [DuoImeTextInputHost]:
 * - When [enabled] is `false`, delegates cleanly to [delegateToNext] (platform IME).
 * - When [enabled] is `true`, binds the intercepted [EmbedInputConnectionSink] and [EditorContext]
 *   onto [state], sets `isInputSessionActive = true`, suspends until cancelled, and always flushes
 *   composing text + detaches the sink in `finally`.
 */
internal suspend fun interceptDuoTextInputSession(
    enabled: Boolean,
    state: DuoImeState,
    createBinding: () -> TextInputSessionBinding,
    delegateToNext: suspend () -> Nothing
): Nothing {
    if (!enabled) {
        delegateToNext()
    }
    val binding = createBinding()
    state.attachSessionSink(sink = binding.sink, context = binding.editorContext)
    try {
        awaitCancellation()
    } finally {
        binding.sink.finishComposingOnDisconnect()
        state.detachSessionSink(binding.sink)
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
                    TextInputSessionBinding(
                        editorContext = context,
                        sink = EmbedInputConnectionSink(inputConnection, capabilities)
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

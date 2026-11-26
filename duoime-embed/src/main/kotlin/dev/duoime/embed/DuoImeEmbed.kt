// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.embed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.duoime.core.engine.InputSink
import dev.duoime.engine.lite.LiteEngineMetadata
import dev.duoime.ui.DuoKeyboardCallbacks
import dev.duoime.ui.DuoKeyboardPlaceholder
import dev.duoime.ui.DuoKeyboardSurface
import dev.duoime.ui.DuoKeyboardSurfaceState
import dev.duoime.ui.theme.DuoTheme

/**
 * Public embed metadata for `:duoime-embed`.
 */
object DuoImeEmbed {
    val defaultEngineId: String = LiteEngineMetadata.ENGINE_ID
}

/**
 * Public Jetpack Compose embed entry point shared by `:app` (system IME) and third-party apps.
 * Renders the full DuoIME keyboard using pure Compose Foundation + [DuoTheme] (zero Material 3).
 */
@Composable
fun DuoImeKeyboard(
    controller: KeyboardController,
    sink: InputSink,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = true
) {
    DisposableEffect(controller, sink) {
        controller.attachSink(sink)
        onDispose { controller.detachSink(sink) }
    }

    val state by controller.uiState.collectAsState()
    val callbacks = remember(controller) {
        DuoKeyboardCallbacks(
            onKeyAction = controller::dispatchKeyAction,
            onSelectCandidate = controller::selectCandidate,
            onSelectSyllable = controller::selectSyllable,
            onPage = controller::pageCandidates,
            onExtensionButton = controller::dispatchExtensionButton,
            onKeyPressDown = controller::onKeyPressDown,
            onKeyRepeat = controller::onKeyRepeat,
            onDismissEngineError = controller::dismissEngineError
        )
    }
    val surfaceState = DuoKeyboardSurfaceState(
        snapshot = state.snapshot,
        layout = state.activeLayout,
        isReady = state.isEngineReady,
        heightFraction = state.heightFraction,
        isOverlayMode = state.isOverlayMode,
        extensionButtons = state.editorContext.extension?.toolbarButtons.orEmpty(),
        engineIssueTag = state.engineIssue?.tag
    )

    DuoTheme(darkTheme = darkTheme) {
        DuoKeyboardSurface(
            state = surfaceState,
            callbacks = callbacks,
            modifier = modifier
        )
    }
}

/**
 * Lightweight status banner host kept for bootstrap launcher screens until M1-5 onboarding lands.
 */
@Composable
fun DuoImeEmbedHost(modifier: Modifier = Modifier, darkTheme: Boolean = true) {
    DuoTheme(darkTheme = darkTheme) {
        DuoKeyboardPlaceholder(
            statusLabel = "DuoIME Embed Ready (${DuoImeEmbed.defaultEngineId})",
            modifier = modifier
        )
    }
}

// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.embed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import dev.duoime.core.engine.InputSink
import dev.duoime.core.model.KeyAction
import dev.duoime.engine.lite.LiteEngineMetadata
import dev.duoime.ui.DuoKeyboardCallbacks
import dev.duoime.ui.DuoKeyboardPlaceholder
import dev.duoime.ui.DuoKeyboardSurface
import dev.duoime.ui.DuoKeyboardSurfaceState
import dev.duoime.ui.theme.DuoTheme
import dev.duoime.ui.theme.DuoThemePreset

/**
 * Public embed metadata for `:duoime-embed`.
 */
object DuoImeEmbed {
    val defaultEngineId: String = LiteEngineMetadata.ENGINE_ID
}

/**
 * Capability-driven Jetpack Compose embed entry point shared by `:app` (system IME) and
 * third-party Compose apps. Renders pure Compose Foundation + [DuoTheme] (zero Material 3).
 */
@Composable
fun DuoImeKeyboard(
    controller: KeyboardController,
    sink: InputSink,
    modifier: Modifier = Modifier,
    chromePolicy: ChromePolicy = ChromePolicy.EMBED_DEFAULT,
    hostChrome: HostChrome = HostChrome.NONE
) {
    DisposableEffect(controller, sink) {
        controller.attachSink(sink)
        onDispose {
            (sink as? EmbedInputConnectionSink)?.finishComposingOnDisconnect()
            controller.detachSink(sink)
        }
    }
    DuoImeKeyboardContent(
        controller = controller,
        chromePolicy = chromePolicy,
        hostChrome = hostChrome,
        modifier = modifier
    )
}

/**
 * Capability-driven Jetpack Compose embed entry point backed by [DuoImeState] (pairs seamlessly
 * with [DuoImeTextInputHost] when [sink] is omitted, or accepts an explicit custom [sink]).
 */
@Composable
fun DuoImeKeyboard(
    state: DuoImeState,
    modifier: Modifier = Modifier,
    sink: InputSink? = null,
    chromePolicy: ChromePolicy = ChromePolicy.EMBED_DEFAULT,
    hostChrome: HostChrome = HostChrome.NONE
) {
    if (sink != null) {
        DisposableEffect(state.controller, sink) {
            state.controller.attachSink(sink)
            onDispose {
                (sink as? EmbedInputConnectionSink)?.finishComposingOnDisconnect()
                state.controller.detachSink(sink)
            }
        }
    }
    val preDispatch = remember(state) { { state.syncSessionSelectionIfComposing() } }
    DuoImeKeyboardContent(
        controller = state.controller,
        chromePolicy = chromePolicy,
        hostChrome = hostChrome,
        modifier = modifier,
        preDispatch = preDispatch
    )
}

/**
 * Source-compatible Jetpack Compose embed entry point preserving the legacy boolean capability
 * flags and `darkTheme` parameter.
 */
@Suppress("LongParameterList")
@Composable
fun DuoImeKeyboard(
    controller: KeyboardController,
    sink: InputSink,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    canSwapScreen: Boolean = true,
    canSwitchIme: Boolean = true,
    showDismiss: Boolean = true
) {
    val preset = if (darkTheme) DuoThemePreset.DARK else DuoThemePreset.LIGHT
    val chromePolicy = remember(preset) {
        ChromePolicy.APP_DEFAULT.copy(themePreset = preset)
    }
    val hostChrome = remember(controller, canSwapScreen, canSwitchIme, showDismiss) {
        HostChrome(
            onSwapScreen = if (canSwapScreen) {
                { controller.dispatchKeyAction(KeyAction.SwapScreen) }
            } else {
                null
            },
            onSwitchIme = if (canSwitchIme) {
                { controller.dispatchKeyAction(KeyAction.SwitchIme) }
            } else {
                null
            },
            onDismiss = if (showDismiss) {
                { controller.dispatchKeyAction(KeyAction.Dismiss) }
            } else {
                null
            }
        )
    }
    DuoImeKeyboard(
        controller = controller,
        sink = sink,
        modifier = modifier,
        chromePolicy = chromePolicy,
        hostChrome = hostChrome
    )
}

@Composable
private fun DuoImeKeyboardContent(
    controller: KeyboardController,
    chromePolicy: ChromePolicy,
    hostChrome: HostChrome,
    modifier: Modifier = Modifier,
    preDispatch: () -> Boolean = { false }
) {
    LaunchedEffect(controller, chromePolicy.langKeyPlacement, chromePolicy.initialSymbolTab) {
        controller.updateHostPolicy(
            langKeyPlacement = chromePolicy.langKeyPlacement,
            initialSymbolTab = chromePolicy.initialSymbolTab
        )
    }

    val state by controller.uiState.collectAsState()
    val currentHostChrome = rememberUpdatedState(hostChrome)
    val currentPreDispatch = rememberUpdatedState(preDispatch)
    val callbacks = remember(controller) {
        buildKeyboardCallbacks(
            controller = controller,
            hostChromeState = currentHostChrome,
            preDispatchState = currentPreDispatch
        )
    }

    val derived = state.derived
    val surfaceState = DuoKeyboardSurfaceState(
        snapshot = state.snapshot,
        layout = state.activeLayout,
        isReady = state.isEngineReady,
        heightFraction = state.heightFraction,
        isOverlayMode = state.isOverlayMode,
        extensionButtons = state.editorContext.extension?.toolbarButtons.orEmpty(),
        engineIssueTag = state.engineIssue?.tag,
        canToggleLanguage = derived.canToggleLanguage,
        activeChineseScheme = derived.effectiveChineseScheme,
        effectiveLanguage = derived.effectiveLanguage,
        activePanel = derived.panel,
        panelLocked = derived.panelLocked,
        symbolTab = derived.symbolTab,
        fieldKind = derived.fieldKind,
        drawerOpen = derived.drawerOpen,
        includeNumberRow = derived.includeNumberRow,
        canSwapScreen = hostChrome.canSwapScreen,
        canSwitchIme = hostChrome.canSwitchIme,
        showDismiss = hostChrome.showDismiss,
        candidatePaging = chromePolicy.resolvedCandidatePaging(derived.candidatePaging),
        windowCandidates = state.windowCandidates,
        hasMoreWindowCandidates = state.hasMoreWindowCandidates,
        toolbarPins = chromePolicy.pins,
        toolbarMode = chromePolicy.toolbarMode
    )

    DuoTheme(preset = chromePolicy.themePreset) {
        DuoKeyboardSurface(
            state = surfaceState,
            callbacks = callbacks,
            modifier = modifier
        )
    }
}

private fun buildKeyboardCallbacks(
    controller: KeyboardController,
    hostChromeState: State<HostChrome>,
    preDispatchState: State<() -> Boolean>
): DuoKeyboardCallbacks = DuoKeyboardCallbacks(
    onKeyAction = { action ->
        when (action) {
            KeyAction.SwapScreen -> hostChromeState.value.onSwapScreen?.invoke()
            KeyAction.SwitchIme -> hostChromeState.value.onSwitchIme?.invoke()
            KeyAction.Dismiss -> hostChromeState.value.onDismiss?.invoke()
            else -> {
                val reset = preDispatchState.value()
                if (!reset || action !is KeyAction.SelectSyllable) {
                    controller.dispatchKeyAction(action)
                }
            }
        }
    },
    onSelectCandidate = { index ->
        if (!preDispatchState.value()) {
            controller.selectCandidate(index)
        }
    },
    onSelectSyllable = { index ->
        if (!preDispatchState.value()) {
            controller.selectSyllable(index)
        }
    },
    onPage = { forward ->
        if (!preDispatchState.value()) {
            controller.pageCandidates(forward)
        }
    },
    onExtensionButton = { button ->
        val handler = hostChromeState.value.onExtensionButton
        if (handler != null) {
            handler(button)
        } else {
            preDispatchState.value()
            controller.dispatchExtensionButton(button)
        }
    },
    onKeyPressDown = controller::onKeyPressDown,
    onKeyRepeat = controller::onKeyRepeat,
    onDismissEngineError = controller::dismissEngineError,
    onSelectWindowCandidate = { globalIndex ->
        if (!preDispatchState.value()) {
            controller.selectWindowCandidate(globalIndex)
        }
    },
    onLoadMoreCandidates = {
        if (!preDispatchState.value()) {
            controller.loadMoreCandidates()
        }
    }
)

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

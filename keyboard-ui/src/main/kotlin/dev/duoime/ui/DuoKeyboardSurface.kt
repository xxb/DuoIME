// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.duoime.core.layout.LayoutSpec
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.KeyAction
import dev.duoime.core.protocol.ExtensionToolbarButton
import dev.duoime.ui.bar.CandidateBar
import dev.duoime.ui.bar.CandidateBarCallbacks
import dev.duoime.ui.bar.DuoToolbar
import dev.duoime.ui.bar.DuoToolbarState
import dev.duoime.ui.grid.KeyGrid
import dev.duoime.ui.theme.LocalDuoColors
import dev.duoime.ui.theme.LocalDuoDimensions

/**
 * Immutable render state passed into [DuoKeyboardSurface].
 */
@Immutable
data class DuoKeyboardSurfaceState(
    val snapshot: EngineSnapshot,
    val layout: LayoutSpec,
    val isReady: Boolean,
    val heightFraction: Float,
    val isOverlayMode: Boolean,
    val extensionButtons: List<ExtensionToolbarButton> = emptyList(),
    val engineError: String? = null,
    val engineErrorDismissed: Boolean = false
)

/**
 * Immutable callbacks bundle passed into [DuoKeyboardSurface].
 */
@Immutable
data class DuoKeyboardCallbacks(
    val onKeyAction: (KeyAction) -> Unit,
    val onSelectCandidate: (Int) -> Unit,
    val onSelectSyllable: (Int) -> Unit,
    val onPage: (Boolean) -> Unit,
    val onExtensionButton: (ExtensionToolbarButton) -> Unit = {},
    val onKeyPressDown: (FeedbackKey) -> Unit = {},
    val onDismissEngineError: () -> Unit = {}
)

/**
 * Pure Compose keyboard root rendering toolbar, candidate/syllable strip, and single-pointer [KeyGrid].
 * Depends solely on `:core` models and zero Material 3 components.
 */
@Composable
fun DuoKeyboardSurface(state: DuoKeyboardSurfaceState, callbacks: DuoKeyboardCallbacks, modifier: Modifier = Modifier) {
    val colors = LocalDuoColors.current
    val dimensions = LocalDuoDimensions.current
    val toolbarState = DuoToolbarState(
        activeMode = state.layout.mode,
        variant = state.snapshot.variant,
        heightFraction = state.heightFraction,
        isOverlayMode = state.isOverlayMode,
        extensionButtons = state.extensionButtons
    )
    val candidateCallbacks = remember(callbacks) {
        CandidateBarCallbacks(
            onSelectCandidate = callbacks.onSelectCandidate,
            onSelectSyllable = callbacks.onSelectSyllable,
            onPage = callbacks.onPage,
            onToggleVariant = { callbacks.onKeyAction(KeyAction.SwitchVariant) }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.keyboardBackground)
    ) {
        val snap = state.snapshot
        val showCandidates = snap.isComposing || snap.candidates.isNotEmpty() || snap.syllables.isNotEmpty()
        val err = state.engineError
        val showErrorStrip = !showCandidates && !err.isNullOrBlank() && !state.engineErrorDismissed

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimensions.toolbarHeight)
        ) {
            if (showErrorStrip) {
                EngineErrorStrip(
                    message = err.orEmpty(),
                    onDismiss = callbacks.onDismissEngineError
                )
            } else {
                DuoToolbar(
                    state = toolbarState,
                    onAction = callbacks.onKeyAction,
                    onExtensionButton = callbacks.onExtensionButton
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.divider)
        )

        if (showCandidates) {
            CandidateBar(
                snapshot = snap,
                callbacks = candidateCallbacks
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.divider)
            )
        }

        KeyGrid(
            layout = state.layout,
            enabled = state.isReady,
            onKeyAction = callbacks.onKeyAction,
            onKeyPressDown = callbacks.onKeyPressDown,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(
                    horizontal = dimensions.gridHorizontalPadding,
                    vertical = dimensions.gridVerticalPadding
                )
        )
    }
}

@Composable
private fun EngineErrorStrip(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDuoColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.warningBackground)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicText(
            text = message,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = colors.warningText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

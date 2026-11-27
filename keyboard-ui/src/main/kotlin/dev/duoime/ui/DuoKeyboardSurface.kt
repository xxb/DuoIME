// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.duoime.core.layout.LayoutSpec
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.KeyboardPanel
import dev.duoime.core.protocol.ExtensionToolbarButton
import dev.duoime.ui.bar.CandidateBar
import dev.duoime.ui.bar.CandidateBarCallbacks
import dev.duoime.ui.bar.DuoToolbar
import dev.duoime.ui.bar.DuoToolbarState
import dev.duoime.ui.bar.SyllableChipRail
import dev.duoime.ui.grid.KeyGrid
import dev.duoime.ui.theme.LocalDuoColors
import dev.duoime.ui.theme.LocalDuoDimensions
import dev.duoime.ui.theme.resolveAdaptiveDimensions

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
    val engineIssueTag: String? = null
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
    val onKeyRepeat: (FeedbackKey) -> Unit = {},
    val onDismissEngineError: () -> Unit = {}
)

/**
 * Pure Compose keyboard root rendering a fixed-height header slot (swapping [DuoToolbar] — with
 * its optional leading dismissible engine-warning chip — and [CandidateBar] in place so [KeyGrid]
 * never shifts vertically mid-word), an optional reserved T9 [SyllableChipRail] slot in T9 mode,
 * and the single-pointer [KeyGrid].
 * Depends solely on `:core` models and zero Material 3 components.
 */
@Composable
fun DuoKeyboardSurface(state: DuoKeyboardSurfaceState, callbacks: DuoKeyboardCallbacks, modifier: Modifier = Modifier) {
    val colors = LocalDuoColors.current
    val baseDimensions = LocalDuoDimensions.current
    val hasSyllableRail = state.layout.panel == KeyboardPanel.PRIMARY && state.layout.mode == InputMode.PINYIN_T9
    val keyRowCount = state.layout.rows.size
    val toolbarState = DuoToolbarState(
        activeMode = state.layout.mode,
        variant = state.snapshot.variant,
        heightFraction = state.heightFraction,
        isOverlayMode = state.isOverlayMode,
        extensionButtons = state.extensionButtons,
        engineIssueTag = state.engineIssueTag,
        activePanel = state.layout.panel
    )
    val candidateCallbacks = remember(callbacks) {
        CandidateBarCallbacks(
            onSelectCandidate = callbacks.onSelectCandidate,
            onSelectSyllable = callbacks.onSelectSyllable,
            onPage = callbacks.onPage,
            onToggleVariant = { callbacks.onKeyAction(KeyAction.SwitchVariant) }
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val resolvedDimensions = if (constraints.hasBoundedHeight) {
            resolveAdaptiveDimensions(
                availableHeightDp = maxHeight.value,
                keyRowCount = keyRowCount,
                hasSyllableRail = hasSyllableRail,
                base = baseDimensions
            )
        } else {
            baseDimensions
        }
        val columnModifier = if (constraints.hasBoundedHeight) {
            Modifier.fillMaxSize()
        } else {
            Modifier.fillMaxWidth()
        }

        CompositionLocalProvider(LocalDuoDimensions provides resolvedDimensions) {
            Column(modifier = columnModifier.background(colors.keyboardBackground)) {
                val snap = state.snapshot
                val showCandidates = snap.isComposing || snap.candidates.isNotEmpty()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(resolvedDimensions.toolbarHeight)
                ) {
                    if (showCandidates) {
                        CandidateBar(
                            snapshot = snap,
                            callbacks = candidateCallbacks
                        )
                    } else {
                        DuoToolbar(
                            state = toolbarState,
                            onAction = callbacks.onKeyAction,
                            onExtensionButton = callbacks.onExtensionButton,
                            onDismissEngineError = callbacks.onDismissEngineError
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.divider)
                )

                if (hasSyllableRail) {
                    SyllableChipRail(
                        snapshot = snap,
                        onSelectSyllable = callbacks.onSelectSyllable
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
                    onKeyRepeat = callbacks.onKeyRepeat,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(
                            horizontal = resolvedDimensions.gridHorizontalPadding,
                            vertical = resolvedDimensions.gridVerticalPadding
                        )
                )
            }
        }
    }
}

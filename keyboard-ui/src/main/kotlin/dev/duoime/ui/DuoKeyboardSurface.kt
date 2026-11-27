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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.duoime.core.layout.BuiltInLayouts
import dev.duoime.core.layout.LayoutSpec
import dev.duoime.core.model.CandidatePaging
import dev.duoime.core.model.ChineseScheme
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.FieldKind
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.KeyboardPanel
import dev.duoime.core.model.Language
import dev.duoime.core.model.QuickPin
import dev.duoime.core.model.SymbolTabId
import dev.duoime.core.model.WindowCandidateItem
import dev.duoime.core.protocol.ExtensionToolbarButton
import dev.duoime.ui.bar.CandidateBar
import dev.duoime.ui.bar.CandidateBarCallbacks
import dev.duoime.ui.bar.CandidateBarState
import dev.duoime.ui.bar.DEFAULT_TOOLBAR_PINS
import dev.duoime.ui.bar.DuoToolbar
import dev.duoime.ui.bar.DuoToolbarState
import dev.duoime.ui.grid.KeyGrid
import dev.duoime.ui.grid.KeyGridGeometry
import dev.duoime.ui.grid.T9LeftRail
import dev.duoime.ui.grid.T9LeftRailState
import dev.duoime.ui.panel.DuoQuickDrawer
import dev.duoime.ui.panel.DuoQuickDrawerState
import dev.duoime.ui.panel.DuoSymbolPanel
import dev.duoime.ui.panel.DuoSymbolPanelState
import dev.duoime.ui.theme.LocalDuoColors
import dev.duoime.ui.theme.LocalDuoDimensions
import dev.duoime.ui.theme.resolveAdaptiveDimensions
import kotlin.math.roundToInt

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
    val engineIssueTag: String? = null,
    val canToggleLanguage: Boolean = true,
    val activeChineseScheme: ChineseScheme = ChineseScheme.PINYIN_26,
    val effectiveLanguage: Language = Language.CHINESE,
    val activePanel: KeyboardPanel = layout.panel,
    val panelLocked: Boolean = false,
    val symbolTab: SymbolTabId = SymbolTabId.COMMON,
    val fieldKind: FieldKind = FieldKind.TEXT,
    val drawerOpen: Boolean = false,
    val includeNumberRow: Boolean = false,
    val canSwapScreen: Boolean = true,
    val canSwitchIme: Boolean = true,
    val showDismiss: Boolean = true,
    val candidatePaging: CandidatePaging = CandidatePaging.SCROLL,
    val windowCandidates: List<WindowCandidateItem> = emptyList(),
    val hasMoreWindowCandidates: Boolean = false,
    val toolbarPins: List<QuickPin> = DEFAULT_TOOLBAR_PINS
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
    val onDismissEngineError: () -> Unit = {},
    val onSelectWindowCandidate: (Int) -> Unit = onSelectCandidate,
    val onLoadMoreCandidates: () -> Unit = {}
)

/**
 * Pure Compose keyboard root rendering:
 * - A fixed-height header slot swapping slim [DuoToolbar] and streamlined [CandidateBar] in place
 *   (zero vertical shift of the keypad area; zero horizontal syllable rail above the grid).
 * - A single keypad body mounting [DuoQuickDrawer], [DuoSymbolPanel], T9 `KeyGrid` + Col-0
 *   [T9LeftRail] via plain Compose [Layout], or standard [KeyGrid].
 *
 * Depends solely on `:core` models and pure Compose Foundation (zero Material 3).
 */
@Composable
fun DuoKeyboardSurface(state: DuoKeyboardSurfaceState, callbacks: DuoKeyboardCallbacks, modifier: Modifier = Modifier) {
    val colors = LocalDuoColors.current
    val baseDimensions = LocalDuoDimensions.current
    val keyRowCount = state.layout.rows.size.coerceAtLeast(1)
    val toolbarState = DuoToolbarState(
        activeChineseScheme = state.activeChineseScheme,
        effectiveLanguage = state.effectiveLanguage,
        variant = state.snapshot.variant,
        heightFraction = state.heightFraction,
        isOverlayMode = state.isOverlayMode,
        drawerOpen = state.drawerOpen,
        canToggleLanguage = state.canToggleLanguage,
        canSwapScreen = state.canSwapScreen,
        canSwitchIme = state.canSwitchIme,
        showDismiss = state.showDismiss,
        includeNumberRow = state.includeNumberRow,
        pins = state.toolbarPins,
        extensionButtons = state.extensionButtons,
        engineIssueTag = state.engineIssueTag
    )
    val candidateCallbacks = remember(callbacks) {
        CandidateBarCallbacks(
            onSelectCandidate = callbacks.onSelectCandidate,
            onSelectWindowCandidate = callbacks.onSelectWindowCandidate,
            onLoadMoreCandidates = callbacks.onLoadMoreCandidates,
            onPage = callbacks.onPage
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val resolvedDimensions = if (constraints.hasBoundedHeight) {
            resolveAdaptiveDimensions(
                availableHeightDp = maxHeight.value,
                keyRowCount = keyRowCount,
                hasSyllableRail = false,
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
                val showCandidates = !state.drawerOpen && (snap.isComposing || snap.candidates.isNotEmpty())
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(resolvedDimensions.toolbarHeight)
                ) {
                    if (showCandidates) {
                        CandidateBar(
                            state = CandidateBarState(
                                snapshot = snap,
                                windowCandidates = state.windowCandidates,
                                hasMoreWindowCandidates = state.hasMoreWindowCandidates,
                                pagingMode = state.candidatePaging
                            ),
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

                SurfaceBody(
                    state = state,
                    callbacks = callbacks,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SurfaceBody(
    state: DuoKeyboardSurfaceState,
    callbacks: DuoKeyboardCallbacks,
    modifier: Modifier = Modifier
) {
    val dimensions = LocalDuoDimensions.current
    when {
        state.drawerOpen -> DuoQuickDrawer(
            state = DuoQuickDrawerState(
                activeChineseScheme = state.activeChineseScheme,
                variant = state.snapshot.variant,
                includeNumberRow = state.includeNumberRow,
                heightFraction = state.heightFraction,
                isOverlayMode = state.isOverlayMode,
                canToggleLanguage = state.canToggleLanguage,
                canSwapScreen = state.canSwapScreen,
                canSwitchIme = state.canSwitchIme
            ),
            onAction = callbacks.onKeyAction,
            onKeyPressDown = callbacks.onKeyPressDown,
            modifier = modifier
        )
        state.activePanel == KeyboardPanel.SYMBOL -> DuoSymbolPanel(
            state = DuoSymbolPanelState(
                activeTab = state.symbolTab,
                fieldKind = state.fieldKind,
                language = state.effectiveLanguage,
                panelLocked = state.panelLocked
            ),
            onAction = callbacks.onKeyAction,
            onKeyPressDown = callbacks.onKeyPressDown,
            onKeyRepeat = callbacks.onKeyRepeat,
            modifier = modifier
        )
        hasLeftSlot(state.layout) -> T9GridWithLeftRail(
            state = state,
            callbacks = callbacks,
            modifier = modifier.padding(
                horizontal = dimensions.gridHorizontalPadding,
                vertical = dimensions.gridVerticalPadding
            )
        )
        else -> KeyGrid(
            layout = state.layout,
            enabled = state.isReady,
            onKeyAction = callbacks.onKeyAction,
            onKeyPressDown = callbacks.onKeyPressDown,
            onKeyRepeat = callbacks.onKeyRepeat,
            modifier = modifier.padding(
                horizontal = dimensions.gridHorizontalPadding,
                vertical = dimensions.gridVerticalPadding
            )
        )
    }
}

@Composable
private fun T9GridWithLeftRail(
    state: DuoKeyboardSurfaceState,
    callbacks: DuoKeyboardCallbacks,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val dimensions = LocalDuoDimensions.current
    val hGapPx = with(density) { dimensions.keyHorizontalGap.toPx() }
    val vGapPx = with(density) { dimensions.keyVerticalGap.toPx() }
    val layout = state.layout
    val railState = T9LeftRailState(
        isComposing = state.snapshot.isComposing,
        syllables = state.snapshot.syllables,
        selectedSyllableIndex = state.snapshot.selectedSyllableIndex
    )

    Layout(
        modifier = modifier,
        content = {
            KeyGrid(
                layout = layout,
                enabled = state.isReady,
                onKeyAction = callbacks.onKeyAction,
                onKeyPressDown = callbacks.onKeyPressDown,
                onKeyRepeat = callbacks.onKeyRepeat,
                modifier = Modifier.fillMaxSize()
            )
            T9LeftRail(
                state = railState,
                onKeyAction = callbacks.onKeyAction,
                onSelectSyllable = callbacks.onSelectSyllable,
                onKeyPressDown = callbacks.onKeyPressDown,
                modifier = Modifier.fillMaxSize()
            )
        }
    ) { measurables, constraints ->
        val fallbackHeightPx = with(density) {
            (layout.rows.size.coerceAtLeast(1) * 56.dp.toPx()).roundToInt()
        }
        val width = (if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth).coerceAtLeast(0)
        val height = (if (constraints.hasBoundedHeight) constraints.maxHeight else fallbackHeightPx).coerceAtLeast(0)
        val gridPlaceable = measurables[0].measure(Constraints.fixed(width, height))
        val hitMap = KeyGridGeometry.computeHitMap(
            layout = layout,
            widthPx = width.toFloat(),
            heightPx = height.toFloat(),
            horizontalGapPx = hGapPx,
            verticalGapPx = vGapPx
        )
        val slot = hitMap.slots[BuiltInLayouts.T9_LEFT_SLOT_ID]
        val railPlaceable = if (slot != null && slot.width >= 1f && slot.height >= 1f) {
            measurables[1].measure(
                Constraints.fixed(
                    width = slot.width.roundToInt().coerceAtLeast(1),
                    height = slot.height.roundToInt().coerceAtLeast(1)
                )
            )
        } else {
            null
        }
        layout(width, height) {
            gridPlaceable.placeRelative(0, 0)
            if (slot != null && railPlaceable != null) {
                railPlaceable.placeRelative(slot.left.roundToInt(), slot.top.roundToInt())
            }
        }
    }
}

private fun hasLeftSlot(layout: LayoutSpec): Boolean =
    layout.rows.any { row -> row.keys.any { it.isSlot && it.id == BuiltInLayouts.T9_LEFT_SLOT_ID } }

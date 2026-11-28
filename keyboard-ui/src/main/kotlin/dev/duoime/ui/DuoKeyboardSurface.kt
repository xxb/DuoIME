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
import dev.duoime.core.model.LangKeyPlacement
import dev.duoime.core.model.Language
import dev.duoime.core.model.QuickPin
import dev.duoime.core.model.SymbolTabId
import dev.duoime.core.model.ToolbarMode
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
import dev.duoime.ui.theme.DuoDefaultDimensions
import dev.duoime.ui.theme.DuoDimensionTokens
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
    val toolbarPins: List<QuickPin> = DEFAULT_TOOLBAR_PINS,
    val toolbarMode: ToolbarMode = ToolbarMode.FULL,
    val langKeyPlacement: LangKeyPlacement = LangKeyPlacement.TOOLBAR,
    val isIncognito: Boolean = false,
    val canToggleIncognito: Boolean = true
)

/**
 * Pure-JVM locked reference row count for adaptive header sizing so `toolbarHeight`,
 * `candidateBarHeight`, and the header divider never jump when switching across 5-row QWERTY,
 * 4-row T9, 5×4 Numpad, Symbol Panel, or Quick Drawer under equal [includeNumberRow].
 */
internal fun resolveHeaderReferenceRowCount(includeNumberRow: Boolean): Int = if (includeNumberRow) 5 else 4

/**
 * Pure-JVM adaptive dimension resolution shared by [DuoKeyboardSurface] and unit tests.
 * Uses [resolveHeaderReferenceRowCount] so `toolbarHeight` and `candidateBarHeight` stay
 * locked across 5-row QWERTY, 4-row T9, 5×4 Numpad, Symbol Panel, and Quick Drawer.
 */
internal fun DuoKeyboardSurfaceState.resolveSurfaceDimensions(
    availableHeightDp: Float,
    base: DuoDimensionTokens = DuoDefaultDimensions
): DuoDimensionTokens = resolveAdaptiveDimensions(
    availableHeightDp = availableHeightDp,
    keyRowCount = resolveHeaderReferenceRowCount(includeNumberRow),
    hasSyllableRail = false,
    base = base,
    hasHeader = resolveHeaderSlotMode() != HeaderSlotMode.NONE
)

/**
 * Pure-JVM resolved header slot visibility for [DuoKeyboardSurface].
 */
internal enum class HeaderSlotMode {
    NONE,
    TOOLBAR,
    CANDIDATES
}

internal fun DuoKeyboardSurfaceState.resolveHeaderSlotMode(): HeaderSlotMode {
    val hasCandidates = snapshot.isComposing || snapshot.candidates.isNotEmpty()
    return when (toolbarMode) {
        ToolbarMode.NONE -> HeaderSlotMode.NONE
        ToolbarMode.CANDIDATE_ONLY -> if (hasCandidates) HeaderSlotMode.CANDIDATES else HeaderSlotMode.NONE
        ToolbarMode.FULL -> if (!drawerOpen && hasCandidates) HeaderSlotMode.CANDIDATES else HeaderSlotMode.TOOLBAR
    }
}

/**
 * Pure-JVM mapping from [DuoKeyboardSurfaceState] to [DuoToolbarState], shared by
 * [DuoKeyboardSurface] and invariant tests to prevent drift between UI and test state wiring.
 */
fun DuoKeyboardSurfaceState.toToolbarState(): DuoToolbarState = DuoToolbarState(
    activeChineseScheme = activeChineseScheme,
    effectiveLanguage = effectiveLanguage,
    variant = snapshot.variant,
    heightFraction = heightFraction,
    isOverlayMode = isOverlayMode,
    drawerOpen = drawerOpen,
    canToggleLanguage = canToggleLanguage,
    canSwapScreen = canSwapScreen,
    canSwitchIme = canSwitchIme,
    showDismiss = showDismiss,
    includeNumberRow = includeNumberRow,
    pins = toolbarPins,
    extensionButtons = extensionButtons,
    engineIssueTag = engineIssueTag,
    langKeyPlacement = langKeyPlacement
)

/**
 * Pure-JVM mapping from [DuoKeyboardSurfaceState] to [CandidateBarState], shared by
 * [DuoKeyboardSurface] and invariant tests to prevent drift between UI and test state wiring.
 */
fun DuoKeyboardSurfaceState.toCandidateBarState(): CandidateBarState = CandidateBarState(
    snapshot = snapshot,
    windowCandidates = windowCandidates,
    hasMoreWindowCandidates = hasMoreWindowCandidates,
    pagingMode = candidatePaging,
    langChipState = if (langKeyPlacement == LangKeyPlacement.TOOLBAR && !drawerOpen) {
        toToolbarState()
    } else {
        null
    }
)

/**
 * Pure-JVM mapping from [DuoKeyboardSurfaceState] to [DuoQuickDrawerState], shared by
 * [DuoKeyboardSurface] and invariant tests to prevent drift between UI and test state wiring.
 */
fun DuoKeyboardSurfaceState.toQuickDrawerState(): DuoQuickDrawerState = DuoQuickDrawerState(
    activeChineseScheme = activeChineseScheme,
    variant = snapshot.variant,
    includeNumberRow = includeNumberRow,
    heightFraction = heightFraction,
    isOverlayMode = isOverlayMode,
    canToggleLanguage = canToggleLanguage,
    canSwapScreen = canSwapScreen,
    canSwitchIme = canSwitchIme,
    effectiveLanguage = effectiveLanguage,
    langKeyPlacement = langKeyPlacement,
    isIncognito = isIncognito,
    canToggleIncognito = canToggleIncognito
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
    val onLoadMoreCandidates: () -> Unit = {},
    val onUnlockSyllablesFrom: (Int) -> Unit = { onSelectSyllable(-1) }
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
    val toolbarState = state.toToolbarState()
    val candidateCallbacks = remember(callbacks) {
        CandidateBarCallbacks(
            onSelectCandidate = callbacks.onSelectCandidate,
            onSelectWindowCandidate = callbacks.onSelectWindowCandidate,
            onLoadMoreCandidates = callbacks.onLoadMoreCandidates,
            onPage = callbacks.onPage,
            onAction = callbacks.onKeyAction,
            onKeyPressDown = callbacks.onKeyPressDown
        )
    }

    val headerMode = state.resolveHeaderSlotMode()
    val hasHeader = headerMode != HeaderSlotMode.NONE

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val resolvedDimensions = if (constraints.hasBoundedHeight) {
            state.resolveSurfaceDimensions(availableHeightDp = maxHeight.value, base = baseDimensions)
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
                if (hasHeader) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(resolvedDimensions.toolbarHeight)
                    ) {
                        when (headerMode) {
                            HeaderSlotMode.CANDIDATES -> CandidateBar(
                                state = state.toCandidateBarState(),
                                callbacks = candidateCallbacks
                            )
                            HeaderSlotMode.TOOLBAR -> DuoToolbar(
                                state = toolbarState,
                                onAction = callbacks.onKeyAction,
                                onExtensionButton = callbacks.onExtensionButton,
                                onDismissEngineError = callbacks.onDismissEngineError,
                                onKeyPressDown = callbacks.onKeyPressDown
                            )
                            HeaderSlotMode.NONE -> Unit
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.divider)
                    )
                }

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
            state = state.toQuickDrawerState(),
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
        selectedSyllableIndex = state.snapshot.selectedSyllableIndex,
        lockedSyllables = state.snapshot.lockedSyllables
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
                onUnlockSyllablesFrom = callbacks.onUnlockSyllablesFrom,
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

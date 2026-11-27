// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.duoime.core.layout.KeySpec
import dev.duoime.core.layout.SymbolCatalog
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.FeedbackZone
import dev.duoime.core.model.FieldKind
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.KeyboardPanel
import dev.duoime.core.model.Language
import dev.duoime.core.model.SymbolTabId
import dev.duoime.ui.grid.KeyHoldController
import dev.duoime.ui.theme.DuoColorTokens
import dev.duoime.ui.theme.LocalDuoColors
import dev.duoime.ui.theme.LocalDuoDimensions

/**
 * Immutable render state for [DuoSymbolPanel].
 */
@Immutable
data class DuoSymbolPanelState(
    val activeTab: SymbolTabId,
    val fieldKind: FieldKind,
    val language: Language,
    val panelLocked: Boolean
)

/**
 * Pure-JVM action metadata for [DuoSymbolPanel] verified by zero-duplication invariant tests.
 */
object DuoSymbolPanelMeta {
    val BACKSPACE_KEY_SPEC: KeySpec = KeySpec(
        id = "sym_backspace",
        label = "⌫",
        action = KeyAction.Backspace,
        weight = 1.1f,
        repeatable = true,
        zone = FeedbackZone.BACKSPACE,
        allowLongPress = false
    )

    fun bottomBarActions(): List<KeyAction> = listOf(
        KeyAction.ClosePanel,
        KeyAction.OpenPanel(KeyboardPanel.NUMBER),
        KeyAction.TogglePanelLock,
        KeyAction.Space,
        BACKSPACE_KEY_SPEC.action
    )
}

/**
 * Unified in-place Symbol panel rendered inside the keypad area (pure Compose Foundation, zero M3):
 * - Context-aware category tabs (`CLI` first on `TERMINAL`, Chinese first on `CHINESE`, ASCII first on `ENGLISH`)
 * - 6-column scrollable symbol grid: tap emits [KeyAction.CommitPanelSymbol] (`keepOpen = false`),
 *   long-press emits `keepOpen = true` for continuous symbol entry without closing the panel
 * - Bottom bar: `[返回]` `[123]` `[🔒]` `[空格]` `[⌫]` (with press-hold auto-repeat on `⌫`)
 */
@Composable
fun DuoSymbolPanel(
    state: DuoSymbolPanelState,
    onAction: (KeyAction) -> Unit,
    modifier: Modifier = Modifier,
    onKeyPressDown: (FeedbackKey) -> Unit = {},
    onKeyRepeat: (FeedbackKey) -> Unit = {}
) {
    val colors = LocalDuoColors.current
    val dimensions = LocalDuoDimensions.current
    val tabs = SymbolCatalog.orderedTabsFor(state.fieldKind, state.language)
    val symbols = SymbolCatalog.symbolsFor(state.activeTab, state.fieldKind, state.language)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.keyboardBackground)
            .padding(
                horizontal = dimensions.gridHorizontalPadding,
                vertical = dimensions.gridVerticalPadding
            ),
        verticalArrangement = Arrangement.spacedBy(dimensions.keyVerticalGap)
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(items = tabs, key = { it.id.name }) { tab ->
                SymbolTabChip(
                    label = tab.label,
                    selected = tab.id == state.activeTab,
                    colors = colors,
                    onClick = {
                        val action = KeyAction.SelectSymbolTab(tab.id)
                        onKeyPressDown(FeedbackKey(action, tab.label, FeedbackZone.TOOLBAR))
                        onAction(action)
                    }
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(dimensions.keyHorizontalGap),
            verticalArrangement = Arrangement.spacedBy(dimensions.keyVerticalGap)
        ) {
            itemsIndexed(
                items = symbols,
                key = { idx, sym -> "${state.activeTab.name}:$idx:$sym" }
            ) { _, sym ->
                SymbolTile(
                    symbol = sym,
                    colors = colors,
                    onTap = {
                        val action = KeyAction.CommitPanelSymbol(sym, keepOpen = false)
                        onKeyPressDown(FeedbackKey(action, sym, FeedbackZone.SYMBOL))
                        onAction(action)
                    },
                    onLongPress = {
                        val action = KeyAction.CommitPanelSymbol(sym, keepOpen = true)
                        onKeyPressDown(FeedbackKey(action, sym, FeedbackZone.SYMBOL))
                        onAction(action)
                    }
                )
            }
        }

        SymbolBottomActionBar(
            panelLocked = state.panelLocked,
            onAction = onAction,
            onKeyPressDown = onKeyPressDown,
            onKeyRepeat = onKeyRepeat
        )
    }
}

@Composable
private fun SymbolTabChip(label: String, selected: Boolean, colors: DuoColorTokens, onClick: () -> Unit) {
    val bg = if (selected) colors.chipSelectedBackground else colors.chipBackground
    val fg = if (selected) colors.chipSelectedText else colors.chipText
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            maxLines = 1,
            style = TextStyle(
                color = fg,
                fontSize = LocalDuoDimensions.current.toolbarFontSp.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SymbolTile(symbol: String, colors: DuoColorTokens, onTap: () -> Unit, onLongPress: () -> Unit) {
    val dimensions = LocalDuoDimensions.current
    val corner = RoundedCornerShape(dimensions.keyCornerRadius)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(corner)
            .background(colors.keyBackground)
            .border(width = 1.dp, color = colors.keyBorder, shape = corner)
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress
            ),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = symbol,
            maxLines = 1,
            style = TextStyle(
                color = colors.keyText,
                fontSize = dimensions.candidateFontSp.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

@Composable
private fun SymbolBottomActionBar(
    panelLocked: Boolean,
    onAction: (KeyAction) -> Unit,
    onKeyPressDown: (FeedbackKey) -> Unit,
    onKeyRepeat: (FeedbackKey) -> Unit
) {
    val colors = LocalDuoColors.current
    val dimensions = LocalDuoDimensions.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        horizontalArrangement = Arrangement.spacedBy(dimensions.keyHorizontalGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SymbolActionButton(
            label = "返回",
            highlighted = false,
            colors = colors,
            modifier = Modifier.weight(1.2f),
            onClick = {
                onKeyPressDown(FeedbackKey(KeyAction.ClosePanel, "返回", FeedbackZone.ACTION))
                onAction(KeyAction.ClosePanel)
            }
        )
        SymbolActionButton(
            label = "123",
            highlighted = false,
            colors = colors,
            modifier = Modifier.weight(1.1f),
            onClick = {
                val action = KeyAction.OpenPanel(KeyboardPanel.NUMBER)
                onKeyPressDown(FeedbackKey(action, "123", FeedbackZone.ACTION))
                onAction(action)
            }
        )
        SymbolActionButton(
            label = "🔒",
            highlighted = panelLocked,
            colors = colors,
            modifier = Modifier.weight(0.9f),
            onClick = {
                onKeyPressDown(FeedbackKey(KeyAction.TogglePanelLock, "🔒", FeedbackZone.ACTION))
                onAction(KeyAction.TogglePanelLock)
            }
        )
        SymbolActionButton(
            label = "空格",
            highlighted = false,
            colors = colors,
            modifier = Modifier.weight(1.8f),
            onClick = {
                onKeyPressDown(FeedbackKey(KeyAction.Space, "空格", FeedbackZone.SPACE))
                onAction(KeyAction.Space)
            }
        )
        RepeatableSymbolActionButton(
            keySpec = DuoSymbolPanelMeta.BACKSPACE_KEY_SPEC,
            onAction = onAction,
            onKeyPressDown = onKeyPressDown,
            onKeyRepeat = onKeyRepeat,
            modifier = Modifier.weight(DuoSymbolPanelMeta.BACKSPACE_KEY_SPEC.weight)
        )
    }
}

private class RepeatableButtonDispatch(
    val onActionState: State<(KeyAction) -> Unit>,
    val onKeyPressDownState: State<(FeedbackKey) -> Unit>
)

@Composable
private fun RepeatableSymbolActionButton(
    keySpec: KeySpec,
    onAction: (KeyAction) -> Unit,
    onKeyPressDown: (FeedbackKey) -> Unit,
    onKeyRepeat: (FeedbackKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDuoColors.current
    val dimensions = LocalDuoDimensions.current
    val corner = RoundedCornerShape(dimensions.keyCornerRadius)
    val coroutineScope = rememberCoroutineScope()
    val currentOnAction = rememberUpdatedState(onAction)
    val currentOnKeyPressDown = rememberUpdatedState(onKeyPressDown)
    val currentOnKeyRepeat = rememberUpdatedState(onKeyRepeat)
    val pressedState = remember { mutableStateOf(false) }
    val dispatch = remember {
        RepeatableButtonDispatch(
            onActionState = currentOnAction,
            onKeyPressDownState = currentOnKeyPressDown
        )
    }
    val holdController = remember(coroutineScope) {
        KeyHoldController(
            scope = coroutineScope,
            onRepeatAction = { action -> currentOnAction.value(action) },
            onVisualChanged = {},
            onRepeatFeedback = { key -> currentOnKeyRepeat.value(key) }
        )
    }

    DisposableEffect(holdController) {
        onDispose { holdController.cancelAll() }
    }

    val bg = if (pressedState.value) colors.actionKeyPressedBackground else colors.actionKeyBackground
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(corner)
            .background(bg)
            .border(width = 1.dp, color = colors.keyBorder, shape = corner)
            .pointerInput(holdController, keySpec) {
                handleRepeatableButtonPointers(
                    keySpec = keySpec,
                    holdController = holdController,
                    pressedState = pressedState,
                    dispatch = dispatch
                )
            },
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = keySpec.label,
            maxLines = 1,
            style = TextStyle(
                color = colors.keyText,
                fontSize = dimensions.barButtonFontSp.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

private suspend fun PointerInputScope.handleRepeatableButtonPointers(
    keySpec: KeySpec,
    holdController: KeyHoldController,
    pressedState: MutableState<Boolean>,
    dispatch: RepeatableButtonDispatch
) {
    var activePointerId: Long? = null
    try {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                for (change in event.changes) {
                    activePointerId = processRepeatablePointerChange(
                        change = change,
                        activePointerId = activePointerId,
                        inside = isInsideBounds(change, size.width.toFloat(), size.height.toFloat()),
                        keySpec = keySpec,
                        holdController = holdController,
                        pressedState = pressedState,
                        dispatch = dispatch
                    )
                }
            }
        }
    } finally {
        activePointerId?.let { holdController.cancelPointer(it) }
        pressedState.value = false
    }
}

private fun isInsideBounds(change: PointerInputChange, width: Float, height: Float): Boolean =
    change.position.x in 0f..width && change.position.y in 0f..height

@Suppress("LongParameterList")
private fun processRepeatablePointerChange(
    change: PointerInputChange,
    activePointerId: Long?,
    inside: Boolean,
    keySpec: KeySpec,
    holdController: KeyHoldController,
    pressedState: MutableState<Boolean>,
    dispatch: RepeatableButtonDispatch
): Long? {
    val pid = change.id.value
    if (change.isConsumed) {
        return cancelIfActive(pid, activePointerId, holdController, pressedState)
    }
    if (change.pressed && !change.previousPressed) {
        if (activePointerId != null || !inside) return activePointerId
        pressedState.value = true
        change.consume()
        dispatch.onKeyPressDownState.value(FeedbackKey(keySpec.action, keySpec.label, keySpec.zone))
        holdController.onKeyDown(pid, SYMBOL_REPEATABLE_KEY_INDEX, keySpec)
        return pid
    }
    if (activePointerId != pid) return activePointerId
    change.consume()
    if (!change.pressed) {
        pressedState.value = false
        val releaseAction = if (inside) holdController.onKeyUp(pid, keySpec) else null
        if (!inside) holdController.cancelPointer(pid)
        if (releaseAction != null) dispatch.onActionState.value(releaseAction)
        return null
    }
    return if (inside) activePointerId else cancelIfActive(pid, activePointerId, holdController, pressedState)
}

private fun cancelIfActive(
    pid: Long,
    activePointerId: Long?,
    holdController: KeyHoldController,
    pressedState: MutableState<Boolean>
): Long? {
    if (activePointerId != pid) return activePointerId
    holdController.cancelPointer(pid)
    pressedState.value = false
    return null
}

private const val SYMBOL_REPEATABLE_KEY_INDEX: Int = 0

@Composable
private fun SymbolActionButton(
    label: String,
    highlighted: Boolean,
    colors: DuoColorTokens,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val dimensions = LocalDuoDimensions.current
    val corner = RoundedCornerShape(dimensions.keyCornerRadius)
    val bg = if (highlighted) colors.chipSelectedBackground else colors.actionKeyBackground
    val fg = if (highlighted) colors.chipSelectedText else colors.keyText
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(corner)
            .background(bg)
            .border(width = 1.dp, color = colors.keyBorder, shape = corner)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            maxLines = 1,
            style = TextStyle(
                color = fg,
                fontSize = dimensions.barButtonFontSp.sp,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium
            )
        )
    }
}

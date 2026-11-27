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
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.duoime.core.layout.SymbolCatalog
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.FeedbackZone
import dev.duoime.core.model.FieldKind
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.KeyboardPanel
import dev.duoime.core.model.Language
import dev.duoime.core.model.SymbolTabId
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
    fun bottomBarActions(): List<KeyAction> = listOf(
        KeyAction.ClosePanel,
        KeyAction.OpenPanel(KeyboardPanel.NUMBER),
        KeyAction.TogglePanelLock,
        KeyAction.Space,
        KeyAction.Backspace
    )
}

/**
 * Unified in-place Symbol panel rendered inside the keypad area (pure Compose Foundation, zero M3):
 * - Context-aware category tabs (`CLI` first on `TERMINAL`, Chinese first on `CHINESE`, ASCII first on `ENGLISH`)
 * - 6-column scrollable symbol grid: tap emits [KeyAction.CommitPanelSymbol] (`keepOpen = false`),
 *   long-press emits `keepOpen = true` for continuous symbol entry without closing the panel
 * - Bottom bar: `[返回]` `[123]` `[🔒]` `[空格]` `[⌫]`
 */
@Composable
fun DuoSymbolPanel(
    state: DuoSymbolPanelState,
    onAction: (KeyAction) -> Unit,
    modifier: Modifier = Modifier,
    onKeyPressDown: (FeedbackKey) -> Unit = {}
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
            colors = colors,
            onAction = onAction,
            onKeyPressDown = onKeyPressDown
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
    colors: DuoColorTokens,
    onAction: (KeyAction) -> Unit,
    onKeyPressDown: (FeedbackKey) -> Unit
) {
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
        SymbolActionButton(
            label = "⌫",
            highlighted = false,
            colors = colors,
            modifier = Modifier.weight(1.1f),
            onClick = {
                onKeyPressDown(FeedbackKey(KeyAction.Backspace, "⌫", FeedbackZone.BACKSPACE))
                onAction(KeyAction.Backspace)
            }
        )
    }
}

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

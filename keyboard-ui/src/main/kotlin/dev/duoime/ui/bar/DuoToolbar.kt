// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.bar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.duoime.core.model.ChineseScheme
import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.Language
import dev.duoime.core.model.QuickPin
import dev.duoime.core.protocol.ExtensionToolbarButton
import dev.duoime.ui.R
import dev.duoime.ui.theme.DuoColorTokens
import dev.duoime.ui.theme.LocalDuoColors
import dev.duoime.ui.theme.LocalDuoDimensions

/**
 * Default priority pins rendered in [DuoToolbar] after host [ExtensionToolbarButton]s.
 */
val DEFAULT_TOOLBAR_PINS: List<QuickPin> = listOf(
    QuickPin.SCHEME_SWITCH,
    QuickPin.SWAP_SCREEN
)

/**
 * Semantic keyboard height preset tier rendered in `DuoQuickDrawer` (`S`/`M`/`L` in English,
 * `矮`/`中`/`高` in Chinese) so users see a preset rather than a raw overlay-only percentage.
 */
enum class HeightPresetTier {
    COMPACT,
    STANDARD,
    TALL
}

fun resolveHeightPresetTier(heightFraction: Float): HeightPresetTier = when {
    heightFraction < 0.675f -> HeightPresetTier.COMPACT
    heightFraction <= 0.825f -> HeightPresetTier.STANDARD
    else -> HeightPresetTier.TALL
}

/**
 * Immutable state bundle for the slim priority-slotted [DuoToolbar].
 */
@Immutable
data class DuoToolbarState(
    val activeChineseScheme: ChineseScheme = ChineseScheme.PINYIN_26,
    val effectiveLanguage: Language = Language.CHINESE,
    val variant: ChineseVariant = ChineseVariant.SIMPLIFIED,
    val heightFraction: Float = 0.75f,
    val isOverlayMode: Boolean = false,
    val drawerOpen: Boolean = false,
    val canToggleLanguage: Boolean = true,
    val canSwapScreen: Boolean = true,
    val canSwitchIme: Boolean = true,
    val showDismiss: Boolean = true,
    val includeNumberRow: Boolean = false,
    val pins: List<QuickPin> = DEFAULT_TOOLBAR_PINS,
    val extensionButtons: List<ExtensionToolbarButton> = emptyList(),
    val engineIssueTag: String? = null
)

/**
 * Pure-JVM action metadata for [DuoToolbar] verified by zero-duplication invariant tests.
 */
object DuoToolbarMeta {
    fun visibleActions(state: DuoToolbarState): List<KeyAction> = buildList {
        add(KeyAction.ToggleDrawer)
        if (!state.drawerOpen) {
            for (pin in state.pins.distinct()) {
                when (pin) {
                    QuickPin.SCHEME_SWITCH -> {
                        val scheme26 = if (state.activeChineseScheme == ChineseScheme.XIAOHE_SHUANGPIN) {
                            ChineseScheme.XIAOHE_SHUANGPIN
                        } else {
                            ChineseScheme.PINYIN_26
                        }
                        add(KeyAction.SelectChineseScheme(scheme26))
                        add(KeyAction.SelectChineseScheme(ChineseScheme.PINYIN_T9))
                    }
                    QuickPin.SWAP_SCREEN -> if (state.canSwapScreen) add(KeyAction.SwapScreen)
                    QuickPin.VARIANT -> add(KeyAction.SwitchVariant)
                    QuickPin.NUMBER_ROW -> add(KeyAction.ToggleNumberRow)
                    QuickPin.HEIGHT -> add(KeyAction.CycleHeight)
                    QuickPin.SWITCH_IME -> if (state.canSwitchIme) add(KeyAction.SwitchIme)
                }
            }
        }
        if (state.showDismiss) {
            add(KeyAction.Dismiss)
        }
    }
}

/**
 * Slim priority-slotted top keyboard toolbar (pure Compose Foundation, zero Material 3):
 * `[☰ (ToggleDrawer)]` + priority `LazyRow` (`engineIssue` -> `extensionButtons` -> capability-gated
 * `pins` such as `[26|9]` and `[换屏]`) + optional `[收起 (Dismiss)]`.
 */
@Composable
fun DuoToolbar(
    state: DuoToolbarState,
    onAction: (KeyAction) -> Unit,
    onExtensionButton: (ExtensionToolbarButton) -> Unit,
    modifier: Modifier = Modifier,
    onDismissEngineError: () -> Unit = {}
) {
    val colors = LocalDuoColors.current
    val dimensions = LocalDuoDimensions.current
    val heightLabel = when (resolveHeightPresetTier(state.heightFraction)) {
        HeightPresetTier.COMPACT -> stringResource(R.string.duo_height_preset_compact)
        HeightPresetTier.STANDARD -> stringResource(R.string.duo_height_preset_standard)
        HeightPresetTier.TALL -> stringResource(R.string.duo_height_preset_tall)
    }
    val issueTag = state.engineIssueTag

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(dimensions.toolbarHeight)
            .background(colors.barBackground)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ToolbarChip(
            label = "☰",
            selected = state.drawerOpen,
            colors = colors,
            onClick = { onAction(KeyAction.ToggleDrawer) }
        )

        LazyRow(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentPadding = PaddingValues(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!issueTag.isNullOrBlank()) {
                item(key = "engine_issue") {
                    EngineIssueChip(
                        tag = issueTag,
                        colors = colors,
                        onDismiss = onDismissEngineError
                    )
                }
            }
            items(
                items = state.extensionButtons,
                key = { button -> "ext:${button.id}" }
            ) { button ->
                ToolbarChip(
                    label = button.label,
                    selected = false,
                    colors = colors,
                    onClick = { onExtensionButton(button) }
                )
            }
            if (!state.drawerOpen) {
                toolbarPinItems(
                    state = state,
                    heightLabel = heightLabel,
                    colors = colors,
                    onAction = onAction
                )
            }
        }

        if (state.showDismiss) {
            ToolbarChip(
                label = "收起",
                selected = false,
                colors = colors,
                onClick = { onAction(KeyAction.Dismiss) }
            )
        }
    }
}

private fun LazyListScope.toolbarPinItems(
    state: DuoToolbarState,
    heightLabel: String,
    colors: DuoColorTokens,
    onAction: (KeyAction) -> Unit
) {
    for (pin in state.pins.distinct()) {
        when (pin) {
            QuickPin.SCHEME_SWITCH -> item(key = "pin_scheme") {
                SchemePillChip(
                    activeScheme = state.activeChineseScheme,
                    enabled = state.canToggleLanguage,
                    colors = colors,
                    onAction = onAction
                )
            }
            QuickPin.SWAP_SCREEN -> if (state.canSwapScreen) {
                item(key = "pin_swap") {
                    ToolbarChip(
                        label = if (state.isOverlayMode) "换屏●" else "换屏",
                        selected = state.isOverlayMode,
                        colors = colors,
                        onClick = { onAction(KeyAction.SwapScreen) }
                    )
                }
            }
            QuickPin.VARIANT -> item(key = "pin_variant") {
                ToolbarChip(
                    label = if (state.variant == ChineseVariant.TRADITIONAL) "繁" else "简",
                    selected = state.variant == ChineseVariant.TRADITIONAL,
                    colors = colors,
                    enabled = state.canToggleLanguage,
                    onClick = { onAction(KeyAction.SwitchVariant) }
                )
            }
            QuickPin.NUMBER_ROW -> item(key = "pin_numrow") {
                ToolbarChip(
                    label = "数字行",
                    selected = state.includeNumberRow,
                    colors = colors,
                    onClick = { onAction(KeyAction.ToggleNumberRow) }
                )
            }
            QuickPin.HEIGHT -> item(key = "pin_height") {
                ToolbarChip(
                    label = heightLabel,
                    selected = false,
                    colors = colors,
                    onClick = { onAction(KeyAction.CycleHeight) }
                )
            }
            QuickPin.SWITCH_IME -> if (state.canSwitchIme) {
                item(key = "pin_ime") {
                    ToolbarChip(
                        label = "输入法",
                        selected = false,
                        colors = colors,
                        onClick = { onAction(KeyAction.SwitchIme) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SchemePillChip(
    activeScheme: ChineseScheme,
    enabled: Boolean,
    colors: DuoColorTokens,
    onAction: (KeyAction) -> Unit
) {
    val isT9 = activeScheme == ChineseScheme.PINYIN_T9
    val target26 = if (activeScheme == ChineseScheme.XIAOHE_SHUANGPIN) {
        ChineseScheme.XIAOHE_SHUANGPIN
    } else {
        ChineseScheme.PINYIN_26
    }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .clip(shape)
            .background(colors.chipBackground)
            .border(width = 1.dp, color = colors.keyBorder, shape = shape)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SchemePillHalf(
            label = "26",
            selected = !isT9,
            enabled = enabled,
            colors = colors,
            onClick = { onAction(KeyAction.SelectChineseScheme(target26)) }
        )
        SchemePillHalf(
            label = "9",
            selected = isT9,
            enabled = enabled,
            colors = colors,
            onClick = { onAction(KeyAction.SelectChineseScheme(ChineseScheme.PINYIN_T9)) }
        )
    }
}

@Composable
private fun SchemePillHalf(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    colors: DuoColorTokens,
    onClick: () -> Unit
) {
    val fontSizeSp = LocalDuoDimensions.current.toolbarFontSp
    val bg = if (selected && enabled) colors.chipSelectedBackground else colors.chipBackground
    val fg = when {
        !enabled -> colors.keySecondaryText
        selected -> colors.chipSelectedText
        else -> colors.chipText
    }
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 32.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            maxLines = 1,
            style = TextStyle(
                color = fg,
                fontSize = fontSizeSp.sp,
                fontWeight = if (selected && enabled) FontWeight.SemiBold else FontWeight.Medium
            )
        )
    }
}

@Composable
private fun EngineIssueChip(tag: String, colors: DuoColorTokens, onDismiss: () -> Unit) {
    val fontSizeSp = (LocalDuoDimensions.current.toolbarFontSp - 1f).coerceAtLeast(12f)
    val warningStyle = TextStyle(
        color = colors.warningText,
        fontSize = fontSizeSp.sp,
        fontWeight = FontWeight.SemiBold
    )
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.warningBackground)
            .widthIn(max = 148.dp)
            .semantics { contentDescription = "Dismiss engine warning: $tag" }
            .clickable(onClick = onDismiss)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BasicText(
            text = "⚠ $tag",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = warningStyle,
            modifier = Modifier.weight(1f, fill = false)
        )
        BasicText(
            text = "✕",
            maxLines = 1,
            style = warningStyle
        )
    }
}

@Composable
private fun ToolbarChip(
    label: String,
    selected: Boolean,
    colors: DuoColorTokens,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val fontSizeSp = LocalDuoDimensions.current.toolbarFontSp
    val bg = if (selected && enabled) colors.chipSelectedBackground else colors.chipBackground
    val fg = when {
        !enabled -> colors.keySecondaryText
        selected -> colors.chipSelectedText
        else -> colors.chipText
    }
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 44.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            maxLines = 1,
            style = TextStyle(
                color = fg,
                fontSize = fontSizeSp.sp,
                fontWeight = if (selected && enabled) FontWeight.SemiBold else FontWeight.Medium
            )
        )
    }
}

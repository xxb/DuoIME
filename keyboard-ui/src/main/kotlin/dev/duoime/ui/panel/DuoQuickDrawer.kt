// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.duoime.core.model.ChineseScheme
import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.FeedbackZone
import dev.duoime.core.model.KeyAction
import dev.duoime.ui.R
import dev.duoime.ui.bar.HeightPresetTier
import dev.duoime.ui.bar.resolveHeightPresetTier
import dev.duoime.ui.theme.DuoColorTokens
import dev.duoime.ui.theme.LocalDuoColors
import dev.duoime.ui.theme.LocalDuoDimensions

/**
 * Immutable render state for [DuoQuickDrawer] mounted in-place over the keypad area.
 */
@Immutable
data class DuoQuickDrawerState(
    val activeChineseScheme: ChineseScheme,
    val variant: ChineseVariant,
    val includeNumberRow: Boolean,
    val heightFraction: Float,
    val isOverlayMode: Boolean,
    val canToggleLanguage: Boolean = true,
    val canSwapScreen: Boolean = true,
    val canSwitchIme: Boolean = true
)

/**
 * Pure-JVM action metadata for [DuoQuickDrawer] verified by zero-duplication invariant tests.
 */
object DuoQuickDrawerMeta {
    fun visibleActions(state: DuoQuickDrawerState): List<KeyAction> = buildList {
        val target26 = if (state.activeChineseScheme == ChineseScheme.XIAOHE_SHUANGPIN) {
            ChineseScheme.XIAOHE_SHUANGPIN
        } else {
            ChineseScheme.PINYIN_26
        }
        add(KeyAction.SelectChineseScheme(target26))
        add(KeyAction.SelectChineseScheme(ChineseScheme.PINYIN_T9))
        add(KeyAction.SwitchVariant)
        add(KeyAction.ToggleNumberRow)
        add(KeyAction.CycleHeight)
        if (state.canSwapScreen) add(KeyAction.SwapScreen)
        if (state.canSwitchIme) add(KeyAction.SwitchIme)
    }
}

/**
 * Pure Compose Foundation (zero Material 3) in-place quick-settings drawer replacing the keypad
 * area when `drawerOpen == true` without shifting keyboard height.
 */
@Composable
fun DuoQuickDrawer(
    state: DuoQuickDrawerState,
    onAction: (KeyAction) -> Unit,
    modifier: Modifier = Modifier,
    onKeyPressDown: (FeedbackKey) -> Unit = {}
) {
    val colors = LocalDuoColors.current
    val dimensions = LocalDuoDimensions.current
    val heightLabel = when (resolveHeightPresetTier(state.heightFraction)) {
        HeightPresetTier.COMPACT -> stringResource(R.string.duo_height_preset_compact)
        HeightPresetTier.STANDARD -> stringResource(R.string.duo_height_preset_standard)
        HeightPresetTier.TALL -> stringResource(R.string.duo_height_preset_tall)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.keyboardBackground)
            .padding(
                horizontal = dimensions.gridHorizontalPadding,
                vertical = dimensions.gridVerticalPadding
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SchemeSegmentedRow(
            activeScheme = state.activeChineseScheme,
            enabled = state.canToggleLanguage,
            colors = colors,
            onAction = onAction,
            onKeyPressDown = onKeyPressDown
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DrawerActionTile(
                label = if (state.variant == ChineseVariant.TRADITIONAL) "简/繁 · 繁" else "简/繁 · 简",
                selected = state.variant == ChineseVariant.TRADITIONAL,
                enabled = state.canToggleLanguage,
                modifier = Modifier.weight(1f),
                onClick = {
                    onKeyPressDown(FeedbackKey(KeyAction.SwitchVariant, "简/繁", FeedbackZone.TOOLBAR))
                    onAction(KeyAction.SwitchVariant)
                }
            )
            DrawerActionTile(
                label = if (state.includeNumberRow) "数字行 · 开" else "数字行 · 关",
                selected = state.includeNumberRow,
                enabled = true,
                modifier = Modifier.weight(1f),
                onClick = {
                    onKeyPressDown(FeedbackKey(KeyAction.ToggleNumberRow, "数字行", FeedbackZone.TOOLBAR))
                    onAction(KeyAction.ToggleNumberRow)
                }
            )
            DrawerActionTile(
                label = "高度 · $heightLabel",
                selected = false,
                enabled = true,
                modifier = Modifier.weight(1f),
                onClick = {
                    onKeyPressDown(FeedbackKey(KeyAction.CycleHeight, "高度", FeedbackZone.TOOLBAR))
                    onAction(KeyAction.CycleHeight)
                }
            )
        }

        if (state.canSwapScreen || state.canSwitchIme) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.canSwapScreen) {
                    DrawerActionTile(
                        label = if (state.isOverlayMode) "换屏 · 异屏" else "换屏 · 同屏",
                        selected = state.isOverlayMode,
                        enabled = true,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onKeyPressDown(FeedbackKey(KeyAction.SwapScreen, "换屏", FeedbackZone.TOOLBAR))
                            onAction(KeyAction.SwapScreen)
                        }
                    )
                }
                if (state.canSwitchIme) {
                    DrawerActionTile(
                        label = "切换输入法",
                        selected = false,
                        enabled = true,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onKeyPressDown(FeedbackKey(KeyAction.SwitchIme, "切换输入法", FeedbackZone.TOOLBAR))
                            onAction(KeyAction.SwitchIme)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SchemeSegmentedRow(
    activeScheme: ChineseScheme,
    enabled: Boolean,
    colors: DuoColorTokens,
    onAction: (KeyAction) -> Unit,
    onKeyPressDown: (FeedbackKey) -> Unit
) {
    val dimensions = LocalDuoDimensions.current
    val corner = RoundedCornerShape(dimensions.keyCornerRadius)
    val isT9 = activeScheme == ChineseScheme.PINYIN_T9
    val target26 = if (activeScheme == ChineseScheme.XIAOHE_SHUANGPIN) {
        ChineseScheme.XIAOHE_SHUANGPIN
    } else {
        ChineseScheme.PINYIN_26
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(corner)
            .background(colors.barBackground)
            .border(width = 1.dp, color = colors.keyBorder, shape = corner)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DrawerSegmentOption(
            label = "26键",
            selected = !isT9,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onClick = {
                val action = KeyAction.SelectChineseScheme(target26)
                onKeyPressDown(FeedbackKey(action, "26键", FeedbackZone.TOOLBAR))
                onAction(action)
            }
        )
        DrawerSegmentOption(
            label = "九宫格",
            selected = isT9,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onClick = {
                val action = KeyAction.SelectChineseScheme(ChineseScheme.PINYIN_T9)
                onKeyPressDown(FeedbackKey(action, "九宫格", FeedbackZone.TOOLBAR))
                onAction(action)
            }
        )
    }
}

@Composable
private fun DrawerSegmentOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = LocalDuoColors.current
    val bg = if (selected && enabled) colors.chipSelectedBackground else colors.chipBackground
    val fg = when {
        !enabled -> colors.keySecondaryText
        selected -> colors.chipSelectedText
        else -> colors.chipText
    }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            maxLines = 1,
            style = TextStyle(
                color = fg,
                fontSize = LocalDuoDimensions.current.barButtonFontSp.sp,
                fontWeight = if (selected && enabled) FontWeight.SemiBold else FontWeight.Medium
            )
        )
    }
}

@Composable
private fun DrawerActionTile(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = LocalDuoColors.current
    val dimensions = LocalDuoDimensions.current
    val corner = RoundedCornerShape(dimensions.keyCornerRadius)
    val bg = if (selected && enabled) colors.chipSelectedBackground else colors.keyBackground
    val fg = when {
        !enabled -> colors.keySecondaryText
        selected -> colors.chipSelectedText
        else -> colors.keyText
    }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(corner)
            .background(bg)
            .border(width = 1.dp, color = colors.keyBorder, shape = corner)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            maxLines = 1,
            style = TextStyle(
                color = fg,
                fontSize = dimensions.barButtonFontSp.sp,
                fontWeight = if (selected && enabled) FontWeight.SemiBold else FontWeight.Medium
            )
        )
    }
}

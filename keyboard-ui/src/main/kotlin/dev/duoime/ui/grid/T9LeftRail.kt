// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.grid

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.FeedbackZone
import dev.duoime.core.model.KeyAction
import dev.duoime.ui.theme.DuoColorTokens
import dev.duoime.ui.theme.DuoThemePreset
import dev.duoime.ui.theme.LocalDuoColors
import dev.duoime.ui.theme.LocalDuoDimensions
import dev.duoime.ui.theme.LocalDuoThemePreset

/**
 * Immutable render state for [T9LeftRail] mounted inside `t9_left_slot` (Col 0, Rows 0–2).
 */
@Immutable
data class T9LeftRailState(
    val isComposing: Boolean,
    val syllables: List<String>,
    val selectedSyllableIndex: Int
)

/**
 * Pure-JVM sizing for vertical T9 syllable buttons inside `t9_left_slot`:
 * - 1–3 syllables match the adjacent 3 rows of T9 digit keys 1:1 vertically.
 * - 4+ syllables show ~3.45 items (`>= minScrollItemHeightDp`) so each target stays thumb-sized
 *   while peeking the 4th chip cleanly as a vertical scroll affordance.
 */
internal fun resolveT9SyllableItemHeightDp(
    railHeightDp: Float,
    itemCount: Int,
    minScrollItemHeightDp: Float,
    gapDp: Float
): Float {
    val safeRail = if (railHeightDp.isFinite()) railHeightDp.coerceAtLeast(1f) else 1f
    val safeGap = if (gapDp.isFinite()) gapDp.coerceAtLeast(0f) else 0f
    val safeMin = if (minScrollItemHeightDp.isFinite()) minScrollItemHeightDp.coerceAtLeast(1f) else 1f
    return if (itemCount <= 3) {
        ((safeRail - safeGap * 2f) / 3f).coerceAtLeast(1f)
    } else {
        ((safeRail - safeGap * 3f) / 3.45f).coerceAtLeast(safeMin)
    }
}

internal fun formatT9SyllableLabel(syllable: String, selected: Boolean): String =
    if (selected) "$syllable ×" else syllable

internal fun resolveT9SyllableClick(
    index: Int,
    syllable: String,
    selected: Boolean,
    onKeyPressDown: (FeedbackKey) -> Unit,
    onSelectSyllable: (Int) -> Unit
) {
    if (selected) {
        onKeyPressDown(clearSyllableFeedbackKey())
        onSelectSyllable(-1)
    } else {
        onKeyPressDown(syllableFeedbackKey(index, syllable))
        onSelectSyllable(index)
    }
}

/**
 * Pure Compose Foundation vertical rail positioned inside T9's `t9_left_slot`:
 * - Idle: 3 equal vertical punctuation buttons (`，`, `。`, `？`).
 * - Composing: full-height thumb-friendly vertical syllable keys matching adjacent T9 row geometry,
 *   with inline `"$syl ×"` selected toggle-to-clear styling.
 */
@Composable
fun T9LeftRail(
    state: T9LeftRailState,
    onKeyAction: (KeyAction) -> Unit,
    onSelectSyllable: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onKeyPressDown: (FeedbackKey) -> Unit = {}
) {
    val colors = LocalDuoColors.current
    val dimensions = LocalDuoDimensions.current
    val corner = RoundedCornerShape(dimensions.keyCornerRadius)

    val indication = if (LocalDuoThemePreset.current == DuoThemePreset.EINK) null else LocalIndication.current

    if (!state.isComposing) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .pointerInput(Unit) {},
            verticalArrangement = Arrangement.spacedBy(dimensions.keyVerticalGap)
        ) {
            IDLE_PUNCTUATION.forEach { punct ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(corner)
                        .background(colors.keyBackground)
                        .border(width = 1.dp, color = colors.keyBorder, shape = corner)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = indication
                        ) {
                            val action = KeyAction.Text(punct)
                            onKeyPressDown(FeedbackKey(action, punct, FeedbackZone.SYMBOL))
                            onKeyAction(action)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = punct,
                        maxLines = 1,
                        style = TextStyle(
                            color = colors.keyText,
                            fontSize = dimensions.candidateFontSp.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
        }
    } else {
        ComposingSyllableRail(
            state = state,
            colors = colors,
            onSelectSyllable = onSelectSyllable,
            onKeyPressDown = onKeyPressDown,
            modifier = modifier
        )
    }
}

@Composable
private fun ComposingSyllableRail(
    state: T9LeftRailState,
    colors: DuoColorTokens,
    onSelectSyllable: (Int) -> Unit,
    onKeyPressDown: (FeedbackKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val dimensions = LocalDuoDimensions.current
    val gapDp = dimensions.keyVerticalGap
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {}
    ) {
        val itemHeight = resolveT9SyllableItemHeightDp(
            railHeightDp = maxHeight.value,
            itemCount = state.syllables.size,
            minScrollItemHeightDp = dimensions.candidateBarHeight.value,
            gapDp = gapDp.value
        ).dp

        if (state.syllables.size <= 3) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(gapDp)
            ) {
                state.syllables.forEachIndexed { idx, syl ->
                    val isSelected = idx == state.selectedSyllableIndex
                    VerticalSyllableChip(
                        label = formatT9SyllableLabel(syl, isSelected),
                        selected = isSelected,
                        height = itemHeight,
                        colors = colors,
                        onClick = {
                            resolveT9SyllableClick(
                                index = idx,
                                syllable = syl,
                                selected = isSelected,
                                onKeyPressDown = onKeyPressDown,
                                onSelectSyllable = onSelectSyllable
                            )
                        }
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(gapDp)
            ) {
                itemsIndexed(
                    items = state.syllables,
                    key = { idx, syl -> "$idx:$syl" }
                ) { idx, syl ->
                    val isSelected = idx == state.selectedSyllableIndex
                    VerticalSyllableChip(
                        label = formatT9SyllableLabel(syl, isSelected),
                        selected = isSelected,
                        height = itemHeight,
                        colors = colors,
                        onClick = {
                            resolveT9SyllableClick(
                                index = idx,
                                syllable = syl,
                                selected = isSelected,
                                onKeyPressDown = onKeyPressDown,
                                onSelectSyllable = onSelectSyllable
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun VerticalSyllableChip(
    label: String,
    selected: Boolean,
    height: Dp,
    colors: DuoColorTokens,
    onClick: () -> Unit
) {
    val dimensions = LocalDuoDimensions.current
    val corner = RoundedCornerShape(dimensions.keyCornerRadius)
    val indication = if (LocalDuoThemePreset.current == DuoThemePreset.EINK) null else LocalIndication.current
    val bg = if (selected) colors.chipSelectedBackground else colors.chipBackground
    val fg = if (selected) colors.chipSelectedText else colors.chipText
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(corner)
            .background(bg)
            .border(width = 1.dp, color = colors.keyBorder, shape = corner)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = indication,
                onClick = onClick
            )
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = fg,
                fontSize = dimensions.candidateFontSp.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
            )
        )
    }
}

private val IDLE_PUNCTUATION: List<String> = listOf("，", "。", "？")

internal fun clearSyllableFeedbackKey(): FeedbackKey =
    FeedbackKey(KeyAction.SelectSyllable(-1), "×", FeedbackZone.CANDIDATE)

internal fun syllableFeedbackKey(index: Int, syllable: String): FeedbackKey =
    FeedbackKey(KeyAction.SelectSyllable(index), syllable, FeedbackZone.CANDIDATE)

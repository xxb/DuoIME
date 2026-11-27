// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.FeedbackZone
import dev.duoime.core.model.KeyAction
import dev.duoime.ui.theme.DuoColorTokens
import dev.duoime.ui.theme.LocalDuoColors
import dev.duoime.ui.theme.LocalDuoDimensions

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
 * Pure Compose Foundation vertical rail positioned inside T9's `t9_left_slot`:
 * - Idle: 3 equal vertical punctuation buttons (`，`, `。`, `？`).
 * - Composing: vertical scrollable syllable selector with subtle `"拼音"` tint and optional `×` clear.
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
                        .clickable {
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
    val corner = RoundedCornerShape(dimensions.keyCornerRadius)
    Column(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {}
            .clip(corner)
            .background(colors.barBackground)
            .border(width = 1.dp, color = colors.keyBorder, shape = corner)
            .padding(horizontal = 3.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BasicText(
            text = "拼音",
            maxLines = 1,
            style = TextStyle(
                color = colors.preeditText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (state.selectedSyllableIndex >= 0) {
                item(key = "clear_syllable") {
                    VerticalSyllableChip(
                        label = "×",
                        selected = false,
                        colors = colors,
                        onClick = {
                            onKeyPressDown(FeedbackKey(KeyAction.ClearPreedit, "×", FeedbackZone.CANDIDATE))
                            onSelectSyllable(-1)
                        }
                    )
                }
            }
            itemsIndexed(
                items = state.syllables,
                key = { idx, syl -> "$idx:$syl" }
            ) { idx, syl ->
                VerticalSyllableChip(
                    label = syl,
                    selected = idx == state.selectedSyllableIndex,
                    colors = colors,
                    onClick = {
                        onKeyPressDown(FeedbackKey(KeyAction.Text(syl), syl, FeedbackZone.CANDIDATE))
                        onSelectSyllable(idx)
                    }
                )
            }
        }
    }
}

@Composable
private fun VerticalSyllableChip(label: String, selected: Boolean, colors: DuoColorTokens, onClick: () -> Unit) {
    val fontSizeSp = LocalDuoDimensions.current.syllableFontSp
    val bg = if (selected) colors.chipSelectedBackground else colors.chipBackground
    val fg = if (selected) colors.chipSelectedText else colors.chipText
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            maxLines = 1,
            style = TextStyle(
                color = fg,
                fontSize = fontSizeSp.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        )
    }
}

private val IDLE_PUNCTUATION: List<String> = listOf("，", "。", "？")

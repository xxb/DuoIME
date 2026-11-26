// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.bar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.ui.theme.DuoColorTokens
import dev.duoime.ui.theme.LocalDuoColors
import dev.duoime.ui.theme.LocalDuoDimensions

/**
 * Immutable callbacks for [CandidateBar].
 */
@Immutable
data class CandidateBarCallbacks(
    val onSelectCandidate: (Int) -> Unit,
    val onSelectSyllable: (Int) -> Unit,
    val onPage: (Boolean) -> Unit,
    val onToggleVariant: () -> Unit
)

/**
 * Fixed-height single-row candidate strip using pure Compose Foundation (zero Material 3).
 * Swaps cleanly in-place with [DuoToolbar] so `KeyGrid` never shifts vertically mid-word.
 * Candidate `LazyRow` items are keyed by `(pageIndex, indexOnPage)` to avoid unnecessary churn.
 */
@Composable
fun CandidateBar(snapshot: EngineSnapshot, callbacks: CandidateBarCallbacks, modifier: Modifier = Modifier) {
    val colors = LocalDuoColors.current
    val dimensions = LocalDuoDimensions.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(dimensions.candidateBarHeight)
            .background(colors.barBackground)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (snapshot.preedit.isNotEmpty()) {
            BasicText(
                text = snapshot.preedit,
                maxLines = 1,
                style = TextStyle(
                    color = colors.preeditText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(
                items = snapshot.candidates,
                key = { _, item -> "${snapshot.pageIndex}:${item.indexOnPage}" }
            ) { idx, item ->
                val highlighted = idx == snapshot.highlightedIndex
                CandidateChip(
                    text = item.text,
                    highlighted = highlighted,
                    colors = colors,
                    onClick = { callbacks.onSelectCandidate(item.indexOnPage) }
                )
            }
        }

        if (snapshot.hasPrevPage || snapshot.hasNextPage) {
            SmallBarButton(
                label = "‹",
                enabled = snapshot.hasPrevPage,
                colors = colors,
                onClick = { callbacks.onPage(false) }
            )
            SmallBarButton(
                label = "›",
                enabled = snapshot.hasNextPage,
                colors = colors,
                onClick = { callbacks.onPage(true) }
            )
        }

        SmallBarButton(
            label = if (snapshot.variant == ChineseVariant.TRADITIONAL) "繁" else "简",
            enabled = true,
            colors = colors,
            onClick = callbacks.onToggleVariant
        )
    }
}

/**
 * Fixed-height T9 syllable filter chip rail rendered in a reserved slot when T9 mode is active.
 */
@Composable
fun SyllableChipRail(snapshot: EngineSnapshot, onSelectSyllable: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDuoColors.current
    val dimensions = LocalDuoDimensions.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(dimensions.syllableBarHeight)
            .background(colors.barBackground),
        contentAlignment = Alignment.CenterStart
    ) {
        if (snapshot.syllables.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (snapshot.selectedSyllableIndex >= 0) {
                    item(key = "clear_syllable") {
                        CandidateChip(
                            text = "×",
                            highlighted = false,
                            colors = colors,
                            onClick = { onSelectSyllable(-1) }
                        )
                    }
                }
                itemsIndexed(
                    items = snapshot.syllables,
                    key = { idx, syl -> "$idx:$syl" }
                ) { idx, syl ->
                    CandidateChip(
                        text = syl,
                        highlighted = idx == snapshot.selectedSyllableIndex,
                        colors = colors,
                        onClick = { onSelectSyllable(idx) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateChip(text: String, highlighted: Boolean, colors: DuoColorTokens, onClick: () -> Unit) {
    val bg = if (highlighted) colors.chipSelectedBackground else colors.chipBackground
    val fg = if (highlighted) colors.chipSelectedText else colors.chipText
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            maxLines = 1,
            style = TextStyle(
                color = fg,
                fontSize = 14.sp,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal
            )
        )
    }
}

@Composable
private fun SmallBarButton(label: String, enabled: Boolean, colors: DuoColorTokens, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.chipBackground)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            maxLines = 1,
            style = TextStyle(
                color = if (enabled) colors.chipText else colors.keySecondaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

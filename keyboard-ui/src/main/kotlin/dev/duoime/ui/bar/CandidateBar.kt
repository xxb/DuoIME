// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.bar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.ui.theme.DuoColorTokens
import dev.duoime.ui.theme.LocalDuoColors
import dev.duoime.ui.theme.LocalDuoDimensions

/**
 * Shared layout metrics for [CandidateBar] and [SyllableChipRail], enabling deterministic pure-JVM
 * horizontal capacity checks without magic-number duplication.
 */
internal object CandidateBarLayout {
    const val OUTER_HORIZONTAL_PADDING_DP: Float = 4f
    const val OUTER_VERTICAL_PADDING_DP: Float = 2f
    const val ROW_SPACING_DP: Float = 4f
    const val PREEDIT_MAX_WIDTH_DP: Float = 64f
    const val LAZY_CONTENT_HORIZONTAL_PADDING_DP: Float = 2f
    const val CANDIDATE_MIN_WIDTH_DP: Float = 48f
    const val CANDIDATE_SPACING_DP: Float = 6f
    const val SMALL_BUTTON_MIN_WIDTH_DP: Float = 38f
    const val SYLLABLE_MIN_WIDTH_DP: Float = 48f
    const val SYLLABLE_VERTICAL_PADDING_DP: Float = 1f

    fun maxVisibleMinWidthCandidates(
        barWidthDp: Float,
        hasPreedit: Boolean = true,
        hasPagingButtons: Boolean = true
    ): Int {
        val buttonCount = if (hasPagingButtons) 3 else 1
        val itemCount = (if (hasPreedit) 1 else 0) + 1 + buttonCount
        val fixedWidth = OUTER_HORIZONTAL_PADDING_DP * 2f +
            (if (hasPreedit) PREEDIT_MAX_WIDTH_DP else 0f) +
            buttonCount * SMALL_BUTTON_MIN_WIDTH_DP +
            (itemCount - 1) * ROW_SPACING_DP +
            LAZY_CONTENT_HORIZONTAL_PADDING_DP * 2f
        val available = barWidthDp - fixedWidth
        if (available < CANDIDATE_MIN_WIDTH_DP) return 0
        return ((available + CANDIDATE_SPACING_DP) / (CANDIDATE_MIN_WIDTH_DP + CANDIDATE_SPACING_DP)).toInt()
    }
}

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
            .padding(
                horizontal = CandidateBarLayout.OUTER_HORIZONTAL_PADDING_DP.dp,
                vertical = CandidateBarLayout.OUTER_VERTICAL_PADDING_DP.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CandidateBarLayout.ROW_SPACING_DP.dp)
    ) {
        if (snapshot.preedit.isNotEmpty()) {
            BasicText(
                text = snapshot.preedit,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = colors.preeditText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                modifier = Modifier
                    .widthIn(max = CandidateBarLayout.PREEDIT_MAX_WIDTH_DP.dp)
                    .padding(horizontal = 4.dp)
            )
        }

        LazyRow(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentPadding = PaddingValues(horizontal = CandidateBarLayout.LAZY_CONTENT_HORIZONTAL_PADDING_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(CandidateBarLayout.CANDIDATE_SPACING_DP.dp),
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
            .background(colors.barBackground)
            .padding(horizontal = 6.dp, vertical = CandidateBarLayout.SYLLABLE_VERTICAL_PADDING_DP.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (snapshot.syllables.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (snapshot.selectedSyllableIndex >= 0) {
                    item(key = "clear_syllable") {
                        SyllableChip(
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
                    SyllableChip(
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
            .defaultMinSize(minWidth = CandidateBarLayout.CANDIDATE_MIN_WIDTH_DP.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            maxLines = 1,
            style = TextStyle(
                color = fg,
                fontSize = 19.sp,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal
            )
        )
    }
}

@Composable
private fun SyllableChip(text: String, highlighted: Boolean, colors: DuoColorTokens, onClick: () -> Unit) {
    val bg = if (highlighted) colors.chipSelectedBackground else colors.chipBackground
    val fg = if (highlighted) colors.chipSelectedText else colors.chipText
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = CandidateBarLayout.SYLLABLE_MIN_WIDTH_DP.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            maxLines = 1,
            style = TextStyle(
                color = fg,
                fontSize = 16.sp,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal
            )
        )
    }
}

@Composable
private fun SmallBarButton(label: String, enabled: Boolean, colors: DuoColorTokens, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = CandidateBarLayout.SMALL_BUTTON_MIN_WIDTH_DP.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.chipBackground)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            maxLines = 1,
            style = TextStyle(
                color = if (enabled) colors.chipText else colors.keySecondaryText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.bar

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.duoime.core.model.CandidatePaging
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.core.model.WindowCandidateItem
import dev.duoime.ui.theme.DuoColorTokens
import dev.duoime.ui.theme.DuoThemePreset
import dev.duoime.ui.theme.LocalDuoColors
import dev.duoime.ui.theme.LocalDuoDimensions
import dev.duoime.ui.theme.LocalDuoThemePreset
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Shared layout metrics for [CandidateBar], enabling deterministic pure-JVM horizontal capacity
 * checks without magic-number duplication.
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
    const val AUTO_LOAD_THRESHOLD_ITEMS: Int = 3

    fun maxVisibleMinWidthCandidates(
        barWidthDp: Float,
        hasPreedit: Boolean = true,
        hasPagingButtons: Boolean = true
    ): Int {
        val buttonCount = if (hasPagingButtons) 2 else 0
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
 * Pure-JVM target scroll index resolver for [CandidateBar] `SCROLL` mode when hardware keys
 * (`L1`/`R1`/`PageUp`/`PageDown`) step `snapshot.pageIndex`.
 */
internal fun resolveScrollTargetIndex(items: List<WindowCandidateItem>, pageIndex: Int): Int {
    if (items.isEmpty()) return -1
    val highlightedIdx = items.indexOfFirst { it.highlighted }
    if (highlightedIdx >= 0) return highlightedIdx
    val pageStartIdx = items.indexOfFirst { it.pageIndex == pageIndex }
    return if (pageStartIdx >= 0) pageStartIdx else 0
}

/**
 * Immutable render state for [CandidateBar].
 */
@Immutable
data class CandidateBarState(
    val snapshot: EngineSnapshot,
    val windowCandidates: List<WindowCandidateItem> = emptyList(),
    val hasMoreWindowCandidates: Boolean = false,
    val pagingMode: CandidatePaging = CandidatePaging.SCROLL
)

/**
 * Immutable callbacks for [CandidateBar].
 */
@Immutable
data class CandidateBarCallbacks(
    val onSelectCandidate: (Int) -> Unit,
    val onSelectWindowCandidate: (Int) -> Unit,
    val onLoadMoreCandidates: () -> Unit,
    val onPage: (Boolean) -> Unit
)

/**
 * Streamlined single-row candidate strip (pure Compose Foundation, zero Material 3):
 * - Swaps cleanly in-place with [DuoToolbar] so `KeyGrid` never shifts vertically mid-word.
 * - Supports [CandidatePaging.SCROLL] (default smooth horizontal scrolling with automatic multi-page
 *   window loading) and [CandidatePaging.PAGED] (`‹` / `›` buttons for E-Ink / discrete paging).
 * - Contains zero duplicate navigation/variant buttons.
 */
@Composable
fun CandidateBar(state: CandidateBarState, callbacks: CandidateBarCallbacks, modifier: Modifier = Modifier) {
    val colors = LocalDuoColors.current
    val dimensions = LocalDuoDimensions.current
    val snapshot = state.snapshot

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
                    fontSize = dimensions.preeditFontSp.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                modifier = Modifier
                    .widthIn(max = CandidateBarLayout.PREEDIT_MAX_WIDTH_DP.dp)
                    .padding(horizontal = 4.dp)
            )
        }

        if (state.pagingMode == CandidatePaging.SCROLL) {
            ScrollableCandidateRow(
                state = state,
                callbacks = callbacks,
                colors = colors,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        } else {
            PagedCandidateRow(
                snapshot = snapshot,
                onSelectCandidate = callbacks.onSelectCandidate,
                colors = colors,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            if (snapshot.hasPrevPage || snapshot.hasNextPage) {
                SmallBarButton(
                    label = "‹",
                    enabled = snapshot.hasPrevPage,
                    fontSizeSp = dimensions.barButtonFontSp,
                    colors = colors,
                    onClick = { callbacks.onPage(false) }
                )
                SmallBarButton(
                    label = "›",
                    enabled = snapshot.hasNextPage,
                    fontSizeSp = dimensions.barButtonFontSp,
                    colors = colors,
                    onClick = { callbacks.onPage(true) }
                )
            }
        }
    }
}

@Composable
private fun ScrollableCandidateRow(
    state: CandidateBarState,
    callbacks: CandidateBarCallbacks,
    colors: DuoColorTokens,
    modifier: Modifier = Modifier
) {
    val dimensions = LocalDuoDimensions.current
    val listState = rememberLazyListState()
    val items = state.windowCandidates

    LaunchedEffect(
        state.snapshot.preedit,
        state.snapshot.selectedSyllableIndex,
        state.snapshot.variant,
        state.snapshot.mode
    ) {
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(state.snapshot.pageIndex) {
        val target = resolveScrollTargetIndex(items, state.snapshot.pageIndex)
        if (target in items.indices &&
            (listState.firstVisibleItemIndex != target || listState.firstVisibleItemScrollOffset > 0)
        ) {
            listState.scrollToItem(target)
        }
    }

    LaunchedEffect(listState, items.size, state.hasMoreWindowCandidates) {
        if (!state.hasMoreWindowCandidates || items.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (lastVisible >= 0 && lastVisible >= items.lastIndex - CandidateBarLayout.AUTO_LOAD_THRESHOLD_ITEMS) {
                    callbacks.onLoadMoreCandidates()
                }
            }
    }

    LazyRow(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = CandidateBarLayout.LAZY_CONTENT_HORIZONTAL_PADDING_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(CandidateBarLayout.CANDIDATE_SPACING_DP.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (items.isNotEmpty()) {
            itemsIndexed(
                items = items,
                key = { _, item -> "w:${item.globalIndex}:${item.pageIndex}:${item.indexOnPage}" }
            ) { _, item ->
                CandidateChip(
                    text = item.text,
                    highlighted = item.highlighted,
                    fontSizeSp = dimensions.candidateFontSp,
                    colors = colors,
                    onClick = { callbacks.onSelectWindowCandidate(item.globalIndex) }
                )
            }
        } else {
            val snap = state.snapshot
            itemsIndexed(
                items = snap.candidates,
                key = { _, item -> "${snap.pageIndex}:${item.indexOnPage}" }
            ) { idx, item ->
                CandidateChip(
                    text = item.text,
                    highlighted = idx == snap.highlightedIndex,
                    fontSizeSp = dimensions.candidateFontSp,
                    colors = colors,
                    onClick = { callbacks.onSelectCandidate(item.indexOnPage) }
                )
            }
        }
    }
}

@Composable
private fun PagedCandidateRow(
    snapshot: EngineSnapshot,
    onSelectCandidate: (Int) -> Unit,
    colors: DuoColorTokens,
    modifier: Modifier = Modifier
) {
    val dimensions = LocalDuoDimensions.current
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = CandidateBarLayout.LAZY_CONTENT_HORIZONTAL_PADDING_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(CandidateBarLayout.CANDIDATE_SPACING_DP.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        itemsIndexed(
            items = snapshot.candidates,
            key = { _, item -> "${snapshot.pageIndex}:${item.indexOnPage}" }
        ) { idx, item ->
            CandidateChip(
                text = item.text,
                highlighted = idx == snapshot.highlightedIndex,
                fontSizeSp = dimensions.candidateFontSp,
                colors = colors,
                onClick = { onSelectCandidate(item.indexOnPage) }
            )
        }
    }
}

@Composable
private fun CandidateChip(
    text: String,
    highlighted: Boolean,
    fontSizeSp: Float,
    colors: DuoColorTokens,
    onClick: () -> Unit
) {
    val bg = if (highlighted) colors.chipSelectedBackground else colors.chipBackground
    val fg = if (highlighted) colors.chipSelectedText else colors.chipText
    val indication = if (LocalDuoThemePreset.current == DuoThemePreset.EINK) null else LocalIndication.current
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = CandidateBarLayout.CANDIDATE_MIN_WIDTH_DP.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = indication,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            maxLines = 1,
            style = TextStyle(
                color = fg,
                fontSize = fontSizeSp.sp,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal
            )
        )
    }
}

@Composable
private fun SmallBarButton(
    label: String,
    enabled: Boolean,
    fontSizeSp: Float,
    colors: DuoColorTokens,
    onClick: () -> Unit
) {
    val indication = if (LocalDuoThemePreset.current == DuoThemePreset.EINK) null else LocalIndication.current
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = CandidateBarLayout.SMALL_BUTTON_MIN_WIDTH_DP.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.chipBackground)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = indication,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            maxLines = 1,
            style = TextStyle(
                color = if (enabled) colors.chipText else colors.keySecondaryText,
                fontSize = fontSizeSp.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

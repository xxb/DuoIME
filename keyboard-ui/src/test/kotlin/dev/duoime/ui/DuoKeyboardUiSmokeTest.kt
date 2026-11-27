/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
package dev.duoime.ui

import androidx.compose.ui.unit.dp
import dev.duoime.core.layout.BuiltInLayouts
import dev.duoime.core.layout.LayoutSpec
import dev.duoime.ui.bar.CandidateBarLayout
import dev.duoime.ui.grid.KeyGridGeometry
import dev.duoime.ui.theme.DuoDefaultDimensions
import dev.duoime.ui.theme.DuoDimensionTokens
import dev.duoime.ui.theme.resolveAdaptiveDimensions
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuoKeyboardUiSmokeTest {
    @Test
    fun placeholderLabelIsNonBlank() {
        assertTrue(DuoKeyboardUiInfo.PLACEHOLDER_LABEL.isNotBlank())
    }

    @Test
    fun defaultBarHeightsSatisfyErgonomicInvariantsAndKeepToolbarEqualToCandidateBar() {
        assertEquals(DuoDefaultDimensions.toolbarHeight, DuoDefaultDimensions.candidateBarHeight)
        assertTrue("Toolbar must be >= 48dp", DuoDefaultDimensions.toolbarHeight >= 48.dp)
        assertTrue("Candidate bar must be >= 48dp", DuoDefaultDimensions.candidateBarHeight >= 48.dp)
        assertTrue("Syllable rail must be >= 48dp", DuoDefaultDimensions.syllableBarHeight >= 48.dp)
        assertTrue(
            "Candidate bar inner hit height must be >= 48dp",
            DuoDefaultDimensions.candidateBarHeight - (CandidateBarLayout.OUTER_VERTICAL_PADDING_DP * 2f).dp >= 48.dp
        )
        assertTrue(
            "Syllable rail inner hit height must be >= 48dp",
            DuoDefaultDimensions.syllableBarHeight - (CandidateBarLayout.SYLLABLE_VERTICAL_PADDING_DP * 2f).dp >= 48.dp
        )
    }

    @Test
    fun candidateBarWidthBudgetFitsComfortableCandidateCountsOnCompactAndStandardScreens() {
        assertTrue(CandidateBarLayout.CANDIDATE_MIN_WIDTH_DP >= 48f)
        assertTrue(CandidateBarLayout.SYLLABLE_MIN_WIDTH_DP >= 48f)
        val capacityAt320 = CandidateBarLayout.maxVisibleMinWidthCandidates(barWidthDp = 320f)
        val capacityAt375 = CandidateBarLayout.maxVisibleMinWidthCandidates(barWidthDp = 375f)
        assertTrue("Expected >= 2 candidate chips at 320dp, got $capacityAt320", capacityAt320 >= 2)
        assertTrue("Expected >= 3 candidate chips at 375dp, got $capacityAt375", capacityAt375 >= 3)
    }

    @Test
    fun resolveAdaptiveDimensionsMatchesRealKeyGridRowHeightAndEnforcesCompactFloorsAndCaps() {
        val qwerty4 = BuiltInLayouts.qwerty(includeNumberRow = false)
        val qwerty5 = BuiltInLayouts.qwerty(includeNumberRow = true)
        val t9 = BuiltInLayouts.t9()

        fun measureRenderedRowHeight(
            availableHeightDp: Float,
            layout: LayoutSpec,
            hasSyllableRail: Boolean,
            resolved: DuoDimensionTokens
        ): Float {
            assertEquals(resolved.toolbarHeight, resolved.candidateBarHeight)
            val syllableTotal = if (hasSyllableRail) resolved.syllableBarHeight.value + 1f else 0f
            val gridHeight = availableHeightDp -
                resolved.candidateBarHeight.value -
                1f -
                syllableTotal -
                resolved.gridVerticalPadding.value * 2f
            val hitMap = KeyGridGeometry.computeHitMap(
                layout = layout,
                widthPx = 360f,
                heightPx = gridHeight,
                horizontalGapPx = resolved.keyHorizontalGap.value,
                verticalGapPx = resolved.keyVerticalGap.value
            )
            return hitMap.bottom(0) - hitMap.top(0)
        }

        // Exact header == rendered key row height (< 0.05dp) across 360dp (4-row, 5-row, T9), 432dp 5-row, 320dp T9
        val r360Four = resolveAdaptiveDimensions(360f, qwerty4.rows.size, hasSyllableRail = false)
        val row360Four = measureRenderedRowHeight(360f, qwerty4, hasSyllableRail = false, r360Four)
        assertTrue(
            "360dp 4-row header (${r360Four.candidateBarHeight.value}) vs key row ($row360Four)",
            abs(r360Four.candidateBarHeight.value - row360Four) < 0.05f
        )
        assertTrue(
            "360dp 4-row inner candidate bar hit height must be >= 48dp",
            r360Four.candidateBarHeight.value - CandidateBarLayout.OUTER_VERTICAL_PADDING_DP * 2f >= 48f
        )

        val r360Five = resolveAdaptiveDimensions(360f, qwerty5.rows.size, hasSyllableRail = false)
        val row360Five = measureRenderedRowHeight(360f, qwerty5, hasSyllableRail = false, r360Five)
        assertTrue(
            "360dp 5-row header (${r360Five.candidateBarHeight.value}) vs key row ($row360Five)",
            abs(r360Five.candidateBarHeight.value - row360Five) < 0.05f
        )
        assertTrue(
            "360dp 5-row inner candidate bar hit height must be >= 48dp",
            r360Five.candidateBarHeight.value - CandidateBarLayout.OUTER_VERTICAL_PADDING_DP * 2f >= 48f
        )
        assertTrue(r360Five.candidateBarHeight < r360Four.candidateBarHeight)

        val r360T9 = resolveAdaptiveDimensions(360f, t9.rows.size, hasSyllableRail = true)
        val row360T9 = measureRenderedRowHeight(360f, t9, hasSyllableRail = true, r360T9)
        assertTrue(
            "360dp T9 header (${r360T9.candidateBarHeight.value}) vs key row ($row360T9)",
            abs(r360T9.candidateBarHeight.value - row360T9) < 0.05f
        )

        val r432Five = resolveAdaptiveDimensions(432f, qwerty5.rows.size, hasSyllableRail = false)
        val row432Five = measureRenderedRowHeight(432f, qwerty5, hasSyllableRail = false, r432Five)
        assertTrue(
            "432dp 5-row header (${r432Five.candidateBarHeight.value}) vs key row ($row432Five)",
            abs(r432Five.candidateBarHeight.value - row432Five) < 0.05f
        )

        // 320dp T9: BOTH header and rendered key rows >= 48dp and exactly matched
        val r320T9 = resolveAdaptiveDimensions(320f, t9.rows.size, hasSyllableRail = true)
        val row320T9 = measureRenderedRowHeight(320f, t9, hasSyllableRail = true, r320T9)
        assertTrue(
            "320dp T9 header (${r320T9.candidateBarHeight.value}) vs key row ($row320T9)",
            abs(r320T9.candidateBarHeight.value - row320T9) < 0.05f
        )
        assertTrue("320dp T9 header must be >= 48dp", r320T9.candidateBarHeight >= 48.dp)
        assertTrue("320dp T9 key row must be >= 48dp, got $row320T9", row320T9 >= 48f)

        // 260dp T9 compact mode: relaxes to 40dp header (36dp inner) / 34dp rail (32dp inner) and 38.5dp key rows
        val r260T9 = resolveAdaptiveDimensions(260f, t9.rows.size, hasSyllableRail = true)
        val row260T9 = measureRenderedRowHeight(260f, t9, hasSyllableRail = true, r260T9)
        assertEquals(40.dp, r260T9.toolbarHeight)
        assertEquals(40.dp, r260T9.candidateBarHeight)
        assertEquals(34.dp, r260T9.syllableBarHeight)
        assertEquals(38.5f, row260T9, 0.05f)
        assertEquals(
            36f,
            r260T9.candidateBarHeight.value - CandidateBarLayout.OUTER_VERTICAL_PADDING_DP * 2f,
            0.01f
        )
        assertEquals(
            32f,
            r260T9.syllableBarHeight.value - CandidateBarLayout.SYLLABLE_VERTICAL_PADDING_DP * 2f,
            0.01f
        )
        assertEquals(16f, r260T9.candidateFontSp, 0.01f)
        assertEquals(15f, r260T9.barButtonFontSp, 0.01f)
        assertEquals(13f, r260T9.preeditFontSp, 0.01f)
        assertEquals(12f, r260T9.toolbarFontSp, 0.01f)
        assertEquals(14f, r260T9.syllableFontSp, 0.01f)

        // 900dp giant screen: strictly capped at 76dp / 64dp and proportional typography upper bounds
        val r900T9 = resolveAdaptiveDimensions(900f, t9.rows.size, hasSyllableRail = true)
        assertEquals(76.dp, r900T9.toolbarHeight)
        assertEquals(76.dp, r900T9.candidateBarHeight)
        assertEquals(64.dp, r900T9.syllableBarHeight)
        assertTrue(r900T9.candidateFontSp in 16f..25f)
        assertTrue(r900T9.barButtonFontSp in 15f..22f)
        assertTrue(r900T9.preeditFontSp in 13f..19f)
        assertTrue(r900T9.toolbarFontSp in 12f..18f)
        assertTrue(r900T9.syllableFontSp in 14f..21f)
    }
}

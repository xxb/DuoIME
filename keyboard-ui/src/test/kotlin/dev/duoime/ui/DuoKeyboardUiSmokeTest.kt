/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
package dev.duoime.ui

import androidx.compose.ui.unit.dp
import dev.duoime.ui.bar.CandidateBarLayout
import dev.duoime.ui.theme.DuoDefaultDimensions
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
    fun resolveAdaptiveDimensionsMatchesKeyRowHeightAndEnforcesFloorsAndCaps() {
        // 260dp compact inline: hits 52dp / 50dp floors
        val compact260 = resolveAdaptiveDimensions(availableHeightDp = 260f, keyRowCount = 4, hasSyllableRail = false)
        assertEquals(DuoDefaultDimensions.toolbarHeight, compact260.toolbarHeight)
        assertEquals(compact260.toolbarHeight, compact260.candidateBarHeight)
        assertEquals(DuoDefaultDimensions.syllableBarHeight, compact260.syllableBarHeight)
        assertEquals(19f, compact260.candidateFontSp, 0.01f)
        assertEquals(17f, compact260.barButtonFontSp, 0.01f)
        assertEquals(15f, compact260.preeditFontSp, 0.01f)
        assertEquals(13f, compact260.toolbarFontSp, 0.01f)
        assertEquals(16f, compact260.syllableFontSp, 0.01f)

        // 360dp 4-row: header height matches the resulting key row height cleanly
        val mid360FourRow = resolveAdaptiveDimensions(
            availableHeightDp = 360f,
            keyRowCount = 4,
            hasSyllableRail = false
        )
        assertEquals(mid360FourRow.toolbarHeight, mid360FourRow.candidateBarHeight)
        assertTrue(mid360FourRow.toolbarHeight > 52.dp && mid360FourRow.toolbarHeight < 76.dp)
        val gridHeight360 = 360f - 1f - DuoDefaultDimensions.gridVerticalPadding.value * 2f -
            mid360FourRow.toolbarHeight.value
        val keyRowHeight360 = gridHeight360 / 4f
        assertTrue(
            "Header height (${mid360FourRow.toolbarHeight.value}) should closely match key row ($keyRowHeight360)",
            abs(mid360FourRow.toolbarHeight.value - keyRowHeight360) < 0.5f
        )

        // 360dp 5-row (number row) scales below 4-row while staying >= 52dp floor
        val mid360FiveRow = resolveAdaptiveDimensions(
            availableHeightDp = 360f,
            keyRowCount = 5,
            hasSyllableRail = false
        )
        assertEquals(mid360FiveRow.toolbarHeight, mid360FiveRow.candidateBarHeight)
        assertTrue(mid360FiveRow.toolbarHeight >= 52.dp)
        assertTrue(mid360FiveRow.toolbarHeight < mid360FourRow.toolbarHeight)

        // 432dp 4-row (tall overlay): caps at 76dp header / 64dp syllable with proportional typography
        val tall432 = resolveAdaptiveDimensions(availableHeightDp = 432f, keyRowCount = 4, hasSyllableRail = false)
        assertEquals(76.dp, tall432.toolbarHeight)
        assertEquals(tall432.toolbarHeight, tall432.candidateBarHeight)
        assertEquals(64.dp, tall432.syllableBarHeight)
        assertEquals(25f, tall432.candidateFontSp, 0.01f)
        assertEquals(22f, tall432.barButtonFontSp, 0.01f)
        assertEquals(19f, tall432.preeditFontSp, 0.01f)
        assertEquals(18f, tall432.toolbarFontSp, 0.01f)

        // T9 mode (hasSyllableRail = true) accounts for extra rail + divider while maintaining floors
        val t9At360 = resolveAdaptiveDimensions(availableHeightDp = 360f, keyRowCount = 4, hasSyllableRail = true)
        assertEquals(t9At360.toolbarHeight, t9At360.candidateBarHeight)
        assertTrue(t9At360.toolbarHeight >= 52.dp)
        assertTrue(t9At360.syllableBarHeight >= 50.dp)
        assertTrue(t9At360.toolbarHeight < mid360FourRow.toolbarHeight)
        assertTrue(t9At360.syllableBarHeight <= t9At360.toolbarHeight)

        // 900dp giant screen: strictly capped at 76dp / 64dp and font bounds
        val giant900 = resolveAdaptiveDimensions(availableHeightDp = 900f, keyRowCount = 4, hasSyllableRail = true)
        assertEquals(76.dp, giant900.toolbarHeight)
        assertEquals(76.dp, giant900.candidateBarHeight)
        assertEquals(64.dp, giant900.syllableBarHeight)
        assertTrue(giant900.candidateFontSp in 19f..25f)
        assertTrue(giant900.barButtonFontSp in 17f..22f)
        assertTrue(giant900.preeditFontSp in 15f..19f)
        assertTrue(giant900.toolbarFontSp in 13f..18f)
        assertTrue(giant900.syllableFontSp in 16f..21f)
    }
}

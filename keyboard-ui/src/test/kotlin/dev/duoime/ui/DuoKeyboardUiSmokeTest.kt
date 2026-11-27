/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
package dev.duoime.ui

import androidx.compose.ui.unit.dp
import dev.duoime.ui.bar.CandidateBarLayout
import dev.duoime.ui.theme.DuoDefaultDimensions
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
}

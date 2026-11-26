/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
package dev.duoime.ui

import androidx.compose.ui.unit.dp
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
    fun defaultBarHeightsAreErgonomicAndKeepToolbarEqualToCandidateBar() {
        assertEquals(52.dp, DuoDefaultDimensions.toolbarHeight)
        assertEquals(DuoDefaultDimensions.toolbarHeight, DuoDefaultDimensions.candidateBarHeight)
        assertEquals(42.dp, DuoDefaultDimensions.syllableBarHeight)
    }
}

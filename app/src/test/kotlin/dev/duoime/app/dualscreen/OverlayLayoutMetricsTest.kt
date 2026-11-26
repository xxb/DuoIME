// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.dualscreen

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OverlayLayoutMetricsTest {

    @Test
    fun overlayLayoutMetricsFollowFormulaAndNeverIntrudeIntoTopInset() {
        // 1280x960 secondary display (gesture bar bottom=48, top=0) at 60%, 75%, 90%
        val m60 = OverlayLayoutCalculator.computeOverlayLayoutMetrics(
            displayHeightPx = 960,
            topInsetPx = 0,
            bottomInsetPx = 48,
            heightFraction = 0.60f
        )
        assertEquals(624, m60.windowHeightPx) // 0.60 * 960 + 48
        assertEquals(576, m60.contentHeightPx)
        assertEquals(48, m60.bottomInsetPx)

        val m75 = OverlayLayoutCalculator.computeOverlayLayoutMetrics(
            displayHeightPx = 960,
            topInsetPx = 48,
            bottomInsetPx = 96,
            heightFraction = 0.75f
        )
        assertEquals(780, m75.windowHeightPx) // 0.75 * (960 - 48) + 96 = 684 + 96
        assertEquals(684, m75.contentHeightPx)
        assertTrue(m75.windowHeightPx <= 960 - 48)

        // 90% with tall 3-button nav + cutout MUST cap at (displayHeight - topInset) so status bar is untouched
        val m90TallNav = OverlayLayoutCalculator.computeOverlayLayoutMetrics(
            displayHeightPx = 960,
            topInsetPx = 64,
            bottomInsetPx = 128,
            heightFraction = 0.90f
        )
        assertEquals(960 - 64, m90TallNav.windowHeightPx)
        assertEquals(960 - 64 - 128, m90TallNav.contentHeightPx)
    }

    @Test
    fun visibilityDebouncerIgnoresTransientFlapsAndInactiveSessionsButCatchesSustainedHostHide() = runTest {
        var hiddenCallbacks = 0
        val debouncer = OverlayVisibilityDebouncer(
            scope = this,
            debounceMs = 150L,
            onHiddenConfirmed = { hiddenCallbacks++ }
        )

        // Inactive session (!VISIBLE during normal hide) -> never triggers fallback
        debouncer.onVisibilityChanged(isVisible = false, isSessionActive = false)
        advanceTimeBy(300L)
        runCurrent()
        assertEquals(0, hiddenCallbacks)

        // Transient !VISIBLE -> VISIBLE within < 150ms -> cancelled cleanly (no flap)
        debouncer.onVisibilityChanged(isVisible = false, isSessionActive = true)
        advanceTimeBy(100L)
        debouncer.onVisibilityChanged(isVisible = true, isSessionActive = true)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(0, hiddenCallbacks)

        // Sustained !VISIBLE while active (>= 150ms, e.g. setHideOverlayWindows) -> triggers once
        debouncer.onVisibilityChanged(isVisible = false, isSessionActive = true)
        advanceTimeBy(160L)
        runCurrent()
        assertEquals(1, hiddenCallbacks)

        // Duplicate !VISIBLE does not fire again until reset()
        debouncer.onVisibilityChanged(isVisible = false, isSessionActive = true)
        advanceTimeBy(300L)
        runCurrent()
        assertEquals(1, hiddenCallbacks)

        // After reset() for a new input-view session, requires a fresh full 150 ms !VISIBLE window
        debouncer.reset()
        debouncer.onVisibilityChanged(isVisible = false, isSessionActive = true)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals("Session 2 must not confirm before full 150ms debounce", 1, hiddenCallbacks)
        advanceTimeBy(60L)
        runCurrent()
        assertEquals(2, hiddenCallbacks)
    }
}

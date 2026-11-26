// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.dualscreen

import dev.duoime.core.policy.DisplayCandidate
import dev.duoime.core.policy.DisplayKind
import dev.duoime.core.policy.DisplayPlacementInput
import dev.duoime.core.policy.DisplayPlacementPolicy
import dev.duoime.core.policy.PlacementDecision
import dev.duoime.core.policy.PlacementReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun showTrackerAndSuppressionReprobeHostHiddenOverlayAcrossSessionBoundaryAndRecover() =
        runTest(StandardTestDispatcher()) {
            val displays = listOf(
                DisplayCandidate(displayId = 0, kind = DisplayKind.BUILT_IN, isOn = true),
                DisplayCandidate(displayId = 2, kind = DisplayKind.BUILT_IN, isOn = true)
            )
            var fallbackCalls = 0
            lateinit var tracker: OverlayShowTracker
            val suppression = OverlaySessionSuppression(
                onResetHostTracking = { tracker.resetVisibilityTracking() },
                onFallback = { fallbackCalls++ }
            )
            tracker = OverlayShowTracker(
                scope = this,
                debounceMs = OverlayVisibilityDebouncer.DEFAULT_DEBOUNCE_MS,
                onHiddenConfirmed = { suppression.onOverlayHiddenConfirmed() }
            )

            // (a) Active overlay -> !VISIBLE for 80 ms -> session boundary reset forces re-show + fresh 150 ms probe
            tracker.beginShow(displayId = 2, heightFraction = 0.75f, darkTheme = true)
            tracker.onWindowVisibilityChanged(isVisible = false)
            advanceTimeBy(80L)
            runCurrent()
            assertFalse(suppression.overlayHiddenByHost)

            suppression.resetForSessionBoundary()
            assertTrue(tracker.forceReshow)
            assertFalse(tracker.shouldSkipShow(2, 0.75f, darkTheme = true, isWindowVisible = false))
            assertFalse(tracker.shouldSkipShow(2, 0.75f, darkTheme = true, isWindowVisible = true))

            // Re-show runs because shouldSkipShow returned false; probing already-hidden window arms fresh 150 ms timer
            tracker.beginShow(displayId = 2, heightFraction = 0.75f, darkTheme = true)
            tracker.probeWindowVisibility(isWindowVisible = false)
            advanceTimeBy(149L)
            runCurrent()
            assertFalse("Must not confirm before full 150 ms probe window", suppression.overlayHiddenByHost)
            assertEquals(0, fallbackCalls)

            advanceTimeBy(1L)
            runCurrent()
            assertTrue(suppression.overlayHiddenByHost)
            assertEquals(1, fallbackCalls)

            val hiddenDecision = DisplayPlacementPolicy.resolve(
                DisplayPlacementInput(
                    displays = displays,
                    sourceDisplayId = 0,
                    hasOverlayPermission = true,
                    systemImePin = false,
                    overlayHiddenByHost = suppression.overlayHiddenByHost,
                    apiLevel = 34
                )
            )
            assertEquals(
                PlacementDecision.Inline(reason = PlacementReason.OVERLAY_HIDDEN_BY_HOST),
                hiddenDecision
            )

            // (c) Post-confirmation reset clears suppression so policy resolves Overlay again
            suppression.resetForSessionBoundary()
            assertFalse(suppression.overlayHiddenByHost)
            val restoredDecision = DisplayPlacementPolicy.resolve(
                DisplayPlacementInput(
                    displays = displays,
                    sourceDisplayId = 0,
                    hasOverlayPermission = true,
                    systemImePin = false,
                    overlayHiddenByHost = suppression.overlayHiddenByHost,
                    apiLevel = 34
                )
            )
            assertTrue(restoredDecision is PlacementDecision.Overlay)
            assertEquals(2, (restoredDecision as PlacementDecision.Overlay).targetDisplayId)

            // (b) Recovery when VISIBLE arrives within 150 ms cancels probe and re-enables shouldSkipShow
            tracker.beginShow(displayId = 2, heightFraction = 0.75f, darkTheme = true)
            tracker.probeWindowVisibility(isWindowVisible = false)
            advanceTimeBy(100L)
            runCurrent()
            tracker.onWindowVisibilityChanged(isVisible = true)
            advanceTimeBy(200L)
            runCurrent()
            assertFalse(suppression.overlayHiddenByHost)
            assertEquals(1, fallbackCalls)
            assertTrue(tracker.shouldSkipShow(2, 0.75f, darkTheme = true, isWindowVisible = true))
        }
}

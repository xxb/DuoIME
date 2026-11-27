// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.dualscreen

import dev.duoime.core.policy.DisplayCandidate
import dev.duoime.core.policy.DisplayKind
import dev.duoime.core.policy.DisplayPlacementInput
import dev.duoime.core.policy.PlacementDecision
import dev.duoime.core.policy.PlacementReason
import dev.duoime.ui.theme.DuoThemePreset
import kotlinx.coroutines.CoroutineScope
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
class DualScreenSessionControllerTest {

    private class FakeOverlaySurface(
        scope: CoroutineScope,
        onHiddenConfirmed: () -> Unit
    ) : OverlaySurface {
        val tracker = OverlayShowTracker(
            scope = scope,
            debounceMs = OverlayVisibilityDebouncer.DEFAULT_DEBOUNCE_MS,
            onHiddenConfirmed = onHiddenConfirmed
        )
        var windowVisible: Boolean = true
        var showCalls: Int = 0
            private set
        var hideCalls: Int = 0
            private set
        var detachCalls: Int = 0
            private set

        override val isShowing: Boolean
            get() = tracker.isShowing

        override val attachedDisplayId: Int
            get() = tracker.attachedDisplayId

        override fun isShowingOn(displayId: Int, heightFraction: Float, themePreset: DuoThemePreset): Boolean =
            tracker.shouldSkipShow(
                displayId = displayId,
                heightFraction = heightFraction,
                themePreset = themePreset,
                isWindowVisible = windowVisible
            )

        override fun show(targetDisplayId: Int, heightFraction: Float, themePreset: DuoThemePreset): Boolean {
            if (isShowingOn(targetDisplayId, heightFraction, themePreset)) {
                return true
            }
            showCalls++
            tracker.beginShow(targetDisplayId, heightFraction, themePreset)
            tracker.probeWindowVisibility(isWindowVisible = windowVisible)
            return true
        }

        override fun hide() {
            hideCalls++
            tracker.markHidden()
        }

        override fun detach() {
            detachCalls++
            tracker.markDetached()
        }

        override fun resetVisibilityTracking() {
            tracker.resetVisibilityTracking()
        }
    }

    private class TestHarness(scope: CoroutineScope) {
        val displays = listOf(
            DisplayCandidate(displayId = 0, kind = DisplayKind.BUILT_IN, isOn = true),
            DisplayCandidate(displayId = 2, kind = DisplayKind.BUILT_IN, isOn = true)
        )
        var isPasswordField: Boolean = false
        var swapToSameScreen: Boolean = false
        var themePreset: DuoThemePreset = DuoThemePreset.DARK
        val appliedDecisions = mutableListOf<PlacementDecision>()
        lateinit var controller: DualScreenSessionController
        val surface = FakeOverlaySurface(
            scope = scope,
            onHiddenConfirmed = { controller.onOverlayHiddenByHost() }
        )

        init {
            controller = DualScreenSessionController(
                overlaySurface = surface,
                evaluatePlacementInput = {
                    PlacementEvaluationSnapshot(
                        input = DisplayPlacementInput(
                            displays = displays,
                            sourceDisplayId = 0,
                            hasOverlayPermission = true,
                            systemImePin = false,
                            isPasswordField = isPasswordField,
                            swapToSameScreen = swapToSameScreen,
                            overlayHiddenByHost = controller.overlayHiddenByHost,
                            apiLevel = 34,
                            heightFraction = 0.75f
                        ),
                        themePreset = themePreset
                    )
                },
                onOverlayApplied = { appliedDecisions += it },
                onInlineApplied = { appliedDecisions += it }
            )
        }
    }

    @Test
    fun sameSessionOnStartInputFocusSwitchesPreserveActiveHideProbeAndSuppression() =
        runTest(StandardTestDispatcher()) {
            val harness = TestHarness(this)
            harness.surface.windowVisible = false

            // t = 0 ms: overlay shows in a setHideOverlayWindows host (!VISIBLE)
            harness.controller.onStartInputView()
            assertEquals(1, harness.surface.showCalls)
            assertTrue(harness.controller.currentDecision is PlacementDecision.Overlay)

            // Same-session onStartInput() focus switches at 50 ms, 100 ms, 140 ms must neither
            // re-call show() nor reset the 150 ms host-hide countdown.
            advanceTimeBy(50L)
            runCurrent()
            harness.controller.onStartInput()
            assertEquals(1, harness.surface.showCalls)
            assertFalse(harness.controller.overlayHiddenByHost)

            advanceTimeBy(50L)
            runCurrent()
            harness.controller.onStartInput()
            assertEquals(1, harness.surface.showCalls)
            assertFalse(harness.controller.overlayHiddenByHost)

            advanceTimeBy(40L)
            runCurrent()
            harness.controller.onStartInput()
            assertEquals(1, harness.surface.showCalls)
            assertFalse(harness.controller.overlayHiddenByHost)

            // t = 149 ms: still not confirmed
            advanceTimeBy(9L)
            runCurrent()
            assertFalse(harness.controller.overlayHiddenByHost)
            assertEquals(0, harness.surface.hideCalls)
            assertTrue(harness.controller.currentDecision is PlacementDecision.Overlay)

            // t = 150 ms: fallback confirms and hides overlay
            advanceTimeBy(1L)
            runCurrent()
            assertTrue(harness.controller.overlayHiddenByHost)
            assertEquals(
                PlacementDecision.Inline(reason = PlacementReason.OVERLAY_HIDDEN_BY_HOST),
                harness.controller.currentDecision
            )
            assertEquals(1, harness.surface.hideCalls)

            // t = 200 ms: subsequent same-session onStartInput() keeps suppression intact
            advanceTimeBy(50L)
            runCurrent()
            harness.controller.onStartInput()
            assertEquals(
                PlacementDecision.Inline(reason = PlacementReason.OVERLAY_HIDDEN_BY_HOST),
                harness.controller.currentDecision
            )
            assertEquals(1, harness.surface.showCalls)
            assertEquals(1, harness.surface.hideCalls)
        }

    @Test
    fun sameSessionOnStartInputImmediatelyTransitionsForPasswordFieldAndBack() = runTest(StandardTestDispatcher()) {
        val harness = TestHarness(this)
        harness.surface.windowVisible = true

        harness.controller.onStartInputView()
        assertTrue(harness.controller.currentDecision is PlacementDecision.Overlay)
        assertEquals(1, harness.surface.showCalls)

        harness.isPasswordField = true
        harness.controller.onStartInput()
        assertEquals(
            PlacementDecision.Inline(reason = PlacementReason.PASSWORD_FIELD),
            harness.controller.currentDecision
        )
        assertEquals(1, harness.surface.hideCalls)

        harness.isPasswordField = false
        harness.controller.onStartInput()
        assertTrue(harness.controller.currentDecision is PlacementDecision.Overlay)
        assertEquals(2, harness.surface.showCalls)
    }

    @Test
    fun onStartInputViewInsideDebounceWindowForcesReshowAndProbesFreshFull150Ms() = runTest(StandardTestDispatcher()) {
        val harness = TestHarness(this)
        harness.surface.windowVisible = false

        harness.controller.onStartInputView()
        assertEquals(1, harness.surface.showCalls)

        // t = 80 ms: real onStartInputView() clears suppression, forces re-show, and restarts probe
        advanceTimeBy(80L)
        runCurrent()
        assertFalse(harness.controller.overlayHiddenByHost)

        harness.controller.onStartInputView()
        assertEquals(2, harness.surface.showCalls)
        assertFalse(harness.controller.overlayHiddenByHost)
        assertTrue(harness.controller.currentDecision is PlacementDecision.Overlay)

        // +149 ms: still within fresh probe window
        advanceTimeBy(149L)
        runCurrent()
        assertFalse(harness.controller.overlayHiddenByHost)
        assertTrue(harness.controller.currentDecision is PlacementDecision.Overlay)

        // +150 ms: confirms hidden and degrades to Inline
        advanceTimeBy(1L)
        runCurrent()
        assertTrue(harness.controller.overlayHiddenByHost)
        assertEquals(
            PlacementDecision.Inline(reason = PlacementReason.OVERLAY_HIDDEN_BY_HOST),
            harness.controller.currentDecision
        )
    }

    @Test
    fun allFiveSessionAndSwapBoundariesClearHostHideSuppression() = runTest(StandardTestDispatcher()) {
        fun TestHarness.triggerHostHide() {
            surface.windowVisible = false
            controller.onStartInputView()
            advanceTimeBy(OverlayVisibilityDebouncer.DEFAULT_DEBOUNCE_MS)
            runCurrent()
            assertTrue(controller.overlayHiddenByHost)
            assertEquals(
                PlacementDecision.Inline(reason = PlacementReason.OVERLAY_HIDDEN_BY_HOST),
                controller.currentDecision
            )
        }

        // 1. onStartInputView
        val h1 = TestHarness(this)
        h1.triggerHostHide()
        h1.surface.windowVisible = true
        h1.controller.onStartInputView()
        assertFalse(h1.controller.overlayHiddenByHost)
        assertTrue(h1.controller.currentDecision is PlacementDecision.Overlay)

        // 2. onFinishInputView
        val h2 = TestHarness(this)
        h2.triggerHostHide()
        h2.controller.onFinishInputView()
        assertFalse(h2.controller.overlayHiddenByHost)
        h2.surface.windowVisible = true
        h2.controller.onStartInputView()
        assertTrue(h2.controller.currentDecision is PlacementDecision.Overlay)

        // 3. onWindowHidden
        val h3 = TestHarness(this)
        h3.triggerHostHide()
        h3.controller.onWindowHidden()
        assertFalse(h3.controller.overlayHiddenByHost)
        h3.surface.windowVisible = true
        h3.controller.onStartInputView()
        assertTrue(h3.controller.currentDecision is PlacementDecision.Overlay)

        // 4. onFinishInput
        val h4 = TestHarness(this)
        h4.triggerHostHide()
        h4.controller.onFinishInput()
        assertFalse(h4.controller.overlayHiddenByHost)
        h4.surface.windowVisible = true
        h4.controller.onStartInputView()
        assertTrue(h4.controller.currentDecision is PlacementDecision.Overlay)

        // 5. onSwapScreenToggled(false)
        val h5 = TestHarness(this)
        h5.triggerHostHide()
        h5.surface.windowVisible = true
        h5.swapToSameScreen = false
        h5.controller.onSwapScreenToggled(swapToSameScreen = false)
        assertFalse(h5.controller.overlayHiddenByHost)
        assertTrue(h5.controller.currentDecision is PlacementDecision.Overlay)

        // Switching LIGHT -> EINK forces overlay presentation refresh
        h5.themePreset = DuoThemePreset.LIGHT
        h5.controller.onRelevantEnvironmentChanged()
        val callsAtLight = h5.surface.showCalls
        h5.themePreset = DuoThemePreset.EINK
        h5.controller.onRelevantEnvironmentChanged()
        assertEquals(callsAtLight + 1, h5.surface.showCalls)
        assertEquals(DuoThemePreset.EINK, h5.surface.tracker.lastThemePreset)
    }
}

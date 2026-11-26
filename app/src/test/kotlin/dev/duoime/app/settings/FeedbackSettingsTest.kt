// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.settings

import dev.duoime.app.dualscreen.DualScreenSessionController
import dev.duoime.app.dualscreen.OverlayShowTracker
import dev.duoime.app.dualscreen.OverlaySurface
import dev.duoime.app.dualscreen.OverlayVisibilityDebouncer
import dev.duoime.app.dualscreen.PlacementEvaluationSnapshot
import dev.duoime.core.policy.DisplayCandidate
import dev.duoime.core.policy.DisplayKind
import dev.duoime.core.policy.DisplayPlacementInput
import dev.duoime.core.policy.PlacementDecision
import dev.duoime.core.policy.PlacementReason
import dev.duoime.ui.feedback.KeyFeedbackOptions
import dev.duoime.ui.feedback.KeySoundStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackSettingsTest {

    @Test
    fun defaultsKeepSoundOffAndHapticsOn() {
        val defaults = DuoImeSettings()
        assertFalse("Key sound must default to OFF", defaults.soundEnabled)
        assertTrue("Key haptics must default to ON", defaults.hapticEnabled)
        assertEquals(KeyFeedbackOptions.DEFAULT_HAPTIC_STRENGTH, defaults.hapticStrength)
        assertEquals(KeySoundStyle.MECHANICAL, defaults.soundStyle)
        assertEquals(KeyFeedbackOptions.DEFAULT_SOUND_VOLUME, defaults.soundVolume, 0.0001f)
    }

    @Test
    fun toFeedbackOptionsClampsOutOfRangeValuesSafely() {
        val raw = DuoImeSettings(
            hapticEnabled = true,
            hapticStrength = 99,
            soundEnabled = true,
            soundStyle = KeySoundStyle.GUZHENG,
            soundVolume = Float.NaN
        )
        val options = raw.toFeedbackOptions()
        assertEquals(KeyFeedbackOptions.MAX_HAPTIC_STRENGTH, options.hapticStrength)
        assertEquals(KeyFeedbackOptions.DEFAULT_SOUND_VOLUME, options.soundVolume, 0.0001f)
        assertEquals(KeySoundStyle.GUZHENG, options.soundStyle)

        val low = raw.copy(hapticStrength = -5, soundVolume = -1f).toFeedbackOptions()
        assertEquals(KeyFeedbackOptions.MIN_HAPTIC_STRENGTH, low.hapticStrength)
        assertEquals(KeyFeedbackOptions.MIN_SOUND_VOLUME, low.soundVolume, 0.0001f)
    }

    @Test
    fun allSoundStylesMapToDistinctLocalizedStringResources() {
        val resIds = KeySoundStyle.entries.map { soundStyleLabelRes(it) }
        assertEquals(KeySoundStyle.entries.size, resIds.toSet().size)
        assertTrue(resIds.all { it != 0 })
    }

    @Test
    fun resolveLiveSettingsActionClassifiesNoOpSwapPlacementAndFeedbackDeltas() {
        val base = DuoImeSettings()

        // (a) Identical snapshot -> strict no-op
        assertEquals(LiveSettingsPlacementAction.NONE, resolveLiveSettingsAction(base, base.copy()))

        // (d) Feedback-only and non-placement preference changes -> NONE
        assertEquals(
            LiveSettingsPlacementAction.NONE,
            resolveLiveSettingsAction(
                base,
                base.copy(
                    hapticEnabled = false,
                    hapticStrength = 5,
                    soundEnabled = true,
                    soundStyle = KeySoundStyle.GRAND_PIANO,
                    soundVolume = 0.8f,
                    includeNumberRow = true,
                    alwaysIncognito = true
                )
            )
        )

        // (b) Swap change -> SWAP_TOGGLED
        assertEquals(
            LiveSettingsPlacementAction.SWAP_TOGGLED,
            resolveLiveSettingsAction(base, base.copy(swapToSameScreen = true))
        )

        // (c) Height, darkTheme, pairedPeer, manualPin, perAppInline -> RELEVANT_ENVIRONMENT_CHANGED
        assertEquals(
            LiveSettingsPlacementAction.RELEVANT_ENVIRONMENT_CHANGED,
            resolveLiveSettingsAction(base, base.copy(heightFraction = 0.90f))
        )
        assertEquals(
            LiveSettingsPlacementAction.RELEVANT_ENVIRONMENT_CHANGED,
            resolveLiveSettingsAction(base, base.copy(darkTheme = false))
        )
        assertEquals(
            LiveSettingsPlacementAction.RELEVANT_ENVIRONMENT_CHANGED,
            resolveLiveSettingsAction(base, base.copy(pairedPeerDisplayId = 2))
        )
        assertEquals(
            LiveSettingsPlacementAction.RELEVANT_ENVIRONMENT_CHANGED,
            resolveLiveSettingsAction(base, base.copy(manualSystemImePin = true))
        )
        assertEquals(
            LiveSettingsPlacementAction.RELEVANT_ENVIRONMENT_CHANGED,
            resolveLiveSettingsAction(base, base.copy(perAppInlinePackages = setOf("com.example.bank")))
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun liveSettingsRouteSwapAndPresentationThroughSessionControllerWithoutFlicker() =
        runTest(StandardTestDispatcher()) {
            var currentSettings = DuoImeSettings()
            var windowVisible = false
            var showCalls = 0
            var hideCalls = 0
            lateinit var sessionController: DualScreenSessionController
            val tracker = OverlayShowTracker(
                scope = this,
                debounceMs = OverlayVisibilityDebouncer.DEFAULT_DEBOUNCE_MS,
                onHiddenConfirmed = { sessionController.onOverlayHiddenByHost() }
            )
            val surface = object : OverlaySurface {
                override val isShowing: Boolean get() = tracker.isShowing
                override val attachedDisplayId: Int get() = tracker.attachedDisplayId
                override fun isShowingOn(displayId: Int, heightFraction: Float, darkTheme: Boolean): Boolean =
                    tracker.shouldSkipShow(displayId, heightFraction, darkTheme, windowVisible)

                override fun show(targetDisplayId: Int, heightFraction: Float, darkTheme: Boolean): Boolean {
                    if (isShowingOn(targetDisplayId, heightFraction, darkTheme)) return true
                    showCalls++
                    tracker.beginShow(targetDisplayId, heightFraction, darkTheme)
                    tracker.probeWindowVisibility(windowVisible)
                    return true
                }

                override fun hide() {
                    hideCalls++
                    tracker.markHidden()
                }

                override fun detach() = tracker.markDetached()

                override fun resetVisibilityTracking() = tracker.resetVisibilityTracking()
            }
            val displays = listOf(
                DisplayCandidate(displayId = 0, kind = DisplayKind.BUILT_IN, isOn = true),
                DisplayCandidate(displayId = 2, kind = DisplayKind.BUILT_IN, isOn = true)
            )
            sessionController = DualScreenSessionController(
                overlaySurface = surface,
                evaluatePlacementInput = {
                    PlacementEvaluationSnapshot(
                        input = DisplayPlacementInput(
                            displays = displays,
                            sourceDisplayId = 0,
                            hasOverlayPermission = true,
                            systemImePin = false,
                            swapToSameScreen = currentSettings.swapToSameScreen,
                            overlayHiddenByHost = sessionController.overlayHiddenByHost,
                            apiLevel = 34,
                            heightFraction = currentSettings.heightFraction
                        ),
                        darkTheme = currentSettings.darkTheme
                    )
                },
                onOverlayApplied = {},
                onInlineApplied = {}
            )

            fun applyDelta(next: DuoImeSettings) {
                val action = resolveLiveSettingsAction(currentSettings, next)
                currentSettings = next
                when (action) {
                    LiveSettingsPlacementAction.SWAP_TOGGLED ->
                        sessionController.onSwapScreenToggled(next.swapToSameScreen)
                    LiveSettingsPlacementAction.RELEVANT_ENVIRONMENT_CHANGED ->
                        sessionController.onRelevantEnvironmentChanged()
                    LiveSettingsPlacementAction.NONE -> Unit
                }
            }

            // Trigger host-hide suppression -> Inline(OVERLAY_HIDDEN_BY_HOST)
            sessionController.onStartInputView()
            advanceTimeBy(OverlayVisibilityDebouncer.DEFAULT_DEBOUNCE_MS)
            runCurrent()
            assertTrue(sessionController.overlayHiddenByHost)
            assertEquals(
                PlacementDecision.Inline(reason = PlacementReason.OVERLAY_HIDDEN_BY_HOST),
                sessionController.currentDecision
            )

            // User swaps to same screen, then swaps back to opposite screen (window now visible):
            // single transition to Overlay with ZERO intermediate hide() flicker.
            applyDelta(currentSettings.copy(swapToSameScreen = true))
            val showsBeforeSwapBack = showCalls
            val hidesBeforeSwapBack = hideCalls
            windowVisible = true
            applyDelta(currentSettings.copy(swapToSameScreen = false))
            assertFalse(sessionController.overlayHiddenByHost)
            assertTrue(sessionController.currentDecision is PlacementDecision.Overlay)
            assertEquals(showsBeforeSwapBack + 1, showCalls)
            assertEquals(hidesBeforeSwapBack, hideCalls)

            // Height and darkTheme live changes each trigger exactly one show/updatePresentation pass
            applyDelta(currentSettings.copy(heightFraction = 0.90f))
            assertEquals(showsBeforeSwapBack + 2, showCalls)
            assertEquals(hidesBeforeSwapBack, hideCalls)
            assertEquals(0.90f, tracker.lastHeightFraction, 0.0001f)

            applyDelta(currentSettings.copy(darkTheme = false))
            assertEquals(showsBeforeSwapBack + 3, showCalls)
            assertEquals(hidesBeforeSwapBack, hideCalls)
            assertFalse(tracker.lastDarkTheme)

            // Feedback-only change triggers zero placement/overlay calls
            applyDelta(currentSettings.copy(soundEnabled = true, soundStyle = KeySoundStyle.KALIMBA_MUSIC_BOX))
            assertEquals(showsBeforeSwapBack + 3, showCalls)
            assertEquals(hidesBeforeSwapBack, hideCalls)
        }
}

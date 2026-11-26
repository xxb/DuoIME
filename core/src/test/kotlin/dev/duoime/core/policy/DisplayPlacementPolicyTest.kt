// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayPlacementPolicyTest {
    private val display0 =
        DisplayCandidate(displayId = 0, kind = DisplayKind.BUILT_IN, isOn = true, widthPx = 1920, heightPx = 1080)
    private val display2 =
        DisplayCandidate(displayId = 2, kind = DisplayKind.BUILT_IN, isOn = true, widthPx = 1240, heightPx = 1080)

    private fun dualScreenInput(
        displays: List<DisplayCandidate> = listOf(display0, display2),
        sourceDisplayId: Int = 0,
        hasOverlayPermission: Boolean = true,
        systemImePin: Boolean? = false,
        isPasswordField: Boolean = false,
        perAppInlineOverride: Boolean = false,
        swapToSameScreen: Boolean = false,
        overlayHiddenByHost: Boolean = false,
        apiLevel: Int = 35,
        heightFraction: Float = 0.75f
    ): DisplayPlacementInput = DisplayPlacementInput(
        displays = displays,
        sourceDisplayId = sourceDisplayId,
        hasOverlayPermission = hasOverlayPermission,
        systemImePin = systemImePin,
        isPasswordField = isPasswordField,
        perAppInlineOverride = perAppInlineOverride,
        swapToSameScreen = swapToSameScreen,
        overlayHiddenByHost = overlayHiddenByHost,
        apiLevel = apiLevel,
        heightFraction = heightFraction
    )

    @Test
    fun case01_editorOnDisplay0PlacesOverlayOnDisplay2() {
        val decision = DisplayPlacementPolicy.resolve(dualScreenInput(sourceDisplayId = 0))
        assertEquals(PlacementDecision.Overlay(targetDisplayId = 2, heightFraction = 0.75f), decision)
    }

    @Test
    fun case02_editorOnDisplay2PlacesOverlayOnDisplay0() {
        val decision = DisplayPlacementPolicy.resolve(dualScreenInput(sourceDisplayId = 2))
        assertEquals(PlacementDecision.Overlay(targetDisplayId = 0, heightFraction = 0.75f), decision)
    }

    @Test
    fun case03_nonZeroArbitraryBuiltInIdsAreResolvedWithoutHardcoding() {
        val d5 = DisplayCandidate(displayId = 5, kind = DisplayKind.BUILT_IN, isOn = true)
        val d9 = DisplayCandidate(displayId = 9, kind = DisplayKind.BUILT_IN, isOn = true)
        val decision = DisplayPlacementPolicy.resolve(dualScreenInput(displays = listOf(d5, d9), sourceDisplayId = 5))
        assertEquals(PlacementDecision.Overlay(targetDisplayId = 9, heightFraction = 0.75f), decision)
    }

    @Test
    fun case04_missingOverlayPermissionFallsBackToInline() {
        val decision = DisplayPlacementPolicy.resolve(dualScreenInput(hasOverlayPermission = false))
        assertEquals(PlacementDecision.Inline(reason = PlacementReason.NO_OVERLAY_PERMISSION), decision)
    }

    @Test
    fun case05_oppositeDisplayOffFallsBackToInline() {
        val offPeer = display2.copy(isOn = false)
        val decision = DisplayPlacementPolicy.resolve(dualScreenInput(displays = listOf(display0, offPeer)))
        assertEquals(PlacementDecision.Inline(reason = PlacementReason.NO_ELIGIBLE_OPPOSITE_DISPLAY), decision)
    }

    @Test
    fun case06_singleBuiltInDisplayFallsBackToInline() {
        val decision = DisplayPlacementPolicy.resolve(dualScreenInput(displays = listOf(display0)))
        assertEquals(PlacementDecision.Inline(reason = PlacementReason.NO_ELIGIBLE_OPPOSITE_DISPLAY), decision)
    }

    @Test
    fun case07_virtualDisplayIsExcluded() {
        val virtualPeer = DisplayCandidate(displayId = 7, kind = DisplayKind.VIRTUAL, isOn = true)
        val decision = DisplayPlacementPolicy.resolve(dualScreenInput(displays = listOf(display0, virtualPeer)))
        assertEquals(PlacementDecision.Inline(reason = PlacementReason.NO_ELIGIBLE_OPPOSITE_DISPLAY), decision)
    }

    @Test
    fun case08_externalCastDisplayIsExcluded() {
        val castPeer = DisplayCandidate(displayId = 3, kind = DisplayKind.EXTERNAL, isOn = true)
        val decision = DisplayPlacementPolicy.resolve(dualScreenInput(displays = listOf(display0, castPeer)))
        assertEquals(PlacementDecision.Inline(reason = PlacementReason.NO_ELIGIBLE_OPPOSITE_DISPLAY), decision)
    }

    @Test
    fun case09_presentationOnlyDisplayIsExcluded() {
        val presPeer = DisplayCandidate(displayId = 4, kind = DisplayKind.PRESENTATION_ONLY, isOn = true)
        val decision = DisplayPlacementPolicy.resolve(dualScreenInput(displays = listOf(display0, presPeer)))
        assertEquals(PlacementDecision.Inline(reason = PlacementReason.NO_ELIGIBLE_OPPOSITE_DISPLAY), decision)
    }

    @Test
    fun case10_nonBuiltInDisplaysAreSkippedInFavorOfActiveBuiltInPeer() {
        val virtualPeer = DisplayCandidate(displayId = 7, kind = DisplayKind.VIRTUAL, isOn = true)
        val decision = DisplayPlacementPolicy.resolve(
            dualScreenInput(displays = listOf(display0, virtualPeer, display2))
        )
        assertEquals(PlacementDecision.Overlay(targetDisplayId = 2, heightFraction = 0.75f), decision)
    }

    @Test
    fun case11_systemImePinTrueForcesInlineWithWarningBanner() {
        val decision = DisplayPlacementPolicy.resolve(dualScreenInput(systemImePin = true))
        assertEquals(
            PlacementDecision.Inline(reason = PlacementReason.SYSTEM_IME_PIN_ACTIVE, warnSystemImePin = true),
            decision
        )
        assertTrue(decision.warnSystemImePin)
    }

    @Test
    fun case12_systemImePinNullAssumesOffAndAllowsOverlayWithoutWarning() {
        val decision = DisplayPlacementPolicy.resolve(dualScreenInput(systemImePin = null))
        assertEquals(PlacementDecision.Overlay(targetDisplayId = 2, heightFraction = 0.75f), decision)
        assertFalse(decision.warnSystemImePin)
    }

    @Test
    fun case13_passwordFieldForcesInline() {
        val decision = DisplayPlacementPolicy.resolve(dualScreenInput(isPasswordField = true))
        assertEquals(PlacementDecision.Inline(reason = PlacementReason.PASSWORD_FIELD), decision)
    }

    @Test
    fun case14_overlayHiddenByHostForcesInline() {
        val decision = DisplayPlacementPolicy.resolve(dualScreenInput(overlayHiddenByHost = true))
        assertEquals(PlacementDecision.Inline(reason = PlacementReason.OVERLAY_HIDDEN_BY_HOST), decision)
    }

    @Test
    fun case15_swapToSameScreenToggleForcesInline() {
        val decision = DisplayPlacementPolicy.resolve(dualScreenInput(swapToSameScreen = true))
        assertEquals(PlacementDecision.Inline(reason = PlacementReason.USER_SWAPPED_TO_SAME_SCREEN), decision)
    }

    @Test
    fun case16_perAppInlineOverrideForcesInline() {
        val decision = DisplayPlacementPolicy.resolve(dualScreenInput(perAppInlineOverride = true))
        assertEquals(PlacementDecision.Inline(reason = PlacementReason.PER_APP_INLINE_OVERRIDE), decision)
    }

    @Test
    fun case17_apiBelow30GuardForcesInline() {
        val decision = DisplayPlacementPolicy.resolve(dualScreenInput(apiLevel = 29))
        assertEquals(PlacementDecision.Inline(reason = PlacementReason.API_BELOW_30), decision)
    }

    @Test
    fun case18_api30ExactlyAllowsOverlay() {
        val decision = DisplayPlacementPolicy.resolve(dualScreenInput(apiLevel = 30))
        assertEquals(PlacementDecision.Overlay(targetDisplayId = 2, heightFraction = 0.75f), decision)
    }

    @Test
    fun case19_heightFractionBelowMinimumClampsTo60Percent() {
        val decision = DisplayPlacementPolicy.resolve(dualScreenInput(heightFraction = 0.25f))
        assertEquals(PlacementDecision.Overlay(targetDisplayId = 2, heightFraction = 0.60f), decision)
    }

    @Test
    fun case20_heightFractionAboveMaximumClampsTo90Percent() {
        val decision = DisplayPlacementPolicy.resolve(dualScreenInput(heightFraction = 1.15f))
        assertEquals(PlacementDecision.Overlay(targetDisplayId = 2, heightFraction = 0.90f), decision)
    }

    @Test
    fun case21_nonFiniteHeightFractionUsesDefault() {
        val nanDecision = DisplayPlacementPolicy.resolve(dualScreenInput(heightFraction = Float.NaN))
        val infDecision = DisplayPlacementPolicy.resolve(dualScreenInput(heightFraction = Float.POSITIVE_INFINITY))
        assertEquals(PlacementDecision.Overlay(targetDisplayId = 2, heightFraction = 0.75f), nanDecision)
        assertEquals(PlacementDecision.Overlay(targetDisplayId = 2, heightFraction = 0.75f), infDecision)
    }
}

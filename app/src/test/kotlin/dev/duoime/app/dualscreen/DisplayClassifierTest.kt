// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.dualscreen

import dev.duoime.core.policy.DisplayKind
import dev.duoime.core.policy.DisplayPlacementInput
import dev.duoime.core.policy.DisplayPlacementPolicy
import dev.duoime.core.policy.PlacementDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayClassifierTest {

    private fun retroidDisplay0() = RawDisplayInfo(
        displayId = 0,
        flags = DisplayClassifier.FLAG_TRUSTED or DisplayClassifier.FLAG_ALLOWED_TO_BE_DEFAULT,
        state = DisplayClassifier.STATE_ON,
        reflectedType = DisplayClassifier.TYPE_INTERNAL,
        description = "Display id 0: type INTERNAL, state ON",
        widthPx = 1920,
        heightPx = 1080
    )

    private fun retroidDisplay2() = RawDisplayInfo(
        displayId = 2,
        flags = DisplayClassifier.FLAG_TRUSTED or
            DisplayClassifier.FLAG_ALLOWED_TO_BE_DEFAULT or
            DisplayClassifier.FLAG_PRESENTATION,
        state = DisplayClassifier.STATE_ON,
        reflectedType = DisplayClassifier.TYPE_INTERNAL,
        description = "Display id 2: type INTERNAL, FLAG_PRESENTATION, FLAG_TRUSTED",
        widthPx = 1280,
        heightPx = 960
    )

    @Test
    fun retroidDisplay2WithPresentationFlagClassifiesAsBuiltInAndRoutesBothDirections() {
        val raw = listOf(retroidDisplay0(), retroidDisplay2())
        val candidates = DisplayClassifier.classifyDisplays(raw)

        assertEquals(DisplayKind.BUILT_IN, candidates[0].kind)
        assertEquals(DisplayKind.BUILT_IN, candidates[1].kind)
        assertTrue(candidates[0].isOn)
        assertTrue(candidates[1].isOn)

        // Editor on Display 0 -> Overlay on Display 2
        val toSecondary = DisplayPlacementPolicy.resolve(
            DisplayPlacementInput(
                displays = candidates,
                sourceDisplayId = 0,
                hasOverlayPermission = true,
                systemImePin = false,
                apiLevel = 34
            )
        )
        assertTrue(toSecondary is PlacementDecision.Overlay)
        assertEquals(2, (toSecondary as PlacementDecision.Overlay).targetDisplayId)

        // Editor on Display 2 -> Overlay on Display 0
        val toPrimary = DisplayPlacementPolicy.resolve(
            DisplayPlacementInput(
                displays = candidates,
                sourceDisplayId = 2,
                hasOverlayPermission = true,
                systemImePin = false,
                apiLevel = 34
            )
        )
        assertTrue(toPrimary is PlacementDecision.Overlay)
        assertEquals(0, (toPrimary as PlacementDecision.Overlay).targetDisplayId)
    }

    @Test
    fun excludesPrivateVirtualWifiOverlayExternalAndUnknownStates() {
        val privateDisplay = RawDisplayInfo(
            displayId = 3,
            flags = DisplayClassifier.FLAG_PRIVATE,
            state = DisplayClassifier.STATE_ON,
            reflectedType = DisplayClassifier.TYPE_INTERNAL
        )
        val virtualViaReflection = RawDisplayInfo(
            displayId = 4,
            flags = 0,
            state = DisplayClassifier.STATE_ON,
            reflectedType = DisplayClassifier.TYPE_VIRTUAL
        )
        val wifiViaDescription = RawDisplayInfo(
            displayId = 5,
            flags = 0,
            state = DisplayClassifier.STATE_ON,
            reflectedType = null,
            description = "Display id 5: type WIFI, state ON"
        )
        val overlayViaDescription = RawDisplayInfo(
            displayId = 6,
            flags = 0,
            state = DisplayClassifier.STATE_ON,
            reflectedType = null,
            description = "Display id 6: type=OVERLAY"
        )
        val externalViaReflection = RawDisplayInfo(
            displayId = 7,
            flags = DisplayClassifier.FLAG_PRESENTATION,
            state = DisplayClassifier.STATE_ON,
            reflectedType = DisplayClassifier.TYPE_EXTERNAL
        )
        val unknownStateBuiltIn = retroidDisplay2().copy(state = DisplayClassifier.STATE_UNKNOWN)
        val onSuspendBuiltIn = retroidDisplay2().copy(state = DisplayClassifier.STATE_ON_SUSPEND)

        assertEquals(DisplayKind.VIRTUAL, DisplayClassifier.classifyKind(privateDisplay))
        assertEquals(DisplayKind.VIRTUAL, DisplayClassifier.classifyKind(virtualViaReflection))
        assertEquals(DisplayKind.VIRTUAL, DisplayClassifier.classifyKind(wifiViaDescription))
        assertEquals(DisplayKind.VIRTUAL, DisplayClassifier.classifyKind(overlayViaDescription))
        assertEquals(DisplayKind.EXTERNAL, DisplayClassifier.classifyKind(externalViaReflection))

        assertFalse(DisplayClassifier.isDisplayActive(unknownStateBuiltIn.state))
        assertFalse(DisplayClassifier.isDisplayActive(DisplayClassifier.STATE_OFF))
        assertFalse(DisplayClassifier.isDisplayActive(DisplayClassifier.STATE_DOZE))
        assertTrue(DisplayClassifier.isDisplayActive(onSuspendBuiltIn.state))
    }

    @Test
    fun calibratedPairedPeerPrioritizesBuiltInPeerAndRejectsLaterCastingDisplays() {
        val newlyConnectedCast = RawDisplayInfo(
            displayId = 9,
            flags = DisplayClassifier.FLAG_PRESENTATION,
            state = DisplayClassifier.STATE_ON,
            reflectedType = null,
            description = "Display id 9: wireless display"
        )
        val raw = listOf(retroidDisplay0(), newlyConnectedCast, retroidDisplay2())

        // Without calibration, findCalibratablePeerDisplay finds an active non-default built-in peer
        val candidatesCalibrated = DisplayClassifier.classifyDisplays(raw, pairedPeerDisplayId = 2)

        // Paired Display #2 must be prioritized first and remain BUILT_IN; Display #9 demoted to EXTERNAL
        assertEquals(2, candidatesCalibrated.first().displayId)
        assertEquals(DisplayKind.BUILT_IN, candidatesCalibrated.first().kind)
        val castCandidate = candidatesCalibrated.first { it.displayId == 9 }
        assertEquals(DisplayKind.EXTERNAL, castCandidate.kind)

        // Even if Display #2 turns OFF, Display #9 (added after calibration) must NOT hijack the overlay
        val rawPeerOff = listOf(
            retroidDisplay0(),
            newlyConnectedCast,
            retroidDisplay2().copy(state = DisplayClassifier.STATE_OFF)
        )
        val decision = DisplayPlacementPolicy.resolve(
            DisplayPlacementInput(
                displays = DisplayClassifier.classifyDisplays(rawPeerOff, pairedPeerDisplayId = 2),
                sourceDisplayId = 0,
                hasOverlayPermission = true,
                systemImePin = false,
                apiLevel = 34
            )
        )
        assertTrue("Post-calibration dynamic display must not hijack overlay", decision is PlacementDecision.Inline)
    }

    @Test
    fun displayChangeFilterIgnoresUnrelatedBrightnessTicksAndCatchesRelevantChanges() {
        // Unrelated display #9 brightness/refresh tick with no on/off flip -> ignore
        assertFalse(
            DisplayClassifier.shouldReevaluateOnDisplayChange(
                changedDisplayId = 9,
                sourceDisplayId = 0,
                attachedOverlayDisplayId = 2,
                pairedPeerDisplayId = 2,
                activeStateChanged = false
            )
        )
        // Relevant source or attached overlay display -> re-evaluate
        assertTrue(
            DisplayClassifier.shouldReevaluateOnDisplayChange(
                changedDisplayId = 2,
                sourceDisplayId = 0,
                attachedOverlayDisplayId = 2,
                pairedPeerDisplayId = 2,
                activeStateChanged = false
            )
        )
        // Any display flipping active on/off state -> re-evaluate
        assertTrue(
            DisplayClassifier.shouldReevaluateOnDisplayChange(
                changedDisplayId = 9,
                sourceDisplayId = 0,
                attachedOverlayDisplayId = -1,
                pairedPeerDisplayId = null,
                activeStateChanged = true
            )
        )
    }
}

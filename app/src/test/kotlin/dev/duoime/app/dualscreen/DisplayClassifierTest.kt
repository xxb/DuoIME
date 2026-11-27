// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.dualscreen

import dev.duoime.core.policy.DisplayKind
import dev.duoime.core.policy.DisplayPlacementInput
import dev.duoime.core.policy.DisplayPlacementPolicy
import dev.duoime.core.policy.PlacementDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val unknownDefault = RawDisplayInfo(
            displayId = DisplayClassifier.DEFAULT_DISPLAY_ID,
            flags = 0,
            state = DisplayClassifier.STATE_ON,
            reflectedType = null,
            description = ""
        )
        val unknownSecondary = RawDisplayInfo(
            displayId = 8,
            flags = DisplayClassifier.FLAG_PRESENTATION,
            state = DisplayClassifier.STATE_ON,
            reflectedType = null,
            description = "Display id 8: wireless display"
        )
        val unknownStateBuiltIn = retroidDisplay2().copy(state = DisplayClassifier.STATE_UNKNOWN)
        val onSuspendBuiltIn = retroidDisplay2().copy(state = DisplayClassifier.STATE_ON_SUSPEND)

        assertEquals(DisplayKind.VIRTUAL, DisplayClassifier.classifyKind(privateDisplay))
        assertEquals(DisplayKind.VIRTUAL, DisplayClassifier.classifyKind(virtualViaReflection))
        assertEquals(DisplayKind.VIRTUAL, DisplayClassifier.classifyKind(wifiViaDescription))
        assertEquals(DisplayKind.VIRTUAL, DisplayClassifier.classifyKind(overlayViaDescription))
        assertEquals(DisplayKind.EXTERNAL, DisplayClassifier.classifyKind(externalViaReflection))
        assertEquals(DisplayKind.BUILT_IN, DisplayClassifier.classifyKind(unknownDefault))
        assertEquals(DisplayKind.PRESENTATION_ONLY, DisplayClassifier.classifyKind(unknownSecondary))

        assertFalse(DisplayClassifier.isDisplayActive(unknownStateBuiltIn.state))
        assertFalse(DisplayClassifier.isDisplayActive(DisplayClassifier.STATE_OFF))
        assertFalse(DisplayClassifier.isDisplayActive(DisplayClassifier.STATE_DOZE))
        assertTrue(DisplayClassifier.isDisplayActive(onSuspendBuiltIn.state))
    }

    @Test
    fun calibratedPairedPeerPrioritizesBuiltInPeerAndRejectsLaterCastingDisplays() {
        val newlyConnectedUnknown = RawDisplayInfo(
            displayId = 9,
            flags = DisplayClassifier.FLAG_PRESENTATION,
            state = DisplayClassifier.STATE_ON,
            reflectedType = null,
            description = "Display id 9: wireless display"
        )
        val thirdInternalDisplay = RawDisplayInfo(
            displayId = 10,
            flags = DisplayClassifier.FLAG_TRUSTED,
            state = DisplayClassifier.STATE_ON,
            reflectedType = DisplayClassifier.TYPE_INTERNAL,
            description = "Display id 10: type INTERNAL"
        )
        val raw = listOf(retroidDisplay0(), newlyConnectedUnknown, thirdInternalDisplay, retroidDisplay2())

        val candidatesCalibrated = DisplayClassifier.classifyDisplays(raw, pairedPeerDisplayId = 2)

        // Paired Display #2 must be prioritized first and remain BUILT_IN; unknown #9 stays PRESENTATION_ONLY
        // and uncalibrated internal #10 is demoted to EXTERNAL once calibrated.
        assertEquals(2, candidatesCalibrated.first().displayId)
        assertEquals(DisplayKind.BUILT_IN, candidatesCalibrated.first().kind)
        assertEquals(DisplayKind.PRESENTATION_ONLY, candidatesCalibrated.first { it.displayId == 9 }.kind)
        assertEquals(DisplayKind.EXTERNAL, candidatesCalibrated.first { it.displayId == 10 }.kind)

        // Even if Display #2 turns OFF, Display #9/#10 (not paired) must NOT hijack the overlay
        val rawPeerOff = listOf(
            retroidDisplay0(),
            newlyConnectedUnknown,
            thirdInternalDisplay,
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
    fun pairedUnknownPeerIsExemptedWhileUnpairedUnknownAndExplicitNonBuiltInRemainRejected() {
        val unknownPairedPeer = RawDisplayInfo(
            displayId = 2,
            flags = DisplayClassifier.FLAG_PRESENTATION,
            state = DisplayClassifier.STATE_ON,
            reflectedType = null,
            description = "Display id 2: built-in secondary panel"
        )
        val unknownUnpaired = RawDisplayInfo(
            displayId = 9,
            flags = DisplayClassifier.FLAG_PRESENTATION,
            state = DisplayClassifier.STATE_ON,
            reflectedType = null,
            description = "Display id 9: wireless display"
        )
        val displays = listOf(retroidDisplay0(), unknownPairedPeer, unknownUnpaired)

        // (a) Paired unknown non-default display -> BUILT_IN, eligible for Overlay, returned by findCalibratablePeerDisplay
        assertEquals(DisplayKind.BUILT_IN, DisplayClassifier.classifyKind(unknownPairedPeer, pairedPeerDisplayId = 2))
        val pairedCandidates = DisplayClassifier.classifyDisplays(displays, pairedPeerDisplayId = 2)
        val overlayDecision = DisplayPlacementPolicy.resolve(
            DisplayPlacementInput(
                displays = pairedCandidates,
                sourceDisplayId = 0,
                hasOverlayPermission = true,
                systemImePin = false,
                apiLevel = 34
            )
        )
        assertTrue(overlayDecision is PlacementDecision.Overlay)
        assertEquals(2, (overlayDecision as PlacementDecision.Overlay).targetDisplayId)
        assertEquals(2, DisplayClassifier.findCalibratablePeerDisplay(displays, pairedPeerDisplayId = 2)?.displayId)

        // (b) Unpaired unknown secondary displays fail closed to PRESENTATION_ONLY and are not calibratable
        assertEquals(
            DisplayKind.PRESENTATION_ONLY,
            DisplayClassifier.classifyKind(unknownPairedPeer, pairedPeerDisplayId = null)
        )
        assertEquals(
            DisplayKind.PRESENTATION_ONLY,
            DisplayClassifier.classifyKind(unknownUnpaired, pairedPeerDisplayId = 2)
        )
        assertNull(DisplayClassifier.findCalibratablePeerDisplay(displays))

        // (c) Paired ID with explicit EXTERNAL / VIRTUAL / FLAG_PRIVATE is still rejected
        val pairedExternal = unknownPairedPeer.copy(reflectedType = DisplayClassifier.TYPE_EXTERNAL)
        val pairedVirtual = unknownPairedPeer.copy(reflectedType = DisplayClassifier.TYPE_VIRTUAL)
        val pairedPrivate = unknownPairedPeer.copy(flags = DisplayClassifier.FLAG_PRIVATE)
        assertEquals(DisplayKind.EXTERNAL, DisplayClassifier.classifyKind(pairedExternal, pairedPeerDisplayId = 2))
        assertEquals(DisplayKind.VIRTUAL, DisplayClassifier.classifyKind(pairedVirtual, pairedPeerDisplayId = 2))
        assertEquals(DisplayKind.VIRTUAL, DisplayClassifier.classifyKind(pairedPrivate, pairedPeerDisplayId = 2))
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

    @Test
    fun hasEligiblePeerDisplayRequiresAtLeastTwoActiveNonVirtualDisplays() {
        // Single active display -> false
        assertFalse(DisplayClassifier.hasEligiblePeerDisplay(listOf(retroidDisplay0())))

        // Two active built-in displays -> true
        assertTrue(DisplayClassifier.hasEligiblePeerDisplay(listOf(retroidDisplay0(), retroidDisplay2())))

        // Second display OFF -> false
        assertFalse(
            DisplayClassifier.hasEligiblePeerDisplay(
                listOf(retroidDisplay0(), retroidDisplay2().copy(state = DisplayClassifier.STATE_OFF))
            )
        )

        // Default + virtual display only -> false
        val virtual = RawDisplayInfo(
            displayId = 4,
            flags = 0,
            state = DisplayClassifier.STATE_ON,
            reflectedType = DisplayClassifier.TYPE_VIRTUAL
        )
        assertFalse(DisplayClassifier.hasEligiblePeerDisplay(listOf(retroidDisplay0(), virtual)))
    }
}

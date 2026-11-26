/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME
 */
package dev.duoime.ui.feedback

import android.os.Build
import android.os.VibrationEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticEffectPlannerTest {

    @Test
    fun `motor tier selection rejects zero-duration HAL primitives like Pocket Duo`() {
        // Real Pocket Duo behavior: API 31+ reports primitives supported, but getPrimitiveDurations returns 0ms
        val pocketDuoProbe = HapticCapabilityProbe(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            supportsTickAndClickPrimitives = true,
            supportsLowTickPrimitive = true,
            minPrimitiveDurationMs = 0,
            hasAmplitudeControl = false
        )
        assertEquals(HapticMotorTier.DURATION_ONESHOT, HapticEffectPlanner.resolveMotorTier(pocketDuoProbe))

        // API 31+ reporting 0ms primitives with amplitude control falls back to AMPLITUDE_ONESHOT
        val brokenPrimWithAmp = pocketDuoProbe.copy(hasAmplitudeControl = true)
        assertEquals(HapticMotorTier.AMPLITUDE_ONESHOT, HapticEffectPlanner.resolveMotorTier(brokenPrimWithAmp))

        // Genuine API 31+ hardware with positive primitive durations selects PRIMITIVES_API31
        val genuinePrimitiveMotor = pocketDuoProbe.copy(minPrimitiveDurationMs = 12)
        assertEquals(HapticMotorTier.PRIMITIVES_API31, HapticEffectPlanner.resolveMotorTier(genuinePrimitiveMotor))

        // Pre-API 31 (API 30) skips primitives safely (no getPrimitiveDurations API) -> amplitude or duration
        val api30Amp = HapticCapabilityProbe(
            sdkInt = Build.VERSION_CODES.R,
            supportsTickAndClickPrimitives = true,
            supportsLowTickPrimitive = true,
            minPrimitiveDurationMs = 10,
            hasAmplitudeControl = true
        )
        assertEquals(HapticMotorTier.AMPLITUDE_ONESHOT, HapticEffectPlanner.resolveMotorTier(api30Amp))
    }

    @Test
    fun `all 3 motor tiers increase monotonically from level 1 night whisper through level 5 strong`() {
        for (profile in KeyFeedbackProfile.entries) {
            var prevDurOnly = 0L
            var prevAmpMs = 0L
            var prevAmp = 0
            for (level in HapticEffectPlanner.MIN_ACTIVE_STRENGTH..HapticEffectPlanner.MAX_STRENGTH) {
                val durOnly = HapticEffectPlanner.resolveDurationOneShotMs(profile, level)
                assertTrue("Duration-only must stay strictly positive at level $level", durOnly > 0L)
                assertTrue("Duration-only must monotonically increase for $profile", durOnly > prevDurOnly)
                prevDurOnly = durOnly

                val ampSpec = HapticEffectPlanner.resolveAmplitudeOneShotSpec(profile, level)
                assertTrue(ampSpec.durationMs > prevAmpMs)
                assertTrue(ampSpec.amplitude >= prevAmp && ampSpec.amplitude in 1..255)
                prevAmpMs = ampSpec.durationMs
                prevAmp = ampSpec.amplitude
            }
        }

        // Level 1 night whisper on duration-only stays ultra-short (<= 5ms vs legacy 27ms fallback)
        assertEquals(4L, HapticEffectPlanner.resolveDurationOneShotMs(KeyFeedbackProfile.ALPHA, 1))
        assertEquals(4L, HapticEffectPlanner.resolveDurationOneShotMs(KeyFeedbackProfile.MODIFIER, 1))
        assertEquals(5L, HapticEffectPlanner.resolveDurationOneShotMs(KeyFeedbackProfile.STABILIZED, 1))

        // Primitives tier selects LOW_TICK at level 1 when supported, falling back cleanly to low-scale TICK
        val lowTickL1 = HapticEffectPlanner.resolvePrimitiveSpec(
            profile = KeyFeedbackProfile.ALPHA,
            strength = 1,
            supportsLowTick = true
        )
        assertEquals(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, lowTickL1.primitiveId)
        assertTrue(lowTickL1.scale in 0.20f..0.35f)

        val fallbackTickL1 = HapticEffectPlanner.resolvePrimitiveSpec(
            profile = KeyFeedbackProfile.ALPHA,
            strength = 1,
            supportsLowTick = false
        )
        assertEquals(VibrationEffect.Composition.PRIMITIVE_TICK, fallbackTickL1.primitiveId)
        assertTrue(fallbackTickL1.scale <= 0.25f)
    }
}

/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME
 */
package dev.duoime.ui.feedback

import android.annotation.SuppressLint
import android.os.Build
import android.os.VibrationEffect

internal enum class HapticMotorTier {
    PRIMITIVES_API31,
    AMPLITUDE_ONESHOT,
    DURATION_ONESHOT
}

internal data class HapticCapabilityProbe(
    val sdkInt: Int,
    val supportsTickAndClickPrimitives: Boolean,
    val supportsLowTickPrimitive: Boolean,
    val minPrimitiveDurationMs: Int,
    val hasAmplitudeControl: Boolean
)

internal data class HapticPrimitiveSpec(
    val primitiveId: Int,
    val scale: Float
)

internal data class HapticOneShotSpec(
    val durationMs: Long,
    val amplitude: Int
)

private data class ProfileTriplet<T>(
    val modifier: T,
    val alpha: T,
    val stabilized: T
) {
    fun forProfile(profile: KeyFeedbackProfile): T = when (profile) {
        KeyFeedbackProfile.MODIFIER -> modifier
        KeyFeedbackProfile.ALPHA -> alpha
        KeyFeedbackProfile.STABILIZED -> stabilized
    }
}

/**
 * Pure capability-tiered planner for 0..5 key haptic feedback, isolating platform/OEM quirks
 * (such as motors that advertise 0ms primitive support or lack amplitude control and degrade
 * system predefined effects to loud 27ms pulses) from Binder/thread dispatch.
 */
internal object HapticEffectPlanner {
    const val MIN_ACTIVE_STRENGTH = 1
    const val MAX_STRENGTH = 5
    const val DEFAULT_STRENGTH = 3

    private val DURATION_ONLY_TABLE: List<ProfileTriplet<Long>> = listOf(
        ProfileTriplet(modifier = 4L, alpha = 4L, stabilized = 5L),
        ProfileTriplet(modifier = 7L, alpha = 8L, stabilized = 10L),
        ProfileTriplet(modifier = 12L, alpha = 13L, stabilized = 15L),
        ProfileTriplet(modifier = 17L, alpha = 19L, stabilized = 21L),
        ProfileTriplet(modifier = 24L, alpha = 26L, stabilized = 28L)
    )

    private val AMPLITUDE_TABLE: List<ProfileTriplet<HapticOneShotSpec>> = listOf(
        ProfileTriplet(
            modifier = HapticOneShotSpec(durationMs = 5L, amplitude = 26),
            alpha = HapticOneShotSpec(durationMs = 6L, amplitude = 30),
            stabilized = HapticOneShotSpec(durationMs = 7L, amplitude = 36)
        ),
        ProfileTriplet(
            modifier = HapticOneShotSpec(durationMs = 7L, amplitude = 50),
            alpha = HapticOneShotSpec(durationMs = 8L, amplitude = 60),
            stabilized = HapticOneShotSpec(durationMs = 10L, amplitude = 72)
        ),
        ProfileTriplet(
            modifier = HapticOneShotSpec(durationMs = 10L, amplitude = 95),
            alpha = HapticOneShotSpec(durationMs = 12L, amplitude = 110),
            stabilized = HapticOneShotSpec(durationMs = 14L, amplitude = 130)
        ),
        ProfileTriplet(
            modifier = HapticOneShotSpec(durationMs = 13L, amplitude = 145),
            alpha = HapticOneShotSpec(durationMs = 15L, amplitude = 170),
            stabilized = HapticOneShotSpec(durationMs = 18L, amplitude = 195)
        ),
        ProfileTriplet(
            modifier = HapticOneShotSpec(durationMs = 17L, amplitude = 220),
            alpha = HapticOneShotSpec(durationMs = 20L, amplitude = 255),
            stabilized = HapticOneShotSpec(durationMs = 24L, amplitude = 255)
        )
    )

    @SuppressLint("InlinedApi")
    private val PRIMITIVE_TABLE_WITH_LOW_TICK: List<ProfileTriplet<HapticPrimitiveSpec>> = listOf(
        ProfileTriplet(
            modifier = HapticPrimitiveSpec(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.24f),
            alpha = HapticPrimitiveSpec(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.28f),
            stabilized = HapticPrimitiveSpec(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.34f)
        ),
        ProfileTriplet(
            modifier = HapticPrimitiveSpec(VibrationEffect.Composition.PRIMITIVE_TICK, 0.40f),
            alpha = HapticPrimitiveSpec(VibrationEffect.Composition.PRIMITIVE_TICK, 0.48f),
            stabilized = HapticPrimitiveSpec(VibrationEffect.Composition.PRIMITIVE_TICK, 0.56f)
        ),
        ProfileTriplet(
            modifier = HapticPrimitiveSpec(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.50f),
            alpha = HapticPrimitiveSpec(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.58f),
            stabilized = HapticPrimitiveSpec(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.68f)
        ),
        ProfileTriplet(
            modifier = HapticPrimitiveSpec(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.72f),
            alpha = HapticPrimitiveSpec(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.82f),
            stabilized = HapticPrimitiveSpec(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.92f)
        ),
        ProfileTriplet(
            modifier = HapticPrimitiveSpec(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.88f),
            alpha = HapticPrimitiveSpec(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.00f),
            stabilized = HapticPrimitiveSpec(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.00f)
        )
    )

    @SuppressLint("InlinedApi")
    private val PRIMITIVE_LEVEL1_TICK_FALLBACK = ProfileTriplet(
        modifier = HapticPrimitiveSpec(VibrationEffect.Composition.PRIMITIVE_TICK, 0.18f),
        alpha = HapticPrimitiveSpec(VibrationEffect.Composition.PRIMITIVE_TICK, 0.20f),
        stabilized = HapticPrimitiveSpec(VibrationEffect.Composition.PRIMITIVE_TICK, 0.25f)
    )

    fun clampActiveStrength(strength: Int): Int = strength.coerceIn(MIN_ACTIVE_STRENGTH, MAX_STRENGTH)

    fun resolveMotorTier(probe: HapticCapabilityProbe): HapticMotorTier = when {
        probe.sdkInt >= Build.VERSION_CODES.S &&
            probe.supportsTickAndClickPrimitives &&
            probe.minPrimitiveDurationMs > 0 -> HapticMotorTier.PRIMITIVES_API31
        probe.hasAmplitudeControl -> HapticMotorTier.AMPLITUDE_ONESHOT
        else -> HapticMotorTier.DURATION_ONESHOT
    }

    @SuppressLint("InlinedApi")
    fun resolvePrimitiveSpec(
        profile: KeyFeedbackProfile,
        strength: Int,
        supportsLowTick: Boolean
    ): HapticPrimitiveSpec {
        val levelIdx = clampActiveStrength(strength) - 1
        val triplet = if (levelIdx == 0 && !supportsLowTick) {
            PRIMITIVE_LEVEL1_TICK_FALLBACK
        } else {
            PRIMITIVE_TABLE_WITH_LOW_TICK[levelIdx]
        }
        return triplet.forProfile(profile)
    }

    fun resolveAmplitudeOneShotSpec(profile: KeyFeedbackProfile, strength: Int): HapticOneShotSpec =
        AMPLITUDE_TABLE[clampActiveStrength(strength) - 1].forProfile(profile)

    fun resolveDurationOneShotMs(profile: KeyFeedbackProfile, strength: Int): Long =
        DURATION_ONLY_TABLE[clampActiveStrength(strength) - 1].forProfile(profile)
}

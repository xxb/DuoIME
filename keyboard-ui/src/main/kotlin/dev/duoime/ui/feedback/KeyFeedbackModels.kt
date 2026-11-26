/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME
 */
package dev.duoime.ui.feedback

import androidx.compose.runtime.Immutable

/**
 * Tactile/haptic weight class for a keyboard strike.
 */
enum class KeyFeedbackProfile {
    ALPHA,
    STABILIZED,
    MODIFIER
}

/**
 * Acoustic voice slots separating tactile keycap impacts, multi-root melody registers,
 * chord confirmations, and drum kit voices.
 */
enum class KeySoundSlot {
    ALPHA,
    STABILIZED,
    ENTER_HEAVY,
    MODIFIER,
    NOTE_C3,
    NOTE_G3,
    NOTE_D4,
    NOTE_A4,
    NOTE_E5,
    ENTER_CHORD,
    MODIFIER_LIGHT,
    DRUM_RIMSHOT
}

/**
 * User-selectable synthesized keycap and musical instrument styles.
 *
 * Persisted by enum [name] in host preferences without requiring `kotlinx.serialization`
 * inside `:keyboard-ui`.
 */
enum class KeySoundStyle(
    val displayLabel: String,
    val isMusical: Boolean = false,
    val isMelodyInstrument: Boolean = false
) {
    MECHANICAL("Mechanical"),
    ARCADE_COMBO("Arcade Combo"),
    RETRO_8BIT("8-Bit Retro"),
    SCIFI_SYNTH("Sci-Fi Synth"),
    GRAND_PIANO("Grand Piano", isMusical = true, isMelodyInstrument = true),
    GUZHENG("Guzheng", isMusical = true, isMelodyInstrument = true),
    KALIMBA_MUSIC_BOX("Kalimba / Music Box", isMusical = true, isMelodyInstrument = true),
    MARIMBA_HANDPAN("Marimba / Handpan", isMusical = true, isMelodyInstrument = true),
    JAZZ_RHODES("Jazz Rhodes", isMusical = true, isMelodyInstrument = true),
    HARP_NYLON("Harp / Nylon Guitar", isMusical = true, isMelodyInstrument = true),
    BRONZE_CHIME_JADE("Bronze Chime / Jade", isMusical = true, isMelodyInstrument = true),
    ACOUSTIC_DRUM_KIT("Acoustic Drum Kit", isMusical = true, isMelodyInstrument = false),
    BAND_ENSEMBLE("Band Ensemble", isMusical = true, isMelodyInstrument = false);

    val isBandMode: Boolean
        get() = this == BAND_ENSEMBLE

    val isTactile: Boolean
        get() = !isMusical

    companion object {
        fun fromNameOrDefault(raw: String?, default: KeySoundStyle = MECHANICAL): KeySoundStyle {
            if (raw.isNullOrBlank()) return default
            return entries.firstOrNull { it.name == raw } ?: default
        }
    }
}

/**
 * Functional key zones used by [KeySoundStyle.BAND_ENSEMBLE] to route different key categories
 * to distinct instruments.
 */
enum class KeyInstrumentZone(val displayLabel: String) {
    LETTER("Letters"),
    NUMBER("Numbers"),
    SYMBOL("Symbols"),
    FUNCTION("Function Keys")
}

/**
 * Sanitizes a zone instrument selection so [KeySoundStyle.BAND_ENSEMBLE] can never recurse onto itself.
 */
fun sanitizeBandZoneInstrument(style: KeySoundStyle, fallback: KeySoundStyle): KeySoundStyle =
    if (style == KeySoundStyle.BAND_ENSEMBLE) {
        if (fallback == KeySoundStyle.BAND_ENSEMBLE) KeySoundStyle.GRAND_PIANO else fallback
    } else {
        style
    }

/**
 * Per-zone instrument routing configuration for [KeySoundStyle.BAND_ENSEMBLE].
 */
@Immutable
data class BandInstrumentConfig(
    val letterInstrument: KeySoundStyle = KeySoundStyle.GRAND_PIANO,
    val numberInstrument: KeySoundStyle = KeySoundStyle.KALIMBA_MUSIC_BOX,
    val symbolInstrument: KeySoundStyle = KeySoundStyle.GUZHENG,
    val functionInstrument: KeySoundStyle = KeySoundStyle.ACOUSTIC_DRUM_KIT
) {
    fun sanitized(): BandInstrumentConfig = BandInstrumentConfig(
        letterInstrument = sanitizeBandZoneInstrument(letterInstrument, KeySoundStyle.GRAND_PIANO),
        numberInstrument = sanitizeBandZoneInstrument(numberInstrument, KeySoundStyle.KALIMBA_MUSIC_BOX),
        symbolInstrument = sanitizeBandZoneInstrument(symbolInstrument, KeySoundStyle.GUZHENG),
        functionInstrument = sanitizeBandZoneInstrument(functionInstrument, KeySoundStyle.ACOUSTIC_DRUM_KIT)
    )

    fun styleFor(zone: KeyInstrumentZone): KeySoundStyle = when (zone) {
        KeyInstrumentZone.LETTER -> sanitizeBandZoneInstrument(letterInstrument, KeySoundStyle.GRAND_PIANO)
        KeyInstrumentZone.NUMBER -> sanitizeBandZoneInstrument(numberInstrument, KeySoundStyle.KALIMBA_MUSIC_BOX)
        KeyInstrumentZone.SYMBOL -> sanitizeBandZoneInstrument(symbolInstrument, KeySoundStyle.GUZHENG)
        KeyInstrumentZone.FUNCTION -> sanitizeBandZoneInstrument(functionInstrument, KeySoundStyle.ACOUSTIC_DRUM_KIT)
    }
}

/**
 * Harmonic scale family assigned to each melody instrument.
 */
enum class KeyMusicalScale(
    val semitoneOffsets: IntArray,
    val maxDegreeIndex: Int
) {
    PENTATONIC(intArrayOf(0, 2, 4, 7, 9), maxDegreeIndex = 13),
    MAJOR(intArrayOf(0, 2, 4, 5, 7, 9, 11), maxDegreeIndex = 18)
}

/**
 * Deterministic pitch resolution selecting the nearest pre-synthesized root slot and playback rate.
 */
data class KeyPitchSelection(
    val degreeIndex: Int,
    val midiNote: Int,
    val slot: KeySoundSlot,
    val rate: Float
)

/**
 * Pure-Kotlin trigger resolution combining band zone routing, slot choice, and pitch rate.
 */
data class ResolvedSoundTrigger(
    val style: KeySoundStyle,
    val zone: KeyInstrumentZone,
    val slot: KeySoundSlot,
    val rate: Float,
    val midiNote: Int?,
    val degreeIndex: Int?
)

/**
 * Immutable feedback configuration bundle passed into [KeyFeedbackController].
 * Defaults keep acoustic key sounds OFF and haptic feedback ON.
 */
@Immutable
data class KeyFeedbackOptions(
    val hapticEnabled: Boolean = true,
    val hapticStrength: Int = DEFAULT_HAPTIC_STRENGTH,
    val soundEnabled: Boolean = false,
    val soundVolume: Float = DEFAULT_SOUND_VOLUME,
    val soundStyle: KeySoundStyle = KeySoundStyle.MECHANICAL,
    val bandConfig: BandInstrumentConfig = BandInstrumentConfig()
) {
    fun clamped(): KeyFeedbackOptions = copy(
        hapticStrength = clampHapticStrength(hapticStrength),
        soundVolume = clampSoundVolume(soundVolume),
        bandConfig = bandConfig.sanitized()
    )

    companion object {
        const val MIN_HAPTIC_STRENGTH: Int = 0
        const val MIN_ACTIVE_HAPTIC_STRENGTH: Int = HapticEffectPlanner.MIN_ACTIVE_STRENGTH
        const val MAX_HAPTIC_STRENGTH: Int = HapticEffectPlanner.MAX_STRENGTH
        const val DEFAULT_HAPTIC_STRENGTH: Int = HapticEffectPlanner.DEFAULT_STRENGTH
        const val MIN_SOUND_VOLUME: Float = 0.05f
        const val MAX_SOUND_VOLUME: Float = 1.0f
        const val DEFAULT_SOUND_VOLUME: Float = 0.65f

        fun clampHapticStrength(strength: Int): Int = strength.coerceIn(MIN_HAPTIC_STRENGTH, MAX_HAPTIC_STRENGTH)

        fun clampSoundVolume(volume: Float): Float = if (volume.isFinite()) {
            volume.coerceIn(MIN_SOUND_VOLUME, MAX_SOUND_VOLUME)
        } else {
            DEFAULT_SOUND_VOLUME
        }
    }
}

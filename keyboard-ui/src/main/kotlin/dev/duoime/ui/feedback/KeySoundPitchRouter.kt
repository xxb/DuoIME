/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME
 */
package dev.duoime.ui.feedback

import dev.duoime.core.layout.KeyCodes
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.FeedbackZone
import dev.duoime.core.model.KeyAction
import kotlin.math.abs
import kotlin.math.pow

/**
 * Pure-Kotlin pitch degree mapping and 4-zone instrument router decoupled strictly via `:core`'s
 * [FeedbackKey] (`action`, `label`, `zone`).
 */
internal object KeySoundPitchRouter {
    private const val BASE_MIDI_C3 = 48

    private val TACTILE_SLOTS = listOf(
        KeySoundSlot.ALPHA,
        KeySoundSlot.STABILIZED,
        KeySoundSlot.ENTER_HEAVY,
        KeySoundSlot.MODIFIER
    )

    private val MELODY_ROOT_SLOTS = listOf(
        KeySoundSlot.NOTE_C3,
        KeySoundSlot.NOTE_G3,
        KeySoundSlot.NOTE_D4,
        KeySoundSlot.NOTE_A4,
        KeySoundSlot.NOTE_E5
    )

    private val MELODY_ROOT_MIDI_NOTES = intArrayOf(48, 55, 62, 69, 76)

    private val MELODY_SLOTS = MELODY_ROOT_SLOTS + listOf(
        KeySoundSlot.ENTER_CHORD,
        KeySoundSlot.MODIFIER_LIGHT
    )

    private val DRUM_SLOTS = listOf(
        KeySoundSlot.ALPHA,
        KeySoundSlot.STABILIZED,
        KeySoundSlot.DRUM_RIMSHOT,
        KeySoundSlot.ENTER_HEAVY,
        KeySoundSlot.MODIFIER
    )

    private val QWERTY_BOTTOM_ROW = charArrayOf('z', 'x', 'c', 'v', 'b', 'n', 'm')
    private val QWERTY_HOME_ROW = charArrayOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l')
    private val QWERTY_TOP_ROW = charArrayOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p')
    private val NUMBER_ROW_DIGITS = charArrayOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0')
    private val T9_GRID_ORDER = charArrayOf('7', '8', '9', '4', '5', '6', '1', '2', '3')
    private val T9_PENTATONIC_DEGREES = intArrayOf(5, 6, 7, 8, 9, 10, 11, 12, 13)
    private val T9_MAJOR_DEGREES = intArrayOf(7, 8, 9, 10, 11, 12, 14, 15, 16)

    private val ORDERED_SYMBOLS = listOf(
        "/", "-", "_", ".", "~", "@", "\"", "'", "`", "|",
        ":", ";", ",", "?", "!", "#", "+", "=", "$", "*", "&", "%",
        "^", "\\", "(", ")", "[", "]", "{", "}", "<", ">"
    )

    private val FULLWIDTH_SYMBOL_EQUIVALENTS = mapOf(
        "，" to ",",
        "。" to ".",
        "；" to ";",
        "：" to ":",
        "！" to "!",
        "？" to "?",
        "、" to "\\",
        "“" to "\"",
        "”" to "\"",
        "‘" to "'",
        "’" to "'",
        "「" to "[",
        "」" to "]",
        "【" to "[",
        "】" to "]"
    )

    fun slotsForStyle(style: KeySoundStyle): List<KeySoundSlot> = when {
        style.isBandMode -> emptyList()
        style.isMelodyInstrument -> MELODY_SLOTS
        style == KeySoundStyle.ACOUSTIC_DRUM_KIT -> DRUM_SLOTS
        else -> TACTILE_SLOTS
    }

    fun scaleForStyle(style: KeySoundStyle): KeyMusicalScale? = when (style) {
        KeySoundStyle.GUZHENG,
        KeySoundStyle.BRONZE_CHIME_JADE,
        KeySoundStyle.KALIMBA_MUSIC_BOX,
        KeySoundStyle.MARIMBA_HANDPAN -> KeyMusicalScale.PENTATONIC
        KeySoundStyle.GRAND_PIANO,
        KeySoundStyle.JAZZ_RHODES,
        KeySoundStyle.HARP_NYLON -> KeyMusicalScale.MAJOR
        else -> null
    }

    fun classifyProfile(key: FeedbackKey): KeyFeedbackProfile = when {
        key.zone == FeedbackZone.ENTER || key.action is KeyAction.Enter -> KeyFeedbackProfile.STABILIZED
        key.zone == FeedbackZone.SPACE || key.action is KeyAction.Space -> KeyFeedbackProfile.STABILIZED
        key.zone == FeedbackZone.BACKSPACE || key.action is KeyAction.Backspace -> KeyFeedbackProfile.STABILIZED
        key.zone == FeedbackZone.ACTION ||
            key.zone == FeedbackZone.TOOLBAR ||
            key.zone == FeedbackZone.CANDIDATE -> KeyFeedbackProfile.MODIFIER
        else -> KeyFeedbackProfile.ALPHA
    }

    fun classifySoundSlot(key: FeedbackKey): KeySoundSlot = when {
        key.zone == FeedbackZone.ENTER || key.action is KeyAction.Enter -> KeySoundSlot.ENTER_HEAVY
        key.zone == FeedbackZone.SPACE || key.action is KeyAction.Space -> KeySoundSlot.STABILIZED
        key.zone == FeedbackZone.BACKSPACE || key.action is KeyAction.Backspace -> KeySoundSlot.STABILIZED
        key.zone == FeedbackZone.ACTION ||
            key.zone == FeedbackZone.TOOLBAR ||
            key.zone == FeedbackZone.CANDIDATE -> KeySoundSlot.MODIFIER
        else -> KeySoundSlot.ALPHA
    }

    fun classifyInstrumentZone(key: FeedbackKey): KeyInstrumentZone = when (key.zone) {
        FeedbackZone.LETTER -> KeyInstrumentZone.LETTER
        FeedbackZone.DIGIT -> classifyDigitZone(key)
        FeedbackZone.SYMBOL -> classifySymbolOrDigitToken(extractPayload(key))
        FeedbackZone.SPACE,
        FeedbackZone.BACKSPACE,
        FeedbackZone.ENTER,
        FeedbackZone.ACTION,
        FeedbackZone.TOOLBAR,
        FeedbackZone.CANDIDATE -> KeyInstrumentZone.FUNCTION
    }

    private fun classifyDigitZone(key: FeedbackKey): KeyInstrumentZone {
        val t9Char = extractT9DigitChar(key) ?: return KeyInstrumentZone.NUMBER
        return when (t9Char) {
            '1' -> KeyInstrumentZone.SYMBOL
            in '2'..'9' -> KeyInstrumentZone.LETTER
            else -> KeyInstrumentZone.NUMBER
        }
    }

    private fun classifySymbolOrDigitToken(payload: String): KeyInstrumentZone {
        val ch = payload.singleOrNull()
        return if (ch != null && ch in '0'..'9') KeyInstrumentZone.NUMBER else KeyInstrumentZone.SYMBOL
    }

    fun resolvePitchSelection(key: FeedbackKey, style: KeySoundStyle): KeyPitchSelection {
        val scale = scaleForStyle(style) ?: KeyMusicalScale.MAJOR
        val degree = resolveKeyDegreeIndex(key, scale)
        return resolvePitchForDegree(degree, style)
    }

    fun resolvePitchForDegree(degreeIndex: Int, style: KeySoundStyle): KeyPitchSelection {
        val scale = scaleForStyle(style) ?: KeyMusicalScale.MAJOR
        val clampedDegree = degreeIndex.coerceIn(0, scale.maxDegreeIndex)
        val semitones = scale.semitoneOffsets
        val octave = clampedDegree / semitones.size
        val step = clampedDegree % semitones.size
        val midiNote = BASE_MIDI_C3 + octave * 12 + semitones[step]

        var bestIdx = 0
        var bestDiff = midiNote - MELODY_ROOT_MIDI_NOTES[0]
        for (i in 1 until MELODY_ROOT_MIDI_NOTES.size) {
            val diff = midiNote - MELODY_ROOT_MIDI_NOTES[i]
            if (abs(diff) < abs(bestDiff)) {
                bestDiff = diff
                bestIdx = i
            }
        }
        val rate = 2.0.pow(bestDiff / 12.0).toFloat()
        return KeyPitchSelection(
            degreeIndex = clampedDegree,
            midiNote = midiNote,
            slot = MELODY_ROOT_SLOTS[bestIdx],
            rate = rate
        )
    }

    fun resolveSoundTrigger(
        key: FeedbackKey?,
        soundStyle: KeySoundStyle,
        bandConfig: BandInstrumentConfig = BandInstrumentConfig(),
        isSecondary: Boolean = false
    ): ResolvedSoundTrigger {
        if (key == null) {
            return resolveFallbackTrigger(soundStyle, bandConfig)
        }
        val zone = classifyInstrumentZone(key)
        val style = if (soundStyle.isBandMode) bandConfig.styleFor(zone) else soundStyle
        return when {
            style.isTactile -> resolveTactileTrigger(key, zone, style, isSecondary)
            style == KeySoundStyle.ACOUSTIC_DRUM_KIT -> resolveDrumTrigger(key, zone, style)
            else -> resolveMelodyTrigger(key, zone, style)
        }
    }

    private fun resolveFallbackTrigger(
        soundStyle: KeySoundStyle,
        bandConfig: BandInstrumentConfig
    ): ResolvedSoundTrigger {
        val zone = KeyInstrumentZone.FUNCTION
        val style = if (soundStyle.isBandMode) bandConfig.styleFor(zone) else soundStyle
        val slot = if (style.isMelodyInstrument) KeySoundSlot.MODIFIER_LIGHT else KeySoundSlot.MODIFIER
        val rate = if (style.isTactile) 1.08f else 1.04f
        return ResolvedSoundTrigger(
            style = style,
            zone = zone,
            slot = slot,
            rate = rate,
            midiNote = null,
            degreeIndex = null
        )
    }

    private fun resolveTactileTrigger(
        key: FeedbackKey,
        zone: KeyInstrumentZone,
        style: KeySoundStyle,
        isSecondary: Boolean
    ): ResolvedSoundTrigger {
        val slot = if (isSecondary) KeySoundSlot.MODIFIER else classifySoundSlot(key)
        val rate = if (isSecondary) 1.08f else 1.0f
        return ResolvedSoundTrigger(
            style = style,
            zone = zone,
            slot = slot,
            rate = rate,
            midiNote = null,
            degreeIndex = null
        )
    }

    fun coerceSlotForStyle(slot: KeySoundSlot, style: KeySoundStyle): KeySoundSlot = when {
        style.isTactile -> coerceTactileSlot(slot)
        style == KeySoundStyle.ACOUSTIC_DRUM_KIT -> coerceDrumSlot(slot)
        else -> coerceMelodySlot(slot)
    }

    private fun coerceTactileSlot(slot: KeySoundSlot): KeySoundSlot = when (slot) {
        KeySoundSlot.ALPHA,
        KeySoundSlot.STABILIZED,
        KeySoundSlot.ENTER_HEAVY,
        KeySoundSlot.MODIFIER -> slot
        KeySoundSlot.ENTER_CHORD -> KeySoundSlot.ENTER_HEAVY
        KeySoundSlot.MODIFIER_LIGHT,
        KeySoundSlot.DRUM_RIMSHOT -> KeySoundSlot.MODIFIER
        else -> KeySoundSlot.ALPHA
    }

    private fun coerceDrumSlot(slot: KeySoundSlot): KeySoundSlot = when (slot) {
        KeySoundSlot.ALPHA,
        KeySoundSlot.STABILIZED,
        KeySoundSlot.DRUM_RIMSHOT,
        KeySoundSlot.ENTER_HEAVY,
        KeySoundSlot.MODIFIER -> slot
        KeySoundSlot.ENTER_CHORD -> KeySoundSlot.ENTER_HEAVY
        KeySoundSlot.MODIFIER_LIGHT -> KeySoundSlot.MODIFIER
        else -> KeySoundSlot.ALPHA
    }

    private fun coerceMelodySlot(slot: KeySoundSlot): KeySoundSlot = when (slot) {
        in MELODY_SLOTS -> slot
        KeySoundSlot.ENTER_HEAVY -> KeySoundSlot.ENTER_CHORD
        KeySoundSlot.MODIFIER,
        KeySoundSlot.DRUM_RIMSHOT -> KeySoundSlot.MODIFIER_LIGHT
        KeySoundSlot.STABILIZED -> KeySoundSlot.NOTE_C3
        else -> KeySoundSlot.NOTE_D4
    }

    private fun resolveDrumTrigger(
        key: FeedbackKey,
        zone: KeyInstrumentZone,
        style: KeySoundStyle
    ): ResolvedSoundTrigger = when {
        key.zone == FeedbackZone.ENTER || key.action is KeyAction.Enter -> ResolvedSoundTrigger(
            style = style,
            zone = zone,
            slot = KeySoundSlot.ENTER_HEAVY,
            rate = 1.0f,
            midiNote = null,
            degreeIndex = null
        )
        key.zone == FeedbackZone.SPACE || key.action is KeyAction.Space -> ResolvedSoundTrigger(
            style = style,
            zone = zone,
            slot = KeySoundSlot.STABILIZED,
            rate = 1.0f,
            midiNote = null,
            degreeIndex = null
        )
        key.zone == FeedbackZone.BACKSPACE || key.action is KeyAction.Backspace -> ResolvedSoundTrigger(
            style = style,
            zone = zone,
            slot = KeySoundSlot.DRUM_RIMSHOT,
            rate = 1.0f,
            midiNote = null,
            degreeIndex = null
        )
        zone == KeyInstrumentZone.FUNCTION -> ResolvedSoundTrigger(
            style = style,
            zone = zone,
            slot = KeySoundSlot.MODIFIER,
            rate = 1.0f,
            midiNote = null,
            degreeIndex = null
        )
        else -> {
            val deg = resolveKeyDegreeIndex(key, KeyMusicalScale.PENTATONIC)
            val rate = (0.86f + (deg / 13.0f) * 0.30f).coerceIn(0.84f, 1.19f)
            ResolvedSoundTrigger(
                style = style,
                zone = zone,
                slot = KeySoundSlot.ALPHA,
                rate = rate,
                midiNote = null,
                degreeIndex = deg
            )
        }
    }

    private fun resolveMelodyTrigger(
        key: FeedbackKey,
        zone: KeyInstrumentZone,
        style: KeySoundStyle
    ): ResolvedSoundTrigger = when {
        key.zone == FeedbackZone.ENTER || key.action is KeyAction.Enter -> ResolvedSoundTrigger(
            style = style,
            zone = zone,
            slot = KeySoundSlot.ENTER_CHORD,
            rate = 1.0f,
            midiNote = null,
            degreeIndex = null
        )
        zone == KeyInstrumentZone.FUNCTION -> ResolvedSoundTrigger(
            style = style,
            zone = zone,
            slot = KeySoundSlot.MODIFIER_LIGHT,
            rate = resolveFunctionModifierRate(key),
            midiNote = null,
            degreeIndex = null
        )
        else -> {
            val pitch = resolvePitchSelection(key, style)
            ResolvedSoundTrigger(
                style = style,
                zone = zone,
                slot = pitch.slot,
                rate = pitch.rate,
                midiNote = pitch.midiNote,
                degreeIndex = pitch.degreeIndex
            )
        }
    }

    private fun resolveFunctionModifierRate(key: FeedbackKey): Float = when {
        key.zone == FeedbackZone.SPACE || key.action is KeyAction.Space -> 0.94f
        key.zone == FeedbackZone.BACKSPACE || key.action is KeyAction.Backspace -> 0.89f
        key.zone == FeedbackZone.CANDIDATE -> 1.04f
        key.action is KeyAction.SwitchMode ||
            key.action is KeyAction.ToggleLanguage ||
            key.action is KeyAction.SelectChineseScheme ||
            key.action is KeyAction.OpenPanel ||
            key.action is KeyAction.ClosePanel -> 1.06f
        key.zone == FeedbackZone.TOOLBAR -> 1.02f
        else -> 1.0f
    }

    private fun resolveKeyDegreeIndex(key: FeedbackKey, scale: KeyMusicalScale): Int {
        extractT9DigitChar(key)?.let { digit ->
            return resolveT9DigitDegree(digit, scale)
        }
        val payload = extractPayload(key)
        return when (key.zone) {
            FeedbackZone.LETTER -> resolveTokenDegree(payload, scale)
            FeedbackZone.DIGIT -> resolveTokenDegree(payload, scale)
            FeedbackZone.SYMBOL -> resolveSymbolOrNumberDegree(payload, scale)
            else -> 0
        }
    }

    private fun extractT9DigitChar(key: FeedbackKey): Char? {
        if (key.zone != FeedbackZone.DIGIT) return null
        val engineAction = key.action as? KeyAction.Engine ?: return null
        val ch = KeyCodes.charForKeysym(engineAction.keysym) ?: return null
        return ch.takeIf { it in '1'..'9' }
    }

    private fun extractPayload(key: FeedbackKey): String = when (val action = key.action) {
        is KeyAction.Engine -> KeyCodes.charForKeysym(action.keysym)?.toString() ?: key.label
        is KeyAction.Text -> action.text.ifEmpty { key.label }
        else -> key.label
    }

    private fun resolveSymbolOrNumberDegree(payload: String, scale: KeyMusicalScale): Int {
        val ch = payload.singleOrNull()
        return if (ch != null && ch in '0'..'9') {
            resolveNumberDegree(ch, scale)
        } else {
            resolveSymbolDegree(payload, scale)
        }
    }

    private fun resolveTokenDegree(payload: String, scale: KeyMusicalScale): Int {
        val ch = payload.singleOrNull() ?: return resolveSymbolDegree(payload, scale)
        return when {
            ch in 'a'..'z' || ch in 'A'..'Z' -> resolveLetterDegree(ch.lowercaseChar(), scale)
            ch in '0'..'9' -> resolveNumberDegree(ch, scale)
            else -> resolveSymbolDegree(payload, scale)
        }
    }

    private fun resolveLetterDegree(ch: Char, scale: KeyMusicalScale): Int {
        val bottomIdx = QWERTY_BOTTOM_ROW.indexOf(ch)
        if (bottomIdx >= 0) {
            return if (scale == KeyMusicalScale.PENTATONIC) 3 + bottomIdx else 4 + bottomIdx
        }

        val homeIdx = QWERTY_HOME_ROW.indexOf(ch)
        if (homeIdx >= 0) {
            return if (scale == KeyMusicalScale.PENTATONIC) 4 + homeIdx else 7 + homeIdx
        }

        val topIdx = QWERTY_TOP_ROW.indexOf(ch)
        if (topIdx >= 0) {
            return if (scale == KeyMusicalScale.PENTATONIC) 5 + (topIdx * 8) / 9 else 9 + topIdx
        }
        return 0
    }

    private fun resolveNumberDegree(ch: Char, scale: KeyMusicalScale): Int {
        val idx = NUMBER_ROW_DIGITS.indexOf(ch).coerceAtLeast(0)
        return if (scale == KeyMusicalScale.PENTATONIC) {
            4 + idx
        } else {
            4 + (idx * 14) / 9
        }
    }

    private fun resolveT9DigitDegree(ch: Char?, scale: KeyMusicalScale): Int {
        val orderIdx = if (ch != null) T9_GRID_ORDER.indexOf(ch) else -1
        if (orderIdx < 0) {
            return if (scale == KeyMusicalScale.PENTATONIC) 5 else 7
        }
        val degrees = if (scale == KeyMusicalScale.PENTATONIC) T9_PENTATONIC_DEGREES else T9_MAJOR_DEGREES
        return degrees[orderIdx].coerceIn(0, scale.maxDegreeIndex)
    }

    private fun resolveSymbolDegree(rawSymbol: String, scale: KeyMusicalScale): Int {
        val normalized = FULLWIDTH_SYMBOL_EQUIVALENTS[rawSymbol] ?: rawSymbol
        val idx = ORDERED_SYMBOLS.indexOf(normalized)
        if (idx >= 0) {
            return if (idx < 10) {
                if (scale == KeyMusicalScale.PENTATONIC) {
                    3 + idx
                } else {
                    5 + (idx * 12) / 9
                }
            } else {
                (idx - 10) % (scale.maxDegreeIndex + 1)
            }
        }
        val positiveHash = rawSymbol.hashCode() and 0x7FFFFFFF
        return positiveHash % (scale.maxDegreeIndex + 1)
    }
}

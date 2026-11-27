/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME
 */
package dev.duoime.ui.feedback

import android.media.AudioAttributes
import android.media.AudioManager
import dev.duoime.core.layout.BuiltInLayouts
import dev.duoime.core.layout.KeyCodes
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.FeedbackZone
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.KeyboardPanel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeySoundAndMixerTest {

    @Test
    fun `synthesizes valid 44-1kHz 16-bit mono RIFF WAVE buffers with distinct non-clipping tactile profiles`() {
        val peaks = mutableMapOf<KeyFeedbackProfile, Int>()
        val sizes = mutableMapOf<KeyFeedbackProfile, Int>()

        for (profile in KeyFeedbackProfile.entries) {
            val wav = KeySoundSynthesizer.generateWavBytes(profile)
            assertValidMonoWav(wav, expectedSampleCount = (wav.size - 44) / 2, label = "$profile")
            val peak = readPcmPeak(wav)
            assertTrue("Expected audible non-zero peak for $profile, got $peak", peak > 4000)
            assertTrue("Expected headroom without clipping for $profile, got $peak", peak < Short.MAX_VALUE)
            peaks[profile] = peak
            sizes[profile] = (wav.size - 44) / 2
        }

        assertTrue(sizes.getValue(KeyFeedbackProfile.STABILIZED) > sizes.getValue(KeyFeedbackProfile.ALPHA))
        assertTrue(sizes.getValue(KeyFeedbackProfile.ALPHA) > sizes.getValue(KeyFeedbackProfile.MODIFIER))
        assertNotEquals(peaks[KeyFeedbackProfile.ALPHA], peaks[KeyFeedbackProfile.MODIFIER])

        assertEquals(
            KeyFeedbackProfile.STABILIZED,
            KeySoundSynthesizer.classifyKey(FeedbackKey(KeyAction.Backspace, "⌫", FeedbackZone.BACKSPACE))
        )
        assertEquals(
            KeyFeedbackProfile.STABILIZED,
            KeySoundSynthesizer.classifyKey(FeedbackKey(KeyAction.Space, "空格", FeedbackZone.SPACE))
        )
        assertEquals(
            KeyFeedbackProfile.STABILIZED,
            KeySoundSynthesizer.classifyKey(FeedbackKey(KeyAction.Enter, "↵", FeedbackZone.ENTER))
        )
        assertEquals(
            KeyFeedbackProfile.MODIFIER,
            KeySoundSynthesizer.classifyKey(FeedbackKey(KeyAction.Shift, "Shift", FeedbackZone.ACTION))
        )
        assertEquals(
            KeyFeedbackProfile.MODIFIER,
            KeySoundSynthesizer.classifyKey(FeedbackKey(KeyAction.SwapScreen, "换屏", FeedbackZone.TOOLBAR))
        )
        assertEquals(
            KeyFeedbackProfile.ALPHA,
            KeySoundSynthesizer.classifyKey(
                FeedbackKey(KeyAction.Engine(KeyCodes.keysymForChar('q')), "q", FeedbackZone.LETTER)
            )
        )
    }

    @Test
    fun `synthesizes valid 45ms non-clipping multi-style WAVs across all tactile styles with heavy Enter distinction`() {
        val maxSamples45Ms = KeySoundSynthesizer.SAMPLE_RATE_HZ * KeySoundSynthesizer.MAX_DURATION_MS / 1000
        val tactileStyles = KeySoundStyle.entries.filter { it.isTactile }
        assertEquals(
            listOf(
                KeySoundStyle.MECHANICAL,
                KeySoundStyle.ARCADE_COMBO,
                KeySoundStyle.RETRO_8BIT,
                KeySoundStyle.SCIFI_SYNTH
            ),
            tactileStyles
        )

        val alphaHashes = mutableSetOf<Int>()
        for (style in KeySoundStyle.entries) {
            assertTrue("Display label must not be blank for $style", style.displayLabel.isNotBlank())
            assertEquals(style, KeySoundStyle.fromNameOrDefault(style.name))
        }
        assertEquals(KeySoundStyle.MECHANICAL, KeySoundStyle.fromNameOrDefault("UNKNOWN_STYLE"))

        for (style in tactileStyles) {
            val slots = KeySoundSynthesizer.slotsForStyle(style)
            assertEquals(4, slots.size)
            for (slot in slots) {
                val wav = KeySoundSynthesizer.generateWavBytes(slot, style)
                val normalized = KeySoundSynthesizer.synthesizeNormalizedSamples(slot, style)
                assertValidMonoWav(wav, expectedSampleCount = normalized.size, label = "$style/$slot")
                assertTrue(normalized.size in 200..maxSamples45Ms)
                assertTrue(abs(normalized.first()) < 0.02)
                assertTrue(abs(normalized.last()) < 0.02)
                assertTrue(normalized.maxOf { abs(it) } < 0.95)
                val pcmPeak = readPcmPeak(wav)
                assertTrue(pcmPeak in 4001 until Short.MAX_VALUE)
                if (slot == KeySoundSlot.ALPHA) alphaHashes.add(wav.contentHashCode())
            }
        }
        assertEquals(tactileStyles.size, alphaHashes.size)

        val arcadeAlpha = KeySoundSynthesizer.synthesizeNormalizedSamples(
            KeySoundSlot.ALPHA,
            KeySoundStyle.ARCADE_COMBO
        )
        val arcadeEnter = KeySoundSynthesizer.synthesizeNormalizedSamples(
            KeySoundSlot.ENTER_HEAVY,
            KeySoundStyle.ARCADE_COMBO
        )
        assertTrue(arcadeEnter.size > arcadeAlpha.size)
        assertTrue(arcadeEnter.maxOf { abs(it) } > arcadeAlpha.maxOf { abs(it) })
        assertTrue(
            computeLowBandEnergy(arcadeEnter, maxFreqHz = 350.0) >
                computeLowBandEnergy(arcadeAlpha, maxFreqHz = 350.0) * 4.0
        )
    }

    @Test
    fun `synthesizes valid 160-to-360ms non-clipping natural-decay WAVs across all musical instruments`() {
        val minMusicalSamples = KeySoundSynthesizer.SAMPLE_RATE_HZ * KeySoundSynthesizer.MIN_MUSICAL_DURATION_MS / 1000
        val maxMusicalSamples = KeySoundSynthesizer.SAMPLE_RATE_HZ * KeySoundSynthesizer.MAX_MUSICAL_DURATION_MS / 1000
        val directMusicalStyles = KeySoundStyle.entries.filter { it.isMusical && !it.isBandMode }
        assertEquals(8, directMusicalStyles.size)
        assertTrue(KeySoundSynthesizer.slotsForStyle(KeySoundStyle.BAND_ENSEMBLE).isEmpty())

        val signatureHashes = mutableSetOf<Int>()
        for (style in directMusicalStyles) {
            val slots = KeySoundSynthesizer.slotsForStyle(style)
            assertEquals(if (style.isMelodyInstrument) 7 else 5, slots.size)
            for (slot in slots) {
                val wav = KeySoundSynthesizer.generateWavBytes(slot, style)
                val normalized = KeySoundSynthesizer.synthesizeNormalizedSamples(slot, style)
                assertValidMonoWav(wav, expectedSampleCount = normalized.size, label = "$style/$slot")
                assertTrue(normalized.size in minMusicalSamples..maxMusicalSamples)
                assertTrue(abs(normalized.first()) < 0.02)
                assertTrue(abs(normalized.last()) < 0.02)
                assertTrue(normalized.maxOf { abs(it) } < 0.95)
                assertTrue(readPcmPeak(wav) in 4001 until Short.MAX_VALUE)

                val headLen = (KeySoundSynthesizer.SAMPLE_RATE_HZ * 60) / 1000
                val tailLen = (KeySoundSynthesizer.SAMPLE_RATE_HZ * 35) / 1000
                val headRms = computeRms(normalized, start = 0, end = headLen)
                val tailRms = computeRms(normalized, start = normalized.size - tailLen, end = normalized.size)
                assertTrue("Expected natural decay for $style/$slot", tailRms < headRms * 0.75)

                if (slot == KeySoundSlot.NOTE_D4 || slot == KeySoundSlot.ALPHA) {
                    signatureHashes.add(wav.contentHashCode())
                }
            }
        }
        assertEquals(directMusicalStyles.size, signatureHashes.size)
    }

    @Test
    fun `maps QWERTY letters digits T9 and symbols across multiple octaves via FeedbackKey only`() {
        val pentatonicSemitones = setOf(0, 2, 4, 7, 9)
        val majorSemitones = setOf(0, 2, 4, 5, 7, 9, 11)

        val letters = ('a'..'z').map { ch ->
            FeedbackKey(KeyAction.Engine(KeyCodes.keysymForChar(ch)), ch.toString(), FeedbackZone.LETTER)
        }
        val numberRow = ('0'..'9').map { ch ->
            FeedbackKey(KeyAction.Text(ch.toString()), ch.toString(), FeedbackZone.DIGIT)
        }
        val t9Keys = listOf('7', '8', '9', '4', '5', '6', '1', '2', '3').map { digit ->
            FeedbackKey(KeyAction.Engine(KeyCodes.keysymForChar(digit)), digit.toString(), FeedbackZone.DIGIT)
        }
        val symbolKeys = BuiltInLayouts.symbolsV0().rows.flatMap { it.keys }
            .filter { it.zone == FeedbackZone.SYMBOL }
            .map { FeedbackKey(it.action, it.label, it.zone) }

        for (style in KeySoundStyle.entries.filter { it.isMelodyInstrument }) {
            val expectedMod12 = if (KeySoundSynthesizer.scaleForStyle(style) == KeyMusicalScale.PENTATONIC) {
                pentatonicSemitones
            } else {
                majorSemitones
            }

            val letterNotes = checkPitchGroup(letters, style, expectedMod12, minDistinct = 11, minSpan = 24)
            assertTrue(letterNotes.min() >= 55)
            val zMidi = KeySoundSynthesizer.resolvePitchSelection(letters['z' - 'a'], style).midiNote
            val aMidi = KeySoundSynthesizer.resolvePitchSelection(letters['a' - 'a'], style).midiNote
            val qMidi = KeySoundSynthesizer.resolvePitchSelection(letters['q' - 'a'], style).midiNote
            assertTrue("Expected z ($zMidi) < a ($aMidi) < q ($qMidi)", zMidi < aMidi && aMidi < qMidi)

            checkPitchGroup(numberRow, style, expectedMod12, minDistinct = 10, minSpan = 18)

            val t9Notes = checkPitchGroup(t9Keys, style, expectedMod12, minDistinct = 9, minSpan = 15)
            assertTrue(t9Notes.first() >= 60)
            for ((low, high) in t9Notes.zipWithNext()) {
                assertTrue(high > low && (high - low) < 7)
            }

            checkPitchGroup(symbolKeys, style, expectedMod12, minDistinct = 8, minSpan = 12)
        }

        val pqrsDrum = KeySoundSynthesizer.resolveSoundTrigger(
            key = FeedbackKey(KeyAction.Engine(KeyCodes.keysymForChar('7')), "PQRS", FeedbackZone.DIGIT),
            soundStyle = KeySoundStyle.ACOUSTIC_DRUM_KIT
        )
        assertTrue(pqrsDrum.rate >= 0.96f)
    }

    @Test
    fun `band ensemble classifies 4 zones accurately via FeedbackKey and guards against recursion`() {
        val letterKey = FeedbackKey(KeyAction.Engine(KeyCodes.keysymForChar('q')), "q", FeedbackZone.LETTER)
        val t9LetterKey = FeedbackKey(KeyAction.Engine(KeyCodes.keysymForChar('2')), "ABC", FeedbackZone.DIGIT)
        val t9Key1 = FeedbackKey(KeyAction.Engine(KeyCodes.keysymForChar('1')), ",.", FeedbackZone.DIGIT)
        val digitKey = FeedbackKey(KeyAction.Text("5"), "5", FeedbackZone.DIGIT)
        val symbolKey = FeedbackKey(KeyAction.Text("，"), "，", FeedbackZone.SYMBOL)
        val spaceKey = FeedbackKey(KeyAction.Space, "空格", FeedbackZone.SPACE)
        val enterKey = FeedbackKey(KeyAction.Enter, "↵", FeedbackZone.ENTER)
        val backspaceKey = FeedbackKey(KeyAction.Backspace, "⌫", FeedbackZone.BACKSPACE)
        val modeKey = FeedbackKey(KeyAction.OpenPanel(KeyboardPanel.SYMBOL), "123", FeedbackZone.ACTION)

        assertEquals(KeyInstrumentZone.LETTER, KeySoundSynthesizer.classifyInstrumentZone(letterKey))
        assertEquals(KeyInstrumentZone.LETTER, KeySoundSynthesizer.classifyInstrumentZone(t9LetterKey))
        assertEquals(KeyInstrumentZone.SYMBOL, KeySoundSynthesizer.classifyInstrumentZone(t9Key1))
        assertEquals(KeyInstrumentZone.NUMBER, KeySoundSynthesizer.classifyInstrumentZone(digitKey))
        assertEquals(KeyInstrumentZone.SYMBOL, KeySoundSynthesizer.classifyInstrumentZone(symbolKey))
        assertEquals(KeyInstrumentZone.FUNCTION, KeySoundSynthesizer.classifyInstrumentZone(spaceKey))
        assertEquals(KeyInstrumentZone.FUNCTION, KeySoundSynthesizer.classifyInstrumentZone(enterKey))
        assertEquals(KeyInstrumentZone.FUNCTION, KeySoundSynthesizer.classifyInstrumentZone(backspaceKey))
        assertEquals(KeyInstrumentZone.FUNCTION, KeySoundSynthesizer.classifyInstrumentZone(modeKey))

        val config = BandInstrumentConfig()
        assertEquals(
            KeySoundStyle.GRAND_PIANO,
            KeySoundSynthesizer.resolveSoundTrigger(letterKey, KeySoundStyle.BAND_ENSEMBLE, config).style
        )
        assertEquals(
            KeySoundStyle.KALIMBA_MUSIC_BOX,
            KeySoundSynthesizer.resolveSoundTrigger(digitKey, KeySoundStyle.BAND_ENSEMBLE, config).style
        )
        assertEquals(
            KeySoundStyle.GUZHENG,
            KeySoundSynthesizer.resolveSoundTrigger(symbolKey, KeySoundStyle.BAND_ENSEMBLE, config).style
        )
        assertEquals(
            KeySoundSlot.STABILIZED,
            KeySoundSynthesizer.resolveSoundTrigger(spaceKey, KeySoundStyle.BAND_ENSEMBLE, config).slot
        )
        assertEquals(
            KeySoundSlot.ENTER_HEAVY,
            KeySoundSynthesizer.resolveSoundTrigger(enterKey, KeySoundStyle.BAND_ENSEMBLE, config).slot
        )
        assertEquals(
            KeySoundSlot.DRUM_RIMSHOT,
            KeySoundSynthesizer.resolveSoundTrigger(backspaceKey, KeySoundStyle.BAND_ENSEMBLE, config).slot
        )

        val nullTrigger = KeySoundSynthesizer.resolveSoundTrigger(
            key = null,
            soundStyle = KeySoundStyle.BAND_ENSEMBLE,
            bandConfig = config
        )
        assertEquals(KeyInstrumentZone.FUNCTION, nullTrigger.zone)
        assertEquals(KeySoundStyle.ACOUSTIC_DRUM_KIT, nullTrigger.style)

        val poisoned = BandInstrumentConfig(
            letterInstrument = KeySoundStyle.BAND_ENSEMBLE,
            numberInstrument = KeySoundStyle.BAND_ENSEMBLE,
            symbolInstrument = KeySoundStyle.BAND_ENSEMBLE,
            functionInstrument = KeySoundStyle.BAND_ENSEMBLE
        ).sanitized()
        assertFalse(poisoned.letterInstrument.isBandMode)
        assertFalse(poisoned.numberInstrument.isBandMode)
        assertFalse(poisoned.symbolInstrument.isBandMode)
        assertFalse(poisoned.functionInstrument.isBandMode)
    }

    @Test
    fun `cached WAV validation atomic writer and Karplus sweep edge cases stay safe`() {
        val tempDir = Files.createTempDirectory("duoime_wav_cache_test").toFile()
        try {
            val validBytes = KeySoundSynthesizer.generateWavBytes(KeySoundSlot.NOTE_D4, KeySoundStyle.GRAND_PIANO)
            val target = File(tempDir, "grand_piano_note_d4.wav")
            assertFalse(KeySoundSynthesizer.isValidCachedWavFile(target))

            target.writeBytes(validBytes.copyOf(512))
            assertFalse(KeySoundSynthesizer.isValidCachedWavFile(target))

            target.writeBytes(validBytes.copyOf(44))
            assertFalse(KeySoundSynthesizer.isValidCachedWavFile(target))

            val badMagic = validBytes.copyOf()
            badMagic[0] = 'X'.code.toByte()
            target.writeBytes(badMagic)
            assertFalse(KeySoundSynthesizer.isValidCachedWavFile(target))

            KeySoundSynthesizer.writeWavAtomically(target, validBytes)
            assertTrue(KeySoundSynthesizer.isValidCachedWavFile(target))
            assertTrue(target.readBytes().contentEquals(validBytes))
            assertTrue(tempDir.listFiles()?.none { it.name.endsWith(".tmp") } == true)
        } finally {
            tempDir.deleteRecursively()
        }

        val sweep = MusicalInstrumentDsp.renderKarplusSweep(
            durationSec = 0.200,
            freqs = doubleArrayOf(261.63, 392.00, 523.25, 659.25),
            offsetsSec = doubleArrayOf(0.0, 0.1999, 0.200, 0.500),
            seed = 0x1234
        )
        assertEquals((KeySoundSynthesizer.SAMPLE_RATE_HZ * 0.200).toInt(), sweep.size)
        assertTrue(sweep.maxOf { abs(it) } < 0.95)
        assertTrue(abs(sweep.first()) < 0.02)
        assertTrue(abs(sweep.last()) < 0.02)
    }

    @Test
    fun `ComboPitchEscalator ramps within 220ms clamps at 1-18f and resets on gap ENTER or style change`() {
        val escalator = ComboPitchEscalator()
        val t0 = 1_000_000_000L

        assertEquals(1.00f, escalator.nextRate(t0, KeySoundStyle.ARCADE_COMBO, KeySoundSlot.ALPHA), 0.0001f)
        assertEquals(
            1.02f,
            escalator.nextRate(t0 + 100_000_000L, KeySoundStyle.ARCADE_COMBO, KeySoundSlot.ALPHA),
            0.0001f
        )
        var cursorNs = t0 + 100_000_000L
        var lastRate = 1.02f
        repeat(10) {
            cursorNs += 80_000_000L
            lastRate = escalator.nextRate(cursorNs, KeySoundStyle.ARCADE_COMBO, KeySoundSlot.ALPHA)
        }
        assertEquals(1.18f, lastRate, 0.0001f)
        assertEquals(ComboPitchEscalator.DEFAULT_MAX_STEPS, escalator.currentStep)

        assertEquals(
            1.00f,
            escalator.nextRate(cursorNs + 50_000_000L, KeySoundStyle.ARCADE_COMBO, KeySoundSlot.ENTER_HEAVY),
            0.0001f
        )
        assertEquals(0, escalator.currentStep)

        escalator.nextRate(cursorNs + 100_000_000L, KeySoundStyle.ARCADE_COMBO, KeySoundSlot.ALPHA)
        assertEquals(
            1.02f,
            escalator.nextRate(cursorNs + 200_000_000L, KeySoundStyle.ARCADE_COMBO, KeySoundSlot.MODIFIER),
            0.0001f
        )
        assertEquals(
            1.00f,
            escalator.nextRate(cursorNs + 420_000_001L, KeySoundStyle.ARCADE_COMBO, KeySoundSlot.ALPHA),
            0.0001f
        )
        assertEquals(
            1.00f,
            escalator.nextRate(cursorNs + 500_000_000L, KeySoundStyle.MECHANICAL, KeySoundSlot.ALPHA),
            0.0001f
        )
        assertEquals(0, escalator.currentStep)
    }

    @Test
    fun `polyphonicVoiceEngine mixes overlapping voices without clipping and honors queued limit priming`() {
        val engine = PolyphonicVoiceEngine(outputSampleRateHz = 48_000, sourceSampleRateHz = 44_100, maxVoices = 8)
        assertFalse(engine.hasActiveVoices)

        val loudTone = ShortArray(2_205) { i ->
            (26_000.0 * sin(2.0 * PI * 440.0 * i / 44_100.0)).toInt().toShort()
        }
        for (n in 0 until 10) {
            engine.noteOn(loudTone, gain = 1.0f, rate = 0.85f + n * 0.05f)
        }
        assertTrue(engine.hasActiveVoices)

        val chunk = ShortArray(192)
        var maxPeak = 0
        var iterations = 0
        while (engine.mixFrames(chunk, chunk.size) && iterations < 100) {
            for (sample in chunk) {
                val mag = abs(sample.toInt())
                assertTrue(mag < Short.MAX_VALUE)
                if (mag > maxPeak) maxPeak = mag
            }
            iterations++
        }
        assertFalse(engine.hasActiveVoices)
        assertTrue(iterations in 5..40)
        assertTrue(maxPeak > 20_000)

        engine.noteOn(loudTone, gain = 1.0f, rate = 1.0f)
        engine.clear()
        assertFalse(engine.hasActiveVoices)
        assertFalse(engine.mixFrames(chunk, chunk.size))
        assertTrue(chunk.all { it == 0.toShort() })

        assertEquals(1152, resolveEffectiveQueuedLimit(576, 1152, awaitingPlaybackStart = true))
        assertEquals(576, resolveEffectiveQueuedLimit(576, 1152, awaitingPlaybackStart = false))
        assertEquals(576, resolveEffectiveQueuedLimit(576, 384, awaitingPlaybackStart = true))
    }

    @Test
    fun `RingerModeGate mutes non-normal modes and queries lazily once per typing burst`() {
        var ringerMode = AudioManager.RINGER_MODE_NORMAL
        var queries = 0
        val gate = RingerModeGate(burstGapMs = 600L, maxStaleMs = 2_000L) {
            queries++
            ringerMode
        }

        // First burst: 10 rapid keys (80ms apart) -> only 1 AudioManager query!
        for (i in 0 until 10) {
            assertTrue(gate.isSoundAllowed(nowMs = 1_000L + i * 80L))
        }
        assertEquals(1, queries)

        // Switching to vibrate during the same rapid burst does not query every key
        ringerMode = AudioManager.RINGER_MODE_VIBRATE
        assertTrue(gate.isSoundAllowed(nowMs = 1_800L))
        assertEquals(1, queries)

        // Pause >= 600ms starts a new burst -> refreshes and mutes cleanly
        assertFalse(gate.isSoundAllowed(nowMs = 2_500L))
        assertEquals(2, queries)

        // Subsequent rapid keys while muted also stay cached (zero per-key IPC)
        assertFalse(gate.isSoundAllowed(nowMs = 2_600L))
        assertFalse(gate.isSoundAllowed(nowMs = 2_700L))
        assertEquals(2, queries)

        // Continuous typing exceeding maxStaleMs refreshes automatically
        ringerMode = AudioManager.RINGER_MODE_SILENT
        assertFalse(gate.isSoundAllowed(nowMs = 4_600L))
        assertEquals(3, queries)

        // invalidate() (onInputHidden) forces a fresh query on the next keyboard show
        ringerMode = AudioManager.RINGER_MODE_NORMAL
        gate.invalidate()
        assertTrue(gate.isSoundAllowed(nowMs = 4_700L))
        assertEquals(4, queries)
    }

    @Test
    fun `RingerModeGate fails closed when AudioManager is unavailable silent or query throws`() {
        // Missing AudioManager resolves to RINGER_MODE_SILENT and fails closed (mute key sounds)
        assertEquals(AudioManager.RINGER_MODE_SILENT, resolveRingerMode(null))
        val unavailableGate = RingerModeGate(burstGapMs = 600L, maxStaleMs = 2_000L) {
            resolveRingerMode(null)
        }
        assertFalse(unavailableGate.isSoundAllowed(nowMs = 1_000L))

        val silentGate = RingerModeGate(burstGapMs = 600L, maxStaleMs = 2_000L) {
            AudioManager.RINGER_MODE_SILENT
        }
        assertFalse(silentGate.isSoundAllowed(nowMs = 1_000L))

        // Cold start exception must fail closed (mute key sounds)
        val coldFailGate = RingerModeGate(burstGapMs = 600L, maxStaleMs = 2_000L) {
            throw SecurityException("AudioManager query failed")
        }
        assertFalse(coldFailGate.isSoundAllowed(nowMs = 1_000L))

        // Previously allowed normal mode must also fail closed after invalidate() if query throws
        var shouldThrow = false
        val flappingGate = RingerModeGate(burstGapMs = 600L, maxStaleMs = 2_000L) {
            if (shouldThrow) {
                throw IllegalStateException("Binder failure")
            }
            AudioManager.RINGER_MODE_NORMAL
        }
        assertTrue(flappingGate.isSoundAllowed(nowMs = 2_000L))
        shouldThrow = true
        flappingGate.invalidate()
        assertFalse(flappingGate.isSoundAllowed(nowMs = 2_100L))
    }

    @Test
    fun `audio usage defaults to USAGE_GAME and switches to sonification with fail-closed ringer gate`() {
        assertEquals(AudioAttributes.USAGE_GAME, resolveAudioUsage(followSystemRingerMode = false))
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, resolveAudioUsage(followSystemRingerMode = true))

        var silentQueries = 0
        val silentGate = RingerModeGate {
            silentQueries++
            AudioManager.RINGER_MODE_SILENT
        }
        assertTrue(shouldAllowSound(followSystemRingerMode = false, nowMs = 1_000L, ringerModeGate = silentGate))
        assertEquals("Media-channel default must not query or block on ringerMode", 0, silentQueries)
        assertFalse(shouldAllowSound(followSystemRingerMode = true, nowMs = 1_000L, ringerModeGate = silentGate))
        assertEquals(1, silentQueries)

        val unavailableGate = RingerModeGate { resolveRingerMode(null) }
        assertTrue(shouldAllowSound(followSystemRingerMode = false, nowMs = 1_000L, ringerModeGate = unavailableGate))
        assertFalse(shouldAllowSound(followSystemRingerMode = true, nowMs = 1_000L, ringerModeGate = unavailableGate))

        val throwingGate = RingerModeGate { throw SecurityException("AudioManager denied") }
        assertTrue(shouldAllowSound(followSystemRingerMode = false, nowMs = 1_000L, ringerModeGate = throwingGate))
        assertFalse(shouldAllowSound(followSystemRingerMode = true, nowMs = 1_000L, ringerModeGate = throwingGate))

        val normalGate = RingerModeGate { AudioManager.RINGER_MODE_NORMAL }
        assertTrue(shouldAllowSound(followSystemRingerMode = true, nowMs = 1_000L, ringerModeGate = normalGate))
    }

    @Test
    fun `TrackSessionLifecycle reuses matching usage recreates on usage switch and recovers uninitialized tracks`() {
        class FakeManagedTrack(val usage: Int, override var isInitialized: Boolean = true) : ManagedAudioTrack {
            var released: Boolean = false
                private set

            override fun release() {
                released = true
            }
        }

        val created = mutableListOf<FakeManagedTrack>()
        var beforeReleaseCallbacks = 0
        val lifecycle = TrackSessionLifecycle { usage ->
            FakeManagedTrack(usage = usage).also { created += it }
        }

        // (a) Cold start creates track with USAGE_GAME
        val gameTrack = lifecycle.ensureTrack(AudioAttributes.USAGE_GAME)
        assertEquals(1, created.size)
        assertEquals(AudioAttributes.USAGE_GAME, gameTrack?.usage)
        assertEquals(AudioAttributes.USAGE_GAME, lifecycle.activeAudioUsage)
        assertTrue(lifecycle.shouldContinueStreaming(false, AudioAttributes.USAGE_GAME, hasWork = true))

        // (b) Matching usage reuses existing initialized track (zero recreations)
        val reusedGame = lifecycle.ensureTrack(AudioAttributes.USAGE_GAME)
        assertTrue(gameTrack === reusedGame)
        assertEquals(1, created.size)
        assertFalse(gameTrack!!.released)

        // (c) Switching requestedAudioUsage breaks streaming, releases old track, creates new one, and reuses cleanly
        assertFalse(
            lifecycle.shouldContinueStreaming(
                released = false,
                requestedUsage = AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
                hasWork = true
            )
        )
        val sonificationTrack = lifecycle.ensureTrack(
            requestedUsage = AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
            onBeforeRelease = { beforeReleaseCallbacks++ }
        )
        assertTrue(gameTrack.released)
        assertEquals(1, beforeReleaseCallbacks)
        assertEquals(2, created.size)
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, sonificationTrack?.usage)
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, lifecycle.activeAudioUsage)
        assertTrue(
            lifecycle.shouldContinueStreaming(
                released = false,
                requestedUsage = AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
                hasWork = true
            )
        )
        assertTrue(sonificationTrack === lifecycle.ensureTrack(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION))
        assertEquals(2, created.size)

        // Switching back to USAGE_GAME releases sonification track and creates fresh game track
        assertFalse(lifecycle.shouldContinueStreaming(false, AudioAttributes.USAGE_GAME, hasWork = true))
        val restoredGame = lifecycle.ensureTrack(
            requestedUsage = AudioAttributes.USAGE_GAME,
            onBeforeRelease = { beforeReleaseCallbacks++ }
        )
        assertTrue(sonificationTrack!!.released)
        assertEquals(2, beforeReleaseCallbacks)
        assertEquals(3, created.size)
        assertEquals(AudioAttributes.USAGE_GAME, restoredGame?.usage)
        assertEquals(AudioAttributes.USAGE_GAME, lifecycle.activeAudioUsage)

        // (d) Uninitialized track triggers clean release + recreation
        restoredGame!!.isInitialized = false
        val recoveredTrack = lifecycle.ensureTrack(
            requestedUsage = AudioAttributes.USAGE_GAME,
            onBeforeRelease = { beforeReleaseCallbacks++ }
        )
        assertTrue(restoredGame.released)
        assertEquals(3, beforeReleaseCallbacks)
        assertEquals(4, created.size)
        assertTrue(recoveredTrack !== restoredGame)
        assertEquals(AudioAttributes.USAGE_GAME, recoveredTrack?.usage)
        assertFalse(recoveredTrack!!.released)
    }

    @Test
    fun `KeyFeedbackOptions defaults keep sound OFF haptics ON and followSystemRingerMode OFF`() {
        val defaults = KeyFeedbackOptions()
        assertFalse(defaults.soundEnabled)
        assertTrue(defaults.hapticEnabled)
        assertFalse(defaults.followSystemRingerMode)
        assertEquals(KeyFeedbackOptions.DEFAULT_HAPTIC_STRENGTH, defaults.hapticStrength)
        assertEquals(KeySoundStyle.MECHANICAL, defaults.soundStyle)
    }

    private fun checkPitchGroup(
        keys: List<FeedbackKey>,
        style: KeySoundStyle,
        expectedMod12: Set<Int>,
        minDistinct: Int,
        minSpan: Int
    ): List<Int> {
        val notes = keys.map { key ->
            val pitch = KeySoundSynthesizer.resolvePitchSelection(key, style)
            assertTrue("Scale violation for $style / $key: ${pitch.midiNote}", (pitch.midiNote % 12) in expectedMod12)
            assertTrue("Rate out of window for $style / $key: ${pitch.rate}", pitch.rate in 0.84f..1.19f)
            pitch.midiNote
        }
        assertTrue("Expected >= $minDistinct distinct notes", notes.toSet().size >= minDistinct)
        assertTrue("Expected >= $minSpan semitone span", (notes.max() - notes.min()) >= minSpan)
        return notes
    }

    private fun assertValidMonoWav(wav: ByteArray, expectedSampleCount: Int, label: String) {
        assertTrue("$label WAV must exceed 44 bytes", wav.size > 44)
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", readFourCc(buf))
        assertEquals(wav.size - 8, buf.int)
        assertEquals("WAVE", readFourCc(buf))
        assertEquals("fmt ", readFourCc(buf))
        assertEquals(16, buf.int)
        assertEquals(1, buf.short.toInt())
        assertEquals(1, buf.short.toInt())
        assertEquals(KeySoundSynthesizer.SAMPLE_RATE_HZ, buf.int)
        assertEquals(KeySoundSynthesizer.SAMPLE_RATE_HZ * 2, buf.int)
        assertEquals(2, buf.short.toInt())
        assertEquals(16, buf.short.toInt())
        assertEquals("data", readFourCc(buf))
        assertEquals(expectedSampleCount * 2, buf.int)
    }

    private fun readFourCc(buf: ByteBuffer): String = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)

    private fun readPcmPeak(wav: ByteArray): Int {
        val buf = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        var peak = 0
        repeat((wav.size - 44) / 2) {
            val s = abs(buf.short.toInt())
            if (s > peak) peak = s
        }
        return peak
    }

    private fun computeRms(samples: DoubleArray, start: Int, end: Int): Double {
        var sumSq = 0.0
        val count = (end - start).coerceAtLeast(1)
        for (i in start until end) {
            sumSq += samples[i] * samples[i]
        }
        return sqrt(sumSq / count)
    }

    private fun computeLowBandEnergy(samples: DoubleArray, maxFreqHz: Double): Double {
        val sr = KeySoundSynthesizer.SAMPLE_RATE_HZ.toDouble()
        var energy = 0.0
        var freq = 60.0
        while (freq <= maxFreqHz) {
            var re = 0.0
            var im = 0.0
            val w = 2.0 * PI * freq / sr
            for (i in samples.indices) {
                re += samples[i] * cos(w * i)
                im += samples[i] * sin(w * i)
            }
            energy += (re * re + im * im) / samples.size.coerceAtLeast(1)
            freq += 30.0
        }
        return energy
    }
}

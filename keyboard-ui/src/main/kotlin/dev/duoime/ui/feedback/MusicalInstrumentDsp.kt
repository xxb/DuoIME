/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME
 */
package dev.duoime.ui.feedback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pure-Kotlin procedural DSP synthesizers for DuoIME's 8 musical instrument styles
 * (`160ms..360ms` natural decay, 44.1 kHz normalized doubles, anti-pop envelope, peak `<= 0.88`).
 */
internal object MusicalInstrumentDsp {
    private const val SAMPLE_RATE_HZ = KeySoundSynthesizer.SAMPLE_RATE_HZ
    private const val TWO_PI = 2.0 * PI
    private const val INV_SAMPLE_RATE = 1.0 / SAMPLE_RATE_HZ
    private const val ATTACK_SECONDS = 0.00045
    private const val MUSICAL_RELEASE_TAIL_SECONDS = 0.006
    private const val MUSICAL_PEAK_CEILING = 0.88

    fun synthesize(slot: KeySoundSlot, style: KeySoundStyle): DoubleArray = when (style) {
        KeySoundStyle.GRAND_PIANO -> synthesizeGrandPiano(slot)
        KeySoundStyle.GUZHENG -> synthesizeGuzheng(slot)
        KeySoundStyle.KALIMBA_MUSIC_BOX -> synthesizeKalimbaMusicBox(slot)
        KeySoundStyle.MARIMBA_HANDPAN -> synthesizeMarimbaHandpan(slot)
        KeySoundStyle.JAZZ_RHODES -> synthesizeJazzRhodes(slot)
        KeySoundStyle.HARP_NYLON -> synthesizeHarpNylon(slot)
        KeySoundStyle.BRONZE_CHIME_JADE -> synthesizeBronzeChimeJade(slot)
        KeySoundStyle.ACOUSTIC_DRUM_KIT -> synthesizeAcousticDrumKit(slot)
        else -> synthesizeGrandPiano(slot)
    }

    private fun slotRootFrequencyHz(slot: KeySoundSlot): Double {
        val midi = when (slot) {
            KeySoundSlot.NOTE_C3 -> 48
            KeySoundSlot.NOTE_G3 -> 55
            KeySoundSlot.NOTE_D4 -> 62
            KeySoundSlot.NOTE_A4 -> 69
            KeySoundSlot.NOTE_E5 -> 76
            else -> 60
        }
        return 440.0 * 2.0.pow((midi - 69) / 12.0)
    }

    private fun synthesizeGrandPiano(slot: KeySoundSlot): DoubleArray = when (slot) {
        KeySoundSlot.MODIFIER_LIGHT -> renderMusicalSamples(0.170, attackSec = 0.0012) { _, t ->
            val feltThump = sin(TWO_PI * 195.0 * t) * exp(-t / 0.020) * 0.34
            val mutedHarm = sin(TWO_PI * 261.63 * t) * exp(-t / 0.048) * 0.32
            val overtone = sin(TWO_PI * 523.25 * t) * exp(-t / 0.025) * 0.14
            (feltThump + mutedHarm + overtone) * exp(-t / 0.120)
        }
        KeySoundSlot.ENTER_CHORD -> {
            val chord = doubleArrayOf(261.63, 329.63, 392.00, 523.25)
            val offsets = doubleArrayOf(0.0, 0.010, 0.020, 0.030)
            renderChordSamples(0.340, chord, offsets, inharmonicity = 0.00032, decaySec = 0.145)
        }
        else -> {
            val f0 = slotRootFrequencyHz(slot)
            val rng = Random(0x91A40 + slot.ordinal)
            renderMusicalSamples(0.285, attackSec = 0.0008) { _, t ->
                val hammerNoise = rng.nextDouble(-1.0, 1.0) * exp(-t / 0.0018) * 0.14
                val soundboard = sin(TWO_PI * 168.0 * t) * exp(-t / 0.014) * 0.16
                val promptEnv = exp(-t / 0.032) * 0.35 + exp(-t / 0.145) * 0.65
                var sum = hammerNoise + soundboard
                val weights = doubleArrayOf(0.54, 0.28, 0.16, 0.10, 0.055, 0.03)
                for (n in 1..6) {
                    val fn = n * f0 * sqrt(1.0 + 0.00035 * n * n)
                    val decay = exp(-t / (0.155 / (1.0 + 0.38 * (n - 1))))
                    sum += sin(TWO_PI * fn * t) * weights[n - 1] * decay
                }
                sum * (0.55 + 0.45 * promptEnv)
            }
        }
    }

    private fun synthesizeGuzheng(slot: KeySoundSlot): DoubleArray = when (slot) {
        KeySoundSlot.MODIFIER_LIGHT -> renderMusicalSamples(0.175, attackSec = 0.0015) { _, t ->
            val woodTap = sin(TWO_PI * 310.0 * t) * exp(-t / 0.018) * 0.30
            val dampedString = sin(TWO_PI * 523.25 * t) * exp(-t / 0.045) * 0.35
            val silk = sin(TWO_PI * 1046.5 * t) * exp(-t / 0.015) * 0.12
            (woodTap + dampedString + silk) * exp(-t / 0.11)
        }
        KeySoundSlot.ENTER_CHORD -> {
            val sweepFreqs = doubleArrayOf(261.63, 293.66, 329.63, 392.00, 523.25)
            val offsets = doubleArrayOf(0.0, 0.016, 0.032, 0.048, 0.064)
            renderKarplusSweep(0.355, sweepFreqs, offsets, seed = 0x628E49)
        }
        else -> {
            val f0 = slotRootFrequencyHz(slot)
            renderKarplusSingle(0.335, f0, damp = 0.9962, seed = 0x602E40 + slot.ordinal)
        }
    }

    private fun synthesizeKalimbaMusicBox(slot: KeySoundSlot): DoubleArray = when (slot) {
        KeySoundSlot.MODIFIER_LIGHT -> renderMusicalSamples(0.165, attackSec = 0.0006) { _, t ->
            val tine = sin(TWO_PI * 880.0 * t) * exp(-t / 0.038) * 0.42
            val sparkle = sin(TWO_PI * 2570.0 * t) * exp(-t / 0.009) * 0.24
            val box = sin(TWO_PI * 210.0 * t) * exp(-t / 0.016) * 0.22
            tine + sparkle + box
        }
        KeySoundSlot.ENTER_CHORD -> {
            val chord = doubleArrayOf(523.25, 659.25, 783.99, 1046.50)
            val offsets = doubleArrayOf(0.0, 0.014, 0.028, 0.042)
            renderMusicalSamples(0.320, attackSec = 0.0006) { _, t ->
                var acc = 0.0
                for (k in chord.indices) {
                    val lt = t - offsets[k]
                    if (lt > 0.0) {
                        val f = chord[k]
                        val main = sin(TWO_PI * f * lt) * exp(-lt / 0.125) * 0.34
                        val tine = sin(TWO_PI * (f * 2.92) * lt) * exp(-lt / 0.014) * 0.11
                        acc += main + tine
                    }
                }
                acc
            }
        }
        else -> {
            val f0 = slotRootFrequencyHz(slot)
            renderMusicalSamples(0.260, attackSec = 0.0006) { _, t ->
                val fundamental = sin(TWO_PI * f0 * t) * exp(-t / 0.105) * 0.62
                val metalMode1 = sin(TWO_PI * (f0 * 2.92) * t) * exp(-t / 0.017) * 0.22
                val metalMode2 = sin(TWO_PI * (f0 * 5.40) * t) * exp(-t / 0.007) * 0.12
                val woodBox = sin(TWO_PI * 192.0 * t) * exp(-t / 0.018) * 0.14
                fundamental + metalMode1 + metalMode2 + woodBox
            }
        }
    }

    private fun synthesizeMarimbaHandpan(slot: KeySoundSlot): DoubleArray = when (slot) {
        KeySoundSlot.MODIFIER_LIGHT -> renderMusicalSamples(0.165, attackSec = 0.0022) { _, t ->
            val palm = sin(TWO_PI * 220.0 * t) * exp(-t / 0.026) * 0.40
            val fifth = sin(TWO_PI * 330.0 * t) * exp(-t / 0.036) * 0.25
            palm + fifth
        }
        KeySoundSlot.ENTER_CHORD -> {
            val chord = doubleArrayOf(196.00, 293.66, 392.00, 587.33)
            val offsets = doubleArrayOf(0.0, 0.014, 0.028, 0.040)
            renderMusicalSamples(0.310, attackSec = 0.0022) { _, t ->
                var acc = 0.0
                for (k in chord.indices) {
                    val lt = t - offsets[k]
                    if (lt > 0.0) {
                        val f = chord[k]
                        val bloom = 1.0 - exp(-lt / 0.004)
                        val body = sin(TWO_PI * f * lt) * exp(-lt / 0.120) * 0.36
                        val oct = sin(TWO_PI * (f * 2.0) * lt) * exp(-lt / 0.075) * 0.14
                        acc += (body + oct) * bloom
                    }
                }
                acc
            }
        }
        else -> {
            val f0 = slotRootFrequencyHz(slot)
            renderMusicalSamples(0.240, attackSec = 0.0022) { _, t ->
                val bloom = 1.0 - exp(-t / 0.0038)
                val fund = sin(TWO_PI * f0 * t) * exp(-t / 0.095) * 0.60
                val octaveHarm = sin(TWO_PI * (f0 * 2.0) * t) * exp(-t / 0.068) * 0.24
                val fifthHarm = sin(TWO_PI * (f0 * 3.0) * t) * exp(-t / 0.052) * 0.12
                val marimbaFourth = sin(TWO_PI * (f0 * 4.0) * t) * exp(-t / 0.022) * 0.16
                (fund + octaveHarm + fifthHarm) * bloom + marimbaFourth
            }
        }
    }

    private fun synthesizeJazzRhodes(slot: KeySoundSlot): DoubleArray = when (slot) {
        KeySoundSlot.MODIFIER_LIGHT -> renderMusicalSamples(0.170, attackSec = 0.0010) { _, t ->
            val mod = 0.45 * exp(-t / 0.015) * sin(TWO_PI * 330.0 * t)
            val body = sin(TWO_PI * 330.0 * t + mod) * exp(-t / 0.048) * 0.45
            val tine = sin(TWO_PI * (330.0 * 7.0) * t) * exp(-t / 0.008) * 0.12
            body + tine
        }
        KeySoundSlot.ENTER_CHORD -> {
            val chord = doubleArrayOf(261.63, 329.63, 392.00, 493.88)
            val offsets = doubleArrayOf(0.0, 0.008, 0.016, 0.024)
            renderMusicalSamples(0.330, attackSec = 0.0010) { _, t ->
                var acc = 0.0
                for (k in chord.indices) {
                    val lt = t - offsets[k]
                    if (lt > 0.0) {
                        val f = chord[k]
                        val fm1 = 0.65 * exp(-lt / 0.065) * sin(TWO_PI * f * lt)
                        val fm7 = 0.28 * exp(-lt / 0.014) * sin(TWO_PI * (f * 7.0) * lt)
                        acc += sin(TWO_PI * f * lt + fm1 + fm7) * exp(-lt / 0.145) * 0.28
                    }
                }
                acc
            }
        }
        else -> {
            val f0 = slotRootFrequencyHz(slot)
            renderMusicalSamples(0.265, attackSec = 0.0010) { _, t ->
                val modWarm = (0.85 * exp(-t / 0.055) + 0.12) * sin(TWO_PI * f0 * t)
                val modBell = 0.36 * exp(-t / 0.014) * sin(TWO_PI * (f0 * 7.0) * t)
                val tonebar = sin(TWO_PI * f0 * t + modWarm + modBell) * exp(-t / 0.115) * 0.62
                val warmEven = sin(TWO_PI * (f0 * 2.0) * t) * exp(-t / 0.060) * 0.18
                tonebar + warmEven
            }
        }
    }

    private fun synthesizeHarpNylon(slot: KeySoundSlot): DoubleArray = when (slot) {
        KeySoundSlot.MODIFIER_LIGHT -> renderMusicalSamples(0.170, attackSec = 0.0018) { _, t ->
            val palm = sin(TWO_PI * 246.94 * t) * exp(-t / 0.042) * 0.42
            val wood = sin(TWO_PI * 118.0 * t) * exp(-t / 0.022) * 0.24
            palm + wood
        }
        KeySoundSlot.ENTER_CHORD -> {
            val chord = doubleArrayOf(261.63, 329.63, 392.00, 523.25)
            val offsets = doubleArrayOf(0.0, 0.015, 0.030, 0.045)
            renderChordSamples(0.340, chord, offsets, inharmonicity = 0.00008, decaySec = 0.140)
        }
        else -> {
            val f0 = slotRootFrequencyHz(slot)
            renderMusicalSamples(0.280, attackSec = 0.0018) { _, t ->
                val bloom = 1.0 - exp(-t / 0.0032)
                val body = sin(TWO_PI * 114.0 * t) * exp(-t / 0.025) * 0.12
                var sum = 0.0
                for (n in 1..5) {
                    val amp = 0.56 / n.toDouble().pow(1.45)
                    val decay = exp(-t / (0.135 / (1.0 + 0.28 * (n - 1))))
                    sum += sin(TWO_PI * (n * f0) * t) * amp * decay
                }
                (sum * bloom) + body
            }
        }
    }

    private fun synthesizeBronzeChimeJade(slot: KeySoundSlot): DoubleArray = when (slot) {
        KeySoundSlot.MODIFIER_LIGHT -> renderMusicalSamples(0.180, attackSec = 0.0008) { _, t ->
            val jade1 = sin(TWO_PI * 783.99 * t) * exp(-t / 0.050) * 0.36
            val jade2 = sin(TWO_PI * (783.99 * 1.5) * t) * exp(-t / 0.024) * 0.22
            val tap = sin(TWO_PI * 2140.0 * t) * exp(-t / 0.008) * 0.14
            jade1 + jade2 + tap
        }
        KeySoundSlot.ENTER_CHORD -> {
            val chord = doubleArrayOf(196.00, 293.66, 392.00, 587.33)
            val offsets = doubleArrayOf(0.0, 0.012, 0.024, 0.036)
            renderMusicalSamples(0.355, attackSec = 0.0008) { _, t ->
                var acc = 0.0
                for (k in chord.indices) {
                    val lt = t - offsets[k]
                    if (lt > 0.0) {
                        val f = chord[k]
                        val main = sin(TWO_PI * f * lt) * exp(-lt / 0.145) * 0.30
                        val minorThird = sin(TWO_PI * (f * 1.20) * lt) * exp(-lt / 0.095) * 0.14
                        val bellFifth = sin(TWO_PI * (f * 2.0) * lt) * exp(-lt / 0.065) * 0.10
                        acc += main + minorThird + bellFifth
                    }
                }
                acc
            }
        }
        else -> {
            val f0 = slotRootFrequencyHz(slot)
            renderMusicalSamples(0.320, attackSec = 0.0008) { _, t ->
                val suibei = sin(TWO_PI * f0 * t) * exp(-t / 0.140) * 0.46
                val zhengbei = sin(TWO_PI * (f0 * 1.0035) * t) * exp(-t / 0.130) * 0.20
                val guPitch = sin(TWO_PI * (f0 * 1.20) * t) * exp(-t / 0.088) * 0.22
                val octaveBell = sin(TWO_PI * (f0 * 2.00) * t) * exp(-t / 0.064) * 0.14
                val jadeShimmer = sin(TWO_PI * (f0 * 2.76) * t) * exp(-t / 0.022) * 0.10
                suibei + zhengbei + guPitch + octaveBell + jadeShimmer
            }
        }
    }

    private fun synthesizeAcousticDrumKit(slot: KeySoundSlot): DoubleArray {
        val rng = Random(0xD40417 + slot.ordinal)
        return when (slot) {
            KeySoundSlot.STABILIZED -> {
                var phase = 0.0
                renderMusicalSamples(0.220, attackSec = 0.0006) { _, t ->
                    val freq = 48.0 + 115.0 * exp(-t / 0.022)
                    phase = advancePhase(phase, freq)
                    val sub = sin(TWO_PI * phase) * exp(-t / 0.072) * 0.72
                    val beater = (rng.nextDouble(-1.0, 1.0) * 0.30 + sin(TWO_PI * 1650.0 * t) * 0.22) *
                        exp(-t / 0.0030)
                    sub + beater
                }
            }
            KeySoundSlot.DRUM_RIMSHOT -> renderMusicalSamples(0.175, attackSec = 0.0005) { _, t ->
                val rim = sin(TWO_PI * 445.0 * t) * exp(-t / 0.024) * 0.42 +
                    sin(TWO_PI * 920.0 * t) * exp(-t / 0.015) * 0.30
                val snareWire = rng.nextDouble(-1.0, 1.0) * exp(-t / 0.036) * 0.34
                rim + snareWire
            }
            KeySoundSlot.ENTER_HEAVY -> renderMusicalSamples(0.315, attackSec = 0.0005) { _, t ->
                val noise = rng.nextDouble(-1.0, 1.0)
                val snareBody = sin(TWO_PI * (145.0 + 60.0 * exp(-t / 0.016)) * t) * exp(-t / 0.048) * 0.42
                val snareNoise = noise * exp(-t / 0.060) * 0.36
                val crash = (
                    sin(TWO_PI * 2180.0 * t) * 0.12 +
                        sin(TWO_PI * 3310.0 * t) * 0.11 +
                        sin(TWO_PI * 4890.0 * t) * 0.09 +
                        noise * 0.18
                    ) * exp(-t / 0.135)
                snareBody + snareNoise + crash
            }
            KeySoundSlot.MODIFIER -> renderMusicalSamples(0.165, attackSec = 0.00045) { _, t ->
                val noise = rng.nextDouble(-1.0, 1.0)
                val metallic = (
                    sin(TWO_PI * 3140.0 * t) +
                        sin(TWO_PI * 4730.0 * t) +
                        sin(TWO_PI * 6310.0 * t) +
                        sin(TWO_PI * 7890.0 * t)
                    ) * 0.14
                (metallic + noise * 0.36) * exp(-t / 0.024)
            }
            else -> {
                var phase = 0.0
                renderMusicalSamples(0.195, attackSec = 0.0006) { _, t ->
                    val freq = 165.0 + 95.0 * exp(-t / 0.018)
                    phase = advancePhase(phase, freq)
                    val tom = sin(TWO_PI * phase) * exp(-t / 0.056) * 0.64
                    val stick = rng.nextDouble(-1.0, 1.0) * exp(-t / 0.0025) * 0.24
                    tom + stick
                }
            }
        }
    }

    private fun renderChordSamples(
        durationSec: Double,
        chordFreqs: DoubleArray,
        offsetsSec: DoubleArray,
        inharmonicity: Double,
        decaySec: Double
    ): DoubleArray = renderMusicalSamples(durationSec, attackSec = 0.0010) { _, t ->
        var acc = 0.0
        for (k in chordFreqs.indices) {
            val lt = t - offsetsSec[k]
            if (lt > 0.0) {
                val f = chordFreqs[k]
                val f2 = 2.0 * f * sqrt(1.0 + inharmonicity * 4.0)
                val f3 = 3.0 * f * sqrt(1.0 + inharmonicity * 9.0)
                val note = sin(TWO_PI * f * lt) * 0.56 +
                    sin(TWO_PI * f2 * lt) * 0.24 * exp(-lt / (decaySec * 0.65)) +
                    sin(TWO_PI * f3 * lt) * 0.12 * exp(-lt / (decaySec * 0.40))
                acc += note * exp(-lt / decaySec) * 0.36
            }
        }
        acc
    }

    private fun renderKarplusSingle(durationSec: Double, freqHz: Double, damp: Double, seed: Int): DoubleArray {
        val totalSamples = (SAMPLE_RATE_HZ * durationSec).toInt()
            .coerceIn(64, (SAMPLE_RATE_HZ * KeySoundSynthesizer.MAX_MUSICAL_DURATION_MS) / 1000)
        val raw = synthesizeKarplusBuffer(totalSamples, freqHz, damp, seed)
        return finalizeMusicalArray(raw, attackSec = 0.0007)
    }

    internal fun renderKarplusSweep(
        durationSec: Double,
        freqs: DoubleArray,
        offsetsSec: DoubleArray,
        seed: Int
    ): DoubleArray {
        val totalSamples = (SAMPLE_RATE_HZ * durationSec).toInt()
            .coerceIn(64, (SAMPLE_RATE_HZ * KeySoundSynthesizer.MAX_MUSICAL_DURATION_MS) / 1000)
        val mix = DoubleArray(totalSamples)
        for (k in freqs.indices) {
            val offsetSec = offsetsSec.getOrElse(k) { 0.0 }
            val startIdx = (offsetSec * SAMPLE_RATE_HZ).toInt().coerceAtLeast(0)
            if (startIdx >= totalSamples) continue
            val len = totalSamples - startIdx
            val noteBuf = synthesizeKarplusBuffer(len, freqs[k], damp = 0.9964, seed = seed + k)
            for (i in 0 until minOf(len, noteBuf.size)) {
                mix[startIdx + i] += noteBuf[i] * 0.38
            }
        }
        return finalizeMusicalArray(mix, attackSec = 0.0008)
    }

    private fun synthesizeKarplusBuffer(sampleCount: Int, freqHz: Double, damp: Double, seed: Int): DoubleArray {
        val delaySamples = (SAMPLE_RATE_HZ / freqHz).toInt().coerceIn(8, 2048)
        val ring = DoubleArray(delaySamples)
        val rng = Random(seed)
        var lp = 0.0
        for (i in 0 until delaySamples) {
            val pluckShape = sin(PI * i / delaySamples)
            val white = rng.nextDouble(-1.0, 1.0)
            lp = 0.60 * lp + 0.40 * white
            ring[i] = (lp * 0.65 + pluckShape * 0.35) * 0.85
        }
        val out = DoubleArray(sampleCount)
        var idx = 0
        var prev = ring[delaySamples - 1]
        for (i in 0 until sampleCount) {
            val cur = ring[idx]
            val t = i * INV_SAMPLE_RATE
            val bodyRes = sin(TWO_PI * 245.0 * t) * exp(-t / 0.020) * 0.10
            out[i] = cur * exp(-t / 0.175) + bodyRes
            val nextVal = damp * 0.5 * (cur + prev)
            prev = cur
            ring[idx] = nextVal
            idx = (idx + 1) % delaySamples
        }
        return out
    }

    private inline fun renderMusicalSamples(
        durationSec: Double,
        attackSec: Double = ATTACK_SECONDS,
        sampleAt: (index: Int, timeSec: Double) -> Double
    ): DoubleArray {
        val minSamples = (SAMPLE_RATE_HZ * KeySoundSynthesizer.MIN_MUSICAL_DURATION_MS) / 1000
        val maxSamples = (SAMPLE_RATE_HZ * KeySoundSynthesizer.MAX_MUSICAL_DURATION_MS) / 1000
        val totalSamples = (SAMPLE_RATE_HZ * durationSec).toInt().coerceIn(minSamples, maxSamples)
        val raw = DoubleArray(totalSamples)
        for (i in 0 until totalSamples) {
            raw[i] = sampleAt(i, i * INV_SAMPLE_RATE)
        }
        return finalizeMusicalArray(raw, attackSec)
    }

    private fun finalizeMusicalArray(raw: DoubleArray, attackSec: Double): DoubleArray {
        val totalSamples = raw.size
        val attackSamples = (SAMPLE_RATE_HZ * attackSec).toInt().coerceAtLeast(4)
        val tailSamples = (SAMPLE_RATE_HZ * MUSICAL_RELEASE_TAIL_SECONDS).toInt().coerceAtLeast(16)
        var peak = 0.0
        for (i in 0 until totalSamples) {
            val attackEnv = if (i < attackSamples) i.toDouble() / attackSamples else 1.0
            val tailIndex = totalSamples - 1 - i
            val tailEnv = if (tailIndex < tailSamples) tailIndex.toDouble() / tailSamples else 1.0
            val shaped = raw[i] * attackEnv * tailEnv
            raw[i] = shaped
            val mag = abs(shaped)
            if (mag > peak) peak = mag
        }
        if (peak > MUSICAL_PEAK_CEILING) {
            val gain = MUSICAL_PEAK_CEILING / peak
            for (i in 0 until totalSamples) {
                raw[i] *= gain
            }
        }
        return raw
    }

    private fun advancePhase(currentPhase: Double, freqHz: Double): Double {
        val next = currentPhase + freqHz * INV_SAMPLE_RATE
        return next - next.toInt()
    }
}

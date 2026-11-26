/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME
 */
package dev.duoime.ui.feedback

import dev.duoime.core.model.FeedbackKey
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Pure-Kotlin procedural synthesizer generating deterministic 44.1 kHz 16-bit mono RIFF/WAVE samples:
 * - Tactile styles (`MECHANICAL`, `ARCADE_COMBO`, `RETRO_8BIT`, `SCIFI_SYNTH`) remain `<= 45ms`.
 * - Musical instrument styles render `160ms..360ms` natural decay waveforms with anti-pop envelopes
 *   and headroom `< 0.95`.
 */
object KeySoundSynthesizer {
    const val SAMPLE_RATE_HZ = 44_100
    const val MAX_DURATION_MS = 45
    const val MIN_MUSICAL_DURATION_MS = 160
    const val MAX_MUSICAL_DURATION_MS = 360

    private const val WAV_HEADER_BYTES = 44
    private const val TWO_PI = 2.0 * PI
    private const val INV_SAMPLE_RATE = 1.0 / SAMPLE_RATE_HZ
    private const val ATTACK_SECONDS = 0.00045
    private const val RELEASE_TAIL_SECONDS = 0.0015
    private const val MAX_HEADROOM = 0.949

    fun slotsForStyle(style: KeySoundStyle): List<KeySoundSlot> = KeySoundPitchRouter.slotsForStyle(style)

    fun scaleForStyle(style: KeySoundStyle): KeyMusicalScale? = KeySoundPitchRouter.scaleForStyle(style)

    fun classifyKey(key: FeedbackKey): KeyFeedbackProfile = KeySoundPitchRouter.classifyProfile(key)

    fun classifySoundSlot(key: FeedbackKey): KeySoundSlot = KeySoundPitchRouter.classifySoundSlot(key)

    fun classifyInstrumentZone(key: FeedbackKey): KeyInstrumentZone = KeySoundPitchRouter.classifyInstrumentZone(key)

    fun resolvePitchSelection(key: FeedbackKey, style: KeySoundStyle): KeyPitchSelection =
        KeySoundPitchRouter.resolvePitchSelection(key, style)

    fun resolvePitchForDegree(degreeIndex: Int, style: KeySoundStyle): KeyPitchSelection =
        KeySoundPitchRouter.resolvePitchForDegree(degreeIndex, style)

    fun resolveSoundTrigger(
        key: FeedbackKey?,
        soundStyle: KeySoundStyle,
        bandConfig: BandInstrumentConfig = BandInstrumentConfig(),
        isSecondary: Boolean = false
    ): ResolvedSoundTrigger = KeySoundPitchRouter.resolveSoundTrigger(
        key = key,
        soundStyle = soundStyle,
        bandConfig = bandConfig,
        isSecondary = isSecondary
    )

    fun toSoundSlot(profile: KeyFeedbackProfile, isEnter: Boolean = false): KeySoundSlot = when {
        isEnter -> KeySoundSlot.ENTER_HEAVY
        profile == KeyFeedbackProfile.STABILIZED -> KeySoundSlot.STABILIZED
        profile == KeyFeedbackProfile.MODIFIER -> KeySoundSlot.MODIFIER
        else -> KeySoundSlot.ALPHA
    }

    fun generateWavBytes(profile: KeyFeedbackProfile): ByteArray =
        generateWavBytes(toSoundSlot(profile), KeySoundStyle.MECHANICAL)

    fun generateWavBytes(slot: KeySoundSlot, style: KeySoundStyle): ByteArray =
        encodePcm16ToWav(synthesizePcm16(slot, style))

    internal fun isValidCachedWavFile(file: File): Boolean {
        if (!file.isFile) return false
        val fileLen = file.length()
        if (fileLen <= WAV_HEADER_BYTES || fileLen > Int.MAX_VALUE) return false
        val header = readHeaderBytes(file) ?: return false
        return hasValidWavHeader(header, fileLen)
    }

    internal fun decodeWavPcm16OrNull(file: File): ShortArray? {
        if (!isValidCachedWavFile(file)) return null
        return runCatching {
            val bytes = file.readBytes()
            val pcmByteCount = bytes.size - WAV_HEADER_BYTES
            if (pcmByteCount <= 0 || pcmByteCount % 2 != 0) return null
            val buf = ByteBuffer.wrap(bytes, WAV_HEADER_BYTES, pcmByteCount).order(ByteOrder.LITTLE_ENDIAN)
            ShortArray(pcmByteCount / 2) { buf.short }
        }.getOrNull()
    }

    internal fun writeWavAtomically(targetFile: File, wavBytes: ByteArray) {
        val parent = targetFile.parentFile?.apply { mkdirs() } ?: File(".")
        val tmpFile = File.createTempFile("${targetFile.nameWithoutExtension}_", ".wav.tmp", parent)
        try {
            tmpFile.writeBytes(wavBytes)
            try {
                Files.move(
                    tmpFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tmpFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    private fun readHeaderBytes(file: File): ByteArray? = runCatching {
        val header = ByteArray(WAV_HEADER_BYTES)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < WAV_HEADER_BYTES) {
                val n = input.read(header, offset, WAV_HEADER_BYTES - offset)
                if (n <= 0) break
                offset += n
            }
            if (offset == WAV_HEADER_BYTES) header else null
        }
    }.getOrNull()

    private fun hasValidWavHeader(header: ByteArray, fileLen: Long): Boolean {
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val riffTag = readFourCc(buf)
        val riffChunkSize = buf.int
        val waveTag = readFourCc(buf)
        val fmtTag = readFourCc(buf)
        val validFmt = hasValidPcmFormat(buf)
        val dataTag = readFourCc(buf)
        val dataSize = buf.int

        val validTags = riffTag == "RIFF" && waveTag == "WAVE" && fmtTag == "fmt " && dataTag == "data"
        return validTags && validFmt && hasExactWavLengths(riffChunkSize, dataSize, fileLen)
    }

    private fun hasValidPcmFormat(buf: ByteBuffer): Boolean {
        val fmtChunkSize = buf.int
        val audioFormat = buf.short.toInt()
        val channels = buf.short.toInt()
        val sampleRate = buf.int
        val byteRate = buf.int
        val blockAlign = buf.short.toInt()
        val bitsPerSample = buf.short.toInt()
        return fmtChunkSize == 16 &&
            audioFormat == 1 &&
            channels == 1 &&
            sampleRate == SAMPLE_RATE_HZ &&
            byteRate == SAMPLE_RATE_HZ * 2 &&
            blockAlign == 2 &&
            bitsPerSample == 16
    }

    private fun hasExactWavLengths(riffChunkSize: Int, dataSize: Int, fileLen: Long): Boolean =
        riffChunkSize.toLong() == fileLen - 8L &&
            dataSize > 0 &&
            dataSize % 2 == 0 &&
            WAV_HEADER_BYTES.toLong() + dataSize.toLong() == fileLen

    private fun readFourCc(buf: ByteBuffer): String = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)

    internal fun synthesizePcm16(profile: KeyFeedbackProfile): ShortArray =
        synthesizePcm16(toSoundSlot(profile), KeySoundStyle.MECHANICAL)

    internal fun synthesizePcm16(
        profile: KeyFeedbackProfile,
        style: KeySoundStyle,
        isEnter: Boolean = false
    ): ShortArray = synthesizePcm16(toSoundSlot(profile, isEnter), style)

    internal fun synthesizePcm16(slot: KeySoundSlot, style: KeySoundStyle): ShortArray {
        val normalized = synthesizeNormalizedSamples(slot, style)
        return ShortArray(normalized.size) { i ->
            (normalized[i].coerceIn(-MAX_HEADROOM, MAX_HEADROOM) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    internal fun synthesizeNormalizedSamples(slot: KeySoundSlot, style: KeySoundStyle): DoubleArray {
        val normalizedSlot = KeySoundPitchRouter.coerceSlotForStyle(slot, style)
        return when (style) {
            KeySoundStyle.MECHANICAL -> synthesizeMechanical(normalizedSlot)
            KeySoundStyle.ARCADE_COMBO -> synthesizeArcadeCombo(normalizedSlot)
            KeySoundStyle.RETRO_8BIT -> synthesizeRetro8Bit(normalizedSlot)
            KeySoundStyle.SCIFI_SYNTH -> synthesizeSciFiSynth(normalizedSlot)
            else -> MusicalInstrumentDsp.synthesize(normalizedSlot, style)
        }
    }

    private fun synthesizeMechanical(slot: KeySoundSlot): DoubleArray {
        val durationSec = when (slot) {
            KeySoundSlot.ALPHA -> 0.022
            KeySoundSlot.STABILIZED -> 0.030
            KeySoundSlot.ENTER_HEAVY -> 0.034
            else -> 0.015
        }
        val rng = Random(0x5E11DEC + slot.ordinal)
        return renderTactileSamples(durationSec) { _, t ->
            val noise = rng.nextDouble(-1.0, 1.0)
            when (slot) {
                KeySoundSlot.ALPHA -> {
                    val clickBurst = noise * exp(-t / 0.0014) * 0.42
                    val leafSnap = sin(TWO_PI * 2350.0 * t) * exp(-t / 0.0021) * 0.34
                    val bodyRes = (
                        sin(TWO_PI * 1080.0 * t) * 0.27 +
                            sin(TWO_PI * 1720.0 * t) * 0.16
                        ) * exp(-t / 0.0040)
                    (clickBurst + leafSnap + bodyRes) * 0.88
                }
                KeySoundSlot.STABILIZED -> {
                    val impact = noise * exp(-t / 0.0019) * 0.32
                    val lowThock = sin(TWO_PI * 330.0 * t) * exp(-t / 0.0068) * 0.44
                    val midBody = sin(TWO_PI * 690.0 * t) * exp(-t / 0.0046) * 0.28
                    (impact + lowThock + midBody) * 0.90
                }
                KeySoundSlot.ENTER_HEAVY -> {
                    val slam = noise * exp(-t / 0.0021) * 0.30
                    val deepThock = sin(TWO_PI * 240.0 * t) * exp(-t / 0.0085) * 0.48
                    val midSnap = sin(TWO_PI * 580.0 * t) * exp(-t / 0.0052) * 0.26
                    val stemPing = sin(TWO_PI * 1480.0 * t) * exp(-t / 0.0022) * 0.16
                    (slam + deepThock + midSnap + stemPing) * 0.85
                }
                else -> {
                    val tickNoise = noise * exp(-t / 0.0009) * 0.34
                    val detent = sin(TWO_PI * 1850.0 * t) * exp(-t / 0.0020) * 0.38
                    tickNoise + detent
                }
            }
        }
    }

    private fun synthesizeArcadeCombo(slot: KeySoundSlot): DoubleArray {
        val durationSec = when (slot) {
            KeySoundSlot.ALPHA -> 0.020
            KeySoundSlot.STABILIZED -> 0.026
            KeySoundSlot.ENTER_HEAVY -> 0.035
            else -> 0.016
        }
        val rng = Random(0xA4CADEC + slot.ordinal)
        var phaseMain = 0.0
        var phaseAux = 0.0
        return renderTactileSamples(durationSec) { _, t ->
            val noise = rng.nextDouble(-1.0, 1.0)
            when (slot) {
                KeySoundSlot.ALPHA -> {
                    val freq = 460.0 + 1220.0 * exp(-t / 0.0038)
                    phaseMain = advancePhase(phaseMain, freq)
                    val body = sin(TWO_PI * phaseMain) * exp(-t / 0.0055) * 0.48
                    val sparkle = sin(TWO_PI * 2480.0 * t) * exp(-t / 0.0026) * 0.22
                    val transient = noise * exp(-t / 0.0008) * 0.20
                    (body + sparkle + transient) * 0.88
                }
                KeySoundSlot.STABILIZED -> {
                    val freq = 150.0 + 330.0 * exp(-t / 0.0055)
                    phaseMain = advancePhase(phaseMain, freq)
                    val thud = sin(TWO_PI * phaseMain) * exp(-t / 0.0075) * 0.54
                    val snap = sin(TWO_PI * 920.0 * t) * exp(-t / 0.0030) * 0.20
                    val transient = noise * exp(-t / 0.0012) * 0.16
                    (thud + snap + transient) * 0.90
                }
                KeySoundSlot.ENTER_HEAVY -> {
                    val lowFreq = 82.0 + 178.0 * exp(-t / 0.0095)
                    phaseMain = advancePhase(phaseMain, lowFreq)
                    val chimeBloom = (1.0 - exp(-t / 0.0015)) * exp(-t / 0.0110)
                    val impact = sin(TWO_PI * phaseMain) * exp(-t / 0.0130) * 0.56
                    val chime = (
                        sin(TWO_PI * 1320.0 * t) * 0.20 +
                            sin(TWO_PI * 1980.0 * t) * 0.14
                        ) * chimeBloom
                    val slam = noise * exp(-t / 0.0016) * 0.22
                    (impact + chime + slam) * 0.86
                }
                else -> {
                    val freq = 1180.0 + 620.0 * (1.0 - exp(-t / 0.0045))
                    phaseMain = advancePhase(phaseMain, freq)
                    phaseAux = advancePhase(phaseAux, freq * 2.0)
                    val ping = sin(TWO_PI * phaseMain) * exp(-t / 0.0042) * 0.46
                    val shimmer = sin(TWO_PI * phaseAux) * exp(-t / 0.0025) * 0.16
                    val tick = noise * exp(-t / 0.0007) * 0.14
                    ping + shimmer + tick
                }
            }
        }
    }

    private fun synthesizeRetro8Bit(slot: KeySoundSlot): DoubleArray {
        val durationSec = when (slot) {
            KeySoundSlot.ALPHA -> 0.018
            KeySoundSlot.STABILIZED -> 0.026
            KeySoundSlot.ENTER_HEAVY -> 0.036
            else -> 0.022
        }
        var phaseMain = 0.0
        var phaseAux = 0.0
        return renderTactileSamples(durationSec) { _, t ->
            when (slot) {
                KeySoundSlot.ALPHA -> {
                    val freq = 380.0 + 1100.0 * exp(-t / 0.0035)
                    phaseMain = advancePhase(phaseMain, freq)
                    pulseWave(phaseMain, 0.25) * exp(-t / 0.0052) * 0.68
                }
                KeySoundSlot.STABILIZED -> {
                    val freq = 130.0 + 290.0 * exp(-t / 0.0065)
                    phaseMain = advancePhase(phaseMain, freq)
                    pulseWave(phaseMain, 0.50) * exp(-t / 0.0080) * 0.66
                }
                KeySoundSlot.ENTER_HEAVY -> {
                    val lowFreq = 88.0 + 132.0 * exp(-t / 0.0090)
                    val chimeFreq = if (t < 0.016) 440.0 else 660.0
                    phaseMain = advancePhase(phaseMain, lowFreq)
                    phaseAux = advancePhase(phaseAux, chimeFreq)
                    val bassSquare = pulseWave(phaseMain, 0.50) * exp(-t / 0.0135) * 0.52
                    val chimePulse = pulseWave(phaseAux, 0.25) * exp(-t / 0.0120) * 0.28
                    bassSquare + chimePulse
                }
                else -> {
                    val freq = if (t < 0.010) 880.0 else 1320.0
                    phaseMain = advancePhase(phaseMain, freq)
                    pulseWave(phaseMain, 0.50) * exp(-t / 0.0075) * 0.56
                }
            }
        }
    }

    private fun synthesizeSciFiSynth(slot: KeySoundSlot): DoubleArray {
        val durationSec = when (slot) {
            KeySoundSlot.ALPHA -> 0.019
            KeySoundSlot.STABILIZED -> 0.027
            KeySoundSlot.ENTER_HEAVY -> 0.036
            else -> 0.018
        }
        var phaseCarrier = 0.0
        var phaseMod = 0.0
        var phaseSub = 0.0
        return renderTactileSamples(durationSec) { _, t ->
            when (slot) {
                KeySoundSlot.ALPHA -> {
                    val carrierFreq = 860.0 + 660.0 * exp(-t / 0.0045)
                    phaseCarrier = advancePhase(phaseCarrier, carrierFreq)
                    phaseMod = advancePhase(phaseMod, carrierFreq * 2.0)
                    val modIdx = 1.35 * exp(-t / 0.0035)
                    val fm = sin(TWO_PI * phaseCarrier + modIdx * sin(TWO_PI * phaseMod))
                    val tri = triangleWave(phaseCarrier)
                    (fm * 0.56 + tri * 0.22) * exp(-t / 0.0058)
                }
                KeySoundSlot.STABILIZED -> {
                    val carrierFreq = 195.0 + 345.0 * exp(-t / 0.0072)
                    phaseCarrier = advancePhase(phaseCarrier, carrierFreq)
                    phaseMod = advancePhase(phaseMod, carrierFreq * 2.0)
                    val modIdx = 1.10 * exp(-t / 0.0050)
                    val fm = sin(TWO_PI * phaseCarrier + modIdx * sin(TWO_PI * phaseMod))
                    val tri = triangleWave(phaseCarrier)
                    (fm * 0.50 + tri * 0.24) * exp(-t / 0.0085)
                }
                KeySoundSlot.ENTER_HEAVY -> {
                    val subFreq = 108.0 + 162.0 * exp(-t / 0.0105)
                    val sweepFreq = 880.0 + 880.0 * (1.0 - exp(-t / 0.0120))
                    phaseSub = advancePhase(phaseSub, subFreq)
                    phaseCarrier = advancePhase(phaseCarrier, sweepFreq)
                    phaseMod = advancePhase(phaseMod, sweepFreq * 1.5)
                    val modIdx = 1.20 * exp(-t / 0.0060)
                    val subPulse = sin(TWO_PI * phaseSub) * exp(-t / 0.0130) * 0.48
                    val hudLock = sin(TWO_PI * phaseCarrier + modIdx * sin(TWO_PI * phaseMod)) *
                        exp(-t / 0.0120) * 0.34
                    subPulse + hudLock
                }
                else -> {
                    val carrierFreq = 1040.0 + 720.0 * (1.0 - exp(-t / 0.0055))
                    phaseCarrier = advancePhase(phaseCarrier, carrierFreq)
                    phaseMod = advancePhase(phaseMod, carrierFreq * 1.5)
                    val modIdx = 0.80 * exp(-t / 0.0040)
                    sin(TWO_PI * phaseCarrier + modIdx * sin(TWO_PI * phaseMod)) * exp(-t / 0.0055) * 0.62
                }
            }
        }
    }

    private inline fun renderTactileSamples(
        durationSec: Double,
        sampleAt: (index: Int, timeSec: Double) -> Double
    ): DoubleArray {
        val maxSamples = (SAMPLE_RATE_HZ * MAX_DURATION_MS) / 1000
        val totalSamples = (SAMPLE_RATE_HZ * durationSec).toInt().coerceIn(64, maxSamples)
        val attackSamples = (SAMPLE_RATE_HZ * ATTACK_SECONDS).toInt().coerceAtLeast(4)
        val tailSamples = (SAMPLE_RATE_HZ * RELEASE_TAIL_SECONDS).toInt().coerceAtLeast(8)
        val out = DoubleArray(totalSamples)

        for (i in 0 until totalSamples) {
            val t = i * INV_SAMPLE_RATE
            val attackEnv = if (i < attackSamples) i.toDouble() / attackSamples else 1.0
            val tailIndex = totalSamples - 1 - i
            val tailEnv = if (tailIndex < tailSamples) tailIndex.toDouble() / tailSamples else 1.0
            out[i] = sampleAt(i, t) * attackEnv * tailEnv
        }
        return out
    }

    private fun advancePhase(currentPhase: Double, freqHz: Double): Double {
        val next = currentPhase + freqHz * INV_SAMPLE_RATE
        return next - next.toInt()
    }

    private fun pulseWave(phase: Double, duty: Double): Double = if (phase < duty) 1.0 else -1.0

    private fun triangleWave(phase: Double): Double = 1.0 - 4.0 * abs(phase - 0.5)

    internal fun encodePcm16ToWav(pcmSamples: ShortArray): ByteArray {
        val dataBytes = pcmSamples.size * 2
        val buf = ByteBuffer.allocate(WAV_HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buf.put('R'.code.toByte())
        buf.put('I'.code.toByte())
        buf.put('F'.code.toByte())
        buf.put('F'.code.toByte())
        buf.putInt(36 + dataBytes)
        buf.put('W'.code.toByte())
        buf.put('A'.code.toByte())
        buf.put('V'.code.toByte())
        buf.put('E'.code.toByte())

        // fmt subchunk (PCM = 1, mono = 1, 44100 Hz, 16-bit)
        buf.put('f'.code.toByte())
        buf.put('m'.code.toByte())
        buf.put('t'.code.toByte())
        buf.put(' '.code.toByte())
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(1)
        buf.putInt(SAMPLE_RATE_HZ)
        buf.putInt(SAMPLE_RATE_HZ * 2)
        buf.putShort(2)
        buf.putShort(16)

        // data subchunk
        buf.put('d'.code.toByte())
        buf.put('a'.code.toByte())
        buf.put('t'.code.toByte())
        buf.put('a'.code.toByte())
        buf.putInt(dataBytes)
        for (sample in pcmSamples) {
            buf.putShort(sample)
        }
        return buf.array()
    }
}

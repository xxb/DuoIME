/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME
 */
package dev.duoime.ui.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import java.util.Arrays
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure-Kotlin burst-cached ringer mode gate matching AOSP IME expectations:
 * mutes key sounds whenever `AudioManager.ringerMode != RINGER_MODE_NORMAL`, while reading
 * `AudioManager.ringerMode` lazily once per typing burst rather than on every single keystroke.
 */
internal class RingerModeGate(
    private val burstGapMs: Long = DEFAULT_BURST_GAP_MS,
    private val maxStaleMs: Long = DEFAULT_MAX_STALE_MS,
    private val queryRingerMode: () -> Int
) {
    private var initialized: Boolean = false
    private var cachedAllowed: Boolean = true
    private var lastKeyTimeMs: Long = 0L
    private var lastQueryTimeMs: Long = 0L

    @Synchronized
    fun isSoundAllowed(nowMs: Long): Boolean {
        val needsRefresh = !initialized ||
            nowMs < lastKeyTimeMs ||
            (nowMs - lastKeyTimeMs) >= burstGapMs ||
            (nowMs - lastQueryTimeMs) >= maxStaleMs
        if (needsRefresh) {
            cachedAllowed = runCatching { queryRingerMode() == AudioManager.RINGER_MODE_NORMAL }.getOrDefault(false)
            lastQueryTimeMs = nowMs
            initialized = true
        }
        lastKeyTimeMs = nowMs
        return cachedAllowed
    }

    @Synchronized
    fun invalidate() {
        initialized = false
    }

    companion object {
        const val DEFAULT_BURST_GAP_MS: Long = 600L
        const val DEFAULT_MAX_STALE_MS: Long = 2_000L
    }
}

/**
 * Pure-Kotlin lock-free polyphonic PCM mixer supporting fractional pitch-shifted playback
 * (`44.1 kHz` source banks resampled on the fly to the device HAL's native `48 kHz` mixer rate)
 * with smooth soft-knee limiting so concurrent multi-thumb chords never clip or allocate per frame.
 */
internal class PolyphonicVoiceEngine(
    val outputSampleRateHz: Int = DEFAULT_OUTPUT_SAMPLE_RATE_HZ,
    val sourceSampleRateHz: Int = KeySoundSynthesizer.SAMPLE_RATE_HZ,
    val maxVoices: Int = DEFAULT_MAX_VOICES
) {
    private class VoiceSlot {
        var active: Boolean = false
        var samples: ShortArray = EMPTY_SHORTS
        var framePos: Double = 0.0
        var step: Double = 1.0
        var gain: Float = 0f
    }

    private val voices = Array(maxVoices.coerceAtLeast(1)) { VoiceSlot() }
    private var mixAccum = FloatArray(DEFAULT_ACCUM_CAPACITY)

    val hasActiveVoices: Boolean
        get() = voices.any { it.active }

    fun noteOn(samples: ShortArray, gain: Float, rate: Float) {
        if (samples.isEmpty() || !gain.isFinite() || gain <= 0f) return
        val slot = findOrStealVoiceSlot()
        val safeRate = if (rate.isFinite()) {
            rate.coerceIn(MIN_PLAYBACK_RATE, MAX_PLAYBACK_RATE).toDouble()
        } else {
            1.0
        }
        val safeOutRate = outputSampleRateHz.coerceAtLeast(MIN_SAMPLE_RATE_HZ).toDouble()
        slot.samples = samples
        slot.framePos = 0.0
        slot.step = (safeRate * sourceSampleRateHz.toDouble()) / safeOutRate
        slot.gain = gain.coerceIn(MIN_VOICE_GAIN, MAX_VOICE_GAIN)
        slot.active = true
    }

    fun clear() {
        for (slot in voices) {
            slot.active = false
            slot.samples = EMPTY_SHORTS
            slot.framePos = 0.0
        }
    }

    fun mixFrames(outPcm: ShortArray, frameCount: Int): Boolean {
        val count = minOf(frameCount, outPcm.size)
        if (count <= 0) return hasActiveVoices
        ensureAccumCapacity(count)
        Arrays.fill(mixAccum, 0, count, 0f)

        var anyMixed = false
        for (slot in voices) {
            if (slot.active && mixSlotIntoAccum(slot, count)) {
                anyMixed = true
            }
        }

        if (!anyMixed) {
            Arrays.fill(outPcm, 0, count, 0)
            return false
        }

        for (i in 0 until count) {
            outPcm[i] = softKneeSampleToPcm16(mixAccum[i])
        }
        return hasActiveVoices
    }

    private fun findOrStealVoiceSlot(): VoiceSlot {
        var furthestSlot = voices[0]
        var highestProgress = -1.0
        for (slot in voices) {
            if (!slot.active) return slot
            val len = slot.samples.size.coerceAtLeast(1)
            val progress = slot.framePos / len.toDouble()
            if (progress > highestProgress) {
                highestProgress = progress
                furthestSlot = slot
            }
        }
        return furthestSlot
    }

    private fun ensureAccumCapacity(needed: Int) {
        if (needed > mixAccum.size) {
            mixAccum = FloatArray(needed)
        }
    }

    private fun mixSlotIntoAccum(slot: VoiceSlot, count: Int): Boolean {
        val src = slot.samples
        val lastIdx = src.size - 1
        if (lastIdx < 0) {
            slot.active = false
            return false
        }
        if (lastIdx == 0) {
            mixAccum[0] += src[0].toFloat() * slot.gain * INV_SHORT_SCALE
            slot.active = false
            return true
        }

        var pos = slot.framePos
        val step = slot.step
        val voiceScale = slot.gain * INV_SHORT_SCALE
        for (f in 0 until count) {
            val idx = pos.toInt()
            if (idx >= lastIdx) {
                slot.active = false
                slot.framePos = pos
                return true
            }
            val frac = (pos - idx).toFloat()
            val s0 = src[idx].toFloat()
            val s1 = src[idx + 1].toFloat()
            mixAccum[f] += (s0 + (s1 - s0) * frac) * voiceScale
            pos += step
        }
        slot.framePos = pos
        if (pos.toInt() >= lastIdx) {
            slot.active = false
        }
        return true
    }

    private fun softKneeSampleToPcm16(x: Float): Short {
        val mag = abs(x)
        if (mag <= SOFT_KNEE_THRESHOLD) {
            return (x * SHORT_MAX_FLOAT)
                .roundToInt()
                .coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        val excess = mag - SOFT_KNEE_THRESHOLD
        val limitedMag = SOFT_KNEE_THRESHOLD + SOFT_KNEE_HEADROOM * excess / (excess + SOFT_KNEE_HEADROOM)
        val signed = if (x >= 0f) limitedMag else -limitedMag
        return (signed * SHORT_MAX_FLOAT)
            .roundToInt()
            .coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

    companion object {
        const val DEFAULT_OUTPUT_SAMPLE_RATE_HZ = 48_000
        const val DEFAULT_MAX_VOICES = 20

        private const val MIN_SAMPLE_RATE_HZ = 8_000
        private const val MIN_PLAYBACK_RATE = 0.5f
        private const val MAX_PLAYBACK_RATE = 2.0f
        private const val MIN_VOICE_GAIN = 0.01f
        private const val MAX_VOICE_GAIN = 1.0f
        private const val DEFAULT_ACCUM_CAPACITY = 1024
        private const val SHORT_MAX_FLOAT = 32767.0f
        private const val INV_SHORT_SCALE = 1.0f / 32768.0f
        private const val SOFT_KNEE_THRESHOLD = 0.85f
        private const val SOFT_KNEE_HEADROOM = 0.149f
        private val EMPTY_SHORTS = ShortArray(0)
    }
}

private data class QueuedAudioNote(
    val style: KeySoundStyle,
    val slot: KeySoundSlot,
    val gain: Float,
    val rate: Float
)

private data class AudioHardwareSpec(
    val outputSampleRateHz: Int,
    val burstFrames: Int
)

private class AudioStreamSession(
    val outputRateHz: Int,
    val burstFrames: Int,
    val bufferFrames: Int
) {
    val engine = PolyphonicVoiceEngine(
        outputSampleRateHz = outputRateHz,
        sourceSampleRateHz = KeySoundSynthesizer.SAMPLE_RATE_HZ
    )
    val burstPcm = ShortArray(burstFrames)
    var track: AudioTrack? = null
    var trackPlaying: Boolean = false
    var targetLeadFrames: Int = (burstFrames * DEFAULT_LEAD_BURSTS)
        .coerceIn(TARGET_LEAD_MIN_FRAMES, TARGET_LEAD_MAX_FRAMES)
        .coerceAtMost(bufferFrames)
    var startThresholdFrames: Int = bufferFrames
    var awaitingPlaybackStart: Boolean = true
    var resumeBaseHeadFrames: Long = 0L
    var writtenFramesSinceResume: Long = 0L
    var lastUnderrunCount: Int = 0
    var pendingOffset: Int = 0
    var pendingCount: Int = 0
    var silentTailBursts: Int = 0

    fun hasWork(noteQueueEmpty: Boolean): Boolean =
        !noteQueueEmpty || engine.hasActiveVoices || silentTailBursts > 0 || pendingCount > 0

    fun resetBufferCursors(headFrames: Long, underrunBaseline: Int) {
        resumeBaseHeadFrames = headFrames
        writtenFramesSinceResume = 0L
        lastUnderrunCount = underrunBaseline
        awaitingPlaybackStart = true
        pendingOffset = 0
        pendingCount = 0
    }

    fun requiredSilentTailBursts(): Int {
        val effectiveLead = maxOf(targetLeadFrames, startThresholdFrames)
        return maxOf(
            MIN_SILENT_TAIL_BURSTS,
            (effectiveLead / burstFrames.coerceAtLeast(1)) + 4
        )
    }

    companion object {
        private const val DEFAULT_LEAD_BURSTS = 3
        private const val TARGET_LEAD_MIN_FRAMES = 256
        private const val TARGET_LEAD_MAX_FRAMES = 960
        private const val MIN_SILENT_TAIL_BURSTS = 8
    }
}

/**
 * Lazily-initialized low-latency `AudioTrack` streaming driver running on a high-priority daemon
 * audio thread (`DuoIME-KeyAudio`) with `USAGE_ASSISTANCE_SONIFICATION`.
 *
 * - Never creates a worker thread or `AudioTrack` at construction (`Service.onCreate`).
 * - Pauses cleanly after the silent tail during active typing sessions.
 * - Releases the `AudioTrack` after the idle tail when [releaseAfterIdleTail] is called
 *   (`onWindowHidden` / `onFinishInputView`), and tears down immediately on [release] (`onDestroy`).
 */
internal class StreamingKeyAudioDriver(
    context: Context,
    private val sampleResolver: (KeySoundStyle, KeySoundSlot) -> ShortArray
) {
    private val appContext = context.applicationContext
    private val noteQueue = ConcurrentLinkedQueue<QueuedAudioNote>()
    private val queuedCount = AtomicInteger(0)
    private val released = AtomicBoolean(false)
    private val releaseTrackWhenIdle = AtomicBoolean(false)
    private val workerLock = Any()

    @Volatile
    private var workerThread: Thread? = null

    fun enqueue(style: KeySoundStyle, slot: KeySoundSlot, gain: Float, rate: Float) {
        if (released.get()) return
        releaseTrackWhenIdle.set(false)
        val thread = ensureWorkerStarted() ?: return
        noteQueue.offer(QueuedAudioNote(style = style, slot = slot, gain = gain, rate = rate))
        if (queuedCount.incrementAndGet() > MAX_QUEUED_NOTES) {
            if (noteQueue.poll() != null) {
                queuedCount.decrementAndGet()
            }
        }
        LockSupport.unpark(thread)
    }

    fun releaseAfterIdleTail() {
        val thread = workerThread ?: return
        if (released.get()) return
        releaseTrackWhenIdle.set(true)
        LockSupport.unpark(thread)
    }

    fun release() {
        if (released.compareAndSet(false, true)) {
            noteQueue.clear()
            queuedCount.set(0)
            LockSupport.unpark(workerThread)
        }
    }

    private fun ensureWorkerStarted(): Thread? {
        workerThread?.let { return it }
        return synchronized(workerLock) {
            if (released.get()) return null
            workerThread ?: Thread(::runAudioLoop, AUDIO_THREAD_NAME).apply {
                isDaemon = true
                workerThread = this
                start()
            }
        }
    }

    private fun runAudioLoop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val hwSpec = queryAudioHardwareSpec(appContext)
        val bufferFrames = computeStreamBufferFrames(hwSpec.outputSampleRateHz, hwSpec.burstFrames)
        val session = AudioStreamSession(
            outputRateHz = hwSpec.outputSampleRateHz,
            burstFrames = hwSpec.burstFrames,
            bufferFrames = bufferFrames
        )

        try {
            while (!released.get()) {
                stepAudioIteration(session)
            }
        } finally {
            releaseTrack(session)
            session.engine.clear()
        }
    }

    private fun stepAudioIteration(session: AudioStreamSession) {
        if (!session.hasWork(noteQueue.isEmpty())) {
            if (releaseTrackWhenIdle.compareAndSet(true, false)) {
                releaseTrack(session)
            } else if (session.trackPlaying) {
                pauseAndFlushTrack(session)
            }
            if (!session.hasWork(noteQueue.isEmpty()) && !releaseTrackWhenIdle.get() && !released.get()) {
                LockSupport.park()
            }
            return
        }
        if (!stepActivePlayback(session)) {
            LockSupport.parkNanos(TRACK_RETRY_PARK_NANOS)
        }
    }

    private fun stepActivePlayback(session: AudioStreamSession): Boolean {
        val track = ensureTrack(session) ?: run {
            drainDiscardPendingNotes()
            session.engine.clear()
            session.silentTailBursts = 0
            session.pendingCount = 0
            return false
        }

        drainNotesIntoEngine(session)
        if (!session.trackPlaying && !primeAndStartTrack(track, session)) {
            releaseTrack(session)
            return false
        }

        updateLeadForUnderruns(track, session)
        var queuedFrames = computeQueuedFrames(track, session)
        val queuedLimit = resolveEffectiveQueuedLimit(
            targetLeadFrames = session.targetLeadFrames,
            startThresholdFrames = session.startThresholdFrames,
            awaitingPlaybackStart = session.awaitingPlaybackStart
        )
        while (!released.get() && queuedFrames < queuedLimit && session.hasWork(noteQueue.isEmpty())) {
            drainNotesIntoEngine(session)
            val written = writeNextChunk(track, session)
            if (written < 0) {
                releaseTrack(session)
                return false
            }
            if (written == 0) break
            queuedFrames += written
        }

        if (session.hasWork(noteQueue.isEmpty()) && !released.get()) {
            LockSupport.parkNanos(ACTIVE_POLL_NANOS)
        }
        return true
    }

    private fun ensureTrack(session: AudioStreamSession): AudioTrack? {
        session.track?.let { existing ->
            if (existing.state == AudioTrack.STATE_INITIALIZED) return existing
            releaseTrack(session)
        }
        val bufBytes = session.bufferFrames * BYTES_PER_MONO_16BIT_FRAME
        val created = createPersistentAudioTrack(session.outputRateHz, bufBytes) ?: return null
        session.track = created
        session.trackPlaying = false
        configureTrackStartThreshold(created, session, session.targetLeadFrames)
        session.resetBufferCursors(readUnsignedHeadFrames(created), readUnderrunCount(created))
        return created
    }

    private fun primeAndStartTrack(track: AudioTrack, session: AudioStreamSession): Boolean {
        session.resetBufferCursors(readUnsignedHeadFrames(track), readUnderrunCount(track))
        val primeTarget = maxOf(session.targetLeadFrames, session.startThresholdFrames)
        while (!released.get() && session.writtenFramesSinceResume < primeTarget) {
            drainNotesIntoEngine(session)
            val written = writeNextChunk(track, session)
            if (written < 0) return false
            if (written == 0) break
        }
        if (session.writtenFramesSinceResume <= 0L) return false
        return runCatching {
            track.play()
            session.trackPlaying = true
            true
        }.getOrDefault(false)
    }

    private fun writeNextChunk(track: AudioTrack, session: AudioStreamSession): Int {
        if (session.pendingCount == 0) {
            val activeAfterMix = session.engine.mixFrames(session.burstPcm, session.burstFrames)
            if (activeAfterMix) {
                session.silentTailBursts = session.requiredSilentTailBursts()
            } else if (session.silentTailBursts > 0) {
                session.silentTailBursts--
            }
            session.pendingOffset = 0
            session.pendingCount = session.burstFrames
        }

        val written = runCatching {
            track.write(
                session.burstPcm,
                session.pendingOffset,
                session.pendingCount,
                AudioTrack.WRITE_NON_BLOCKING
            )
        }.getOrElse { AudioTrack.ERROR_DEAD_OBJECT }

        if (written > 0) {
            session.pendingOffset += written
            session.pendingCount -= written
            session.writtenFramesSinceResume += written
        }
        return written
    }

    private fun drainNotesIntoEngine(session: AudioStreamSession) {
        var armedVoices = false
        while (true) {
            val note = noteQueue.poll() ?: break
            queuedCount.decrementAndGet()
            val samples = runCatching { sampleResolver(note.style, note.slot) }.getOrNull() ?: continue
            if (samples.isNotEmpty()) {
                session.engine.noteOn(samples, gain = note.gain, rate = note.rate)
                armedVoices = true
            }
        }
        if (armedVoices) {
            session.silentTailBursts = session.requiredSilentTailBursts()
        }
    }

    private fun drainDiscardPendingNotes() {
        while (noteQueue.poll() != null) {
            queuedCount.decrementAndGet()
        }
    }

    private fun updateLeadForUnderruns(track: AudioTrack, session: AudioStreamSession) {
        val underruns = readUnderrunCount(track)
        if (underruns > session.lastUnderrunCount) {
            session.lastUnderrunCount = underruns
            val capacityFrames = maxOf(session.bufferFrames, readTrackCapacityFrames(track))
            val grown = (session.targetLeadFrames + session.burstFrames).coerceAtMost(capacityFrames)
            configureTrackStartThreshold(track, session, grown)
        }
    }

    private fun configureTrackStartThreshold(track: AudioTrack, session: AudioStreamSession, desiredFrames: Int) {
        val capacityFrames = maxOf(session.bufferFrames, readTrackCapacityFrames(track))
        val clamped = desiredFrames.coerceIn(session.burstFrames.coerceAtLeast(1), capacityFrames)
        val appliedBuffer = runCatching { track.setBufferSizeInFrames(clamped) }.getOrDefault(-1)
        val effectiveLead = if (appliedBuffer > 0) appliedBuffer else clamped
        session.targetLeadFrames = effectiveLead
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { track.setStartThresholdInFrames(effectiveLead) }
        }
        session.startThresholdFrames = readStartThresholdFrames(track, session.bufferFrames)
    }

    private fun readTrackCapacityFrames(track: AudioTrack): Int =
        runCatching { track.bufferCapacityInFrames }.getOrDefault(0)

    private fun readStartThresholdFrames(track: AudioTrack, fallbackFrames: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val startThreshold = runCatching { track.startThresholdInFrames }.getOrDefault(-1)
            if (startThreshold > 0) return startThreshold
        }
        val effectiveBuffer = runCatching { track.bufferSizeInFrames }.getOrDefault(-1)
        if (effectiveBuffer > 0) return effectiveBuffer
        val capacity = readTrackCapacityFrames(track)
        return if (capacity > 0) capacity else fallbackFrames
    }

    private fun computeQueuedFrames(track: AudioTrack, session: AudioStreamSession): Long {
        val curHead = readUnsignedHeadFrames(track)
        val base = session.resumeBaseHeadFrames
        val playedFrames = when {
            curHead >= base -> curHead - base
            base - curHead > HALF_UINT32_RANGE -> curHead + (FULL_UINT32_RANGE - base)
            else -> {
                session.resumeBaseHeadFrames = curHead
                0L
            }
        }
        if (playedFrames > 0L) {
            session.awaitingPlaybackStart = false
        }
        return (session.writtenFramesSinceResume - playedFrames).coerceAtLeast(0L)
    }

    private fun pauseAndFlushTrack(session: AudioStreamSession) {
        val track = session.track ?: return
        runCatching {
            track.pause()
            track.flush()
        }
        session.trackPlaying = false
        session.resetBufferCursors(readUnsignedHeadFrames(track), readUnderrunCount(track))
    }

    private fun releaseTrack(session: AudioStreamSession) {
        val track = session.track ?: return
        session.track = null
        session.trackPlaying = false
        runCatching {
            track.pause()
            track.flush()
            track.release()
        }
        session.pendingOffset = 0
        session.pendingCount = 0
        session.silentTailBursts = 0
    }

    private fun queryAudioHardwareSpec(context: Context): AudioHardwareSpec {
        val am = runCatching { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()
        val sampleRate = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?.coerceIn(MIN_OUTPUT_RATE_HZ, MAX_OUTPUT_RATE_HZ)
            ?: PolyphonicVoiceEngine.DEFAULT_OUTPUT_SAMPLE_RATE_HZ
        val burstFrames = am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()
            ?.coerceIn(MIN_BURST_FRAMES, MAX_BURST_FRAMES)
            ?: DEFAULT_BURST_FRAMES
        return AudioHardwareSpec(outputSampleRateHz = sampleRate, burstFrames = burstFrames)
    }

    private fun computeStreamBufferFrames(outputRateHz: Int, burstFrames: Int): Int {
        val minBufBytes = runCatching {
            AudioTrack.getMinBufferSize(
                outputRateHz,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        }.getOrDefault(0).coerceAtLeast(burstFrames * BYTES_PER_MONO_16BIT_FRAME * DEFAULT_BUFFER_BURSTS)
        val minFrames = (minBufBytes + 1) / BYTES_PER_MONO_16BIT_FRAME
        return maxOf(minFrames, burstFrames * DEFAULT_BUFFER_BURSTS)
    }

    private fun createPersistentAudioTrack(outputRateHz: Int, bufBytes: Int): AudioTrack? =
        buildTrackWithMode(outputRateHz, bufBytes, AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            ?: buildTrackWithMode(outputRateHz, bufBytes, AudioTrack.PERFORMANCE_MODE_NONE)

    private fun buildTrackWithMode(outputRateHz: Int, bufBytes: Int, perfMode: Int): AudioTrack? = runCatching {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(outputRateHz)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufBytes)
            .setPerformanceMode(perfMode)
            .build()
        if (track.state == AudioTrack.STATE_INITIALIZED) {
            track
        } else {
            runCatching { track.release() }
            null
        }
    }.getOrNull()

    private fun readUnsignedHeadFrames(track: AudioTrack): Long =
        runCatching { track.playbackHeadPosition.toLong() and UINT32_MASK }.getOrDefault(0L)

    private fun readUnderrunCount(track: AudioTrack): Int = runCatching { track.underrunCount }.getOrDefault(0)

    companion object {
        private const val AUDIO_THREAD_NAME = "DuoIME-KeyAudio"
        private const val MAX_QUEUED_NOTES = 48
        private const val BYTES_PER_MONO_16BIT_FRAME = 2
        private const val MIN_OUTPUT_RATE_HZ = 22_050
        private const val MAX_OUTPUT_RATE_HZ = 96_000
        private const val MIN_BURST_FRAMES = 64
        private const val MAX_BURST_FRAMES = 512
        private const val DEFAULT_BURST_FRAMES = 192
        private const val DEFAULT_BUFFER_BURSTS = 6
        private const val ACTIVE_POLL_NANOS = 2_000_000L
        private const val TRACK_RETRY_PARK_NANOS = 250_000_000L
        private const val UINT32_MASK = 0xFFFF_FFFFL
        private const val HALF_UINT32_RANGE = 0x8000_0000L
        private const val FULL_UINT32_RANGE = 0x1_0000_0000L
    }
}

internal fun resolveEffectiveQueuedLimit(
    targetLeadFrames: Int,
    startThresholdFrames: Int,
    awaitingPlaybackStart: Boolean
): Int = if (awaitingPlaybackStart) {
    maxOf(targetLeadFrames, startThresholdFrames)
} else {
    targetLeadFrames
}

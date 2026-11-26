/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME
 */
package dev.duoime.ui.feedback

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.FeedbackZone
import dev.duoime.core.model.KeyAction
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Pure-Kotlin state machine driving smooth combo pitch escalation for [KeySoundStyle.ARCADE_COMBO]:
 * - Rapid non-repeat key strikes within [windowNs] (`<= 220ms`) step playback rate from `1.00f` up to `1.18f`.
 * - Idle gaps `> 220ms`, [KeySoundSlot.ENTER_HEAVY] confirmations, or non-combo styles reset the streak cleanly.
 */
internal class ComboPitchEscalator(
    private val windowNs: Long = DEFAULT_COMBO_WINDOW_NS,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val stepIncrement: Float = DEFAULT_STEP_INCREMENT
) {
    private var streak = 0
    private var lastStrikeNs = Long.MIN_VALUE

    val currentStep: Int
        @Synchronized get() = streak

    @Synchronized
    fun nextRate(nowNs: Long, style: KeySoundStyle, slot: KeySoundSlot): Float {
        if (style != KeySoundStyle.ARCADE_COMBO || slot == KeySoundSlot.ENTER_HEAVY) {
            streak = 0
            lastStrikeNs = Long.MIN_VALUE
            return 1.0f
        }
        val withinWindow = lastStrikeNs != Long.MIN_VALUE &&
            nowNs >= lastStrikeNs &&
            (nowNs - lastStrikeNs) <= windowNs
        streak = if (withinWindow) {
            (streak + 1).coerceAtMost(maxSteps)
        } else {
            0
        }
        lastStrikeNs = nowNs
        return (1.0f + streak * stepIncrement).coerceAtMost(MAX_COMBO_RATE)
    }

    @Synchronized
    fun reset() {
        streak = 0
        lastStrikeNs = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_COMBO_WINDOW_NS = 220_000_000L
        const val DEFAULT_MAX_STEPS = 9
        const val DEFAULT_STEP_INCREMENT = 0.02f
        const val MAX_COMBO_RATE = 1.18f
    }
}

private data class SoundCacheKey(
    val style: KeySoundStyle,
    val slot: KeySoundSlot
)

private data class SoundTriggerSpec(
    val slot: KeySoundSlot,
    val style: KeySoundStyle,
    val volume: Float,
    val baseRate: Float,
    val minGapNs: Long,
    val useComboEscalation: Boolean = false
)

internal fun resolveRingerMode(audioManager: AudioManager?): Int =
    audioManager?.ringerMode ?: AudioManager.RINGER_MODE_SILENT

/**
 * Low-latency tactile & acoustic feedback controller decoupled strictly via `:core` [FeedbackKey]:
 * 1. Acoustic: Lazily creates a `USAGE_ASSISTANCE_SONIFICATION` streaming `AudioTrack` on first
 *    sound (never in `Service.onCreate`), mixes `44.1 kHz` PCM banks in software on a dedicated
 *    `URGENT_AUDIO` thread, respects `AudioManager.ringerMode == RINGER_MODE_NORMAL` lazily per
 *    typing burst, pauses after silent tails during typing, and releases the `AudioTrack` after the
 *    idle tail via [onInputHidden] (`onWindowHidden` / `onFinishInputView`) or immediately on [release].
 * 2. Haptic: Dispatches pre-cached capability-tiered waveforms off the UI thread when `VIBRATE` is
 *    granted, and falls back to `View.performHapticFeedback` (no permission required) for embed hosts.
 */
@Stable
class KeyFeedbackController(
    context: Context,
    fallbackView: View? = null
) {
    private val appContext = context.applicationContext
    private val fallbackViewRef = AtomicReference<WeakReference<View>?>(fallbackView?.let(::WeakReference))
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheDir = File(appContext.cacheDir, CACHE_DIR_NAME)

    private val released = AtomicBoolean(false)
    private val preloadStarted = AtomicBoolean(false)
    private val pcmBank = ConcurrentHashMap<SoundCacheKey, ShortArray>()
    private val scheduledDiskWrites = ConcurrentHashMap.newKeySet<SoundCacheKey>()
    private val lastStrikeBySignature = ConcurrentHashMap<Long, Long>()
    private val lastHapticNs = AtomicLong(0L)
    private val comboEscalator = ComboPitchEscalator()

    private val audioManager by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching { appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()
    }
    private val ringerModeGate = RingerModeGate {
        resolveRingerMode(audioManager)
    }
    private val hapticDriver = AsyncHapticDriver(appContext)
    private val audioDriver = StreamingKeyAudioDriver(appContext, ::loadOrSynthesizePcm)

    fun attachFallbackView(view: View?) {
        fallbackViewRef.set(view?.let(::WeakReference))
    }

    fun onKeyPress(key: FeedbackKey, options: KeyFeedbackOptions) {
        if (released.get()) return
        val profile = KeySoundSynthesizer.classifyKey(key)
        if (options.hapticEnabled && options.hapticStrength > 0) {
            triggerHaptic(profile = profile, strength = options.hapticStrength, minGapNs = 8_000_000L)
        }
        if (options.soundEnabled) {
            val resolved = KeySoundSynthesizer.resolveSoundTrigger(
                key = key,
                soundStyle = options.soundStyle,
                bandConfig = options.bandConfig
            )
            triggerSound(
                SoundTriggerSpec(
                    slot = resolved.slot,
                    style = resolved.style,
                    volume = options.soundVolume,
                    baseRate = resolved.rate,
                    minGapNs = 1_500_000L,
                    useComboEscalation = resolved.style == KeySoundStyle.ARCADE_COMBO
                )
            )
        }
    }

    fun onKeyRepeat(key: FeedbackKey, options: KeyFeedbackOptions) {
        if (released.get()) return
        if (options.hapticEnabled && options.hapticStrength > 0) {
            triggerHaptic(
                profile = KeyFeedbackProfile.MODIFIER,
                strength = (options.hapticStrength - 1).coerceAtLeast(1),
                minGapNs = 30_000_000L
            )
        }
        if (options.soundEnabled) {
            val effectiveStyle = if (options.soundStyle.isBandMode) {
                options.bandConfig.styleFor(KeySoundSynthesizer.classifyInstrumentZone(key))
            } else {
                options.soundStyle
            }
            val slot = if (effectiveStyle.isMelodyInstrument) KeySoundSlot.MODIFIER_LIGHT else KeySoundSlot.MODIFIER
            val isBackspace = key.zone == FeedbackZone.BACKSPACE || key.action is KeyAction.Backspace
            triggerSound(
                SoundTriggerSpec(
                    slot = slot,
                    style = effectiveStyle,
                    volume = (options.soundVolume * 0.55f).coerceIn(0.04f, 1.0f),
                    baseRate = if (isBackspace) 0.96f else 1.02f,
                    minGapNs = 30_000_000L
                )
            )
        }
    }

    fun playPreview(
        style: KeySoundStyle,
        volume: Float = KeyFeedbackOptions.DEFAULT_SOUND_VOLUME,
        slot: KeySoundSlot = KeySoundSlot.ALPHA,
        bandConfig: BandInstrumentConfig = BandInstrumentConfig(),
        previewStep: Int = 0
    ) {
        if (released.get()) return
        ringerModeGate.invalidate()
        if (style.isBandMode) {
            playBandPreview(volume = volume, bandConfig = bandConfig, previewStep = previewStep)
            return
        }
        val (targetSlot, targetRate) = resolvePreviewTarget(
            style = style,
            slot = slot,
            previewStep = previewStep
        )
        triggerSound(
            SoundTriggerSpec(
                slot = targetSlot,
                style = style,
                volume = volume,
                baseRate = targetRate,
                minGapNs = 10_000_000L
            )
        )
    }

    fun previewHapticStrength(strength: Int) {
        val clean = KeyFeedbackOptions.clampHapticStrength(strength)
        if (clean <= 0 || released.get()) return
        lastHapticNs.set(System.nanoTime())
        if (!hapticDriver.trigger(profile = KeyFeedbackProfile.ALPHA, strength = clean)) {
            performFallbackViewHaptic()
        }
    }

    /**
     * Signals that the keyboard window is hiding (`onWindowHidden` / `onFinishInputView`):
     * invalidates the cached ringer mode and releases `AudioTrack` cleanly after any active silent tail.
     */
    fun onInputHidden() {
        ringerModeGate.invalidate()
        comboEscalator.reset()
        audioDriver.releaseAfterIdleTail()
    }

    fun release() {
        if (released.compareAndSet(false, true)) {
            ringerModeGate.invalidate()
            ioScope.cancel()
            audioDriver.release()
            hapticDriver.release()
            pcmBank.clear()
            fallbackViewRef.set(null)
        }
    }

    private fun playBandPreview(volume: Float, bandConfig: BandInstrumentConfig, previewStep: Int) {
        val zones = KeyInstrumentZone.entries
        val zone = zones[previewStep.mod(zones.size)]
        val zoneStyle = bandConfig.styleFor(zone)
        val zoneStep = when (zone) {
            KeyInstrumentZone.LETTER -> 2
            KeyInstrumentZone.NUMBER -> 4
            KeyInstrumentZone.SYMBOL -> 6
            KeyInstrumentZone.FUNCTION -> 3
        }
        val zoneSlot = when {
            zone != KeyInstrumentZone.FUNCTION -> KeySoundSlot.ALPHA
            zoneStyle == KeySoundStyle.ACOUSTIC_DRUM_KIT -> KeySoundSlot.ENTER_HEAVY
            zoneStyle.isMelodyInstrument -> KeySoundSlot.ENTER_CHORD
            else -> KeySoundSlot.ALPHA
        }
        playPreview(
            style = zoneStyle,
            volume = volume,
            slot = zoneSlot,
            bandConfig = bandConfig,
            previewStep = zoneStep
        )
    }

    private fun resolvePreviewTarget(
        style: KeySoundStyle,
        slot: KeySoundSlot,
        previewStep: Int
    ): Pair<KeySoundSlot, Float> = when {
        style.isMelodyInstrument && slot == KeySoundSlot.ALPHA -> {
            val scale = KeySoundSynthesizer.scaleForStyle(style) ?: KeyMusicalScale.MAJOR
            val arpeggio = if (scale == KeyMusicalScale.PENTATONIC) {
                PENTATONIC_PREVIEW_DEGREES
            } else {
                MAJOR_PREVIEW_DEGREES
            }
            val deg = arpeggio[previewStep.mod(arpeggio.size)]
            val pitch = KeySoundSynthesizer.resolvePitchForDegree(deg, style)
            pitch.slot to pitch.rate
        }
        style == KeySoundStyle.ACOUSTIC_DRUM_KIT && slot == KeySoundSlot.ALPHA -> {
            DRUM_PREVIEW_SLOTS[previewStep.mod(DRUM_PREVIEW_SLOTS.size)] to 1.0f
        }
        else -> slot to 1.0f
    }

    private fun triggerSound(request: SoundTriggerSpec) {
        if (released.get()) return
        val nowNs = System.nanoTime()
        if (!ringerModeGate.isSoundAllowed(nowNs / NANOS_PER_MILLI)) return
        ensureSoundBankPreload(request.style)

        val coercedSlot = KeySoundPitchRouter.coerceSlotForStyle(request.slot, request.style)
        val signature = buildStrikeSignature(request.style, coercedSlot, request.baseRate)
        val prevForSig = lastStrikeBySignature[signature]
        if (prevForSig != null && (nowNs - prevForSig) < request.minGapNs) return
        if (lastStrikeBySignature.size >= MAX_SIGNATURE_ENTRIES) {
            lastStrikeBySignature.clear()
        }
        lastStrikeBySignature[signature] = nowNs

        val effectiveRate = if (request.useComboEscalation && request.style == KeySoundStyle.ARCADE_COMBO) {
            comboEscalator.nextRate(nowNs = nowNs, style = request.style, slot = coercedSlot)
        } else {
            if (request.style != KeySoundStyle.ARCADE_COMBO) {
                comboEscalator.reset()
            }
            request.baseRate
        }
        val gain = KeyFeedbackOptions.clampSoundVolume(request.volume)
        audioDriver.enqueue(
            style = request.style,
            slot = coercedSlot,
            gain = gain,
            rate = effectiveRate
        )
    }

    private fun ensureSoundBankPreload(priorityStyle: KeySoundStyle) {
        if (!preloadStarted.compareAndSet(false, true)) return
        ioScope.launch {
            preloadSoundBank(priorityStyle)
        }
    }

    private fun preloadSoundBank(priorityStyle: KeySoundStyle) {
        runCatching {
            cacheDir.mkdirs()
            cleanupTempCacheFiles(cacheDir)
            val orderedStyles = buildList {
                if (!priorityStyle.isBandMode) add(priorityStyle)
                KeySoundStyle.entries.forEach { if (it != priorityStyle) add(it) }
            }
            for (style in orderedStyles) {
                for (slot in KeySoundSynthesizer.slotsForStyle(style)) {
                    if (released.get()) return
                    preloadSlot(style, slot)
                }
            }
        }
    }

    private fun preloadSlot(style: KeySoundStyle, slot: KeySoundSlot) {
        val coercedSlot = KeySoundPitchRouter.coerceSlotForStyle(slot, style)
        val key = SoundCacheKey(style, coercedSlot)
        val wavFile = wavFileFor(style, coercedSlot)
        val existingPcm = pcmBank[key]
        if (existingPcm != null) {
            if (!KeySoundSynthesizer.isValidCachedWavFile(wavFile)) {
                KeySoundSynthesizer.writeWavAtomically(wavFile, KeySoundSynthesizer.encodePcm16ToWav(existingPcm))
            }
            return
        }
        val diskPcm = KeySoundSynthesizer.decodeWavPcm16OrNull(wavFile)
        val pcm = diskPcm ?: KeySoundSynthesizer.synthesizePcm16(coercedSlot, style).also { fresh ->
            KeySoundSynthesizer.writeWavAtomically(wavFile, KeySoundSynthesizer.encodePcm16ToWav(fresh))
        }
        pcmBank.putIfAbsent(key, pcm)
    }

    private fun loadOrSynthesizePcm(style: KeySoundStyle, slot: KeySoundSlot): ShortArray {
        val coercedSlot = KeySoundPitchRouter.coerceSlotForStyle(slot, style)
        val key = SoundCacheKey(style, coercedSlot)
        pcmBank[key]?.let { return it }

        val wavFile = wavFileFor(style, coercedSlot)
        val diskPcm = KeySoundSynthesizer.decodeWavPcm16OrNull(wavFile)
        val resolved = if (diskPcm != null) {
            diskPcm
        } else {
            val fresh = KeySoundSynthesizer.synthesizePcm16(coercedSlot, style)
            scheduleWavCacheWrite(wavFile, fresh, key)
            fresh
        }
        return pcmBank.putIfAbsent(key, resolved) ?: resolved
    }

    private fun scheduleWavCacheWrite(wavFile: File, pcm: ShortArray, key: SoundCacheKey) {
        if (released.get() || !scheduledDiskWrites.add(key)) return
        ioScope.launch {
            runCatching {
                if (!released.get()) {
                    cacheDir.mkdirs()
                    KeySoundSynthesizer.writeWavAtomically(wavFile, KeySoundSynthesizer.encodePcm16ToWav(pcm))
                }
            }
        }
    }

    private fun wavFileFor(style: KeySoundStyle, slot: KeySoundSlot): File =
        File(cacheDir, "${style.name.lowercase()}_${slot.name.lowercase()}.wav")

    private fun cleanupTempCacheFiles(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".tmp")) {
                file.delete()
            }
        }
    }

    private fun buildStrikeSignature(style: KeySoundStyle, slot: KeySoundSlot, baseRate: Float): Long {
        val styleBits = style.ordinal.toLong() shl 40
        val slotBits = slot.ordinal.toLong() shl 32
        val rateBits = baseRate.toRawBits().toLong() and UINT32_MASK
        return styleBits or slotBits or rateBits
    }

    private fun triggerHaptic(profile: KeyFeedbackProfile, strength: Int, minGapNs: Long) {
        if (strength <= 0) return
        val now = System.nanoTime()
        val prev = lastHapticNs.get()
        if (now - prev < minGapNs || !lastHapticNs.compareAndSet(prev, now)) return

        if (!hapticDriver.trigger(profile = profile, strength = strength)) {
            performFallbackViewHaptic()
        }
    }

    private fun performFallbackViewHaptic() {
        val view = fallbackViewRef.get()?.get() ?: return
        runCatching {
            view.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        }
    }

    companion object {
        private const val CACHE_DIR_NAME = "duoime_key_sfx_v1"
        private const val MAX_SIGNATURE_ENTRIES = 256
        private const val UINT32_MASK = 0xFFFF_FFFFL
        private const val NANOS_PER_MILLI = 1_000_000L
        private val PENTATONIC_PREVIEW_DEGREES = intArrayOf(5, 6, 7, 8, 9, 10, 11, 12)
        private val MAJOR_PREVIEW_DEGREES = intArrayOf(7, 9, 11, 14, 16, 18, 16, 14)
        private val DRUM_PREVIEW_SLOTS = listOf(
            KeySoundSlot.STABILIZED,
            KeySoundSlot.DRUM_RIMSHOT,
            KeySoundSlot.ALPHA,
            KeySoundSlot.ENTER_HEAVY
        )
    }
}

@Composable
fun rememberKeyFeedbackController(): KeyFeedbackController {
    val context = LocalContext.current
    val view = LocalView.current
    val controller = remember(context) { KeyFeedbackController(context, view) }
    SideEffect {
        controller.attachFallbackView(view)
    }
    DisposableEffect(controller) {
        onDispose {
            controller.release()
        }
    }
    return controller
}

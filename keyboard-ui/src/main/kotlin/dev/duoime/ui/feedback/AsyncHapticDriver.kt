/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME
 */
package dev.duoime.ui.feedback

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

private data class PendingHapticRequest(
    val profileIndex: Int,
    val strengthIndex: Int
)

/**
 * Offloads all `Vibrator` Binder queries and `vibrate()` IPCs onto a single lazily started daemon
 * thread (`DuoIME-KeyHaptic`) with pre-cached `3 x 5` [VibrationEffect] entries so typing on the
 * Main UI thread executes zero Binder calls.
 *
 * Checks `Manifest.permission.VIBRATE` at runtime rather than declaring it in the library manifest;
 * when an embed host lacks `VIBRATE`, [trigger] returns `false` immediately so
 * [KeyFeedbackController] falls back cleanly to `View.performHapticFeedback`.
 */
internal class AsyncHapticDriver(context: Context) {
    private val appContext = context.applicationContext
    private val pendingRequest = AtomicReference<PendingHapticRequest?>(null)
    private val released = AtomicBoolean(false)
    private val hardwareAvailable = AtomicBoolean(true)
    private val workerLock = Any()

    @Volatile
    private var permissionChecked: Boolean = false

    @Volatile
    private var hasVibratePermission: Boolean = false

    @Volatile
    private var workerThread: Thread? = null

    fun trigger(profile: KeyFeedbackProfile, strength: Int): Boolean {
        if (released.get() || !hardwareAvailable.get() || strength <= 0) return false
        if (!checkVibratePermission()) return false
        val thread = ensureWorkerStarted() ?: return false
        pendingRequest.set(
            PendingHapticRequest(
                profileIndex = profile.ordinal.coerceIn(0, PROFILE_COUNT - 1),
                strengthIndex = (strength.coerceIn(1, STRENGTH_COUNT) - 1)
            )
        )
        LockSupport.unpark(thread)
        return true
    }

    fun release() {
        if (released.compareAndSet(false, true)) {
            pendingRequest.set(null)
            LockSupport.unpark(workerThread)
        }
    }

    private fun checkVibratePermission(): Boolean {
        if (permissionChecked) return hasVibratePermission
        val granted = runCatching {
            appContext.checkSelfPermission(Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        hasVibratePermission = granted
        permissionChecked = true
        return granted
    }

    private fun ensureWorkerStarted(): Thread? {
        workerThread?.let { return it }
        return synchronized(workerLock) {
            if (released.get()) return null
            workerThread ?: Thread(::runHapticLoop, HAPTIC_THREAD_NAME).apply {
                isDaemon = true
                workerThread = this
                start()
            }
        }
    }

    private fun runHapticLoop() {
        if (!checkVibratePermission()) {
            hardwareAvailable.set(false)
            pendingRequest.set(null)
            return
        }
        val vib = resolveVibrator(appContext)
        if (vib == null || runCatching { vib.hasVibrator() }.getOrDefault(false).not()) {
            hardwareAvailable.set(false)
            pendingRequest.set(null)
            return
        }
        val effectsTable = buildEffectTable(vib)
        while (!released.get()) {
            val req = pendingRequest.getAndSet(null)
            if (req == null) {
                LockSupport.park()
                continue
            }
            val effect = effectsTable[req.profileIndex][req.strengthIndex]
            vibrateSafely(vib, effect)
        }
    }

    // Permission is explicitly verified in checkVibratePermission() before reaching this call;
    // :keyboard-ui intentionally omits VIBRATE from its library manifest so embed hosts opt in.
    @SuppressLint("MissingPermission")
    private fun vibrateSafely(vib: Vibrator, effect: VibrationEffect) {
        if (appContext.checkSelfPermission(Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) {
            hardwareAvailable.set(false)
            return
        }
        runCatching { vib.vibrate(effect) }
    }

    private fun resolveVibrator(context: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    private fun buildEffectTable(vib: Vibrator): Array<Array<VibrationEffect>> {
        val probe = probeMotorCapabilities(vib)
        val tier = HapticEffectPlanner.resolveMotorTier(probe)
        return Array(PROFILE_COUNT) { pIdx ->
            val profile = KeyFeedbackProfile.entries[pIdx]
            Array(STRENGTH_COUNT) { sIdx ->
                buildSingleEffect(
                    profile = profile,
                    strength = sIdx + 1,
                    tier = tier,
                    supportsLowTick = probe.supportsLowTickPrimitive
                )
            }
        }
    }

    private fun probeMotorCapabilities(vib: Vibrator): HapticCapabilityProbe {
        val hasAmplitude = runCatching { vib.hasAmplitudeControl() }.getOrDefault(false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return HapticCapabilityProbe(
                sdkInt = Build.VERSION.SDK_INT,
                supportsTickAndClickPrimitives = false,
                supportsLowTickPrimitive = false,
                minPrimitiveDurationMs = 0,
                hasAmplitudeControl = hasAmplitude
            )
        }
        return probeApi31MotorCapabilities(vib, hasAmplitude)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun probeApi31MotorCapabilities(vib: Vibrator, hasAmplitude: Boolean): HapticCapabilityProbe {
        val tickClickSupported = runCatching {
            vib.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_TICK,
                VibrationEffect.Composition.PRIMITIVE_CLICK
            )
        }.getOrDefault(false)
        val minTickClickDuration = if (tickClickSupported) {
            runCatching {
                vib.getPrimitiveDurations(
                    VibrationEffect.Composition.PRIMITIVE_TICK,
                    VibrationEffect.Composition.PRIMITIVE_CLICK
                ).minOrNull() ?: 0
            }.getOrDefault(0)
        } else {
            0
        }
        val lowTickDurationOk = if (minTickClickDuration > 0) {
            val lowSupported = runCatching {
                vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
            }.getOrDefault(false)
            lowSupported && runCatching {
                (vib.getPrimitiveDurations(VibrationEffect.Composition.PRIMITIVE_LOW_TICK).firstOrNull() ?: 0) > 0
            }.getOrDefault(false)
        } else {
            false
        }
        return HapticCapabilityProbe(
            sdkInt = Build.VERSION.SDK_INT,
            supportsTickAndClickPrimitives = tickClickSupported,
            supportsLowTickPrimitive = lowTickDurationOk,
            minPrimitiveDurationMs = minTickClickDuration,
            hasAmplitudeControl = hasAmplitude
        )
    }

    private fun buildSingleEffect(
        profile: KeyFeedbackProfile,
        strength: Int,
        tier: HapticMotorTier,
        supportsLowTick: Boolean
    ): VibrationEffect = when (tier) {
        HapticMotorTier.PRIMITIVES_API31 -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val spec = HapticEffectPlanner.resolvePrimitiveSpec(
                    profile = profile,
                    strength = strength,
                    supportsLowTick = supportsLowTick
                )
                VibrationEffect.startComposition()
                    .addPrimitive(spec.primitiveId, spec.scale)
                    .compose()
            } else {
                VibrationEffect.createOneShot(
                    HapticEffectPlanner.resolveDurationOneShotMs(profile, strength),
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            }
        }
        HapticMotorTier.AMPLITUDE_ONESHOT -> {
            val spec = HapticEffectPlanner.resolveAmplitudeOneShotSpec(profile, strength)
            VibrationEffect.createOneShot(spec.durationMs, spec.amplitude)
        }
        HapticMotorTier.DURATION_ONESHOT -> {
            val ms = HapticEffectPlanner.resolveDurationOneShotMs(profile, strength)
            VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    companion object {
        private const val HAPTIC_THREAD_NAME = "DuoIME-KeyHaptic"
        private const val PROFILE_COUNT = 3
        private const val STRENGTH_COUNT = HapticEffectPlanner.MAX_STRENGTH
    }
}

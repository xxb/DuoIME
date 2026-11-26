// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.settings

import android.content.Context
import android.content.SharedPreferences
import dev.duoime.core.policy.DisplayPlacementPolicy
import dev.duoime.ui.feedback.KeyFeedbackOptions
import dev.duoime.ui.feedback.KeySoundStyle
import kotlin.math.roundToInt

/**
 * Persisted user preferences for DuoIME stored strictly in app-private storage (`allowBackup=false`).
 * Defaults keep acoustic key sounds OFF and haptic feedback ON.
 */
data class DuoImeSettings(
    val darkTheme: Boolean = true,
    val includeNumberRow: Boolean = false,
    val alwaysIncognito: Boolean = false,
    val heightFraction: Float = DisplayPlacementPolicy.DEFAULT_HEIGHT_FRACTION,
    val swapToSameScreen: Boolean = false,
    val pairedPeerDisplayId: Int? = null,
    val manualSystemImePin: Boolean = false,
    val perAppInlinePackages: Set<String> = emptySet(),
    val hapticEnabled: Boolean = true,
    val hapticStrength: Int = KeyFeedbackOptions.DEFAULT_HAPTIC_STRENGTH,
    val soundEnabled: Boolean = false,
    val soundStyle: KeySoundStyle = KeySoundStyle.MECHANICAL,
    val soundVolume: Float = KeyFeedbackOptions.DEFAULT_SOUND_VOLUME,
    val followSystemRingerMode: Boolean = false
) {
    fun toFeedbackOptions(): KeyFeedbackOptions = KeyFeedbackOptions(
        hapticEnabled = hapticEnabled,
        hapticStrength = KeyFeedbackOptions.clampHapticStrength(hapticStrength),
        soundEnabled = soundEnabled,
        soundVolume = KeyFeedbackOptions.clampSoundVolume(soundVolume),
        soundStyle = soundStyle,
        followSystemRingerMode = followSystemRingerMode
    )
}

enum class LiveSettingsPlacementAction {
    NONE,
    SWAP_TOGGLED,
    RELEVANT_ENVIRONMENT_CHANGED
}

internal fun resolveLiveSettingsAction(previous: DuoImeSettings, updated: DuoImeSettings): LiveSettingsPlacementAction =
    when {
        updated == previous -> LiveSettingsPlacementAction.NONE
        updated.swapToSameScreen != previous.swapToSameScreen -> LiveSettingsPlacementAction.SWAP_TOGGLED
        updated.heightFraction != previous.heightFraction ||
            updated.darkTheme != previous.darkTheme ||
            updated.pairedPeerDisplayId != previous.pairedPeerDisplayId ||
            updated.manualSystemImePin != previous.manualSystemImePin ||
            updated.perAppInlinePackages != previous.perAppInlinePackages ->
            LiveSettingsPlacementAction.RELEVANT_ENVIRONMENT_CHANGED
        else -> LiveSettingsPlacementAction.NONE
    }

class DuoImePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): DuoImeSettings {
        val rawPeerId = prefs.getInt(KEY_PAIRED_PEER_DISPLAY_ID, NO_PAIRED_DISPLAY)
        val packages = prefs.getStringSet(KEY_PER_APP_INLINE_PACKAGES, emptySet())
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
        return DuoImeSettings(
            darkTheme = prefs.getBoolean(KEY_DARK_THEME, true),
            includeNumberRow = prefs.getBoolean(KEY_NUMBER_ROW, false),
            alwaysIncognito = prefs.getBoolean(KEY_ALWAYS_INCOGNITO, false),
            heightFraction = DisplayPlacementPolicy.clampHeightFraction(
                prefs.getFloat(KEY_HEIGHT_FRACTION, DisplayPlacementPolicy.DEFAULT_HEIGHT_FRACTION)
            ),
            swapToSameScreen = prefs.getBoolean(KEY_SWAP_TO_SAME_SCREEN, false),
            pairedPeerDisplayId = rawPeerId.takeIf { it != NO_PAIRED_DISPLAY },
            manualSystemImePin = prefs.getBoolean(KEY_MANUAL_SYSTEM_IME_PIN, false),
            perAppInlinePackages = packages,
            hapticEnabled = prefs.getBoolean(KEY_HAPTIC_ENABLED, true),
            hapticStrength = KeyFeedbackOptions.clampHapticStrength(
                prefs.getInt(KEY_HAPTIC_STRENGTH, KeyFeedbackOptions.DEFAULT_HAPTIC_STRENGTH)
            ),
            soundEnabled = prefs.getBoolean(KEY_SOUND_ENABLED, false),
            soundStyle = KeySoundStyle.fromNameOrDefault(prefs.getString(KEY_SOUND_STYLE, null)),
            soundVolume = KeyFeedbackOptions.clampSoundVolume(
                prefs.getFloat(KEY_SOUND_VOLUME, KeyFeedbackOptions.DEFAULT_SOUND_VOLUME)
            ),
            followSystemRingerMode = prefs.getBoolean(KEY_FOLLOW_SYSTEM_RINGER_MODE, false)
        )
    }

    fun save(settings: DuoImeSettings) {
        val sanitizedPackages = settings.perAppInlinePackages
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        prefs.edit()
            .putBoolean(KEY_DARK_THEME, settings.darkTheme)
            .putBoolean(KEY_NUMBER_ROW, settings.includeNumberRow)
            .putBoolean(KEY_ALWAYS_INCOGNITO, settings.alwaysIncognito)
            .putFloat(KEY_HEIGHT_FRACTION, DisplayPlacementPolicy.clampHeightFraction(settings.heightFraction))
            .putBoolean(KEY_SWAP_TO_SAME_SCREEN, settings.swapToSameScreen)
            .putInt(KEY_PAIRED_PEER_DISPLAY_ID, settings.pairedPeerDisplayId ?: NO_PAIRED_DISPLAY)
            .putBoolean(KEY_MANUAL_SYSTEM_IME_PIN, settings.manualSystemImePin)
            .putStringSet(KEY_PER_APP_INLINE_PACKAGES, sanitizedPackages)
            .putBoolean(KEY_HAPTIC_ENABLED, settings.hapticEnabled)
            .putInt(KEY_HAPTIC_STRENGTH, KeyFeedbackOptions.clampHapticStrength(settings.hapticStrength))
            .putBoolean(KEY_SOUND_ENABLED, settings.soundEnabled)
            .putString(KEY_SOUND_STYLE, settings.soundStyle.name)
            .putFloat(KEY_SOUND_VOLUME, KeyFeedbackOptions.clampSoundVolume(settings.soundVolume))
            .putBoolean(KEY_FOLLOW_SYSTEM_RINGER_MODE, settings.followSystemRingerMode)
            .apply()
    }

    fun updateHeightFraction(fraction: Float): DuoImeSettings {
        val updated = load().copy(heightFraction = DisplayPlacementPolicy.clampHeightFraction(fraction))
        save(updated)
        return updated
    }

    fun toggleSwapToSameScreen(): DuoImeSettings {
        val current = load()
        val updated = current.copy(swapToSameScreen = !current.swapToSameScreen)
        save(updated)
        return updated
    }

    /**
     * Observes persisted preference updates across components in the same process.
     *
     * Retains the underlying [SharedPreferences.OnSharedPreferenceChangeListener] strongly inside
     * the returned [AutoCloseable] handle so Android's internal `WeakHashMap` cannot GC it early.
     */
    fun observe(onChanged: (DuoImeSettings) -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            onChanged(load())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    companion object {
        private const val PREFS_NAME = "duoime_settings"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_NUMBER_ROW = "include_number_row"
        private const val KEY_ALWAYS_INCOGNITO = "always_incognito"
        private const val KEY_HEIGHT_FRACTION = "height_fraction"
        private const val KEY_SWAP_TO_SAME_SCREEN = "swap_to_same_screen"
        private const val KEY_PAIRED_PEER_DISPLAY_ID = "paired_peer_display_id"
        private const val KEY_MANUAL_SYSTEM_IME_PIN = "manual_system_ime_pin"
        private const val KEY_PER_APP_INLINE_PACKAGES = "per_app_inline_packages"
        private const val KEY_HAPTIC_ENABLED = "haptic_enabled"
        private const val KEY_HAPTIC_STRENGTH = "haptic_strength"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_SOUND_STYLE = "sound_style"
        private const val KEY_SOUND_VOLUME = "sound_volume"
        private const val KEY_FOLLOW_SYSTEM_RINGER_MODE = "follow_system_ringer_mode"
        private const val NO_PAIRED_DISPLAY = -1

        /**
         * Fraction of the single-screen window height allocated to the Inline keyboard at 100%
         * preset scale so roughly half of the display stays visible for the focused editor.
         * Combined with [dev.duoime.embed.KeyboardController.HEIGHT_PRESETS] (`60%..90%`), inline
         * heights span roughly `31%..47%` of the window while opposite-screen overlays use `60%..90%`.
         */
        const val INLINE_HEIGHT_SCALE: Float = 0.52f

        fun inlineHeightPercentRange(presets: List<Float>): IntRange {
            val minPreset = presets.minOrNull() ?: DisplayPlacementPolicy.MIN_HEIGHT_FRACTION
            val maxPreset = presets.maxOrNull() ?: DisplayPlacementPolicy.MAX_HEIGHT_FRACTION
            val minPct = (minPreset * INLINE_HEIGHT_SCALE * 100f).roundToInt()
            val maxPct = (maxPreset * INLINE_HEIGHT_SCALE * 100f).roundToInt()
            return minPct..maxPct
        }

        fun overlayHeightPercentRange(presets: List<Float>): IntRange {
            val minPreset = presets.minOrNull() ?: DisplayPlacementPolicy.MIN_HEIGHT_FRACTION
            val maxPreset = presets.maxOrNull() ?: DisplayPlacementPolicy.MAX_HEIGHT_FRACTION
            return (minPreset * 100f).roundToInt()..(maxPreset * 100f).roundToInt()
        }

        /**
         * Computes a proportional inline keyboard height in pixels that scales with [heightFraction]
         * (`60%–90%`) by [INLINE_HEIGHT_SCALE] while keeping roughly half the window visible for
         * the focused editor across portrait, landscape, and near-square displays.
         */
        fun computeInlineKeyboardHeightPx(windowHeightPx: Int, heightFraction: Float, density: Float): Int {
            val safeDensity = density.coerceAtLeast(1f)
            val clampedFraction = DisplayPlacementPolicy.clampHeightFraction(heightFraction)
            val fallbackHeight = (280f * safeDensity).roundToInt()
            if (windowHeightPx <= 0) return fallbackHeight

            val maxInlinePx = (windowHeightPx * INLINE_HEIGHT_SCALE).roundToInt().coerceAtLeast(1)
            val minInlinePx = minOf(
                (180f * safeDensity).roundToInt(),
                (windowHeightPx * 0.34f).roundToInt().coerceAtLeast(1)
            )
            val desiredPx = (windowHeightPx * (clampedFraction * INLINE_HEIGHT_SCALE)).roundToInt()
            val capPx = minOf((420f * safeDensity).roundToInt(), maxInlinePx)
            return desiredPx.coerceIn(minInlinePx, capPx)
        }
    }
}

// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.settings

import android.content.Context
import android.content.SharedPreferences
import dev.duoime.core.policy.DisplayPlacementPolicy
import kotlin.math.roundToInt

/**
 * Persisted user preferences for DuoIME stored strictly in app-private storage (`allowBackup=false`).
 */
data class DuoImeSettings(
    val darkTheme: Boolean = true,
    val includeNumberRow: Boolean = false,
    val alwaysIncognito: Boolean = false,
    val heightFraction: Float = DisplayPlacementPolicy.DEFAULT_HEIGHT_FRACTION,
    val swapToSameScreen: Boolean = false,
    val pairedPeerDisplayId: Int? = null,
    val manualSystemImePin: Boolean = false,
    val perAppInlinePackages: Set<String> = emptySet()
)

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
            perAppInlinePackages = packages
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

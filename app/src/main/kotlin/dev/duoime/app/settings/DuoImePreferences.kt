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
    val heightFraction: Float = DisplayPlacementPolicy.DEFAULT_HEIGHT_FRACTION
)

class DuoImePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): DuoImeSettings = DuoImeSettings(
        darkTheme = prefs.getBoolean(KEY_DARK_THEME, true),
        includeNumberRow = prefs.getBoolean(KEY_NUMBER_ROW, false),
        alwaysIncognito = prefs.getBoolean(KEY_ALWAYS_INCOGNITO, false),
        heightFraction = DisplayPlacementPolicy.clampHeightFraction(
            prefs.getFloat(KEY_HEIGHT_FRACTION, DisplayPlacementPolicy.DEFAULT_HEIGHT_FRACTION)
        )
    )

    fun save(settings: DuoImeSettings) {
        prefs.edit()
            .putBoolean(KEY_DARK_THEME, settings.darkTheme)
            .putBoolean(KEY_NUMBER_ROW, settings.includeNumberRow)
            .putBoolean(KEY_ALWAYS_INCOGNITO, settings.alwaysIncognito)
            .putFloat(KEY_HEIGHT_FRACTION, DisplayPlacementPolicy.clampHeightFraction(settings.heightFraction))
            .apply()
    }

    fun updateHeightFraction(fraction: Float): DuoImeSettings {
        val updated = load().copy(heightFraction = DisplayPlacementPolicy.clampHeightFraction(fraction))
        save(updated)
        return updated
    }

    companion object {
        private const val PREFS_NAME = "duoime_settings"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_NUMBER_ROW = "include_number_row"
        private const val KEY_ALWAYS_INCOGNITO = "always_incognito"
        private const val KEY_HEIGHT_FRACTION = "height_fraction"

        /**
         * Computes a proportional inline keyboard height in pixels that scales with [heightFraction]
         * (60%–90%) while keeping at least ~48% of the window visible for the focused editor across
         * portrait, landscape, and near-square displays.
         */
        fun computeInlineKeyboardHeightPx(windowHeightPx: Int, heightFraction: Float, density: Float): Int {
            val safeDensity = density.coerceAtLeast(1f)
            val clampedFraction = DisplayPlacementPolicy.clampHeightFraction(heightFraction)
            val fallbackHeight = (280f * safeDensity).roundToInt()
            if (windowHeightPx <= 0) return fallbackHeight

            val maxInlinePx = (windowHeightPx * 0.52f).roundToInt().coerceAtLeast(1)
            val minInlinePx = minOf(
                (180f * safeDensity).roundToInt(),
                (windowHeightPx * 0.34f).roundToInt().coerceAtLeast(1)
            )
            val desiredPx = (windowHeightPx * (clampedFraction * 0.52f)).roundToInt()
            val capPx = minOf((420f * safeDensity).roundToInt(), maxInlinePx)
            return desiredPx.coerceIn(minInlinePx, capPx)
        }
    }
}

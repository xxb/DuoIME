// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.dualscreen

import android.util.DisplayMetrics
import android.view.Display

/**
 * Computes a physical-DPI anchored Compose density for keyboard surfaces so `wm density` overrides
 * (smaller-UI user tweaks) never shrink key or candidate touch targets below physical comfort,
 * while remaining orientation-invariant under `wm size` resolution overrides.
 */
object PhysicalDisplayMetrics {
    const val MIN_DENSITY: Float = 1.0f
    const val MAX_DENSITY: Float = 4.5f
    const val MIN_VALID_DPI: Float = 120f
    const val MAX_VALID_DPI: Float = 800f
    const val MIN_DPI_ASPECT_RATIO: Float = 0.75f
    const val MIN_PIXEL_SCALE: Float = 0.25f
    const val MAX_PIXEL_SCALE: Float = 2.0f
    private const val BASELINE_DPI: Float = 160f

    @Suppress("LongParameterList")
    fun computePhysicalDensity(
        logicalDensity: Float,
        xdpi: Float,
        ydpi: Float,
        currentWidthPx: Int = 0,
        currentHeightPx: Int = 0,
        physicalWidthPx: Int = 0,
        physicalHeightPx: Int = 0
    ): Float {
        val fallback = if (logicalDensity.isFinite()) {
            logicalDensity.coerceIn(MIN_DENSITY, MAX_DENSITY)
        } else {
            MIN_DENSITY
        }
        if (!xdpi.isFinite() || !ydpi.isFinite()) return fallback
        if (xdpi !in MIN_VALID_DPI..MAX_VALID_DPI || ydpi !in MIN_VALID_DPI..MAX_VALID_DPI) {
            return fallback
        }
        val minDpi = minOf(xdpi, ydpi)
        val maxDpi = maxOf(xdpi, ydpi)
        if (maxDpi <= 0f || (minDpi / maxDpi) < MIN_DPI_ASPECT_RATIO) {
            return fallback
        }
        val pixelScale = if (
            currentWidthPx > 0 && currentHeightPx > 0 && physicalWidthPx > 0 && physicalHeightPx > 0
        ) {
            val curLong = maxOf(currentWidthPx, currentHeightPx).toFloat()
            val curShort = minOf(currentWidthPx, currentHeightPx).toFloat()
            val physLong = maxOf(physicalWidthPx, physicalHeightPx).toFloat()
            val physShort = minOf(physicalWidthPx, physicalHeightPx).toFloat()
            (((curLong / physLong) + (curShort / physShort)) * 0.5f)
                .coerceIn(MIN_PIXEL_SCALE, MAX_PIXEL_SCALE)
        } else {
            1.0f
        }
        return (((xdpi + ydpi) * 0.5f * pixelScale) / BASELINE_DPI).coerceIn(MIN_DENSITY, MAX_DENSITY)
    }

    @Suppress("LongParameterList")
    internal fun resolveFromModeSize(
        logicalDensity: Float,
        xdpi: Float,
        ydpi: Float,
        currentWidthPx: Int,
        currentHeightPx: Int,
        modePhysicalWidthPx: Int?,
        modePhysicalHeightPx: Int?
    ): Float = computePhysicalDensity(
        logicalDensity = logicalDensity,
        xdpi = xdpi,
        ydpi = ydpi,
        currentWidthPx = currentWidthPx,
        currentHeightPx = currentHeightPx,
        physicalWidthPx = modePhysicalWidthPx ?: 0,
        physicalHeightPx = modePhysicalHeightPx ?: 0
    )

    fun resolvePhysicalDensity(
        display: Display?,
        displayMetrics: DisplayMetrics,
        currentWidthPx: Int,
        currentHeightPx: Int
    ): Float {
        val mode = runCatching { display?.mode }.getOrNull()
        return resolveFromModeSize(
            logicalDensity = displayMetrics.density,
            xdpi = displayMetrics.xdpi,
            ydpi = displayMetrics.ydpi,
            currentWidthPx = currentWidthPx,
            currentHeightPx = currentHeightPx,
            modePhysicalWidthPx = mode?.physicalWidth,
            modePhysicalHeightPx = mode?.physicalHeight
        )
    }
}

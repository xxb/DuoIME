// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.dualscreen

import org.junit.Assert.assertEquals
import org.junit.Test

class PhysicalDisplayMetricsTest {

    @Test
    fun wmDensityOverrideAnchorsToPhysicalDpi() {
        // Physical 320 dpi panel overridden via `wm density 213` (logicalDensity = 1.33125f)
        val densityDown = PhysicalDisplayMetrics.computePhysicalDensity(
            logicalDensity = 213f / 160f,
            xdpi = 320f,
            ydpi = 320f,
            currentWidthPx = 1280,
            currentHeightPx = 960,
            physicalWidthPx = 1280,
            physicalHeightPx = 960
        )
        assertEquals(2.0f, densityDown, 0.0001f)

        // Same panel overridden via `wm density 480` (logicalDensity = 3.0f) stays anchored at 2.0f
        val densityUp = PhysicalDisplayMetrics.computePhysicalDensity(
            logicalDensity = 3.0f,
            xdpi = 320f,
            ydpi = 320f,
            currentWidthPx = 1280,
            currentHeightPx = 960,
            physicalWidthPx = 1280,
            physicalHeightPx = 960
        )
        assertEquals(2.0f, densityUp, 0.0001f)
    }

    @Test
    fun wmSize720pOverrideIsOrientationInvariantAcrossPortraitAndLandscape() {
        val expected = (400f * (2f / 3f)) / 160f

        val landscape = PhysicalDisplayMetrics.computePhysicalDensity(
            logicalDensity = 2.0f,
            xdpi = 400f,
            ydpi = 400f,
            currentWidthPx = 1280,
            currentHeightPx = 720,
            physicalWidthPx = 1920,
            physicalHeightPx = 1080
        )
        val portrait = PhysicalDisplayMetrics.computePhysicalDensity(
            logicalDensity = 2.0f,
            xdpi = 400f,
            ydpi = 400f,
            currentWidthPx = 720,
            currentHeightPx = 1280,
            physicalWidthPx = 1920,
            physicalHeightPx = 1080
        )
        assertEquals(expected, landscape, 0.001f)
        assertEquals(expected, portrait, 0.001f)
        assertEquals(landscape, portrait, 0.0001f)
    }

    @Test
    fun invalidNonFiniteOutOfRangeOrSkewedDpiFallsBackToClampedLogicalDensity() {
        // NaN / Infinite xdpi or ydpi
        assertEquals(
            2.25f,
            PhysicalDisplayMetrics.computePhysicalDensity(logicalDensity = 2.25f, xdpi = Float.NaN, ydpi = 320f),
            0.0001f
        )
        assertEquals(
            2.25f,
            PhysicalDisplayMetrics.computePhysicalDensity(
                logicalDensity = 2.25f,
                xdpi = 320f,
                ydpi = Float.POSITIVE_INFINITY
            ),
            0.0001f
        )
        // Non-finite logicalDensity falls back to MIN_DENSITY (1.0f)
        assertEquals(
            PhysicalDisplayMetrics.MIN_DENSITY,
            PhysicalDisplayMetrics.computePhysicalDensity(logicalDensity = Float.NaN, xdpi = 0f, ydpi = 0f),
            0.0001f
        )

        // Out-of-range xdpi/ydpi (< 120 or > 800)
        assertEquals(
            1.75f,
            PhysicalDisplayMetrics.computePhysicalDensity(logicalDensity = 1.75f, xdpi = 80f, ydpi = 80f),
            0.0001f
        )
        assertEquals(
            1.75f,
            PhysicalDisplayMetrics.computePhysicalDensity(logicalDensity = 1.75f, xdpi = 900f, ydpi = 900f),
            0.0001f
        )

        // Skewed xdpi/ydpi (ratio < 0.75) falls back, whereas mild skew (>= 0.75) averages cleanly
        assertEquals(
            2.5f,
            PhysicalDisplayMetrics.computePhysicalDensity(logicalDensity = 2.5f, xdpi = 320f, ydpi = 200f),
            0.0001f
        )
        assertEquals(
            300f / 160f,
            PhysicalDisplayMetrics.computePhysicalDensity(logicalDensity = 2.5f, xdpi = 320f, ydpi = 280f),
            0.0001f
        )
    }

    @Test
    fun physicalDensityAndFallbackAlwaysClampToOneThroughFourPointFive() {
        // Extreme low computed density -> 1.0f
        assertEquals(
            PhysicalDisplayMetrics.MIN_DENSITY,
            PhysicalDisplayMetrics.computePhysicalDensity(
                logicalDensity = 0.5f,
                xdpi = 120f,
                ydpi = 120f,
                currentWidthPx = 480,
                currentHeightPx = 320,
                physicalWidthPx = 1920,
                physicalHeightPx = 1280
            ),
            0.0001f
        )

        // Extreme high computed density -> 4.5f
        assertEquals(
            PhysicalDisplayMetrics.MAX_DENSITY,
            PhysicalDisplayMetrics.computePhysicalDensity(
                logicalDensity = 3.0f,
                xdpi = 760f,
                ydpi = 760f,
                currentWidthPx = 3840,
                currentHeightPx = 2160,
                physicalWidthPx = 1920,
                physicalHeightPx = 1080
            ),
            0.0001f
        )

        // Out-of-bounds logicalDensity fallback clamps to [1.0f, 4.5f]
        assertEquals(
            PhysicalDisplayMetrics.MIN_DENSITY,
            PhysicalDisplayMetrics.computePhysicalDensity(logicalDensity = 0.4f, xdpi = 0f, ydpi = 0f),
            0.0001f
        )
        assertEquals(
            PhysicalDisplayMetrics.MAX_DENSITY,
            PhysicalDisplayMetrics.computePhysicalDensity(logicalDensity = 6.0f, xdpi = 0f, ydpi = 0f),
            0.0001f
        )
    }
}

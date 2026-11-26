// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.grid

import dev.duoime.core.layout.BuiltInLayouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyGridHitTest {

    @Test
    fun singleTapHitsExpectedKeysAndFallsBackInGaps() {
        val layout = BuiltInLayouts.qwerty()
        val hitMap = KeyGridGeometry.computeHitMap(
            layout = layout,
            widthPx = 1000f,
            heightPx = 400f,
            horizontalGapPx = 10f,
            verticalGapPx = 10f
        )

        // Row 0 has 10 keys (q..p); top-left hits 'q' (index 0), top-right hits 'p' (index 9)
        assertEquals(0, KeyGridGeometry.hitTestIndex(hitMap, 20f, 20f))
        assertEquals("q", hitMap.keys[0].label)
        assertEquals(9, KeyGridGeometry.hitTestIndex(hitMap, 980f, 20f))
        assertEquals("p", hitMap.keys[9].label)

        // Point inside gap between key 0 and key 1 still resolves to a valid adjacent key (never drops)
        val gapX = (hitMap.right(0) + hitMap.left(1)) * 0.5f
        val midY = (hitMap.top(0) + hitMap.bottom(0)) * 0.5f
        val gapHit = KeyGridGeometry.hitTestIndex(hitMap, gapX, midY)
        assertTrue("Expected gap tap to hit key 0 or 1, got $gapHit", gapHit == 0 || gapHit == 1)

        // Points outside grid bounds return -1
        assertEquals(-1, KeyGridGeometry.hitTestIndex(hitMap, -1f, 50f))
        assertEquals(-1, KeyGridGeometry.hitTestIndex(hitMap, 50f, -5f))
        assertEquals(-1, KeyGridGeometry.hitTestIndex(hitMap, 1001f, 50f))
        assertEquals(-1, KeyGridGeometry.hitTestIndex(hitMap, 50f, 405f))
        assertEquals(-1, KeyGridGeometry.hitTestIndex(hitMap, Float.NaN, 50f))
    }

    @Test
    fun multiTouchTracksDistinctPointerIdsIndependentlyAndCancelsCleanly() {
        val hitMap = KeyGridGeometry.computeHitMap(
            layout = BuiltInLayouts.qwerty(),
            widthPx = 1000f,
            heightPx = 400f,
            horizontalGapPx = 6f,
            verticalGapPx = 6f
        )
        val tracker = MultiTouchPointerTracker()
        tracker.reset(hitMap.size)

        // Thumb 1 presses 'q' (index 0); Thumb 2 rolls over onto 'p' (index 9)
        val idx1 = tracker.onPointerDown(pointerId = 101L, x = 25f, y = 25f, hitMap = hitMap)
        val idx2 = tracker.onPointerDown(pointerId = 202L, x = 975f, y = 25f, hitMap = hitMap)
        assertEquals(0, idx1)
        assertEquals(9, idx2)
        assertEquals(2, tracker.activePointerCount)
        assertTrue(tracker.isKeyPressed(0))
        assertTrue(tracker.isKeyPressed(9))

        // Pointer 1 releases 'q' while Pointer 2 remains pressed on 'p'
        assertEquals(0, tracker.onPointerUp(pointerId = 101L))
        assertFalse(tracker.isKeyPressed(0))
        assertTrue(tracker.isKeyPressed(9))
        assertEquals(1, tracker.activePointerCount)

        // Pointer 2 is canceled (e.g., gesture interrupt) -> clears pressed visual and subsequent up returns -1
        tracker.onPointerCancel(pointerId = 202L)
        assertFalse(tracker.isKeyPressed(9))
        assertEquals(0, tracker.activePointerCount)
        assertEquals(-1, tracker.onPointerUp(pointerId = 202L))

        // Two pointers pressing the same key keep pressed state until both release
        tracker.onPointerDown(pointerId = 301L, x = 25f, y = 25f, hitMap = hitMap)
        tracker.onPointerDown(pointerId = 302L, x = 30f, y = 30f, hitMap = hitMap)
        assertTrue(tracker.isKeyPressed(0))
        assertEquals(0, tracker.onPointerUp(301L))
        assertTrue(tracker.isKeyPressed(0))
        tracker.cancelAll()
        assertFalse(tracker.isKeyPressed(0))
        assertEquals(0, tracker.activePointerCount)
    }

    @Test
    fun nearSquareSecondaryDisplayGeometryScalesProportionallyWithoutOverlap() {
        val layouts = listOf(
            BuiltInLayouts.qwerty(includeNumberRow = false),
            BuiltInLayouts.qwerty(includeNumberRow = true),
            BuiltInLayouts.t9(),
            BuiltInLayouts.symbolsV0()
        )
        // Secondary near-square display dimensions (1280x960, 960x1280, 1240x1080) at 60%/75%/90% height
        val screenBoxes = listOf(
            1280f to (960f * 0.60f),
            1280f to (960f * 0.90f),
            960f to (1280f * 0.75f),
            1240f to (1080f * 0.90f)
        )

        for (layout in layouts) {
            for ((w, h) in screenBoxes) {
                val hitMap = KeyGridGeometry.computeHitMap(
                    layout = layout,
                    widthPx = w,
                    heightPx = h,
                    horizontalGapPx = 6f,
                    verticalGapPx = 8f
                )
                assertTrue(hitMap.size > 0)
                for (i in 0 until hitMap.size) {
                    val left = hitMap.left(i)
                    val top = hitMap.top(i)
                    val right = hitMap.right(i)
                    val bottom = hitMap.bottom(i)
                    assertTrue("Positive width expected for ${layout.id} key $i", right > left)
                    assertTrue("Positive height expected for ${layout.id} key $i", bottom > top)
                    assertTrue("Rect within horizontal bounds", left >= 0f && right <= w + 0.01f)
                    assertTrue("Rect within vertical bounds", top >= 0f && bottom <= h + 0.01f)

                    // Center point of every key must hit that exact key
                    val cx = (left + right) * 0.5f
                    val cy = (top + bottom) * 0.5f
                    assertEquals(i, KeyGridGeometry.hitTestIndex(hitMap, cx, cy))
                }

                // Verify zero overlapping key rectangles
                for (i in 0 until hitMap.size) {
                    for (j in i + 1 until hitMap.size) {
                        val overlapX = hitMap.left(i) < hitMap.right(j) - 0.01f &&
                            hitMap.right(i) > hitMap.left(j) + 0.01f
                        val overlapY = hitMap.top(i) < hitMap.bottom(j) - 0.01f &&
                            hitMap.bottom(i) > hitMap.top(j) + 0.01f
                        assertFalse(
                            "Overlap between key $i and $j in ${layout.id} (${w}x$h)",
                            overlapX && overlapY
                        )
                    }
                }
            }
        }
    }

    @Test
    fun labelVerticalPlacementNeverOverlapsPrimaryAndSecondaryAcrossCompactAndTallKeys() {
        // Compact key heights matching 1280x960 secondary display (48px, 60px, 75px, 90px) and tall 150px
        val keyHeights = listOf(48f, 60f, 75f, 90f, 150f)
        val primaryH = 26f
        val secondaryH = 14f
        for (h in keyHeights) {
            val placement = KeyGridGeometry.computeLabelVerticalPlacement(
                keyTop = 10f,
                keyHeight = h,
                primaryHeight = primaryH,
                secondaryHeight = secondaryH
            )
            if (placement.secondaryTop != null) {
                val secBottom = placement.secondaryTop!! + secondaryH
                assertTrue(
                    "Secondary bottom ($secBottom) must stay above primary top (${placement.primaryTop}) for h=$h",
                    secBottom <= placement.primaryTop - 1.99f
                )
                assertTrue(
                    "Primary bottom must stay inside key height for h=$h",
                    placement.primaryTop + primaryH <= 10f + h + 0.01f
                )
            }
        }

        // Below 36dp key height (e.g. 68px at 320dpi/density=2) cleanly omits secondary label instead of colliding
        val tight = KeyGridGeometry.computeLabelVerticalPlacement(
            keyTop = 0f,
            keyHeight = 68f,
            primaryHeight = 30f,
            secondaryHeight = 16f,
            density = 2f
        )
        assertEquals(null, tight.secondaryTop)
        assertEquals(19f, tight.primaryTop, 0.01f)
    }
}

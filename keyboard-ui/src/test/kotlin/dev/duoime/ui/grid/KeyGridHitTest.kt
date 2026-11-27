// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.grid

import dev.duoime.core.layout.BuiltInLayouts
import dev.duoime.core.model.FieldKind
import dev.duoime.core.model.LangKeyPlacement
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun t9TrackGridAlignsSlotSymbolBottomRowAndRowSpan2EnterWithZeroDrift() {
        val width = 1000f
        val height = 400f
        val hGap = 8f
        val vGap = 10f
        val expectedColW = (width - hGap * 4f) / 5f
        val expectedRowH = (height - vGap * 3f) / 4f

        for (placement in listOf(LangKeyPlacement.GRID, LangKeyPlacement.HOST)) {
            val layout = BuiltInLayouts.t9(langKeyPlacement = placement)
            val hitMap = KeyGridGeometry.computeHitMap(layout, width, height, hGap, vGap)

            val slot = hitMap.slots[BuiltInLayouts.T9_LEFT_SLOT_ID]
            assertNotNull("Expected t9_left_slot for $placement", slot)
            requireNotNull(slot)
            assertEquals(0f, slot.left, 0.01f)
            assertEquals(0f, slot.top, 0.01f)
            assertEquals(expectedColW, slot.right, 0.01f)
            assertEquals(expectedRowH * 3f + vGap * 2f, slot.bottom, 0.01f)

            // Slot is NOT in hitMap.keys and hitTestIndex inside slot returns -1
            assertTrue(hitMap.keys.none { it.id == BuiltInLayouts.T9_LEFT_SLOT_ID })
            assertEquals(-1, KeyGridGeometry.hitTestIndex(hitMap, slot.width * 0.5f, slot.height * 0.5f))

            // Row 3 [符号] aligns pixel-exact under t9_left_slot with ZERO drift in BOTH GRID and HOST
            val symIdx = hitMap.keys.indexOfFirst { it.id == "t9_sym" }
            assertTrue(symIdx >= 0)
            assertEquals(slot.left, hitMap.left(symIdx), 0.01f)
            assertEquals(slot.right, hitMap.right(symIdx), 0.01f)
            assertEquals(expectedRowH * 3f + vGap * 3f, hitMap.top(symIdx), 0.01f)
            assertEquals(height, hitMap.bottom(symIdx), 0.01f)

            // Row 3 [123] starts pixel-exact at Col 1 left; [空格] ends pixel-exact at Col 3 right
            val numIdx = hitMap.keys.indexOfFirst { it.id == "t9_num" }
            val spaceIdx = hitMap.keys.indexOfFirst { it.id == "t9_space" }
            val col1Left = expectedColW + hGap
            val col3Right = expectedColW * 4f + hGap * 3f
            assertEquals(col1Left, hitMap.left(numIdx), 0.01f)
            assertEquals(col3Right, hitMap.right(spaceIdx), 0.01f)

            // Col 4 Enter spans Rows 2-3 cleanly
            val enterIdx = hitMap.keys.indexOfFirst { it.id == "enter" }
            assertTrue(enterIdx >= 0)
            assertEquals(col3Right + hGap, hitMap.left(enterIdx), 0.01f)
            assertEquals(width, hitMap.right(enterIdx), 0.01f)
            assertEquals(expectedRowH * 2f + vGap * 2f, hitMap.top(enterIdx), 0.01f)
            assertEquals(height, hitMap.bottom(enterIdx), 0.01f)
            assertTrue(abs((hitMap.bottom(enterIdx) - hitMap.top(enterIdx)) - (expectedRowH * 2f + vGap)) < 0.05f)
        }
    }

    @Test
    fun disabledKeysReturnMinusOneOnDirectTapAndAreSkippedByGapFallback() {
        val numPad = BuiltInLayouts.numberPad(FieldKind.NUMBER)
        val hitMap = KeyGridGeometry.computeHitMap(numPad, 1000f, 400f, 8f, 8f)
        val backIdx = hitMap.keys.indexOfFirst { it.id == "num_back" }
        assertTrue(backIdx >= 0)
        assertFalse(hitMap.keys[backIdx].enabled)

        // Direct tap on disabled [返回] returns -1 (does not fall back to neighbor)
        val cx = (hitMap.left(backIdx) + hitMap.right(backIdx)) * 0.5f
        val cy = (hitMap.top(backIdx) + hitMap.bottom(backIdx)) * 0.5f
        assertEquals(-1, KeyGridGeometry.hitTestIndex(hitMap, cx, cy))

        // Gap between [符号] and disabled [返回] resolves to enabled [符号], never disabled [返回]
        val symIdx = hitMap.keys.indexOfFirst { it.id == "num_sym" }
        val gapX = (hitMap.right(symIdx) + hitMap.left(backIdx)) * 0.5f
        assertEquals(symIdx, KeyGridGeometry.hitTestIndex(hitMap, gapX, cy))
    }

    @Test
    fun canPreserveActivePointersKeepsT9BackspaceAndRolloverAcrossIdleComposingSwapAndResetsOnLayoutChange() {
        val t9Idle = KeyGridGeometry.computeHitMap(BuiltInLayouts.t9(isComposing = false), 1000f, 400f, 8f, 10f)
        val t9Comp = KeyGridGeometry.computeHitMap(BuiltInLayouts.t9(isComposing = true), 1000f, 400f, 8f, 10f)
        val qwerty = KeyGridGeometry.computeHitMap(BuiltInLayouts.qwerty(), 1000f, 400f, 8f, 10f)

        val backspaceIdx = t9Comp.keys.indexOfFirst { it.id == "backspace" }
        val t9Digit4Idx = t9Idle.keys.indexOfFirst { it.id == "t9_4" }
        val t9Key1Idx = t9Idle.keys.indexOfFirst { it.id == "t9_1" }
        assertTrue(backspaceIdx >= 0 && t9Digit4Idx >= 0 && t9Key1Idx >= 0)

        // Held Backspace survives t9_comp -> t9_idle so auto-repeat keeps deleting committed text
        assertTrue(KeyGridGeometry.canPreserveActivePointers(t9Comp, t9Idle, listOf(backspaceIdx)))

        // Held t9_4 survives t9_idle -> t9_comp so two-thumb rollover is never dropped
        assertTrue(KeyGridGeometry.canPreserveActivePointers(t9Idle, t9Comp, listOf(t9Digit4Idx)))

        // Key whose action mutates (t9_1: "、" -> "分词") resets instead of preserving stale press
        assertFalse(KeyGridGeometry.canPreserveActivePointers(t9Idle, t9Comp, listOf(t9Key1Idx)))

        // Layout switch (T9 -> QWERTY) and empty active pointers always reset cleanly
        assertFalse(KeyGridGeometry.canPreserveActivePointers(t9Idle, qwerty, listOf(t9Digit4Idx)))
        assertFalse(KeyGridGeometry.canPreserveActivePointers(t9Idle, t9Comp, emptyList()))
    }
}

// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.grid

import dev.duoime.core.layout.KeySpec
import dev.duoime.core.layout.LayoutSpec
import kotlin.math.max
import kotlin.math.min

/**
 * Flat layout-phase geometry table for a [LayoutSpec].
 * [rects] stores 4 floats per key: `[left, top, right, bottom]` at `index * 4`.
 */
class KeyGridHitMap(
    val keys: List<KeySpec>,
    val rects: FloatArray,
    val widthPx: Float,
    val heightPx: Float
) {
    val size: Int get() = keys.size

    fun left(index: Int): Float = rects[index * RECT_STRIDE]

    fun top(index: Int): Float = rects[index * RECT_STRIDE + 1]

    fun right(index: Int): Float = rects[index * RECT_STRIDE + 2]

    fun bottom(index: Int): Float = rects[index * RECT_STRIDE + 3]

    companion object {
        const val RECT_STRIDE: Int = 4
        val EMPTY: KeyGridHitMap = KeyGridHitMap(emptyList(), FloatArray(0), 0f, 0f)
    }
}

/**
 * Pure proportional key geometry and hit-testing math.
 * Scales keys strictly from available width/height so asymmetric and near-square secondary
 * displays (such as 1280x960 / 960x1280 at 60%–90% height) never clip or rely on fixed dp heights.
 */
object KeyGridGeometry {

    fun computeHitMap(
        layout: LayoutSpec,
        widthPx: Float,
        heightPx: Float,
        horizontalGapPx: Float = 0f,
        verticalGapPx: Float = 0f
    ): KeyGridHitMap {
        val rows = layout.rows
        if (rows.isEmpty() || widthPx <= 0f || heightPx <= 0f) {
            return KeyGridHitMap.EMPTY
        }

        val totalKeys = rows.sumOf { it.keys.size }
        if (totalKeys == 0) return KeyGridHitMap.EMPTY

        val flatKeys = ArrayList<KeySpec>(totalKeys)
        val rects = FloatArray(totalKeys * KeyGridHitMap.RECT_STRIDE)

        val safeVGap = verticalGapPx.coerceAtLeast(0f)
        val safeHGap = horizontalGapPx.coerceAtLeast(0f)
        val totalVGap = safeVGap * (rows.size - 1)
        val rowHeight = max(1f, (heightPx - totalVGap) / rows.size)

        var cursor = 0
        for ((rowIdx, row) in rows.withIndex()) {
            val rowKeys = row.keys
            if (rowKeys.isEmpty()) continue
            val top = rowIdx * (rowHeight + safeVGap)
            val bottom = min(heightPx, top + rowHeight)
            val totalWeight = row.totalWeight.takeIf { it > 0f } ?: rowKeys.size.toFloat()
            val totalHGap = safeHGap * (rowKeys.size - 1)
            val usableWidth = max(1f, widthPx - totalHGap)

            var x = 0f
            for ((colIdx, key) in rowKeys.withIndex()) {
                val keyWeight = key.weight.takeIf { it > 0f } ?: 1f
                val keyWidth = usableWidth * (keyWeight / totalWeight)
                val right = if (colIdx == rowKeys.lastIndex) widthPx else min(widthPx, x + keyWidth)
                val base = cursor * KeyGridHitMap.RECT_STRIDE
                rects[base] = x
                rects[base + 1] = top
                rects[base + 2] = max(x, right)
                rects[base + 3] = max(top, bottom)
                flatKeys.add(key)
                cursor++
                x = right + safeHGap
            }
        }
        return KeyGridHitMap(flatKeys, rects, widthPx, heightPx)
    }

    /**
     * Returns the hit key index for `(x, y)`, or `-1` if outside the grid bounds.
     * Points landing in inter-key gaps fall back to the nearest key rect so rapid thumb taps never drop.
     */
    fun hitTestIndex(hitMap: KeyGridHitMap, x: Float, y: Float): Int {
        if (!isInsideBounds(hitMap, x, y)) return -1
        val direct = findDirectHit(hitMap, x, y)
        return if (direct >= 0) direct else findNearestHit(hitMap, x, y)
    }

    private fun isInsideBounds(hitMap: KeyGridHitMap, x: Float, y: Float): Boolean =
        hitMap.size > 0 && x.isFinite() && y.isFinite() &&
            x >= 0f && y >= 0f && x <= hitMap.widthPx && y <= hitMap.heightPx

    private fun findDirectHit(hitMap: KeyGridHitMap, x: Float, y: Float): Int {
        val rects = hitMap.rects
        for (i in 0 until hitMap.size) {
            val base = i * KeyGridHitMap.RECT_STRIDE
            if (x >= rects[base] && x <= rects[base + 2] && y >= rects[base + 1] && y <= rects[base + 3]) {
                return i
            }
        }
        return -1
    }

    private fun findNearestHit(hitMap: KeyGridHitMap, x: Float, y: Float): Int {
        val rects = hitMap.rects
        var bestIdx = -1
        var bestDistSq = Float.POSITIVE_INFINITY
        for (i in 0 until hitMap.size) {
            val base = i * KeyGridHitMap.RECT_STRIDE
            val dx = axisDistance(x, rects[base], rects[base + 2])
            val dy = axisDistance(y, rects[base + 1], rects[base + 3])
            val distSq = dx * dx + dy * dy
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun axisDistance(value: Float, minVal: Float, maxVal: Float): Float = when {
        value < minVal -> minVal - value
        value > maxVal -> value - maxVal
        else -> 0f
    }
}

/**
 * Pure-JVM multi-touch pointer tracker keyed by `pointerId` for single-`pointerInput` key grids.
 * Supports true two-thumb rollover and clean pointer cancellation without leaking stuck press states.
 */
class MultiTouchPointerTracker {
    private val activePointers = HashMap<Long, Int>(8)
    private var pressedCounts = IntArray(0)

    val activePointerCount: Int get() = activePointers.size

    fun reset(keyCount: Int) {
        activePointers.clear()
        pressedCounts = IntArray(keyCount.coerceAtLeast(0))
    }

    fun isKeyPressed(keyIndex: Int): Boolean = keyIndex in pressedCounts.indices && pressedCounts[keyIndex] > 0

    fun keyForPointer(pointerId: Long): Int = activePointers[pointerId] ?: -1

    fun onPointerDown(pointerId: Long, x: Float, y: Float, hitMap: KeyGridHitMap): Int {
        ensureCapacity(hitMap.size)
        clearPointer(pointerId)
        val idx = KeyGridGeometry.hitTestIndex(hitMap, x, y)
        if (idx >= 0) {
            activePointers[pointerId] = idx
            pressedCounts[idx]++
        }
        return idx
    }

    fun onPointerMove(pointerId: Long, x: Float, y: Float, hitMap: KeyGridHitMap): Int {
        if (!activePointers.containsKey(pointerId)) return -1
        ensureCapacity(hitMap.size)
        val prev = activePointers[pointerId] ?: -1
        val next = KeyGridGeometry.hitTestIndex(hitMap, x, y)
        if (next == prev) return next
        if (prev in pressedCounts.indices && pressedCounts[prev] > 0) {
            pressedCounts[prev]--
        }
        if (next >= 0) {
            activePointers[pointerId] = next
            pressedCounts[next]++
        } else {
            activePointers.remove(pointerId)
        }
        return next
    }

    /**
     * Releases [pointerId] and returns the key index to commit, or `-1` if it was not over a key.
     */
    fun onPointerUp(pointerId: Long): Int = clearPointer(pointerId)

    /**
     * Cancels [pointerId] (e.g. system gesture / interrupted touch) without committing any key.
     * Returns `true` if a pressed key was cleared.
     */
    fun onPointerCancel(pointerId: Long): Boolean = clearPointer(pointerId) >= 0

    fun cancelAll() {
        activePointers.clear()
        pressedCounts.fill(0)
    }

    private fun clearPointer(pointerId: Long): Int {
        val prev = activePointers.remove(pointerId) ?: return -1
        if (prev in pressedCounts.indices && pressedCounts[prev] > 0) {
            pressedCounts[prev]--
        }
        return prev
    }

    private fun ensureCapacity(size: Int) {
        if (pressedCounts.size != size) {
            pressedCounts = IntArray(size.coerceAtLeast(0))
            activePointers.clear()
        }
    }
}

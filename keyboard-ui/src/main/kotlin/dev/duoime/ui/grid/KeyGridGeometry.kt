// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.grid

import androidx.compose.runtime.Immutable
import dev.duoime.core.layout.KeyRowSpec
import dev.duoime.core.layout.KeySpec
import dev.duoime.core.layout.LayoutSpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pixel bounding box for an embedded widget slot declared with `KeySpec.isSlot == true`
 * (such as T9's Col-0 vertical rail `t9_left_slot`).
 */
@Immutable
data class KeySlotRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

/**
 * Flat layout-phase geometry table for a [LayoutSpec].
 * [rects] stores 4 floats per key: `[left, top, right, bottom]` at `index * 4`.
 * Embedded widget slots (`KeySpec.isSlot == true`) are recorded in [slots] and excluded from
 * [keys]/[rects] so [KeyGrid] never draws or hit-tests over them.
 */
class KeyGridHitMap(
    val keys: List<KeySpec>,
    val rects: FloatArray,
    val widthPx: Float,
    val heightPx: Float,
    val slots: Map<String, KeySlotRect> = emptyMap()
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
 *
 * Supports both independent row-by-row proportional layouts (`columnWeights == null`, e.g. QWERTY)
 * and track-aligned multi-row grids (`columnWeights != null`, e.g. 5×4 T9 and Numpad) with
 * `rowSpan >= 2` and embedded `isSlot` rectangles with zero inter-column drift.
 */
object KeyGridGeometry {
    private const val TRACK_EPSILON: Float = 1e-3f

    private class HitMapBuilder(
        capacity: Int,
        val widthPx: Float,
        val heightPx: Float
    ) {
        val keys = ArrayList<KeySpec>(capacity)
        private val rawRects = FloatArray(capacity * KeyGridHitMap.RECT_STRIDE)
        val slots = LinkedHashMap<String, KeySlotRect>()
        private var count = 0

        fun addKeyOrSlot(key: KeySpec, left: Float, top: Float, right: Float, bottom: Float) {
            val safeRight = max(left, right)
            val safeBottom = max(top, bottom)
            if (key.isSlot) {
                slots[key.id] = KeySlotRect(left, top, safeRight, safeBottom)
                return
            }
            val base = count * KeyGridHitMap.RECT_STRIDE
            rawRects[base] = left
            rawRects[base + 1] = top
            rawRects[base + 2] = safeRight
            rawRects[base + 3] = safeBottom
            keys.add(key)
            count++
        }

        fun build(): KeyGridHitMap = KeyGridHitMap(
            keys = keys,
            rects = rawRects.copyOf(count * KeyGridHitMap.RECT_STRIDE),
            widthPx = widthPx,
            heightPx = heightPx,
            slots = slots
        )
    }

    private data class ColumnTracks(
        val weights: List<Float>,
        val lefts: FloatArray,
        val rights: FloatArray
    )

    private data class GridVerticalMetrics(
        val rowCount: Int,
        val rowHeight: Float,
        val safeVGap: Float,
        val heightPx: Float
    ) {
        fun rowTop(rowIdx: Int): Float = rowIdx * (rowHeight + safeVGap)

        fun spanBottom(rowIdx: Int, rowSpan: Int): Float {
            val endRow = (rowIdx + rowSpan.coerceAtLeast(1) - 1).coerceIn(rowIdx, rowCount - 1)
            return min(heightPx, rowTop(endRow) + rowHeight)
        }
    }

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
        val totalEntries = rows.sumOf { it.keys.size }
        if (totalEntries == 0) return KeyGridHitMap.EMPTY

        val safeVGap = verticalGapPx.coerceAtLeast(0f)
        val safeHGap = horizontalGapPx.coerceAtLeast(0f)
        val totalVGap = safeVGap * (rows.size - 1)
        val rowHeight = max(1f, (heightPx - totalVGap) / rows.size)
        val frame = GridLayoutFrame(widthPx, safeHGap, GridVerticalMetrics(rows.size, rowHeight, safeVGap, heightPx))
        val builder = HitMapBuilder(totalEntries, widthPx, heightPx)

        val columnWeights = layout.columnWeights?.takeIf { it.isNotEmpty() }
        if (columnWeights == null) {
            layoutIndependentRows(rows, frame, builder)
        } else {
            layoutTrackGrid(rows, columnWeights, frame, builder)
        }
        return builder.build()
    }

    private class GridLayoutFrame(
        val widthPx: Float,
        val safeHGap: Float,
        val vMetrics: GridVerticalMetrics
    )

    private fun layoutIndependentRows(rows: List<KeyRowSpec>, frame: GridLayoutFrame, builder: HitMapBuilder) {
        for ((rowIdx, row) in rows.withIndex()) {
            val rowKeys = row.keys
            if (rowKeys.isEmpty()) continue
            val top = frame.vMetrics.rowTop(rowIdx)
            val totalWeight = row.totalWeight.takeIf { it > 0f } ?: rowKeys.size.toFloat()
            val totalHGap = frame.safeHGap * (rowKeys.size - 1)
            val usableWidth = max(1f, frame.widthPx - totalHGap)

            var x = 0f
            for ((colIdx, key) in rowKeys.withIndex()) {
                val keyWeight = key.weight.takeIf { it > 0f } ?: 1f
                val keyWidth = usableWidth * (keyWeight / totalWeight)
                val right = if (colIdx == rowKeys.lastIndex) frame.widthPx else min(frame.widthPx, x + keyWidth)
                val bottom = frame.vMetrics.spanBottom(rowIdx, key.rowSpan)
                builder.addKeyOrSlot(key, x, top, right, bottom)
                x = right + frame.safeHGap
            }
        }
    }

    private fun layoutTrackGrid(
        rows: List<KeyRowSpec>,
        columnWeights: List<Float>,
        frame: GridLayoutFrame,
        builder: HitMapBuilder
    ) {
        val tracks = buildColumnTracks(columnWeights, frame.widthPx, frame.safeHGap)
        val occupiedUntilRow = IntArray(columnWeights.size)

        for ((rowIdx, row) in rows.withIndex()) {
            val rowKeys = row.keys
            if (rowKeys.isEmpty()) continue
            val freeSpans = findFreeColumnSpans(occupiedUntilRow, rowIdx)
            if (freeSpans.isEmpty()) continue

            val keySlices = partitionKeysAcrossSpans(rowKeys, freeSpans, columnWeights)
            for ((spanIdx, span) in freeSpans.withIndex()) {
                val spanKeys = keySlices[spanIdx]
                if (spanKeys.isEmpty()) continue
                layoutKeysInTrackSpan(
                    rowIdx = rowIdx,
                    span = span,
                    spanKeys = spanKeys,
                    tracks = tracks,
                    safeHGap = frame.safeHGap,
                    vMetrics = frame.vMetrics,
                    occupiedUntilRow = occupiedUntilRow,
                    builder = builder
                )
            }
        }
    }

    private fun buildColumnTracks(columnWeights: List<Float>, widthPx: Float, safeHGap: Float): ColumnTracks {
        val count = columnWeights.size
        val lefts = FloatArray(count)
        val rights = FloatArray(count)
        val totalWeight = columnWeights.fold(0f) { acc, w -> acc + w.coerceAtLeast(0.01f) }
        val usableWidth = max(1f, widthPx - safeHGap * (count - 1))
        var x = 0f
        for (c in 0 until count) {
            val w = columnWeights[c].coerceAtLeast(0.01f)
            lefts[c] = x
            val right = if (c == count - 1) widthPx else min(widthPx, x + usableWidth * (w / totalWeight))
            rights[c] = max(x, right)
            x = rights[c] + safeHGap
        }
        return ColumnTracks(columnWeights, lefts, rights)
    }

    private fun findFreeColumnSpans(occupiedUntilRow: IntArray, rowIdx: Int): List<IntRange> {
        val spans = ArrayList<IntRange>(2)
        var col = 0
        while (col < occupiedUntilRow.size) {
            if (occupiedUntilRow[col] <= rowIdx) {
                val start = col
                while (col < occupiedUntilRow.size && occupiedUntilRow[col] <= rowIdx) {
                    col++
                }
                spans.add(start until col)
            } else {
                col++
            }
        }
        return spans
    }

    private fun partitionKeysAcrossSpans(
        rowKeys: List<KeySpec>,
        freeSpans: List<IntRange>,
        columnWeights: List<Float>
    ): List<List<KeySpec>> {
        if (freeSpans.size == 1) return listOf(rowKeys)
        val result = ArrayList<List<KeySpec>>(freeSpans.size)
        var cursor = 0
        for ((idx, span) in freeSpans.withIndex()) {
            if (idx == freeSpans.lastIndex) {
                result.add(rowKeys.subList(cursor, rowKeys.size))
                break
            }
            val spanWeight = span.sumOf { columnWeights[it].toDouble() }.toFloat()
            val group = ArrayList<KeySpec>()
            var acc = 0f
            while (cursor < rowKeys.size && (group.isEmpty() || acc + TRACK_EPSILON < spanWeight)) {
                val key = rowKeys[cursor++]
                group.add(key)
                acc += key.weight.takeIf { it > 0f } ?: 1f
            }
            result.add(group)
        }
        return result
    }

    @Suppress("LongParameterList")
    private fun layoutKeysInTrackSpan(
        rowIdx: Int,
        span: IntRange,
        spanKeys: List<KeySpec>,
        tracks: ColumnTracks,
        safeHGap: Float,
        vMetrics: GridVerticalMetrics,
        occupiedUntilRow: IntArray,
        builder: HitMapBuilder
    ) {
        val spanTrackWeight = span.sumOf { tracks.weights[it].toDouble() }.toFloat()
        val keyWeightSum = spanKeys.fold(0f) { acc, k -> acc + (k.weight.takeIf { it > 0f } ?: 1f) }
        val scale = if (keyWeightSum > 0f) spanTrackWeight / keyWeightSum else 1f

        var groupKeyStart = 0
        var groupColStart = span.first
        var accKeyWeight = 0f
        var colCursor = span.first
        var accColWeight = tracks.weights[colCursor]

        for (i in spanKeys.indices) {
            accKeyWeight += (spanKeys[i].weight.takeIf { it > 0f } ?: 1f) * scale
            while (colCursor < span.last && accColWeight + TRACK_EPSILON < accKeyWeight) {
                colCursor++
                accColWeight += tracks.weights[colCursor]
            }
            val atTrackBoundary = abs(accKeyWeight - accColWeight) <= TRACK_EPSILON
            val isLastKey = i == spanKeys.lastIndex
            if (atTrackBoundary || isLastKey) {
                val endCol = if (isLastKey) span.last else colCursor
                layoutSubGroupInTrackRange(
                    rowIdx = rowIdx,
                    colRange = groupColStart..endCol,
                    keys = spanKeys.subList(groupKeyStart, i + 1),
                    tracks = tracks,
                    safeHGap = safeHGap,
                    vMetrics = vMetrics,
                    occupiedUntilRow = occupiedUntilRow,
                    builder = builder
                )
                groupKeyStart = i + 1
                groupColStart = endCol + 1
                if (!isLastKey && groupColStart <= span.last) {
                    colCursor = groupColStart
                    accColWeight += tracks.weights[colCursor]
                }
            }
        }
    }

    @Suppress("LongParameterList")
    private fun layoutSubGroupInTrackRange(
        rowIdx: Int,
        colRange: IntRange,
        keys: List<KeySpec>,
        tracks: ColumnTracks,
        safeHGap: Float,
        vMetrics: GridVerticalMetrics,
        occupiedUntilRow: IntArray,
        builder: HitMapBuilder
    ) {
        if (keys.isEmpty()) return
        val groupLeft = tracks.lefts[colRange.first]
        val groupRight = tracks.rights[colRange.last]
        val top = vMetrics.rowTop(rowIdx)
        val totalWeight = keys.fold(0f) { acc, k -> acc + (k.weight.takeIf { it > 0f } ?: 1f) }
        val usableWidth = max(1f, (groupRight - groupLeft) - safeHGap * (keys.size - 1))

        var x = groupLeft
        for ((idx, key) in keys.withIndex()) {
            val keyWeight = key.weight.takeIf { it > 0f } ?: 1f
            val right = if (idx == keys.lastIndex) {
                groupRight
            } else {
                min(groupRight, x + usableWidth * (keyWeight / totalWeight))
            }
            val bottom = vMetrics.spanBottom(rowIdx, key.rowSpan)
            builder.addKeyOrSlot(key, x, top, right, bottom)
            if (key.rowSpan > 1) {
                val until = rowIdx + key.rowSpan
                for (c in colRange) {
                    occupiedUntilRow[c] = max(occupiedUntilRow[c], until)
                }
            }
            x = right + safeHGap
        }
    }

    /**
     * Returns the hit key index for `(x, y)`, or `-1` if outside the grid bounds, directly over a
     * disabled key, or inside an embedded widget slot. Points landing in inter-key gaps fall back
     * to the nearest enabled key rect so rapid thumb taps never drop.
     */
    fun hitTestIndex(hitMap: KeyGridHitMap, x: Float, y: Float): Int {
        if (!isInsideBounds(hitMap, x, y)) return -1
        for (slot in hitMap.slots.values) {
            if (x >= slot.left && x <= slot.right && y >= slot.top && y <= slot.bottom) {
                return -1
            }
        }
        val direct = findDirectHit(hitMap, x, y)
        if (direct >= 0) {
            return if (hitMap.keys[direct].enabled) direct else -1
        }
        return findNearestEnabledHit(hitMap, x, y)
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

    private fun findNearestEnabledHit(hitMap: KeyGridHitMap, x: Float, y: Float): Int {
        val rects = hitMap.rects
        var bestIdx = -1
        var bestDistSq = Float.POSITIVE_INFINITY
        for (i in 0 until hitMap.size) {
            if (!hitMap.keys[i].enabled) continue
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

    /**
     * Computes guaranteed non-overlapping vertical top positions for primary and optional secondary
     * key labels. Drops the secondary label whenever key height is below [MIN_SECONDARY_KEY_HEIGHT_DP]
     * (`36dp`) or too compact to preserve at least 2 px clearance, centering the primary label cleanly.
     */
    fun computeLabelVerticalPlacement(
        keyTop: Float,
        keyHeight: Float,
        primaryHeight: Float,
        secondaryHeight: Float?,
        density: Float = 1f
    ): KeyLabelVerticalPlacement {
        val centeredPrimary = keyTop + (keyHeight - primaryHeight) * 0.5f
        val minKeyHeightForSecondary = MIN_SECONDARY_KEY_HEIGHT_DP * density.coerceAtLeast(1f)
        if (secondaryHeight == null || keyHeight < minKeyHeightForSecondary) {
            return KeyLabelVerticalPlacement(primaryTop = centeredPrimary, secondaryTop = null)
        }
        val topPad = (keyHeight * 0.07f).coerceIn(1f, 6f)
        val bottomPad = (keyHeight * 0.07f).coerceIn(1f, 6f)
        val minGap = 2f
        val required = topPad + secondaryHeight + minGap + primaryHeight + bottomPad
        if (required > keyHeight) {
            return KeyLabelVerticalPlacement(primaryTop = centeredPrimary, secondaryTop = null)
        }
        val secTop = keyTop + topPad
        val lowerTop = secTop + secondaryHeight + minGap
        val lowerHeight = (keyTop + keyHeight - bottomPad) - lowerTop
        val priTop = lowerTop + (lowerHeight - primaryHeight) * 0.5f
        return KeyLabelVerticalPlacement(primaryTop = priTop, secondaryTop = secTop)
    }

    const val MIN_SECONDARY_KEY_HEIGHT_DP: Float = 36f

    /**
     * Returns `true` when [next] preserves the geometry size and key identity/action of every
     * currently pressed key in [activeKeyIndices], so mid-touch layout refreshes (e.g., T9
     * `t9_idle` <-> `t9_comp` during Backspace auto-repeat or two-thumb rollover) keep active
     * pointers and hold timers intact instead of dropping them.
     */
    fun canPreserveActivePointers(
        previous: KeyGridHitMap,
        next: KeyGridHitMap,
        activeKeyIndices: Collection<Int>
    ): Boolean {
        if (activeKeyIndices.isEmpty()) return false
        if (previous.size != next.size) return false
        if (previous.widthPx != next.widthPx || previous.heightPx != next.heightPx) return false
        for (index in activeKeyIndices) {
            if (index !in previous.keys.indices || index !in next.keys.indices) return false
            val prevKey = previous.keys[index]
            val nextKey = next.keys[index]
            if (!nextKey.enabled || prevKey.id != nextKey.id || prevKey.action != nextKey.action) {
                return false
            }
        }
        return true
    }
}

/**
 * Vertical draw positions computed for a key's primary and optional secondary labels.
 */
data class KeyLabelVerticalPlacement(val primaryTop: Float, val secondaryTop: Float?)

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

    fun activeKeyIndices(): Collection<Int> = activePointers.values

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

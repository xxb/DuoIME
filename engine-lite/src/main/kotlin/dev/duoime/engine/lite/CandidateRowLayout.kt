/*
 * SPDX-License-Identifier: MIT
 * MIT License
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME by DuoIME Contributors (2026).
 */
package dev.duoime.engine.lite

/**
 * Pure width-estimation and row-packing engine for IME candidates so long phrases and whole
 * sentences never truncate into ellipsis, reducing items per row cleanly instead.
 */
object CandidateRowLayout {
    private const val ASCII_CHAR_UNITS = 0.62f
    private const val FULLWIDTH_CHAR_UNITS = 1.04f
    private const val BADGE_CHROME_UNITS = 2.25f
    private const val PLAIN_CHROME_UNITS = 1.45f
    private const val BADGE_MIN_CELL_UNITS = 3.25f
    private const val PLAIN_MIN_CELL_UNITS = 2.45f
    private const val REF_CHAR_DP = 19.5f
    private const val MIN_ROW_UNITS = 4f

    fun measureCandidateTextUnits(text: String): Float {
        var units = 0f
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            units += if (cp in 0x20..0x7E) ASCII_CHAR_UNITS else FULLWIDTH_CHAR_UNITS
        }
        return units
    }

    fun measureCandidateCellUnits(text: String, showBadge: Boolean): Float {
        val chrome = if (showBadge) BADGE_CHROME_UNITS else PLAIN_CHROME_UNITS
        val minUnits = if (showBadge) BADGE_MIN_CELL_UNITS else PLAIN_MIN_CELL_UNITS
        return maxOf(minUnits, measureCandidateTextUnits(text) + chrome)
    }

    fun resolveMaxRowUnits(availableWidthDp: Float, typographyScale: Float = 1f, fontScale: Float = 1f): Float {
        if (!availableWidthDp.isFinite() || availableWidthDp <= 0f) return 0f
        val safeTypo = if (typographyScale.isFinite()) typographyScale.coerceIn(0.72f, 1f) else 1f
        val safeFont = if (fontScale.isFinite()) fontScale.coerceIn(0.85f, 1.8f) else 1f
        val refCharDp = REF_CHAR_DP * safeTypo * safeFont
        return (availableWidthDp / refCharDp).coerceAtLeast(MIN_ROW_UNITS)
    }

    fun packRows(
        candidates: List<LiteCandidate>,
        maxRowUnits: Float,
        maxColsPerRow: Int = CANDIDATE_COLS_PER_ROW,
        showBadges: Boolean = true
    ): List<IntRange> {
        if (candidates.isEmpty()) return emptyList()
        val maxCols = maxColsPerRow.coerceAtLeast(1)
        if (!maxRowUnits.isFinite() || maxRowUnits <= 0f) {
            return packFixedColumns(candidates.size, maxCols)
        }
        val rows = ArrayList<IntRange>()
        var start = 0
        while (start < candidates.size) {
            var end = start
            var usedUnits = 0f
            var colCount = 0
            while (end < candidates.size && colCount < maxCols) {
                val itemUnits = measureCandidateCellUnits(candidates[end].text, showBadge = showBadges)
                if (colCount > 0 && usedUnits + itemUnits > maxRowUnits) break
                usedUnits += itemUnits
                colCount++
                end++
            }
            rows.add(start until end)
            start = end
        }
        return rows
    }

    private fun packFixedColumns(size: Int, maxCols: Int): List<IntRange> {
        val rows = ArrayList<IntRange>((size + maxCols - 1) / maxCols)
        var start = 0
        while (start < size) {
            val end = minOf(start + maxCols, size)
            rows.add(start until end)
            start = end
        }
        return rows
    }
}

/*
 * SPDX-License-Identifier: MIT
 * MIT License
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME by DuoIME Contributors (2026).
 */
package dev.duoime.engine.lite

import dev.duoime.core.model.ChineseVariant

enum class LiteSchemaMode {
    ENGLISH,
    QUANPIN,
    XIAOHE_SHUANGPIN,
    T9_PINYIN
}

data class LiteCandidate(
    val text: String,
    val simplifiedText: String,
    val consumedRawLength: Int,
    val matchedSyllables: Int,
    val score: Long,
    val isFullSentence: Boolean,
    val pinyin: String = "",
    val skipUserLearning: Boolean = false
)

const val CANDIDATE_COLS_PER_ROW = 5
const val CANDIDATE_DEFAULT_WINDOW_ROWS = 1
const val CANDIDATE_EXPANDED_ROWS = 4
const val CANDIDATE_PAGE_SIZE = 5

data class LiteEngineSnapshot(
    val mode: LiteSchemaMode = LiteSchemaMode.ENGLISH,
    val variant: ChineseVariant = ChineseVariant.SIMPLIFIED,
    val rawBuffer: String = "",
    val formattedPreEdit: String = "",
    val candidates: List<LiteCandidate> = emptyList(),
    val candidatePage: Int = 0,
    val highlightedIndex: Int = 0,
    val windowRows: Int = CANDIDATE_DEFAULT_WINDOW_ROWS,
    val pageSize: Int = windowRows * CANDIDATE_COLS_PER_ROW,
    val rowRanges: List<IntRange> = emptyList(),
    val t9SyllableOptions: List<String> = emptyList(),
    val lockedT9Syllables: List<String> = emptyList(),
    val activeSyllableFilter: String? = lockedT9Syllables.firstOrNull()
) {
    val isExpandedWindow: Boolean get() = windowRows > 1
    val hasPreEdit: Boolean get() = rawBuffer.isNotEmpty()
    val highlightedCandidate: LiteCandidate?
        get() = candidates.getOrNull(highlightedIndex) ?: candidates.firstOrNull()
    val activeRowIndex: Int
        get() = when {
            candidates.isEmpty() -> 0
            rowRanges.isNotEmpty() -> rowRanges.indexOfFirst { highlightedIndex in it }.coerceAtLeast(0)
            else -> highlightedIndex.coerceIn(0, candidates.lastIndex) / CANDIDATE_COLS_PER_ROW
        }
    val totalRowCount: Int
        get() = when {
            candidates.isEmpty() -> 0
            rowRanges.isNotEmpty() -> rowRanges.size
            else -> (candidates.size + CANDIDATE_COLS_PER_ROW - 1) / CANDIDATE_COLS_PER_ROW
        }
    val totalPages: Int
        get() {
            val safeRows = windowRows.coerceAtLeast(1)
            return when {
                candidates.isEmpty() || pageSize <= 0 -> 0
                rowRanges.isNotEmpty() -> (rowRanges.size + safeRows - 1) / safeRows
                else -> (candidates.size + pageSize - 1) / pageSize
            }
        }
    val hasPrevPage: Boolean get() = candidatePage > 0
    val hasNextPage: Boolean get() = candidatePage + 1 < totalPages
    val candidateSubPage: Int
        get() = if (candidates.isEmpty()) 0 else activeRowIndex
    val totalSubPages: Int
        get() = if (candidates.isEmpty()) 0 else totalRowCount
    val hasPrevSubPage: Boolean get() = candidateSubPage > 0
    val hasNextSubPage: Boolean get() = candidateSubPage + 1 < totalSubPages
    val isSecondRowSubPage: Boolean
        get() = pageSize > CANDIDATE_COLS_PER_ROW &&
            (highlightedIndex.coerceAtLeast(0) % pageSize) >= CANDIDATE_COLS_PER_ROW
    val pageStartIndex: Int
        get() {
            if (rowRanges.isEmpty()) {
                return (candidatePage.coerceAtLeast(0) * pageSize).coerceAtMost(candidates.size)
            }
            val startRow = candidatePage.coerceAtLeast(0) * windowRows.coerceAtLeast(1)
            return rowRanges.getOrNull(startRow)?.first ?: candidates.size
        }
    val pageCandidates: List<LiteCandidate>
        get() {
            if (candidates.isEmpty() || pageSize <= 0) return emptyList()
            if (rowRanges.isEmpty()) {
                val start = pageStartIndex
                val end = (start + pageSize).coerceAtMost(candidates.size)
                return if (start < end) candidates.subList(start, end) else emptyList()
            }
            val safeRows = windowRows.coerceAtLeast(1)
            val startRow = candidatePage.coerceAtLeast(0) * safeRows
            if (startRow >= rowRanges.size) return emptyList()
            val endRow = (startRow + safeRows).coerceAtMost(rowRanges.size)
            val start = rowRanges[startRow].first.coerceIn(0, candidates.size)
            val end = (rowRanges[endRow - 1].last + 1).coerceIn(start, candidates.size)
            return if (start < end) candidates.subList(start, end) else emptyList()
        }
}

sealed interface LiteKeyResult {
    data object Consumed : LiteKeyResult

    data class CommitText(val text: String) : LiteKeyResult

    data class PassThrough(val text: String) : LiteKeyResult
}

/**
 * Rich offline local input engine contract ported from ShellDeck `:ime-core`.
 * Wrapped by [LiteInputEngine] to implement `:core`'s [dev.duoime.core.engine.InputEngine].
 */
interface LiteLocalEngine {
    val snapshot: LiteEngineSnapshot

    fun setMode(mode: LiteSchemaMode)

    fun toggleChineseEnglish(): LiteSchemaMode

    fun setChineseVariant(variant: ChineseVariant)

    fun toggleChineseVariant(): ChineseVariant

    fun setLearningEnabled(enabled: Boolean) {}

    fun onCharacter(ch: Char): LiteKeyResult

    fun onReplaceLastCharacter(replacement: Char): LiteKeyResult?

    fun onBackspace(): Boolean

    fun onSpace(): LiteKeyResult

    fun onEnter(): LiteKeyResult

    fun selectCandidate(index: Int): LiteKeyResult?

    fun nextCandidatePage(): Boolean = false

    fun prevCandidatePage(): Boolean = false

    fun nextCandidateSubPage(): Boolean = nextCandidatePage()

    fun prevCandidateSubPage(): Boolean = prevCandidatePage()

    fun setCompactSingleRowPaging(enabled: Boolean) {}

    fun setCandidateWindowRows(rows: Int): Boolean = false

    fun setCandidateRowWidthBudget(collapsedMaxUnits: Float? = null, expandedMaxUnits: Float? = null) {}

    fun moveCandidateHighlightHorizontal(forward: Boolean): Boolean = false

    fun moveCandidateHighlightVertical(down: Boolean): Boolean = false

    fun selectSyllableFilter(syllable: String?): Boolean = false

    fun unlockT9SyllablesFrom(index: Int): Boolean = false

    fun clearPreEdit()
}

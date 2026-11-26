/*
 * SPDX-License-Identifier: MIT
 * MIT License
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME by DuoIME Contributors (2026).
 */
package dev.duoime.engine.lite

import dev.duoime.core.model.ChineseVariant
import kotlin.math.abs

/**
 * Pure-local Chinese & English multi-mode input state machine supporting:
 * - ENGLISH direct pass-through
 * - QUANPIN (26-key full Pinyin + initial shorthand)
 * - XIAOHE_SHUANGPIN (Xiaohe double Pinyin)
 * - T9_PINYIN (9-key Pinyin with progressive syllable locking)
 * - SIMPLIFIED / TRADITIONAL conversion, 2D candidate navigation, and local user learning.
 */
class LocalChineseInputEngine
@Suppress("LongParameterList")
constructor(
    private val dictionary: PinyinDictionary,
    private val converter: ChineseConverter = ChineseConverter(),
    private val learningStore: UserLearningStore? = null,
    initialMode: LiteSchemaMode = LiteSchemaMode.ENGLISH,
    initialVariant: ChineseVariant = ChineseVariant.SIMPLIFIED,
    defaultChineseMode: LiteSchemaMode = LiteSchemaMode.QUANPIN,
    initialCompactSingleRowPaging: Boolean = false,
    private val englishLexicon: EnglishLexicon? = null
) : LiteLocalEngine {

    private var currentMode: LiteSchemaMode = initialMode
    private var lastChineseMode: LiteSchemaMode =
        if (initialMode != LiteSchemaMode.ENGLISH) initialMode else defaultChineseMode
    private var currentVariant: ChineseVariant = initialVariant
    private var compactSingleRowPaging: Boolean = initialCompactSingleRowPaging
    private var learningEnabled: Boolean = true

    private val rawBuilder = StringBuilder()
    private var currentTokens: List<SyllableToken> = emptyList()
    private var currentCandidates: List<LiteCandidate> = emptyList()
    private var currentT9SyllableOptions: List<String> = emptyList()
    private val lockedT9Syllables = ArrayList<String>()
    private var highlightedIndex: Int = 0
    private var currentWindowRows: Int = CANDIDATE_DEFAULT_WINDOW_ROWS
    private var collapsedRowMaxUnits: Float = 0f
    private var expandedRowMaxUnits: Float = 0f
    private var currentRowRanges: List<IntRange> = emptyList()

    private val pendingSentenceSimplified = StringBuilder()
    private val pendingSentencePinyin = ArrayList<String>()

    private val effectivePageSize: Int
        get() = (currentWindowRows * CANDIDATE_COLS_PER_ROW).coerceAtLeast(CANDIDATE_COLS_PER_ROW)

    private val currentPage: Int
        get() = pageForHighlight(highlightedIndex)

    override var snapshot: LiteEngineSnapshot = buildSnapshot()
        private set

    override fun setMode(mode: LiteSchemaMode) {
        if (mode != LiteSchemaMode.ENGLISH) {
            lastChineseMode = mode
        }
        if (currentMode != mode) {
            clearPreEditInternal()
            currentMode = mode
            refreshState()
        } else if (currentWindowRows != CANDIDATE_DEFAULT_WINDOW_ROWS) {
            currentWindowRows = CANDIDATE_DEFAULT_WINDOW_ROWS
            snapshot = buildSnapshot()
        }
    }

    override fun toggleChineseEnglish(): LiteSchemaMode {
        val next = if (currentMode == LiteSchemaMode.ENGLISH) lastChineseMode else LiteSchemaMode.ENGLISH
        setMode(next)
        return next
    }

    override fun setChineseVariant(variant: ChineseVariant) {
        if (currentVariant != variant) {
            currentVariant = variant
            refreshState()
        }
    }

    override fun toggleChineseVariant(): ChineseVariant {
        val next = if (currentVariant == ChineseVariant.SIMPLIFIED) {
            ChineseVariant.TRADITIONAL
        } else {
            ChineseVariant.SIMPLIFIED
        }
        setChineseVariant(next)
        return next
    }

    override fun setLearningEnabled(enabled: Boolean) {
        learningEnabled = enabled
        if (!enabled) {
            resetPendingLearning()
        }
    }

    override fun onCharacter(ch: Char): LiteKeyResult {
        if (currentMode == LiteSchemaMode.ENGLISH) {
            return LiteKeyResult.PassThrough(ch.toString())
        }
        if (currentMode == LiteSchemaMode.T9_PINYIN) {
            return onT9Character(ch)
        }
        return onAlphaCharacter(ch)
    }

    private fun onAlphaCharacter(ch: Char): LiteKeyResult {
        if (ch in 'a'..'z' || (ch == '\'' && rawBuilder.isNotEmpty())) {
            if (ch != '\'' || rawBuilder.last() != '\'') {
                rawBuilder.append(ch)
            }
            highlightedIndex = 0
            currentWindowRows = CANDIDATE_DEFAULT_WINDOW_ROWS
            refreshState()
            return LiteKeyResult.Consumed
        }
        if (rawBuilder.isNotEmpty()) {
            handleComposingControlChar(ch)?.let { return it }
        }
        return commitAndEmitNonComposingChar(ch)
    }

    private fun handleComposingControlChar(ch: Char): LiteKeyResult? = when (ch) {
        in '1'..'5' -> selectCandidateInActiveRow(ch - '1')
        in '6'..'9', '0' -> LiteKeyResult.Consumed
        '-' -> {
            prevCandidatePage()
            LiteKeyResult.Consumed
        }
        '=' -> {
            nextCandidatePage()
            LiteKeyResult.Consumed
        }
        else -> null
    }

    private fun selectCandidateInActiveRow(colOffset: Int): LiteKeyResult {
        val globalIdx = if (currentRowRanges.isNotEmpty()) {
            val range = currentRowRanges.getOrNull(activeRowIndex())
            val idx = (range?.first ?: 0) + colOffset
            if (range != null && idx in range) idx else return LiteKeyResult.Consumed
        } else {
            activeRowIndex() * CANDIDATE_COLS_PER_ROW + colOffset
        }
        return selectCandidate(globalIdx) ?: LiteKeyResult.Consumed
    }

    private fun onT9Character(ch: Char): LiteKeyResult {
        val mappedDigit = if (ch in '2'..'9') ch else PinyinTokenizer.charToT9Digit(ch)
        if (mappedDigit != null && ch !in 'A'..'Z') {
            rawBuilder.append(mappedDigit)
            highlightedIndex = 0
            currentWindowRows = CANDIDATE_DEFAULT_WINDOW_ROWS
            refreshState()
            return LiteKeyResult.Consumed
        }
        if (rawBuilder.isNotEmpty()) {
            handleT9ComposingControlChar(ch)?.let { return it }
        }
        return commitAndEmitNonComposingChar(ch)
    }

    private fun handleT9ComposingControlChar(ch: Char): LiteKeyResult? = when (ch) {
        '\'', '1' -> {
            if (rawBuilder.last() != '\'') rawBuilder.append('\'')
            highlightedIndex = 0
            currentWindowRows = CANDIDATE_DEFAULT_WINDOW_ROWS
            refreshState()
            LiteKeyResult.Consumed
        }
        '0' -> onSpace()
        '-' -> {
            prevCandidatePage()
            LiteKeyResult.Consumed
        }
        '=' -> {
            nextCandidatePage()
            LiteKeyResult.Consumed
        }
        else -> null
    }

    private fun commitAndEmitNonComposingChar(ch: Char): LiteKeyResult {
        val prefix = commitPrimaryOrEmpty()
        if (ch in 'A'..'Z') {
            return LiteKeyResult.CommitText(prefix + ch)
        }
        val mappedPunct = LiteCandidateAugmenter.mapChinesePunctuation(ch)
        return if (prefix.isNotEmpty() || mappedPunct != ch.toString()) {
            LiteKeyResult.CommitText(prefix + mappedPunct)
        } else {
            LiteKeyResult.PassThrough(ch.toString())
        }
    }

    override fun onReplaceLastCharacter(replacement: Char): LiteKeyResult? {
        if (currentMode == LiteSchemaMode.ENGLISH || rawBuilder.isEmpty()) return null
        rawBuilder.deleteCharAt(rawBuilder.length - 1)
        highlightedIndex = 0
        if (rawBuilder.isEmpty()) {
            resetPendingLearning()
        }
        refreshState()
        return if (replacement in 'A'..'Z') {
            LiteKeyResult.CommitText(commitPrimaryOrEmpty() + replacement)
        } else {
            onCharacter(replacement)
        }
    }

    override fun onBackspace(): Boolean {
        if (rawBuilder.isEmpty()) {
            currentWindowRows = CANDIDATE_DEFAULT_WINDOW_ROWS
            resetPendingLearning()
            return false
        }
        rawBuilder.deleteCharAt(rawBuilder.length - 1)
        highlightedIndex = 0
        if (rawBuilder.isEmpty()) {
            currentWindowRows = CANDIDATE_DEFAULT_WINDOW_ROWS
            resetPendingLearning()
        }
        refreshState()
        return true
    }

    override fun onSpace(): LiteKeyResult {
        if (rawBuilder.isEmpty()) {
            return LiteKeyResult.PassThrough(" ")
        }
        return selectCandidate(highlightedIndex) ?: selectCandidate(0) ?: run {
            val raw = rawBuilder.toString()
            clearPreEditInternal()
            refreshState()
            LiteKeyResult.CommitText(raw)
        }
    }

    override fun onEnter(): LiteKeyResult {
        if (rawBuilder.isEmpty()) {
            return LiteKeyResult.PassThrough("\r")
        }
        if (currentMode == LiteSchemaMode.T9_PINYIN) {
            return onSpace()
        }
        val raw = rawBuilder.toString()
        clearPreEditInternal()
        refreshState()
        return LiteKeyResult.CommitText(raw)
    }

    override fun selectCandidate(index: Int): LiteKeyResult? {
        if (rawBuilder.isEmpty()) return null
        val cand = currentCandidates.getOrNull(index) ?: return null

        recordCandidateLearningProgress(cand)

        val consumedLen = cand.consumedRawLength.coerceAtMost(rawBuilder.length)
        val remaining = rawBuilder.substring(consumedLen).trimStart('\'')
        rawBuilder.clear()
        rawBuilder.append(remaining)
        trimLockedSyllablesAfterCommit(cand.matchedSyllables, consumedLen)
        highlightedIndex = 0

        if (rawBuilder.isEmpty()) {
            currentWindowRows = CANDIDATE_DEFAULT_WINDOW_ROWS
            flushCompletedLearning()
        }
        refreshState()
        return LiteKeyResult.CommitText(cand.text)
    }

    private fun recordCandidateLearningProgress(cand: LiteCandidate) {
        val isLearningExempt = !learningEnabled || cand.skipUserLearning || (cand.pinyin.isEmpty() && cand.matchedSyllables == 0)
        if (isLearningExempt) {
            resetPendingLearning()
            return
        }
        if (cand.pinyin.isNotEmpty()) {
            cand.pinyin.split(' ').filter { it.isNotEmpty() }.forEach { pendingSentencePinyin.add(it) }
        } else {
            currentTokens.take(cand.matchedSyllables).forEach { pendingSentencePinyin.add(it.display) }
        }
        pendingSentenceSimplified.append(cand.simplifiedText)
    }

    private fun flushCompletedLearning() {
        if (learningEnabled && pendingSentencePinyin.isNotEmpty() && pendingSentenceSimplified.isNotEmpty()) {
            learningStore?.recordCommit(
                pendingSentencePinyin.joinToString(" "),
                pendingSentenceSimplified.toString()
            )
        }
        resetPendingLearning()
    }

    override fun selectSyllableFilter(syllable: String?): Boolean {
        if (currentMode != LiteSchemaMode.T9_PINYIN || rawBuilder.isEmpty()) return false
        val normalized = syllable?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return clearAllLockedT9Syllables()

        val raw = rawBuilder.toString()
        val nextUnlockIndex = PinyinTokenizer.tokenizeT9PathsWithPrefix(raw, lockedT9Syllables).nextUnlockRawIndex
        if (PinyinTokenizer.canLockT9SyllableAt(raw, nextUnlockIndex, normalized) != null) {
            return appendLockedT9Syllable(normalized)
        }
        val missingDigits = PinyinTokenizer.missingT9DigitsForCompletionAt(raw, nextUnlockIndex, normalized)
        if (missingDigits != null) {
            rawBuilder.append(missingDigits)
            return appendLockedT9Syllable(normalized)
        }
        val existingIdx = lockedT9Syllables.lastIndexOf(normalized)
        return if (existingIdx >= 0) unlockT9SyllablesFrom(existingIdx) else false
    }

    override fun unlockT9SyllablesFrom(index: Int): Boolean {
        if (currentMode != LiteSchemaMode.T9_PINYIN || index !in lockedT9Syllables.indices) return false
        while (lockedT9Syllables.size > index) {
            lockedT9Syllables.removeAt(lockedT9Syllables.lastIndex)
        }
        highlightedIndex = 0
        refreshState()
        return true
    }

    override fun setCompactSingleRowPaging(enabled: Boolean) {
        compactSingleRowPaging = enabled
    }

    override fun setCandidateWindowRows(rows: Int): Boolean {
        val target = if (rows > 1) CANDIDATE_EXPANDED_ROWS else CANDIDATE_DEFAULT_WINDOW_ROWS
        if (currentWindowRows == target) return false
        currentWindowRows = target
        recomputeRowRanges()
        snapshot = buildSnapshot()
        return true
    }

    override fun setCandidateRowWidthBudget(collapsedMaxUnits: Float?, expandedMaxUnits: Float?) {
        var changed = false
        val isExpanded = currentWindowRows > CANDIDATE_DEFAULT_WINDOW_ROWS
        if (collapsedMaxUnits != null && collapsedMaxUnits.isFinite()) {
            val safe = collapsedMaxUnits.coerceAtLeast(0f)
            if (abs(safe - collapsedRowMaxUnits) >= 0.25f) {
                collapsedRowMaxUnits = safe
                if (!isExpanded) changed = true
            }
        }
        if (expandedMaxUnits != null && expandedMaxUnits.isFinite()) {
            val safe = expandedMaxUnits.coerceAtLeast(0f)
            if (abs(safe - expandedRowMaxUnits) >= 0.25f) {
                expandedRowMaxUnits = safe
                if (isExpanded) changed = true
            }
        }
        if (changed && rawBuilder.isNotEmpty()) {
            recomputeRowRanges()
            snapshot = buildSnapshot()
        }
    }

    override fun nextCandidateSubPage(): Boolean = stepSubPage(forward = true)

    override fun prevCandidateSubPage(): Boolean = stepSubPage(forward = false)

    private fun stepSubPage(forward: Boolean): Boolean {
        if (rawBuilder.isEmpty() || currentCandidates.isEmpty()) return false
        if (currentRowRanges.isNotEmpty()) {
            val targetRow = activeRowIndex() + if (forward) 1 else -1
            if (targetRow !in currentRowRanges.indices) return false
            highlightedIndex = currentRowRanges[targetRow].first
            snapshot = buildSnapshot()
            return true
        }
        val curSubPage = highlightedIndex.coerceIn(0, currentCandidates.lastIndex) / CANDIDATE_COLS_PER_ROW
        val maxSubPage = ((currentCandidates.size + CANDIDATE_COLS_PER_ROW - 1) / CANDIDATE_COLS_PER_ROW - 1).coerceAtLeast(
            0
        )
        val targetSubPage = curSubPage + if (forward) 1 else -1
        if (targetSubPage !in 0..maxSubPage) return false
        highlightedIndex = targetSubPage * CANDIDATE_COLS_PER_ROW
        snapshot = buildSnapshot()
        return true
    }

    override fun nextCandidatePage(): Boolean = stepPage(forward = true)

    override fun prevCandidatePage(): Boolean = stepPage(forward = false)

    private fun stepPage(forward: Boolean): Boolean {
        if (compactSingleRowPaging && currentWindowRows == CANDIDATE_DEFAULT_WINDOW_ROWS) {
            return stepSubPage(forward)
        }
        if (rawBuilder.isEmpty() || currentCandidates.isEmpty()) return false
        if (currentRowRanges.isNotEmpty()) {
            val rowsPerPage = currentWindowRows.coerceAtLeast(1)
            val maxPage = ((currentRowRanges.size + rowsPerPage - 1) / rowsPerPage - 1).coerceAtLeast(0)
            val targetPage = currentPage + if (forward) 1 else -1
            if (targetPage !in 0..maxPage) return false
            highlightedIndex = currentRowRanges[targetPage * rowsPerPage].first
            snapshot = buildSnapshot()
            return true
        }
        val pageSz = effectivePageSize
        val maxPage = ((currentCandidates.size + pageSz - 1) / pageSz - 1).coerceAtLeast(0)
        val targetPage = currentPage + if (forward) 1 else -1
        if (targetPage !in 0..maxPage) return false
        highlightedIndex = targetPage * pageSz
        snapshot = buildSnapshot()
        return true
    }

    override fun moveCandidateHighlightHorizontal(forward: Boolean): Boolean {
        if (rawBuilder.isEmpty() || currentCandidates.isEmpty()) return false
        val next = highlightedIndex + if (forward) 1 else -1
        if (next !in currentCandidates.indices) return false
        highlightedIndex = next
        snapshot = buildSnapshot()
        return true
    }

    override fun moveCandidateHighlightVertical(down: Boolean): Boolean {
        if (rawBuilder.isEmpty() || currentCandidates.isEmpty()) return false
        if (currentRowRanges.isNotEmpty()) {
            val curRow = activeRowIndex()
            val targetRow = curRow + if (down) 1 else -1
            if (targetRow !in currentRowRanges.indices) return false
            val colOffset = (highlightedIndex - currentRowRanges[curRow].first).coerceAtLeast(0)
            val targetRange = currentRowRanges[targetRow]
            highlightedIndex = (targetRange.first + colOffset).coerceAtMost(targetRange.last)
            snapshot = buildSnapshot()
            return true
        }
        val curRow = highlightedIndex / CANDIDATE_COLS_PER_ROW
        val lastRow = (currentCandidates.size - 1) / CANDIDATE_COLS_PER_ROW
        val targetRow = curRow + if (down) 1 else -1
        if (targetRow !in 0..lastRow) return false
        highlightedIndex = (highlightedIndex + if (down) CANDIDATE_COLS_PER_ROW else -CANDIDATE_COLS_PER_ROW)
            .coerceIn(0, currentCandidates.lastIndex)
        snapshot = buildSnapshot()
        return true
    }

    override fun clearPreEdit() {
        clearPreEditInternal()
        refreshState()
    }

    private fun activeRowIndex(index: Int = highlightedIndex): Int {
        if (currentCandidates.isEmpty()) return 0
        val clamped = index.coerceIn(0, currentCandidates.lastIndex)
        if (currentRowRanges.isEmpty()) return clamped / CANDIDATE_COLS_PER_ROW
        return currentRowRanges.indexOfFirst { clamped in it }.coerceAtLeast(0)
    }

    private fun pageForHighlight(index: Int): Int = when {
        currentCandidates.isEmpty() -> 0
        currentRowRanges.isNotEmpty() -> activeRowIndex(index) / currentWindowRows.coerceAtLeast(1)
        else -> index.coerceIn(0, currentCandidates.lastIndex) / effectivePageSize
    }

    private fun recomputeRowRanges() {
        val maxUnits = if (currentWindowRows > CANDIDATE_DEFAULT_WINDOW_ROWS) {
            expandedRowMaxUnits
        } else {
            collapsedRowMaxUnits
        }
        currentRowRanges = if (maxUnits > 0f && currentCandidates.isNotEmpty()) {
            CandidateRowLayout.packRows(
                candidates = currentCandidates,
                maxRowUnits = maxUnits,
                showBadges = currentMode != LiteSchemaMode.T9_PINYIN
            )
        } else {
            emptyList()
        }
    }

    private fun clearAllLockedT9Syllables(): Boolean {
        if (lockedT9Syllables.isEmpty()) return false
        lockedT9Syllables.clear()
        highlightedIndex = 0
        refreshState()
        return true
    }

    private fun appendLockedT9Syllable(syllable: String): Boolean {
        lockedT9Syllables.add(syllable)
        highlightedIndex = 0
        refreshState()
        return true
    }

    private fun trimLockedSyllablesAfterCommit(matchedSyllables: Int, consumedLen: Int) {
        if (lockedT9Syllables.isEmpty()) return
        if (matchedSyllables <= 0 && consumedLen > 0) {
            lockedT9Syllables.clear()
            return
        }
        val dropCount = matchedSyllables.coerceIn(0, lockedT9Syllables.size)
        if (dropCount >= lockedT9Syllables.size) {
            lockedT9Syllables.clear()
        } else if (dropCount > 0) {
            repeat(dropCount) { lockedT9Syllables.removeAt(0) }
        }
    }

    private fun commitPrimaryOrEmpty(): String {
        if (rawBuilder.isEmpty()) return ""
        val sb = StringBuilder()
        var safety = 0
        while (rawBuilder.isNotEmpty() && safety++ < 16) {
            val res = selectCandidate(highlightedIndex) ?: selectCandidate(0)
            if (res is LiteKeyResult.CommitText) {
                sb.append(res.text)
            } else {
                sb.append(rawBuilder.toString())
                clearPreEditInternal()
                break
            }
        }
        refreshState()
        return sb.toString()
    }

    private fun clearPreEditInternal() {
        rawBuilder.clear()
        currentTokens = emptyList()
        currentCandidates = emptyList()
        currentRowRanges = emptyList()
        currentT9SyllableOptions = emptyList()
        lockedT9Syllables.clear()
        highlightedIndex = 0
        currentWindowRows = CANDIDATE_DEFAULT_WINDOW_ROWS
        resetPendingLearning()
    }

    private fun resetPendingLearning() {
        pendingSentenceSimplified.clear()
        pendingSentencePinyin.clear()
    }

    private fun refreshState() {
        val raw = rawBuilder.toString()
        if (raw.isEmpty() || currentMode == LiteSchemaMode.ENGLISH) {
            currentTokens = emptyList()
            currentCandidates = emptyList()
            currentRowRanges = emptyList()
            currentT9SyllableOptions = emptyList()
            lockedT9Syllables.clear()
            highlightedIndex = 0
            snapshot = buildSnapshot()
            return
        }

        if (currentMode == LiteSchemaMode.T9_PINYIN) {
            refreshT9State(raw)
        } else {
            refreshNonT9State(raw)
        }

        recomputeRowRanges()
        highlightedIndex = if (currentCandidates.isEmpty()) 0 else highlightedIndex.coerceIn(0, currentCandidates.lastIndex)
        snapshot = buildSnapshot()
    }

    private fun refreshT9State(raw: String) {
        val tokenized = PinyinTokenizer.tokenizeT9PathsWithPrefix(raw, lockedT9Syllables)
        if (tokenized.validLockedSyllables.size != lockedT9Syllables.size) {
            lockedT9Syllables.clear()
            lockedT9Syllables.addAll(tokenized.validLockedSyllables)
        }
        currentTokens = tokenized.paths.firstOrNull().orEmpty()
        currentCandidates = dictionary.queryMultiPath(
            tokenPaths = tokenized.paths,
            variant = currentVariant,
            converter = converter,
            learningStore = learningStore
        )
        currentT9SyllableOptions = LiteCandidateAugmenter.buildNextT9SyllableOptions(
            raw = raw,
            nextUnlockRawIndex = tokenized.nextUnlockRawIndex,
            lockedT9Syllables = lockedT9Syllables,
            lockedPaths = tokenized.paths,
            candidates = currentCandidates
        )
    }

    private fun refreshNonT9State(raw: String) {
        lockedT9Syllables.clear()
        currentT9SyllableOptions = emptyList()
        val withMath = if (currentMode == LiteSchemaMode.QUANPIN) {
            val paths = PinyinTokenizer.tokenizeQuanpinPaths(raw)
            currentTokens = paths.firstOrNull().orEmpty()
            val base = dictionary.queryMultiPath(
                tokenPaths = paths,
                variant = currentVariant,
                converter = converter,
                learningStore = learningStore
            )
            LiteCandidateAugmenter.injectMathSymbolAliases(raw, currentTokens, base)
        } else {
            currentTokens = PinyinTokenizer.tokenize(raw, currentMode)
            dictionary.query(
                tokens = currentTokens,
                variant = currentVariant,
                converter = converter,
                learningStore = learningStore
            )
        }
        currentCandidates = LiteCandidateAugmenter.injectMixedEnglishCandidates(
            raw = raw,
            currentTokens = currentTokens,
            baseCandidates = withMath,
            englishLexicon = englishLexicon
        )
    }

    private fun buildSnapshot(): LiteEngineSnapshot {
        val raw = rawBuilder.toString()
        val clampedHighlight = if (currentCandidates.isEmpty()) {
            0
        } else {
            highlightedIndex.coerceIn(
                0,
                currentCandidates.lastIndex
            )
        }
        val formatted = LiteCandidateAugmenter.formatPreEditDisplay(
            raw = raw,
            mode = currentMode,
            currentTokens = currentTokens,
            highlighted = currentCandidates.getOrNull(clampedHighlight),
            lockedT9Syllables = lockedT9Syllables
        )
        val lockedCopy = lockedT9Syllables.toList()
        return LiteEngineSnapshot(
            mode = currentMode,
            variant = currentVariant,
            rawBuffer = raw,
            formattedPreEdit = formatted,
            candidates = currentCandidates,
            candidatePage = pageForHighlight(clampedHighlight),
            highlightedIndex = clampedHighlight,
            windowRows = currentWindowRows,
            pageSize = effectivePageSize,
            rowRanges = currentRowRanges,
            t9SyllableOptions = currentT9SyllableOptions,
            lockedT9Syllables = lockedCopy,
            activeSyllableFilter = lockedCopy.firstOrNull()
        )
    }
}

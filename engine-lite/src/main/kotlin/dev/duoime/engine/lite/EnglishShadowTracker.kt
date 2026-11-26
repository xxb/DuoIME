/*
 * SPDX-License-Identifier: MIT
 * MIT License
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME by DuoIME Contributors (2026).
 */
package dev.duoime.engine.lite

data class AutoCorrectAction(
    val deleteCodePoints: Int,
    val originalWord: String,
    val correctedWord: String,
    val delimiter: Char
)

data class AutoCorrectUndoAction(
    val deleteCodePoints: Int,
    val restoredWord: String,
    val correctedWord: String,
    val trailingChar: Char
)

data class AutoCorrectionRecord(
    val originalWord: String,
    val correctedWord: String,
    val trailingChar: Char
)

data class EnglishShadowState(
    val shadowWord: String = "",
    val isTokenExempt: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val lastAutoCorrection: AutoCorrectionRecord? = null
) {
    val canUndoAutoCorrect: Boolean get() = lastAutoCorrection != null
}

/**
 * Pure-Kotlin non-blocking shadow word tracker and terminal-safety state machine
 * for English word suggestions and optional auto-correction.
 */
class EnglishShadowTracker(
    private val lexiconProvider: () -> EnglishLexicon? = { null }
) {
    constructor(lexicon: EnglishLexicon) : this({ lexicon })

    private val tokenBuilder = StringBuilder()
    private var tokenExempt: Boolean = false
    private var prevExemptBeforeLastChar: Boolean = false
    private var suppressAutocorrectForToken: Boolean = false
    private var lastAutoCorrectionRecord: AutoCorrectionRecord? = null

    val shadowWord: String
        get() = tokenBuilder.toString()

    val isTokenExempt: Boolean
        get() = tokenExempt

    val lastAutoCorrection: AutoCorrectionRecord?
        get() = lastAutoCorrectionRecord

    val suggestions: List<String>
        get() = currentSuggestions()

    val state: EnglishShadowState
        get() = EnglishShadowState(
            shadowWord = shadowWord,
            isTokenExempt = tokenExempt,
            suggestions = currentSuggestions(),
            lastAutoCorrection = lastAutoCorrectionRecord
        )

    fun currentSuggestions(maxResults: Int = DEFAULT_MAX_SUGGESTIONS): List<String> {
        if (tokenExempt || tokenBuilder.length < MIN_SUGGESTION_TOKEN_LENGTH) return emptyList()
        val lexicon = lexiconProvider() ?: return emptyList()
        return lexicon.suggest(tokenBuilder.toString(), maxResults).suggestions
    }

    fun onChar(ch: Char, isHiddenPrompt: Boolean = false, hasModifier: Boolean = false, isTuiActive: Boolean = false) {
        if (hasModifier || isHiddenPrompt || isTuiActive) {
            reset()
            return
        }
        lastAutoCorrectionRecord = null
        if (isTokenBoundaryChar(ch)) {
            clearCurrentToken()
            return
        }
        prevExemptBeforeLastChar = tokenExempt
        suppressAutocorrectForToken = false
        tokenBuilder.append(ch)
        if (isTokenExemptChar(ch)) {
            tokenExempt = true
        }
    }

    fun onBackspace(isHiddenPrompt: Boolean = false, isTuiActive: Boolean = false): AutoCorrectUndoAction? {
        if (isHiddenPrompt || isTuiActive) {
            reset()
            return null
        }
        val pendingUndo = lastAutoCorrectionRecord
        if (pendingUndo != null) {
            restoreWordState(pendingUndo.originalWord, suppressAutocorrect = true)
            val correctedCp = pendingUndo.correctedWord.codePointCount(0, pendingUndo.correctedWord.length)
            val trailingCp = if (pendingUndo.trailingChar == '\u0000') 0 else 1
            return AutoCorrectUndoAction(
                deleteCodePoints = correctedCp + trailingCp,
                restoredWord = pendingUndo.originalWord,
                correctedWord = pendingUndo.correctedWord,
                trailingChar = pendingUndo.trailingChar
            )
        }
        if (tokenBuilder.isEmpty()) return null
        popLastCodePoint()
        if (tokenBuilder.isEmpty()) {
            clearCurrentToken()
        }
        return null
    }

    fun onCommitBoundary(
        delimiter: Char,
        autocorrectEnabled: Boolean,
        isTuiActive: Boolean,
        isHiddenPrompt: Boolean
    ): AutoCorrectAction? {
        val current = tokenBuilder.toString()
        val wasExempt = tokenExempt
        val wasSuppressed = suppressAutocorrectForToken
        clearCurrentToken()

        val blocked = isHiddenPrompt ||
            isTuiActive ||
            !autocorrectEnabled ||
            wasExempt ||
            wasSuppressed ||
            current.isEmpty() ||
            delimiter !in AUTO_CORRECT_DELIMITERS
        if (blocked) {
            lastAutoCorrectionRecord = null
            return null
        }

        val correction = lexiconProvider()?.resolveAutoCorrection(current)
        if (correction == null || correction == current) {
            lastAutoCorrectionRecord = null
            return null
        }

        val deleteCount = current.codePointCount(0, current.length)
        lastAutoCorrectionRecord = AutoCorrectionRecord(
            originalWord = current,
            correctedWord = correction,
            trailingChar = delimiter
        )
        return AutoCorrectAction(
            deleteCodePoints = deleteCount,
            originalWord = current,
            correctedWord = correction,
            delimiter = delimiter
        )
    }

    fun onSuggestionApplied(replacement: String, isTuiActive: Boolean = false, isHiddenPrompt: Boolean = false): Int {
        if (isTuiActive || isHiddenPrompt) {
            reset()
            return 0
        }
        if (tokenExempt || tokenBuilder.isEmpty() || replacement.isEmpty()) {
            return 0
        }
        val deleteCount = tokenBuilder.toString().codePointCount(0, tokenBuilder.length)
        reset()
        return deleteCount
    }

    fun onSwipeUpAdjusted(
        previousCodePoints: Int,
        replacementText: String,
        isHiddenPrompt: Boolean = false,
        isTuiActive: Boolean = false
    ) {
        if (isHiddenPrompt || isTuiActive) {
            reset()
            return
        }
        lastAutoCorrectionRecord = null
        if (previousCodePoints > 0 && tokenBuilder.isNotEmpty()) {
            val existingCp = tokenBuilder.toString().codePointCount(0, tokenBuilder.length)
            val toPop = minOf(previousCodePoints, existingCp)
            repeat(toPop) {
                popLastCodePoint()
            }
            if (tokenBuilder.isEmpty()) {
                clearCurrentToken()
            } else if (previousCodePoints == 1) {
                tokenExempt = prevExemptBeforeLastChar
            }
        }
        for (ch in replacementText) {
            onChar(ch = ch, isHiddenPrompt = false, hasModifier = false, isTuiActive = false)
        }
    }

    fun restoreBeforeBoundaryAutoCorrect(record: AutoCorrectionRecord) {
        restoreWordState(record.originalWord, suppressAutocorrect = false)
    }

    fun reset() {
        clearCurrentToken()
        lastAutoCorrectionRecord = null
    }

    private fun restoreWordState(word: String, suppressAutocorrect: Boolean) {
        lastAutoCorrectionRecord = null
        tokenBuilder.clear()
        tokenBuilder.append(word)
        tokenExempt = false
        prevExemptBeforeLastChar = false
        suppressAutocorrectForToken = suppressAutocorrect
    }

    private fun clearCurrentToken() {
        tokenBuilder.clear()
        tokenExempt = false
        prevExemptBeforeLastChar = false
        suppressAutocorrectForToken = false
    }

    private fun popLastCodePoint() {
        if (tokenBuilder.isEmpty()) return
        val start = tokenBuilder.offsetByCodePoints(tokenBuilder.length, -1)
        tokenBuilder.delete(start, tokenBuilder.length)
    }

    private fun isTokenBoundaryChar(ch: Char): Boolean = ch.isWhitespace() || ch in BOUNDARY_PUNCTUATION

    private fun isTokenExemptChar(ch: Char): Boolean = ch !in 'a'..'z'

    companion object {
        private const val DEFAULT_MAX_SUGGESTIONS = 3
        private const val MIN_SUGGESTION_TOKEN_LENGTH = 2

        private val AUTO_CORRECT_DELIMITERS = setOf(' ', ',', '!', '?', ';')
        private val BOUNDARY_PUNCTUATION = setOf(',', '!', '?', ';', '(', ')', '"')
    }
}

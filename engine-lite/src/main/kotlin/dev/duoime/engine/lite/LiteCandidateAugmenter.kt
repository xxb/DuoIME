/*
 * SPDX-License-Identifier: MIT
 * MIT License
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME by DuoIME Contributors (2026).
 */
package dev.duoime.engine.lite

internal object LiteCandidateAugmenter {
    private const val MIN_MIXED_ENGLISH_LEN = 2

    val MATH_SYMBOL_ALIASES: List<Pair<String, List<String>>> = listOf(
        "alpha" to listOf("α"),
        "beta" to listOf("β"),
        "gamma" to listOf("γ"),
        "delta" to listOf("δ", "Δ"),
        "epsilon" to listOf("ε"),
        "zeta" to listOf("ζ"),
        "eta" to listOf("η"),
        "theta" to listOf("θ", "Θ"),
        "kappa" to listOf("κ"),
        "lambda" to listOf("λ", "Λ"),
        "mu" to listOf("μ"),
        "nu" to listOf("ν"),
        "pi" to listOf("π"),
        "rho" to listOf("ρ"),
        "sigma" to listOf("σ", "Σ"),
        "tau" to listOf("τ"),
        "phi" to listOf("φ", "Φ"),
        "psi" to listOf("ψ"),
        "omega" to listOf("ω", "Ω"),
        "sum" to listOf("∑"),
        "int" to listOf("∫"),
        "sqrt" to listOf("√"),
        "inf" to listOf("∞"),
        "infty" to listOf("∞"),
        "approx" to listOf("≈"),
        "neq" to listOf("≠"),
        "leq" to listOf("≤"),
        "geq" to listOf("≥"),
        "equiv" to listOf("≡"),
        "pm" to listOf("±"),
        "times" to listOf("×"),
        "div" to listOf("÷"),
        "to" to listOf("→"),
        "iff" to listOf("⇔"),
        "implies" to listOf("⇒"),
        "in" to listOf("∈"),
        "notin" to listOf("∉"),
        "subset" to listOf("⊂"),
        "cup" to listOf("∪"),
        "cap" to listOf("∩"),
        "emptyset" to listOf("∅"),
        "forall" to listOf("∀"),
        "exists" to listOf("∃"),
        "partial" to listOf("∂"),
        "nabla" to listOf("∇"),
        "oplus" to listOf("⊕"),
        "because" to listOf("∵"),
        "therefore" to listOf("∴")
    )

    fun mapChinesePunctuation(ch: Char): String = when (ch) {
        ',' -> "，"
        '.' -> "。"
        '?' -> "？"
        '!' -> "！"
        ';' -> "；"
        ':' -> "："
        '(' -> "（"
        ')' -> "）"
        '<' -> "《"
        '>' -> "》"
        '\\' -> "、"
        else -> ch.toString()
    }

    fun injectMathSymbolAliases(
        raw: String,
        currentTokens: List<SyllableToken>,
        baseCandidates: List<LiteCandidate>
    ): List<LiteCandidate> {
        if (raw.isEmpty() || raw.any { it !in 'a'..'z' && it !in 'A'..'Z' }) {
            return baseCandidates
        }
        val query = raw.lowercase()
        val exactCandidates = ArrayList<LiteCandidate>(4)
        val prefixCandidates = ArrayList<LiteCandidate>(8)
        collectMathMatches(raw, query, exactCandidates, prefixCandidates)

        if (exactCandidates.isEmpty() && prefixCandidates.isEmpty()) {
            return baseCandidates
        }
        return mergeMathAliases(raw, currentTokens, baseCandidates, exactCandidates, prefixCandidates)
    }

    private fun collectMathMatches(
        raw: String,
        query: String,
        exactOut: MutableList<LiteCandidate>,
        prefixOut: MutableList<LiteCandidate>
    ) {
        for ((alias, symbols) in MATH_SYMBOL_ALIASES) {
            if (alias == query) {
                symbols.forEachIndexed { idx, sym ->
                    exactOut.add(symbolCandidate(sym, raw.length, 2_000_000L - idx))
                }
            } else if (alias.startsWith(query)) {
                symbols.forEachIndexed { idx, sym ->
                    prefixOut.add(symbolCandidate(sym, raw.length, 150_000L - idx - alias.length))
                }
            }
        }
    }

    private fun mergeMathAliases(
        raw: String,
        currentTokens: List<SyllableToken>,
        baseCandidates: List<LiteCandidate>,
        exactCandidates: List<LiteCandidate>,
        prefixCandidates: List<LiteCandidate>
    ): List<LiteCandidate> {
        val merged = ArrayList<LiteCandidate>(exactCandidates.size + baseCandidates.size + prefixCandidates.size)
        val seenText = HashSet<String>(exactCandidates.size + baseCandidates.size + prefixCandidates.size)
        fun appendUnique(c: LiteCandidate) {
            if (seenText.add(c.text)) merged.add(c)
        }

        exactCandidates.forEach(::appendUnique)
        val allExactChineseSyllables = currentTokens.isNotEmpty() && currentTokens.none { it.isPrefixOnly }
        if (raw.length == 1 || allExactChineseSyllables) {
            val headCount = minOf(baseCandidates.size, if (allExactChineseSyllables) 8 else 6)
            for (i in 0 until headCount) appendUnique(baseCandidates[i])
            prefixCandidates.forEach(::appendUnique)
            for (i in headCount until baseCandidates.size) appendUnique(baseCandidates[i])
        } else {
            prefixCandidates.forEach(::appendUnique)
            baseCandidates.forEach(::appendUnique)
        }
        return merged
    }

    fun injectMixedEnglishCandidates(
        raw: String,
        currentTokens: List<SyllableToken>,
        baseCandidates: List<LiteCandidate>,
        englishLexicon: EnglishLexicon?
    ): List<LiteCandidate> {
        val lexicon = englishLexicon ?: return baseCandidates
        if (raw.length < MIN_MIXED_ENGLISH_LEN || !raw.all { it in 'a'..'z' }) return baseCandidates
        val enRes = lexicon.suggest(raw, maxResults = 4)
        val exactWord = if (enRes.isExactWord) raw else null
        val prefixWords = enRes.suggestions.filter { it != raw && it.startsWith(raw) }.take(2)
        if (exactWord == null && prefixWords.isEmpty()) return baseCandidates

        val hasFullExactChinese = currentTokens.isNotEmpty() &&
            currentTokens.none { it.isPrefixOnly } &&
            baseCandidates.any {
                !it.skipUserLearning && it.isFullSentence && it.consumedRawLength == raw.length && it.matchedSyllables > 0
            }
        return mergeMixedEnglishCandidates(raw, baseCandidates, exactWord, prefixWords, hasFullExactChinese)
    }

    private fun mergeMixedEnglishCandidates(
        raw: String,
        baseCandidates: List<LiteCandidate>,
        exactWord: String?,
        prefixWords: List<String>,
        hasFullExactChinese: Boolean
    ): List<LiteCandidate> {
        val exactMathPrefix = baseCandidates.takeWhile {
            it.skipUserLearning && it.consumedRawLength == raw.length && it.matchedSyllables == 0 && it.score >= 1_500_000L
        }
        val restChinese = baseCandidates.drop(exactMathPrefix.size)
        val merged = ArrayList<LiteCandidate>(baseCandidates.size + prefixWords.size + 1)
        val seenText = HashSet<String>(baseCandidates.size + prefixWords.size + 2)
        fun appendUnique(c: LiteCandidate) {
            if (seenText.add(c.text)) merged.add(c)
        }
        fun appendWord(word: String, rankScore: Long) {
            appendUnique(symbolCandidate(word, raw.length, rankScore))
        }

        exactMathPrefix.forEach(::appendUnique)
        if (!hasFullExactChinese) {
            if (exactWord != null) appendWord(exactWord, 2_500_000L)
            if (raw.length >= 3 || restChinese.isEmpty()) {
                prefixWords.forEachIndexed { idx, w -> appendWord(w, 1_900_000L - idx) }
            }
            restChinese.take(2).forEach(::appendUnique)
            if (raw.length < 3 && restChinese.isNotEmpty()) {
                prefixWords.forEachIndexed { idx, w -> appendWord(w, 400_000L - idx) }
            }
            restChinese.drop(2).forEach(::appendUnique)
        } else {
            restChinese.take(2).forEach(::appendUnique)
            if (exactWord != null) appendWord(exactWord, 850_000L)
            restChinese.drop(2).take(3).forEach(::appendUnique)
            prefixWords.forEachIndexed { idx, w -> appendWord(w, 350_000L - idx) }
            restChinese.drop(5).forEach(::appendUnique)
        }
        return merged
    }

    fun formatPreEditDisplay(
        raw: String,
        mode: LiteSchemaMode,
        currentTokens: List<SyllableToken>,
        highlighted: LiteCandidate?,
        lockedT9Syllables: List<String>
    ): String {
        if (raw.isEmpty()) return ""
        return if (mode == LiteSchemaMode.T9_PINYIN) {
            formatT9PreEdit(raw, currentTokens, highlighted, lockedT9Syllables)
        } else {
            formatNonT9PreEdit(raw, mode, currentTokens, highlighted)
        }
    }

    private fun formatT9PreEdit(
        raw: String,
        currentTokens: List<SyllableToken>,
        hl: LiteCandidate?,
        lockedT9Syllables: List<String>
    ): String {
        val trailingSep = raw.endsWith('\'')
        if (hl != null && hl.pinyin.isNotEmpty() && hl.consumedRawLength > 0) {
            val formatted = buildAlignedT9PreEdit(raw, hl, lockedT9Syllables)
            if (formatted != null) {
                return if (trailingSep) "$formatted'" else formatted
            }
        }
        val fallback = formatTokensPreEdit(raw, currentTokens)
        return when {
            fallback.isEmpty() -> raw
            trailingSep -> "$fallback'"
            else -> fallback
        }
    }

    private fun buildAlignedT9PreEdit(raw: String, hl: LiteCandidate, lockedT9Syllables: List<String>): String? {
        val consumed = hl.consumedRawLength.coerceIn(0, raw.length)
        val typedDigits = raw.substring(0, consumed).count { it != '\'' }
        val syls = hl.pinyin.split(' ').filter { it.isNotEmpty() }
        if (syls.isEmpty() || typedDigits <= 0) return null

        var rem = typedDigits
        val parts = ArrayList<String>(syls.size + 2)
        for ((idx, syl) in syls.withIndex()) {
            val minLeft = syls.size - 1 - idx
            val maxForThis = (rem - minLeft).coerceAtLeast(1)
            val takeLen = minOf(syl.length, maxForThis)
            parts.add(syl.take(takeLen))
            rem = (rem - takeLen).coerceAtLeast(0)
        }
        val unconsumedRaw = raw.substring(consumed).trim('\'')
        if (unconsumedRaw.isNotEmpty()) {
            appendT9TailTokens(unconsumedRaw, hl.matchedSyllables, lockedT9Syllables, parts)
        }
        return parts.joinToString("'")
    }

    private fun formatNonT9PreEdit(
        raw: String,
        mode: LiteSchemaMode,
        currentTokens: List<SyllableToken>,
        hl: LiteCandidate?
    ): String {
        val trailingSep = raw.endsWith('\'')
        val base = if (mode == LiteSchemaMode.QUANPIN && hl != null && hl.pinyin.isNotEmpty() && hl.consumedRawLength > 0) {
            formatAlignedQuanpinPreEdit(raw, currentTokens, hl)
        } else {
            formatTokensPreEdit(raw, currentTokens)
        }
        return when {
            base.isEmpty() -> raw
            trailingSep -> "$base'"
            else -> base
        }
    }

    private fun formatAlignedQuanpinPreEdit(
        raw: String,
        currentTokens: List<SyllableToken>,
        hl: LiteCandidate
    ): String {
        val consumed = hl.consumedRawLength.coerceIn(0, raw.length)
        val typedLetters = raw.substring(0, consumed).count { it != '\'' }
        val syls = hl.pinyin.split(' ').filter { it.isNotEmpty() }
        if (syls.isEmpty() || syls.sumOf { it.length } != typedLetters) {
            return formatTokensPreEdit(raw, currentTokens)
        }
        val parts = ArrayList<String>(syls.size + 2)
        parts.addAll(syls)
        val unconsumed = raw.substring(consumed).trim('\'')
        if (unconsumed.isNotEmpty()) {
            val tailTokens = PinyinTokenizer.tokenizeQuanpin(unconsumed)
            if (tailTokens.isNotEmpty()) {
                for (t in tailTokens) {
                    parts.add(if (t.isPrefixOnly) unconsumed.substring(t.rawStart, t.rawEnd) else t.display)
                }
            } else {
                parts.add(unconsumed)
            }
        }
        return parts.joinToString("'")
    }

    private fun formatTokensPreEdit(raw: String, tokens: List<SyllableToken>): String =
        tokens.joinToString("'") { token ->
            if (token.isPrefixOnly) raw.substring(token.rawStart, token.rawEnd) else token.display
        }

    private fun appendT9TailTokens(
        unconsumedRaw: String,
        matchedSyllables: Int,
        lockedT9Syllables: List<String>,
        parts: MutableList<String>
    ) {
        val lockOffset = matchedSyllables.coerceAtLeast(0)
        val remainingLocks = if (lockOffset < lockedT9Syllables.size) {
            lockedT9Syllables.subList(lockOffset, lockedT9Syllables.size)
        } else {
            emptyList()
        }
        val tailTokens = PinyinTokenizer
            .tokenizeT9PathsWithPrefix(unconsumedRaw, remainingLocks)
            .paths
            .firstOrNull()
            .orEmpty()
        for (t in tailTokens) {
            parts.add(if (t.isPrefixOnly) unconsumedRaw.substring(t.rawStart, t.rawEnd) else t.display)
        }
    }

    private const val MAX_T9_SYLLABLE_CHIPS = 12

    fun buildNextT9SyllableOptions(
        raw: String,
        nextUnlockRawIndex: Int,
        lockedT9Syllables: List<String>,
        lockedPaths: List<List<SyllableToken>>,
        candidates: List<LiteCandidate>
    ): List<String> {
        if (!PinyinTokenizer.hasRemainingT9Digits(raw, nextUnlockRawIndex)) return emptyList()
        val lockCount = lockedT9Syllables.size
        val ordered = LinkedHashSet<String>()

        collectCandidateSyllables(lockedT9Syllables, candidates, ordered) { syl ->
            PinyinTokenizer.canLockT9SyllableAt(raw, nextUnlockRawIndex, syl) != null
        }
        collectExactPathSyllables(raw, nextUnlockRawIndex, lockCount, lockedPaths, ordered)

        val trailingChunk = PinyinTokenizer.trailingT9ChunkAt(raw, nextUnlockRawIndex)
        if (trailingChunk != null && (lockCount > 0 || trailingChunk.length >= 2)) {
            collectCandidateSyllables(lockedT9Syllables, candidates, ordered) { syl ->
                PinyinTokenizer.missingT9DigitsForCompletionAt(raw, nextUnlockRawIndex, syl) != null
            }
            appendT9PrefixFallbacks(raw, nextUnlockRawIndex, ordered)
        }

        return if (ordered.size <= 1 && lockCount == 0) emptyList() else ordered.toList()
    }

    private fun extractNextSyllableAt(candidate: LiteCandidate, lockedT9Syllables: List<String>): String? {
        if (candidate.pinyin.isEmpty()) return null
        val parts = candidate.pinyin.split(' ').filter { it.isNotEmpty() }
        val lockCount = lockedT9Syllables.size
        if (parts.size <= lockCount) return null
        for (i in 0 until lockCount) {
            if (parts[i] != lockedT9Syllables[i]) return null
        }
        return parts[lockCount]
    }

    private fun collectCandidateSyllables(
        lockedT9Syllables: List<String>,
        candidates: List<LiteCandidate>,
        out: LinkedHashSet<String>,
        predicate: (String) -> Boolean
    ) {
        for (cand in candidates) {
            if (out.size >= MAX_T9_SYLLABLE_CHIPS) return
            val nextSyl = extractNextSyllableAt(cand, lockedT9Syllables) ?: continue
            if (predicate(nextSyl)) out.add(nextSyl)
        }
    }

    private fun collectExactPathSyllables(
        raw: String,
        nextUnlockRawIndex: Int,
        lockCount: Int,
        lockedPaths: List<List<SyllableToken>>,
        out: LinkedHashSet<String>
    ) {
        for (path in lockedPaths) {
            if (out.size >= MAX_T9_SYLLABLE_CHIPS) return
            val tok = path.getOrNull(lockCount) ?: continue
            if (tok.isPrefixOnly) continue
            for (opt in tok.options) {
                if (out.size >= MAX_T9_SYLLABLE_CHIPS) return
                if (PinyinTokenizer.canLockT9SyllableAt(raw, nextUnlockRawIndex, opt) != null) {
                    out.add(opt)
                }
            }
        }
    }

    private fun appendT9PrefixFallbacks(raw: String, nextUnlockRawIndex: Int, out: LinkedHashSet<String>) {
        if (out.size >= MAX_T9_SYLLABLE_CHIPS) return
        val completions = PinyinTokenizer.t9PrefixCompletionsAt(raw, nextUnlockRawIndex, MAX_T9_SYLLABLE_CHIPS)
        for (syl in completions) {
            out.add(syl)
            if (out.size >= MAX_T9_SYLLABLE_CHIPS) return
        }
    }

    private fun symbolCandidate(text: String, rawLength: Int, score: Long): LiteCandidate = LiteCandidate(
        text = text,
        simplifiedText = text,
        consumedRawLength = rawLength,
        matchedSyllables = 0,
        score = score,
        isFullSentence = true,
        pinyin = "",
        skipUserLearning = true
    )
}

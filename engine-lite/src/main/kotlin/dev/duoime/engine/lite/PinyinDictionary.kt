/*
 * SPDX-License-Identifier: MIT
 * MIT License
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME by DuoIME Contributors (2026).
 */
package dev.duoime.engine.lite

import dev.duoime.core.model.ChineseVariant
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

data class LexiconWord(
    val word: String,
    val frequency: Int,
    val pinyinKey: String
)

/**
 * Syllable-level Prefix Trie dictionary with Viterbi multi-word sentence composition.
 */
class PinyinDictionary private constructor(
    private val root: TrieNode,
    val entryCount: Int
) {
    private class TrieNode {
        val children = HashMap<String, TrieNode>(4)
        var words: MutableList<LexiconWord>? = null
    }

    private data class SpanMatch(
        val startToken: Int,
        val endToken: Int,
        val word: LexiconWord,
        val effectiveScore: Long
    )

    private data class ComposedSentence(
        val simplified: String,
        val pinyinKey: String,
        val score: Long
    )

    private class TraversalBudget(var remaining: Int = MAX_TRIE_VISITS_PER_START)

    private class SpanSearchContext(
        val tokens: List<SyllableToken>,
        val startToken: Int,
        val learningStore: UserLearningStore?,
        val budget: TraversalBudget,
        val out: MutableList<SpanMatch>
    )

    private class PathEval(
        val tokens: List<SyllableToken>,
        val prefixCount: Int,
        val spansByStart: Array<ArrayList<SpanMatch>>
    )

    private class CandidateCollector(
        private val variant: ChineseVariant,
        private val converter: ChineseConverter,
        private val maxCandidates: Int
    ) {
        val candidates = ArrayList<LiteCandidate>(maxCandidates)
        private val seenSimplified = HashSet<String>(maxCandidates * 2)

        fun addFull(simplified: String, pinyin: String, consumedRawLength: Int, matchedSyllables: Int, score: Long) =
            addCandidate(
                CandidateDraft(
                    simplified = simplified,
                    pinyin = pinyin,
                    consumedRawLength = consumedRawLength,
                    matchedSyllables = matchedSyllables,
                    score = score,
                    isFullSentence = true
                )
            )

        fun addSpan(span: SpanMatch, endRaw: Int, isFull: Boolean) = addCandidate(
            CandidateDraft(
                simplified = span.word.word,
                pinyin = span.word.pinyinKey,
                consumedRawLength = endRaw,
                matchedSyllables = span.endToken,
                score = span.effectiveScore,
                isFullSentence = isFull
            )
        )

        private data class CandidateDraft(
            val simplified: String,
            val pinyin: String,
            val consumedRawLength: Int,
            val matchedSyllables: Int,
            val score: Long,
            val isFullSentence: Boolean
        )

        private fun addCandidate(draft: CandidateDraft) {
            if (candidates.size >= maxCandidates || !seenSimplified.add(draft.simplified)) return
            val display = if (variant == ChineseVariant.TRADITIONAL) {
                converter.toTraditional(draft.simplified)
            } else {
                draft.simplified
            }
            candidates.add(
                LiteCandidate(
                    text = display,
                    simplifiedText = draft.simplified,
                    consumedRawLength = draft.consumedRawLength,
                    matchedSyllables = draft.matchedSyllables,
                    score = draft.score,
                    isFullSentence = draft.isFullSentence,
                    pinyin = draft.pinyin
                )
            )
        }
    }

    fun query(
        tokens: List<SyllableToken>,
        variant: ChineseVariant,
        converter: ChineseConverter,
        learningStore: UserLearningStore?,
        maxCandidates: Int = 80
    ): List<LiteCandidate> {
        if (tokens.isEmpty()) return emptyList()
        return queryMultiPath(
            tokenPaths = listOf(tokens),
            variant = variant,
            converter = converter,
            learningStore = learningStore,
            maxCandidates = maxCandidates
        )
    }

    fun queryMultiPath(
        tokenPaths: List<List<SyllableToken>>,
        variant: ChineseVariant,
        converter: ChineseConverter,
        learningStore: UserLearningStore?,
        maxCandidates: Int = 80
    ): List<LiteCandidate> {
        val validPaths = tokenPaths.filter { it.isNotEmpty() }
        if (validPaths.isEmpty()) return emptyList()

        val evals = buildPathEvals(validPaths, learningStore)
        val collector = CandidateCollector(variant, converter, maxCandidates)

        if (learningStore != null) {
            appendLearnedHits(evals, learningStore, collector)
        }
        appendFullLexiconHits(evals, maxCandidates, collector)
        appendViterbiHits(evals, collector)
        appendPrefixHits(evals, maxCandidates, collector)

        return collector.candidates
    }

    private fun buildPathEvals(
        validPaths: List<List<SyllableToken>>,
        learningStore: UserLearningStore?
    ): List<PathEval> = validPaths.map { tokens ->
        val spansByStart = Array(tokens.size) { ArrayList<SpanMatch>() }
        for (start in tokens.indices) {
            val ctx = SpanSearchContext(
                tokens = tokens,
                startToken = start,
                learningStore = learningStore,
                budget = TraversalBudget(),
                out = spansByStart[start]
            )
            collectSpansFrom(node = root, currentIndex = start, ctx = ctx)
        }
        PathEval(
            tokens = tokens,
            prefixCount = tokens.count { it.isPrefixOnly },
            spansByStart = spansByStart
        )
    }

    private fun appendLearnedHits(
        evals: List<PathEval>,
        learningStore: UserLearningStore,
        collector: CandidateCollector
    ) {
        data class LearnedHit(
            val entry: LearnedEntry,
            val rawEnd: Int,
            val tokenCount: Int,
            val prefixCount: Int
        )
        val learnedHits = ArrayList<LearnedHit>()
        for (ev in evals) {
            val rawEnd = ev.tokens.last().rawEnd
            for (entry in learningStore.matchingEntriesForTokens(ev.tokens)) {
                learnedHits.add(LearnedHit(entry, rawEnd, ev.tokens.size, ev.prefixCount))
            }
        }
        learnedHits.sortWith(
            compareBy<LearnedHit> { it.prefixCount }
                .thenByDescending { it.entry.count }
                .thenByDescending { it.entry.lastUsedEpochMs }
        )
        for (hit in learnedHits) {
            collector.addFull(
                simplified = hit.entry.word,
                pinyin = hit.entry.pinyinKey,
                consumedRawLength = hit.rawEnd,
                matchedSyllables = hit.tokenCount,
                score = 2_000_000L + hit.entry.count * 50_000L
            )
        }
    }

    private fun appendFullLexiconHits(evals: List<PathEval>, maxCandidates: Int, collector: CandidateCollector) {
        data class FullLexiconHit(
            val span: SpanMatch,
            val rawEnd: Int,
            val tokenCount: Int,
            val prefixCount: Int
        )
        val fullHits = ArrayList<FullLexiconHit>()
        for (ev in evals) {
            val n = ev.tokens.size
            val rawEnd = ev.tokens[n - 1].rawEnd
            ev.spansByStart[0]
                .filter { it.endToken == n }
                .mapTo(fullHits) { FullLexiconHit(it, rawEnd, n, ev.prefixCount) }
        }
        fullHits.sortWith(
            compareBy<FullLexiconHit> { it.prefixCount }
                .thenByDescending { it.span.effectiveScore }
        )
        val singlePathSingleSyllable = evals.size == 1 && evals[0].tokens.size == 1
        val stage2Limit = if (singlePathSingleSyllable) maxCandidates else 12
        for (hit in fullHits.take(stage2Limit)) {
            collector.addFull(
                simplified = hit.span.word.word,
                pinyin = hit.span.word.pinyinKey,
                consumedRawLength = hit.rawEnd,
                matchedSyllables = hit.tokenCount,
                score = hit.span.effectiveScore + 1_000_000L
            )
        }
    }

    private fun appendViterbiHits(evals: List<PathEval>, collector: CandidateCollector) {
        val minPrefixCount = evals.minOf { it.prefixCount }
        val primaryEvals = evals.filter { it.prefixCount == minPrefixCount }
        val minTokensInPrimary = primaryEvals.minOf { it.tokens.size }
        val viterbiCandidates = primaryEvals
            .filter { it.tokens.size == minTokensInPrimary && it.tokens.size >= 2 }
            .mapNotNull { ev ->
                composeBestSentence(ev.tokens, ev.spansByStart)?.let { composed ->
                    Triple(composed, ev.tokens.last().rawEnd, ev.tokens.size)
                }
            }
            .sortedByDescending { it.first.score }
        for ((composed, rawEnd, tokenCount) in viterbiCandidates.take(2)) {
            collector.addFull(
                simplified = composed.simplified,
                pinyin = composed.pinyinKey,
                consumedRawLength = rawEnd,
                matchedSyllables = tokenCount,
                score = composed.score
            )
        }
    }

    private fun appendPrefixHits(evals: List<PathEval>, maxCandidates: Int, collector: CandidateCollector) {
        data class PrefixHit(
            val span: SpanMatch,
            val endRaw: Int,
            val isFull: Boolean,
            val prefixTokensUsed: Int
        )
        val prefixHits = ArrayList<PrefixHit>()
        for (ev in evals) {
            val n = ev.tokens.size
            for (span in ev.spansByStart[0]) {
                val endRaw = ev.tokens[span.endToken - 1].rawEnd
                val pfxUsed = (0 until span.endToken).count { ev.tokens[it].isPrefixOnly }
                prefixHits.add(
                    PrefixHit(
                        span = span,
                        endRaw = endRaw,
                        isFull = span.endToken == n,
                        prefixTokensUsed = pfxUsed
                    )
                )
            }
        }
        prefixHits.sortWith(
            compareBy<PrefixHit> { it.prefixTokensUsed }
                .thenByDescending { it.endRaw }
                .thenByDescending { it.span.effectiveScore }
        )
        for (hit in prefixHits) {
            if (collector.candidates.size >= maxCandidates) break
            collector.addSpan(
                span = hit.span,
                endRaw = hit.endRaw,
                isFull = hit.isFull
            )
        }
    }

    private fun composeBestSentence(
        tokens: List<SyllableToken>,
        spansByStart: Array<ArrayList<SpanMatch>>
    ): ComposedSentence? {
        val n = tokens.size
        val bestScore = LongArray(n + 1) { Long.MIN_VALUE / 4 }
        val nextIdx = IntArray(n + 1) { -1 }
        val chosenWord = arrayOfNulls<String>(n + 1)
        val chosenPinyin = arrayOfNulls<String>(n + 1)
        bestScore[n] = 0L

        for (i in n - 1 downTo 0) {
            val bestPerEnd = bestSpanPerEnd(spansByStart[i])
            for ((end, span) in bestPerEnd) {
                val len = end - i
                val chunkBonus = len * len * 420_000L
                val segPenalty = 180_000L
                val candidateScore = span.effectiveScore + chunkBonus - segPenalty + bestScore[end]
                if (candidateScore > bestScore[i]) {
                    bestScore[i] = candidateScore
                    nextIdx[i] = end
                    chosenWord[i] = span.word.word
                    chosenPinyin[i] = span.word.pinyinKey
                }
            }
        }

        if (nextIdx[0] == -1) return null
        val wordSb = StringBuilder()
        val pyParts = ArrayList<String>(n)
        var idx = 0
        while (idx < n) {
            val w = chosenWord[idx] ?: return null
            val py = chosenPinyin[idx] ?: return null
            wordSb.append(w)
            pyParts.add(py)
            idx = nextIdx[idx]
            if (idx <= 0) break
        }
        return ComposedSentence(
            simplified = wordSb.toString(),
            pinyinKey = pyParts.joinToString(" "),
            score = bestScore[0] + 900_000L
        )
    }

    private fun bestSpanPerEnd(spans: List<SpanMatch>): Map<Int, SpanMatch> {
        val bestPerEnd = HashMap<Int, SpanMatch>()
        for (span in spans) {
            val cur = bestPerEnd[span.endToken]
            if (cur == null || span.effectiveScore > cur.effectiveScore) {
                bestPerEnd[span.endToken] = span
            }
        }
        return bestPerEnd
    }

    private fun collectSpansFrom(node: TrieNode, currentIndex: Int, ctx: SpanSearchContext) {
        if (currentIndex >= ctx.tokens.size || ctx.budget.remaining <= 0) return
        val token = ctx.tokens[currentIndex]

        for ((optIdx, syl) in token.options.withIndex()) {
            if (ctx.budget.remaining <= 0) return
            val child = node.children[syl] ?: continue
            ctx.budget.remaining--
            child.words?.let { wordsHere ->
                appendNodeWords(wordsHere, token, optIdx, currentIndex, ctx)
            }
            if (currentIndex + 1 - ctx.startToken < MAX_PHRASE_SYLLABLES) {
                collectSpansFrom(node = child, currentIndex = currentIndex + 1, ctx = ctx)
            }
        }
    }

    private fun appendNodeWords(
        wordsHere: List<LexiconWord>,
        token: SyllableToken,
        optIdx: Int,
        currentIndex: Int,
        ctx: SpanSearchContext
    ) {
        val limit = when {
            ctx.startToken > 0 -> if (token.isPrefixOnly) 4 else 8
            token.isPrefixOnly -> 8
            else -> 50
        }
        val prefixPenalty = if (token.isPrefixOnly) (optIdx * 200L + 15_000L) else 0L
        val count = minOf(wordsHere.size, limit)
        for (wIdx in 0 until count) {
            val lw = wordsHere[wIdx]
            val userBoost = ctx.learningStore?.getBoost(lw.pinyinKey, lw.word) ?: 0L
            ctx.out.add(
                SpanMatch(
                    startToken = ctx.startToken,
                    endToken = currentIndex + 1,
                    word = lw,
                    effectiveScore = lw.frequency.toLong() + userBoost - prefixPenalty
                )
            )
        }
    }

    companion object {
        private const val MAX_TRIE_VISITS_PER_START = 3_500
        private const val MAX_PHRASE_SYLLABLES = 6

        fun fromStream(inputStream: InputStream): PinyinDictionary {
            val root = TrieNode()
            var count = 0

            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).useLines { lines ->
                for (line in lines) {
                    val entry = parseDictionaryLine(line) ?: continue
                    insertWord(root, entry)
                    count++
                }
            }

            sortTrie(root)
            return PinyinDictionary(root, count)
        }

        private fun parseDictionaryLine(line: String): LexiconWord? {
            if (line.isEmpty() || line[0] == '#') return null
            val tab1 = line.indexOf('\t')
            if (tab1 <= 0) return null
            val tab2 = line.indexOf('\t', tab1 + 1)
            if (tab2 <= tab1 + 1) return null

            val py = line.substring(0, tab1).trim()
            val word = line.substring(tab1 + 1, tab2).trim()
            val freq = line.substring(tab2 + 1).trim().toIntOrNull() ?: return null
            if (py.isEmpty() || word.isEmpty()) return null
            return LexiconWord(word = word, frequency = freq, pinyinKey = py)
        }

        private fun insertWord(root: TrieNode, entry: LexiconWord) {
            var cur = root
            for (syl in entry.pinyinKey.split(' ')) {
                if (syl.isEmpty()) continue
                cur = cur.children.getOrPut(syl) { TrieNode() }
            }
            val list = cur.words ?: ArrayList<LexiconWord>(4).also { cur.words = it }
            list.add(entry)
        }

        private fun sortTrie(node: TrieNode) {
            node.words?.sortByDescending { it.frequency }
            for (child in node.children.values) {
                sortTrie(child)
            }
        }
    }
}

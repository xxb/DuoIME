/*
 * SPDX-License-Identifier: MIT
 * MIT License
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME by DuoIME Contributors (2026).
 */
package dev.duoime.engine.lite

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.abs

/**
 * Suggestion and auto-correction lookup result for a lowercase English token.
 */
data class EnglishSuggestionResult(
    val input: String,
    val isExactWord: Boolean,
    val suggestions: List<String>,
    val autoCorrection: String?
)

/**
 * Pure-Kotlin offline English + developer/CLI lexicon with prefix completion
 * and Damerau-Levenshtein (`<= 1`) + QWERTY-adjacency typo correction.
 */
class EnglishLexicon private constructor(
    private val wordFreq: Map<String, Long>,
    private val wordsByLength: Map<Int, List<WordEntry>>,
    private val prefix2Buckets: Map<String, List<WordEntry>>,
    private val maxFrequency: Long
) {
    private data class WordEntry(
        val word: String,
        val frequency: Long
    )

    private enum class EditKind(val weight: Long) {
        SWAP(8L),
        OMISSION(5L),
        EXTRA_CHAR(5L),
        QWERTY_SUB(3L),
        OTHER_SUB(1L)
    }

    private data class ScoredEditCandidate(
        val word: String,
        val kind: EditKind,
        val frequency: Long,
        val score: Long
    )

    val entryCount: Int get() = wordFreq.size

    fun contains(word: String): Boolean = wordFreq.containsKey(word)

    fun frequencyOf(word: String): Long = wordFreq[word] ?: 0L

    fun isProtectedCliToken(word: String): Boolean = word in PROTECTED_CLI_TOKENS

    fun suggest(word: String, maxResults: Int = DEFAULT_MAX_RESULTS): EnglishSuggestionResult {
        val limit = maxResults.coerceAtLeast(0)
        if (limit == 0 || word.isEmpty() || !isLowerAsciiAlpha(word)) {
            return EnglishSuggestionResult(word, isExactWord = false, suggestions = emptyList(), autoCorrection = null)
        }

        val isOverrideTypo = word in UNAMBIGUOUS_TYPO_OVERRIDES
        val isExact = wordFreq.containsKey(word) && !isOverrideTypo
        if (word.length < MIN_SUGGEST_LENGTH) {
            return EnglishSuggestionResult(
                word,
                isExactWord = isExact,
                suggestions = emptyList(),
                autoCorrection = null
            )
        }

        if (isExact) {
            val completions = collectPrefixEntries(word, excludeExact = true, limit = (limit - 1).coerceAtLeast(0))
            val out = ArrayList<String>(limit)
            out.add(word)
            for (entry in completions) {
                out.add(entry.word)
            }
            return EnglishSuggestionResult(word, isExactWord = true, suggestions = out, autoCorrection = null)
        }

        val prefixEntries = collectPrefixEntries(word, excludeExact = false, limit = limit)
        val dl1Candidates = collectEditDistance1Candidates(word)
        val autoCorrection = resolveAutoCorrectionInternal(word, isExact = false, dl1Candidates, prefixEntries)
        val suggestions = mergeSuggestions(word, prefixEntries, dl1Candidates, autoCorrection, limit)
        return EnglishSuggestionResult(
            input = word,
            isExactWord = false,
            suggestions = suggestions,
            autoCorrection = autoCorrection
        )
    }

    fun resolveAutoCorrection(word: String): String? {
        if (word.length < MIN_AUTOCORRECT_LENGTH || !isLowerAsciiAlpha(word)) return null
        if (word in PROTECTED_CLI_TOKENS) return null

        val overrideTarget = UNAMBIGUOUS_TYPO_OVERRIDES[word]
        if (overrideTarget != null && wordFreq.containsKey(overrideTarget)) {
            return overrideTarget
        }
        if (wordFreq.containsKey(word)) return null

        val prefixEntries = collectPrefixEntries(word, excludeExact = false, limit = 2)
        val dl1Candidates = collectEditDistance1Candidates(word)
        return resolveAutoCorrectionInternal(word, isExact = false, dl1Candidates, prefixEntries)
    }

    private fun collectPrefixEntries(word: String, excludeExact: Boolean, limit: Int): List<WordEntry> {
        if (limit <= 0 || word.length < 2) return emptyList()
        val bucket = prefix2Buckets[word.substring(0, 2)] ?: return emptyList()
        val out = ArrayList<WordEntry>(limit)
        for (entry in bucket) {
            if (excludeExact && entry.word == word) continue
            if (entry.word.startsWith(word)) {
                out.add(entry)
                if (out.size >= limit) break
            }
        }
        return out
    }

    private fun collectEditDistance1Candidates(word: String): List<ScoredEditCandidate> {
        val len = word.length
        val out = ArrayList<ScoredEditCandidate>()
        for (targetLen in (len - 1)..(len + 1)) {
            if (targetLen < 2) continue
            val bucket = wordsByLength[targetLen] ?: continue
            collectEditDistance1FromBucket(word, bucket, out)
        }
        out.sortWith(
            compareByDescending<ScoredEditCandidate> { it.score }
                .thenByDescending { it.frequency }
                .thenBy { it.word }
        )
        return out
    }

    private fun collectEditDistance1FromBucket(
        word: String,
        bucket: List<WordEntry>,
        out: MutableList<ScoredEditCandidate>
    ) {
        for (entry in bucket) {
            if (!passesFirstTwoCharFilter(word, entry.word)) continue
            val kind = classifyEditDistance1(word, entry.word) ?: continue
            val score = safeMultiply(entry.frequency, kind.weight)
            out.add(
                ScoredEditCandidate(
                    word = entry.word,
                    kind = kind,
                    frequency = entry.frequency,
                    score = score
                )
            )
        }
    }

    private fun resolveAutoCorrectionInternal(
        word: String,
        isExact: Boolean,
        dl1Candidates: List<ScoredEditCandidate>,
        prefixEntries: List<WordEntry>
    ): String? {
        if (isExact || word.length < MIN_AUTOCORRECT_LENGTH || word in PROTECTED_CLI_TOKENS) return null

        val overrideTarget = UNAMBIGUOUS_TYPO_OVERRIDES[word]
        if (overrideTarget != null && wordFreq.containsKey(overrideTarget)) {
            return overrideTarget
        }

        val top = dl1Candidates.firstOrNull() ?: return null
        val runnerUp = dl1Candidates.getOrNull(1)
        if (!isCandidateConfidentForAutoCorrect(word, top, runnerUp, prefixEntries)) return null
        return top.word
    }

    private fun isCandidateConfidentForAutoCorrect(
        word: String,
        top: ScoredEditCandidate,
        runnerUp: ScoredEditCandidate?,
        prefixEntries: List<WordEntry>
    ): Boolean {
        if (top.word.length < 2) return false
        if (word.length <= SHORT_OTHER_SUB_MAX_LEN && top.kind == EditKind.OTHER_SUB) return false

        val highFreqPrefixGuard = (maxFrequency / HIGH_FREQ_PREFIX_DIVISOR).coerceAtLeast(1L)
        val hasHighFreqPrefix = prefixEntries.any { it.frequency >= highFreqPrefixGuard }
        if (hasHighFreqPrefix && top.kind != EditKind.SWAP) return false

        val scoreDivisor = if (word.length >= LONG_WORD_THRESHOLD) {
            LONG_WORD_SCORE_DIVISOR
        } else {
            SHORT_WORD_SCORE_DIVISOR
        }
        val minScore = (maxFrequency / scoreDivisor).coerceAtLeast(1L)
        if (top.score < minScore) return false

        if (runnerUp != null && safeMultiply(top.score, 10L) < safeMultiply(runnerUp.score, RUNNER_UP_RATIO_X10)) {
            return false
        }
        return true
    }

    private fun mergeSuggestions(
        word: String,
        prefixEntries: List<WordEntry>,
        dl1Candidates: List<ScoredEditCandidate>,
        autoCorrection: String?,
        limit: Int
    ): List<String> {
        val ranked = HashMap<String, Long>(limit * 3)
        val overrideTarget = UNAMBIGUOUS_TYPO_OVERRIDES[word]
        if (overrideTarget != null && wordFreq.containsKey(overrideTarget)) {
            ranked[overrideTarget] = Long.MAX_VALUE / 2L
        }
        if (autoCorrection != null) {
            ranked[autoCorrection] = maxOf(ranked[autoCorrection] ?: 0L, Long.MAX_VALUE / 4L)
        }
        for (entry in prefixEntries) {
            val score = safeMultiply(entry.frequency, PREFIX_MATCH_WEIGHT)
            ranked[entry.word] = maxOf(ranked[entry.word] ?: 0L, score)
        }
        for (cand in dl1Candidates.take(limit * 2)) {
            ranked[cand.word] = maxOf(ranked[cand.word] ?: 0L, cand.score)
        }
        return ranked.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Long>> { it.value }
                    .thenByDescending { wordFreq[it.key] ?: 0L }
                    .thenBy { it.key }
            )
            .take(limit)
            .map { it.key }
    }

    private fun classifyEditDistance1(input: String, candidate: String): EditKind? {
        val n = input.length
        val m = candidate.length
        if (abs(n - m) > 1 || input == candidate) return null
        return when {
            n == m -> classifySameLengthEdit(input, candidate)
            m == n + 1 -> if (isSingleCharInsertion(shortStr = input, longStr = candidate)) EditKind.OMISSION else null
            else -> if (isSingleCharInsertion(shortStr = candidate, longStr = input)) EditKind.EXTRA_CHAR else null
        }
    }

    private fun classifySameLengthEdit(a: String, b: String): EditKind? {
        val n = a.length
        var firstDiff = -1
        for (i in 0 until n) {
            if (a[i] != b[i]) {
                firstDiff = i
                break
            }
        }
        if (firstDiff < 0) return null

        var lastDiff = firstDiff
        for (i in n - 1 downTo firstDiff + 1) {
            if (a[i] != b[i]) {
                lastDiff = i
                break
            }
        }

        if (firstDiff == lastDiff) {
            return if (isQwertyAdjacent(a[firstDiff], b[firstDiff])) {
                EditKind.QWERTY_SUB
            } else {
                EditKind.OTHER_SUB
            }
        }

        val isAdjacentSwap = lastDiff == firstDiff + 1 &&
            a[firstDiff] == b[lastDiff] &&
            a[lastDiff] == b[firstDiff] &&
            hasNoMismatchBetween(a, b, lastDiff + 1)
        return if (isAdjacentSwap) EditKind.SWAP else null
    }

    private fun hasNoMismatchBetween(a: String, b: String, startIndex: Int): Boolean {
        for (i in startIndex until a.length) {
            if (a[i] != b[i]) return false
        }
        return true
    }

    private fun isSingleCharInsertion(shortStr: String, longStr: String): Boolean {
        var i = 0
        val n = shortStr.length
        while (i < n && shortStr[i] == longStr[i]) {
            i++
        }
        for (k in i until n) {
            if (shortStr[k] != longStr[k + 1]) return false
        }
        return true
    }

    private fun passesFirstTwoCharFilter(a: String, b: String): Boolean {
        if (a.length < 2 || b.length < 2) return true
        return a[0] == b[0] || a[1] == b[1] || a[0] == b[1] || a[1] == b[0]
    }

    private fun isQwertyAdjacent(a: Char, b: Char): Boolean {
        if (a == b || a !in 'a'..'z' || b !in 'a'..'z') return false
        val p1 = QWERTY_COORDS[a - 'a']
        val p2 = QWERTY_COORDS[b - 'a']
        if (p1 < 0 || p2 < 0) return false
        val r1 = p1 ushr 4
        val c1 = p1 and 0x0F
        val r2 = p2 ushr 4
        val c2 = p2 and 0x0F
        return abs(r1 - r2) <= 1 && abs(c1 - c2) <= 1
    }

    private fun safeMultiply(a: Long, b: Long): Long {
        if (a <= 0L || b <= 0L) return 0L
        return if (a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b
    }

    private fun isLowerAsciiAlpha(word: String): Boolean {
        for (ch in word) {
            if (ch !in 'a'..'z') return false
        }
        return true
    }

    companion object {
        private const val DEFAULT_MAX_RESULTS = 3
        private const val MIN_SUGGEST_LENGTH = 2
        private const val MIN_AUTOCORRECT_LENGTH = 3
        private const val SHORT_OTHER_SUB_MAX_LEN = 4
        private const val LONG_WORD_THRESHOLD = 6
        private const val LONG_WORD_SCORE_DIVISOR = 2_000L
        private const val SHORT_WORD_SCORE_DIVISOR = 500L
        private const val HIGH_FREQ_PREFIX_DIVISOR = 220L
        private const val RUNNER_UP_RATIO_X10 = 22L
        private const val PREFIX_MATCH_WEIGHT = 4L

        private val QWERTY_COORDS = IntArray(26) { -1 }.apply {
            val rows = arrayOf(
                Triple("qwertyuiop", 0, 0),
                Triple("asdfghjkl", 1, 0),
                Triple("zxcvbnm", 2, 1)
            )
            for ((chars, row, offset) in rows) {
                for (i in chars.indices) {
                    this[chars[i] - 'a'] = (row shl 4) or (i + offset)
                }
            }
        }

        private val PROTECTED_CLI_TOKENS = setOf(
            "ls", "cd", "cp", "mv", "rm", "vi", "vim", "nvim", "hx", "go", "ps",
            "id", "ip", "df", "du", "tr", "wc", "jq", "yq", "rg", "fd", "gh",
            "cat", "bat", "git", "ssh", "scp", "sftp", "grep", "awk", "sed",
            "tar", "zip", "zsh", "sh", "bash", "fish", "tmux", "less", "more",
            "curl", "wget", "sudo", "su", "ln", "dd", "ll", "la", "nc", "ss",
            "nl", "od", "pr", "cc", "ld", "ar", "nm", "xz", "py", "js", "ts",
            "rs", "kt", "ci", "os", "io", "ui", "db", "vm", "fzf", "apt", "dnf",
            "yum", "apk", "nix", "pip", "npm", "npx", "bun", "uv", "gcc", "gdb",
            "man", "pwd", "env", "cut", "tee", "tty", "who", "top", "htop",
            "btop", "kill", "ping", "dig", "ftp", "echo", "find", "sort", "uniq",
            "head", "tail", "diff", "stat", "make", "node", "java", "rust",
            "yaml", "json", "toml", "html", "http", "grpc", "pty", "cli", "tui",
            "foo", "bar", "baz", "qux", "conf", "kube", "ctx", "cfg", "idx",
            "len", "pos", "val", "ptr", "buf", "cmd", "msg", "svc", "impl",
            "util", "args", "argv", "argc", "req", "resp", "str", "num", "err",
            "wip", "ack", "ok", "adb", "sdk", "ide", "ime"
        )

        private val UNAMBIGUOUS_TYPO_OVERRIDES = mapOf(
            "teh" to "the",
            "adn" to "and",
            "hte" to "the",
            "taht" to "that",
            "wiht" to "with",
            "waht" to "what",
            "htis" to "this",
            "tihs" to "this",
            "recieve" to "receive",
            "seperate" to "separate",
            "occured" to "occurred",
            "definately" to "definitely",
            "adress" to "address",
            "enviroment" to "environment",
            "gti" to "git",
            "comit" to "commit",
            "committ" to "commit",
            "pythn" to "python",
            "dockre" to "docker",
            "systmed" to "systemd"
        )

        fun empty(): EnglishLexicon = EnglishLexicon(
            wordFreq = emptyMap(),
            wordsByLength = emptyMap(),
            prefix2Buckets = emptyMap(),
            maxFrequency = 1L
        )

        fun fromStream(inputStream: InputStream): EnglishLexicon {
            val wordFreq = HashMap<String, Long>(5_120)
            var maxFrequency = 1L

            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).useLines { lines ->
                for (rawLine in lines) {
                    val parsed = parseLine(rawLine) ?: continue
                    val prev = wordFreq[parsed.word] ?: 0L
                    val nextFreq = maxOf(prev, parsed.frequency)
                    wordFreq[parsed.word] = nextFreq
                    if (nextFreq > maxFrequency) maxFrequency = nextFreq
                }
            }

            val wordsByLength = HashMap<Int, ArrayList<WordEntry>>()
            val prefix2Buckets = HashMap<String, ArrayList<WordEntry>>()
            for ((word, freq) in wordFreq) {
                val entry = WordEntry(word = word, frequency = freq)
                wordsByLength.getOrPut(word.length) { ArrayList() }.add(entry)
                if (word.length >= 2) {
                    prefix2Buckets.getOrPut(word.substring(0, 2)) { ArrayList() }.add(entry)
                }
            }

            val entryOrder = compareByDescending<WordEntry> { it.frequency }.thenBy { it.word }
            for (list in wordsByLength.values) list.sortWith(entryOrder)
            for (list in prefix2Buckets.values) list.sortWith(entryOrder)

            return EnglishLexicon(
                wordFreq = wordFreq,
                wordsByLength = wordsByLength,
                prefix2Buckets = prefix2Buckets,
                maxFrequency = maxFrequency
            )
        }

        private fun parseLine(rawLine: String): WordEntry? {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed[0] == '#') return null
            val sep = trimmed.indexOfFirst { it == '\t' || it == ' ' }
            if (sep <= 0) return null
            val word = trimmed.substring(0, sep).trim().lowercase()
            if (word.isEmpty() || !isAsciiLowercaseWord(word)) return null
            val freq = trimmed.substring(sep + 1).trim().toLongOrNull()?.coerceAtLeast(1L) ?: return null
            return WordEntry(word = word, frequency = freq)
        }

        private fun isAsciiLowercaseWord(word: String): Boolean {
            for (ch in word) {
                if (ch !in 'a'..'z') return false
            }
            return true
        }
    }
}

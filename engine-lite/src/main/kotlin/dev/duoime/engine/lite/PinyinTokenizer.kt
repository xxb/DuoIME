/*
 * SPDX-License-Identifier: MIT
 * MIT License
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME by DuoIME Contributors (2026).
 */
package dev.duoime.engine.lite

/**
 * Represents a parsed Pinyin syllable token mapped back to its raw input character span.
 */
data class SyllableToken(
    val options: List<String>,
    val rawStart: Int,
    val rawEnd: Int,
    val isPrefixOnly: Boolean = false
) {
    val rawLength: Int get() = rawEnd - rawStart
    val display: String get() = options.firstOrNull() ?: ""
}

/**
 * Result of T9 prefix-locked segmentation: candidate [paths], the validated prefix of
 * [validLockedSyllables] that matches [raw], and [nextUnlockRawIndex] where remaining digits start.
 */
data class T9PrefixTokenizeResult(
    val paths: List<List<SyllableToken>>,
    val validLockedSyllables: List<String>,
    val nextUnlockRawIndex: Int
)

object PinyinTokenizer {

    val VALID_SYLLABLES: Set<String> = setOf(
        "a", "ai", "an", "ang", "ao",
        "ba", "bai", "ban", "bang", "bao", "bei", "ben", "beng", "bi", "bian", "biao", "bie", "bin", "bing", "bo", "bu",
        "ca", "cai", "can", "cang", "cao", "ce", "cen", "ceng", "ci", "cong", "cou", "cu", "cuan", "cui", "cun", "cuo",
        "cha", "chai", "chan", "chang", "chao", "che", "chen", "cheng", "chi", "chong", "chou", "chu", "chua", "chuai", "chuan", "chuang", "chui", "chun", "chuo",
        "da", "dai", "dan", "dang", "dao", "de", "dei", "den", "deng", "di", "dia", "dian", "diao", "die", "ding", "diu", "dong", "dou", "du", "duan", "dui", "dun", "duo",
        "e", "ei", "en", "eng", "er",
        "fa", "fan", "fang", "fei", "fen", "feng", "fo", "fou", "fu",
        "ga", "gai", "gan", "gang", "gao", "ge", "gei", "gen", "geng", "gong", "gou", "gu", "gua", "guai", "guan", "guang", "gui", "gun", "guo",
        "ha", "hai", "han", "hang", "hao", "he", "hei", "hen", "heng", "hong", "hou", "hu", "hua", "huai", "huan", "huang", "hui", "hun", "huo",
        "ji", "jia", "jian", "jiang", "jiao", "jie", "jin", "jing", "jiong", "jiu", "ju", "juan", "jue", "jun",
        "ka", "kai", "kan", "kang", "kao", "ke", "kei", "ken", "keng", "kong", "kou", "ku", "kua", "kuai", "kuan", "kuang", "kui", "kun", "kuo",
        "la", "lai", "lan", "lang", "lao", "le", "lei", "leng", "li", "lia", "lian", "liang", "liao", "lie", "lin", "ling", "liu", "lo", "long", "lou", "lu", "luan", "lun", "luo", "lv", "lve",
        "ma", "mai", "man", "mang", "mao", "me", "mei", "men", "meng", "mi", "mian", "miao", "mie", "min", "ming", "miu", "mo", "mou", "mu",
        "na", "nai", "nan", "nang", "nao", "ne", "nei", "nen", "neng", "ni", "nian", "niang", "niao", "nie", "nin", "ning", "niu", "nong", "nou", "nu", "nuan", "nun", "nuo", "nv", "nve",
        "o", "ou",
        "pa", "pai", "pan", "pang", "pao", "pei", "pen", "peng", "pi", "pian", "piao", "pie", "pin", "ping", "po", "pou", "pu",
        "qi", "qia", "qian", "qiang", "qiao", "qie", "qin", "qing", "qiong", "qiu", "qu", "quan", "que", "qun",
        "ran", "rang", "rao", "re", "ren", "reng", "ri", "rong", "rou", "ru", "rua", "ruan", "rui", "run", "ruo",
        "sa", "sai", "san", "sang", "sao", "se", "sen", "seng", "si", "song", "sou", "su", "suan", "sui", "sun", "suo",
        "sha", "shai", "shan", "shang", "shao", "she", "shei", "shen", "sheng", "shi", "shou", "shu", "shua", "shuai", "shuan", "shuang", "shui", "shun", "shuo",
        "ta", "tai", "tan", "tang", "tao", "te", "tei", "teng", "ti", "tian", "tiao", "tie", "ting", "tong", "tou", "tu", "tuan", "tui", "tun", "tuo",
        "wa", "wai", "wan", "wang", "wei", "wen", "weng", "wo", "wu",
        "xi", "xia", "xian", "xiang", "xiao", "xie", "xin", "xing", "xiong", "xiu", "xu", "xuan", "xue", "xun",
        "ya", "yan", "yang", "yao", "ye", "yi", "yin", "ying", "yo", "yong", "you", "yu", "yuan", "yue", "yun",
        "za", "zai", "zan", "zang", "zao", "ze", "zei", "zen", "zeng", "zi", "zong", "zou", "zu", "zuan", "zui", "zun", "zuo",
        "zha", "zhai", "zhan", "zhang", "zhao", "zhe", "zhei", "zhen", "zheng", "zhi", "zhong", "zhou", "zhu", "zhua", "zhuai", "zhuan", "zhuang", "zhui", "zhun", "zhuo"
    )

    private val QUANPIN_ORTHOGRAPHIC_ALIASES: Map<String, List<String>> = mapOf(
        "lue" to listOf("lve"),
        "nue" to listOf("nve"),
        "ju" to listOf("ju"),
        "jv" to listOf("ju"),
        "qv" to listOf("qu"),
        "xv" to listOf("xu"),
        "yv" to listOf("yu")
    )

    private val XIAOHE_ZERO_INITIAL: Map<String, String> = mapOf(
        "aa" to "a",
        "oo" to "o",
        "ee" to "e",
        "ai" to "ai", "ad" to "ai",
        "ei" to "ei", "ew" to "ei",
        "ao" to "ao", "ac" to "ao",
        "ou" to "ou", "oz" to "ou",
        "an" to "an", "aj" to "an",
        "en" to "en", "ef" to "en",
        "er" to "er",
        "ah" to "ang",
        "eg" to "eng"
    )

    private val XIAOHE_INITIALS: Map<Char, String> = mapOf(
        'b' to "b", 'p' to "p", 'm' to "m", 'f' to "f",
        'd' to "d", 't' to "t", 'n' to "n", 'l' to "l",
        'g' to "g", 'k' to "k", 'h' to "h",
        'j' to "j", 'q' to "q", 'x' to "x",
        'r' to "r", 'z' to "z", 'c' to "c", 's' to "s",
        'y' to "y", 'w' to "w",
        'v' to "zh", 'i' to "ch", 'u' to "sh"
    )

    private val XIAOHE_FINALS: Map<Char, List<String>> = mapOf(
        'a' to listOf("a"),
        'b' to listOf("in"),
        'c' to listOf("ao"),
        'd' to listOf("ai"),
        'e' to listOf("e"),
        'f' to listOf("en"),
        'g' to listOf("eng"),
        'h' to listOf("ang"),
        'i' to listOf("i"),
        'j' to listOf("an"),
        'k' to listOf("ing", "uai"),
        'l' to listOf("iang", "uang"),
        'm' to listOf("ian"),
        'n' to listOf("iao"),
        'o' to listOf("uo", "o"),
        'p' to listOf("ie"),
        'q' to listOf("iu"),
        'r' to listOf("uan"),
        's' to listOf("ong", "iong"),
        't' to listOf("ue", "ve"),
        'u' to listOf("u"),
        'v' to listOf("ui", "v", "u"),
        'w' to listOf("ei"),
        'x' to listOf("ia", "ua"),
        'y' to listOf("un"),
        'z' to listOf("ou")
    )

    private val JQXY_INITIALS = setOf("j", "q", "x", "y")
    private val NL_INITIALS = setOf("n", "l")

    internal data class T9SubPath(
        val cost: Int,
        val prefixCount: Int,
        val tokens: List<SyllableToken>
    )

    internal val T9_EXACT_SYLLABLES: Map<String, List<String>>
    internal val T9_PREFIX_SYLLABLES: Map<String, List<String>>

    init {
        val exactMap = LinkedHashMap<String, MutableList<String>>()
        val prefixMap = LinkedHashMap<String, MutableList<String>>()
        for (syl in VALID_SYLLABLES) {
            val code = t9CodeOf(syl)
            if (code.isEmpty()) continue
            exactMap.getOrPut(code) { ArrayList(4) }.add(syl)
            for (len in 1..code.length) {
                val pfx = code.substring(0, len)
                prefixMap.getOrPut(pfx) { ArrayList(8) }.add(syl)
            }
        }
        for ((pfx, list) in prefixMap) {
            list.sortWith(
                compareBy<String> { it.length != pfx.length }
                    .thenBy { it.length }
                    .thenBy { it }
            )
        }
        T9_EXACT_SYLLABLES = exactMap
        T9_PREFIX_SYLLABLES = prefixMap
    }

    fun charToT9Digit(ch: Char): Char? = when (ch.lowercaseChar()) {
        'a', 'b', 'c' -> '2'
        'd', 'e', 'f' -> '3'
        'g', 'h', 'i' -> '4'
        'j', 'k', 'l' -> '5'
        'm', 'n', 'o' -> '6'
        'p', 'q', 'r', 's' -> '7'
        't', 'u', 'v' -> '8'
        'w', 'x', 'y', 'z' -> '9'
        else -> null
    }

    fun t9CodeOf(syllable: String): String {
        val sb = StringBuilder(syllable.length)
        for (ch in syllable) {
            val d = charToT9Digit(ch) ?: return ""
            sb.append(d)
        }
        return sb.toString()
    }

    fun tokenize(raw: String, mode: LiteSchemaMode): List<SyllableToken> {
        if (raw.isEmpty()) return emptyList()
        return when (mode) {
            LiteSchemaMode.ENGLISH -> emptyList()
            LiteSchemaMode.QUANPIN -> tokenizeQuanpin(raw.lowercase())
            LiteSchemaMode.XIAOHE_SHUANGPIN -> tokenizeXiaohe(raw.lowercase())
            LiteSchemaMode.T9_PINYIN -> tokenizeT9Paths(raw).firstOrNull().orEmpty()
        }
    }

    fun tokenizeT9Paths(
        raw: String,
        lockedFirstSyllable: String? = null,
        maxPaths: Int = 6
    ): List<List<SyllableToken>> {
        val locked = if (lockedFirstSyllable != null) listOf(lockedFirstSyllable) else emptyList()
        return tokenizeT9PathsWithPrefix(raw, locked, maxPaths).paths
    }

    fun tokenizeT9PathsWithPrefix(
        raw: String,
        lockedSyllables: List<String>,
        maxPaths: Int = 6
    ): T9PrefixTokenizeResult {
        if (raw.isEmpty()) {
            return T9PrefixTokenizeResult(emptyList(), emptyList(), 0)
        }
        var cursor = 0
        val lockedTokens = ArrayList<SyllableToken>(lockedSyllables.size)
        val validLocked = ArrayList<String>(lockedSyllables.size)
        for (syl in lockedSyllables) {
            val normalized = syl.trim().lowercase()
            val token = matchLockedT9SyllableAt(raw, cursor, normalized) ?: break
            lockedTokens.add(token)
            validLocked.add(normalized)
            cursor = token.rawEnd
        }

        val suffixPaths = T9PathSegmenter.segmentFromIndex(raw, cursor, maxPaths)
        val paths = when {
            lockedTokens.isEmpty() -> suffixPaths.map { it.tokens }
            suffixPaths.isEmpty() -> listOf(lockedTokens)
            else -> suffixPaths.map { sfx -> lockedTokens + sfx.tokens }
        }
        return T9PrefixTokenizeResult(
            paths = paths,
            validLockedSyllables = validLocked,
            nextUnlockRawIndex = cursor
        )
    }

    fun canLockLeadingT9Syllable(raw: String, syllable: String): Boolean = canLockT9SyllableAt(raw, 0, syllable) != null

    fun canLockT9SyllableAt(raw: String, fromIndex: Int, syllable: String): Int? =
        matchLockedT9SyllableAt(raw, fromIndex, syllable)?.rawEnd

    fun hasRemainingT9Digits(raw: String, fromIndex: Int): Boolean = skipApostrophes(raw, fromIndex) < raw.length

    fun trailingT9ChunkAt(raw: String, fromIndex: Int): String? {
        val start = skipApostrophes(raw, fromIndex)
        if (start >= raw.length) return null
        val chunkEnd = findChunkEnd(raw, start)
        if (chunkEnd != raw.length) return null
        return raw.substring(start, chunkEnd)
    }

    fun missingT9DigitsForCompletionAt(raw: String, fromIndex: Int, syllable: String): String? {
        if (syllable !in VALID_SYLLABLES) return null
        val remainingChunk = trailingT9ChunkAt(raw, fromIndex) ?: return null
        val code = t9CodeOf(syllable)
        if (code.length <= remainingChunk.length || !code.startsWith(remainingChunk)) {
            return null
        }
        return code.substring(remainingChunk.length)
    }

    fun t9PrefixCompletionsAt(raw: String, fromIndex: Int, maxCount: Int = 12): List<String> {
        val remainingChunk = trailingT9ChunkAt(raw, fromIndex) ?: return emptyList()
        if (remainingChunk.length !in 1..5) return emptyList()
        val candidates = T9_PREFIX_SYLLABLES[remainingChunk] ?: return emptyList()
        val result = ArrayList<String>(minOf(candidates.size, maxCount))
        for (syl in candidates) {
            if (t9CodeOf(syl).length > remainingChunk.length) {
                result.add(syl)
                if (result.size >= maxCount) break
            }
        }
        return result
    }

    private fun matchLockedT9SyllableAt(raw: String, fromIndex: Int, syllable: String): SyllableToken? {
        if (syllable !in VALID_SYLLABLES) return null
        val start = skipApostrophes(raw, fromIndex)
        if (start >= raw.length) return null
        val chunkEnd = findChunkEnd(raw, start)
        val code = t9CodeOf(syllable)
        if (code.isEmpty() || start + code.length > chunkEnd) return null
        if (!raw.regionMatches(start, code, 0, code.length)) return null
        return SyllableToken(
            options = listOf(syllable),
            rawStart = start,
            rawEnd = start + code.length,
            isPrefixOnly = false
        )
    }

    private fun skipApostrophes(raw: String, fromIndex: Int): Int {
        var idx = fromIndex.coerceAtLeast(0)
        while (idx < raw.length && raw[idx] == '\'') {
            idx++
        }
        return idx
    }

    private fun findChunkEnd(raw: String, start: Int): Int {
        var end = start
        while (end < raw.length && raw[end] != '\'') {
            end++
        }
        return end
    }

    fun tokenizeQuanpin(raw: String): List<SyllableToken> = tokenizeQuanpinPaths(raw).firstOrNull().orEmpty()

    fun tokenizeQuanpinPaths(raw: String, maxPaths: Int = 6): List<List<SyllableToken>> =
        QuanpinPathSegmenter.tokenizePaths(raw, maxPaths)

    internal fun resolveQuanpinSyllable(sub: String): List<String>? {
        QUANPIN_ORTHOGRAPHIC_ALIASES[sub]?.let { return it }
        if (sub in VALID_SYLLABLES) return listOf(sub)
        return null
    }

    internal fun isSyllablePrefix(prefix: String): Boolean = VALID_SYLLABLES.any { it.startsWith(prefix) }

    internal fun expandPrefixSyllables(prefix: String): List<String> =
        VALID_SYLLABLES.filter { it.startsWith(prefix) }.ifEmpty { listOf(prefix) }

    fun tokenizeXiaohe(raw: String): List<SyllableToken> {
        val tokens = mutableListOf<SyllableToken>()
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '\'') {
                i++
                continue
            }
            val pairToken = tryDecodeXiaohePairToken(raw, i)
            if (pairToken != null) {
                tokens.add(pairToken)
                i += 2
                continue
            }
            tokens.add(buildSingleCharXiaoheToken(raw[i], i))
            i += 1
        }
        return tokens
    }

    private fun tryDecodeXiaohePairToken(raw: String, index: Int): SyllableToken? {
        if (index + 1 >= raw.length || raw[index + 1] == '\'') return null
        val decoded = decodeXiaohePair(raw.substring(index, index + 2))
        if (decoded.isEmpty()) return null
        return SyllableToken(
            options = decoded,
            rawStart = index,
            rawEnd = index + 2,
            isPrefixOnly = false
        )
    }

    private fun buildSingleCharXiaoheToken(ch: Char, index: Int): SyllableToken {
        val initial = XIAOHE_INITIALS[ch]
        val options = if (initial != null) {
            expandXiaoheInitial(initial)
        } else {
            VALID_SYLLABLES.filter { it.startsWith(ch) }
        }
        return SyllableToken(
            options = options.ifEmpty { listOf(ch.toString()) },
            rawStart = index,
            rawEnd = index + 1,
            isPrefixOnly = true
        )
    }

    private fun expandXiaoheInitial(initial: String): List<String> {
        val isFlatSibilant = initial.length == 1 && (initial == "z" || initial == "c" || initial == "s")
        return VALID_SYLLABLES.filter { syl ->
            if (isFlatSibilant) {
                syl.startsWith(initial) && !syl.startsWith("${initial}h")
            } else {
                syl.startsWith(initial)
            }
        }
    }

    fun decodeXiaohePair(pair: String): List<String> {
        if (pair.length != 2) return emptyList()
        XIAOHE_ZERO_INITIAL[pair]?.let { return listOf(it) }

        val initial = XIAOHE_INITIALS[pair[0]] ?: return emptyList()
        val finals = XIAOHE_FINALS[pair[1]] ?: return emptyList()
        val finalChar = pair[1]

        val valid = mutableListOf<String>()
        for (fin in finals) {
            if (!isAllowedXiaoheCombination(initial, finalChar, fin)) continue
            val candidate = initial + fin
            if (candidate in VALID_SYLLABLES && candidate !in valid) {
                valid.add(candidate)
            }
        }
        return valid
    }

    private fun isAllowedXiaoheCombination(initial: String, finalChar: Char, fin: String): Boolean {
        if (finalChar != 'v') return true
        if (fin == "u" && initial !in JQXY_INITIALS) return false
        if (fin == "v" && initial !in NL_INITIALS) return false
        return true
    }
}

private object T9PathSegmenter {
    private data class T9StepContext(
        val raw: String,
        val start: Int,
        val end: Int,
        val i: Int
    ) {
        val len: Int get() = end - start
    }

    fun segmentFromIndex(raw: String, fromIndex: Int, maxPaths: Int): List<PinyinTokenizer.T9SubPath> {
        var combined = listOf(PinyinTokenizer.T9SubPath(cost = 0, prefixCount = 0, tokens = emptyList()))
        var segStart = fromIndex
        while (segStart < raw.length) {
            if (raw[segStart] == '\'') {
                segStart++
                continue
            }
            var segEnd = segStart
            while (segEnd < raw.length && raw[segEnd] != '\'') {
                segEnd++
            }
            val chunkPaths = segmentDigitChunk(raw, segStart, segEnd, maxPaths)
            if (chunkPaths.isNotEmpty()) {
                combined = mergeT9Chunks(combined, chunkPaths, maxPaths)
            }
            segStart = segEnd
        }
        return combined.filter { it.tokens.isNotEmpty() }
    }

    private fun mergeT9Chunks(
        leftPaths: List<PinyinTokenizer.T9SubPath>,
        rightPaths: List<PinyinTokenizer.T9SubPath>,
        maxPaths: Int
    ): List<PinyinTokenizer.T9SubPath> {
        val next = ArrayList<PinyinTokenizer.T9SubPath>(leftPaths.size * rightPaths.size)
        for (left in leftPaths) {
            for (right in rightPaths) {
                next.add(
                    PinyinTokenizer.T9SubPath(
                        cost = left.cost + right.cost,
                        prefixCount = left.prefixCount + right.prefixCount,
                        tokens = left.tokens + right.tokens
                    )
                )
            }
        }
        next.sortWith(
            compareBy<PinyinTokenizer.T9SubPath> { it.cost }
                .thenBy { it.prefixCount }
                .thenBy { it.tokens.size }
        )
        return next.take(maxPaths)
    }

    private fun segmentDigitChunk(raw: String, start: Int, end: Int, maxPaths: Int): List<PinyinTokenizer.T9SubPath> {
        val len = end - start
        if (len <= 0) return emptyList()

        val pathsAt = arrayOfNulls<List<PinyinTokenizer.T9SubPath>>(len + 1)
        pathsAt[len] = listOf(PinyinTokenizer.T9SubPath(cost = 0, prefixCount = 0, tokens = emptyList()))

        for (i in len - 1 downTo 0) {
            val candidates = ArrayList<PinyinTokenizer.T9SubPath>(16)
            val ctx = T9StepContext(raw = raw, start = start, end = end, i = i)
            collectExactMultiDigitEdges(ctx, pathsAt, candidates)
            collectExactSingleDigitEdge(ctx, pathsAt, candidates)
            collectTrailingPrefixEdge(ctx, pathsAt, candidates)
            collectSingleDigitFallbackEdge(ctx, pathsAt, candidates)

            candidates.sortWith(
                compareBy<PinyinTokenizer.T9SubPath> { it.cost }
                    .thenBy { it.prefixCount }
                    .thenByDescending { it.tokens.firstOrNull()?.rawLength ?: 0 }
            )
            pathsAt[i] = candidates.take(maxPaths)
        }
        return pathsAt[0].orEmpty()
    }

    private fun collectExactMultiDigitEdges(
        ctx: T9StepContext,
        pathsAt: Array<List<PinyinTokenizer.T9SubPath>?>,
        out: MutableList<PinyinTokenizer.T9SubPath>
    ) {
        val maxL = minOf(6, ctx.len - ctx.i)
        for (l in maxL downTo 2) {
            val sub = ctx.raw.substring(ctx.start + ctx.i, ctx.start + ctx.i + l)
            val exact = PinyinTokenizer.T9_EXACT_SYLLABLES[sub] ?: continue
            val tok = SyllableToken(exact, ctx.start + ctx.i, ctx.start + ctx.i + l, isPrefixOnly = false)
            appendT9Paths(out, tok, edgeCost = 10, suffixes = pathsAt[ctx.i + l])
        }
    }

    private fun collectExactSingleDigitEdge(
        ctx: T9StepContext,
        pathsAt: Array<List<PinyinTokenizer.T9SubPath>?>,
        out: MutableList<PinyinTokenizer.T9SubPath>
    ) {
        val sub1 = ctx.raw.substring(ctx.start + ctx.i, ctx.start + ctx.i + 1)
        val exact1 = PinyinTokenizer.T9_EXACT_SYLLABLES[sub1] ?: return
        val tok = SyllableToken(exact1, ctx.start + ctx.i, ctx.start + ctx.i + 1, isPrefixOnly = false)
        appendT9Paths(out, tok, edgeCost = 18, suffixes = pathsAt[ctx.i + 1])
    }

    private fun collectTrailingPrefixEdge(
        ctx: T9StepContext,
        pathsAt: Array<List<PinyinTokenizer.T9SubPath>?>,
        out: MutableList<PinyinTokenizer.T9SubPath>
    ) {
        val tailLen = ctx.len - ctx.i
        if (tailLen !in 2..5) return
        val tail = ctx.raw.substring(ctx.start + ctx.i, ctx.end)
        val pref = PinyinTokenizer.T9_PREFIX_SYLLABLES[tail] ?: return
        val exactTail = PinyinTokenizer.T9_EXACT_SYLLABLES[tail]
        if (exactTail == null || pref.size > exactTail.size) {
            val tok = SyllableToken(pref, ctx.start + ctx.i, ctx.end, isPrefixOnly = true)
            appendT9Paths(out, tok, edgeCost = 85, suffixes = pathsAt[ctx.len])
        }
    }

    private fun collectSingleDigitFallbackEdge(
        ctx: T9StepContext,
        pathsAt: Array<List<PinyinTokenizer.T9SubPath>?>,
        out: MutableList<PinyinTokenizer.T9SubPath>
    ) {
        val sub1 = ctx.raw.substring(ctx.start + ctx.i, ctx.start + ctx.i + 1)
        val pref1 = PinyinTokenizer.T9_PREFIX_SYLLABLES[sub1] ?: listOf(sub1)
        val singleCost = if (ctx.i + 1 == ctx.len) 95 else 320
        val tok = SyllableToken(pref1, ctx.start + ctx.i, ctx.start + ctx.i + 1, isPrefixOnly = true)
        appendT9Paths(out, tok, edgeCost = singleCost, suffixes = pathsAt[ctx.i + 1])
    }

    private fun appendT9Paths(
        out: MutableList<PinyinTokenizer.T9SubPath>,
        token: SyllableToken,
        edgeCost: Int,
        suffixes: List<PinyinTokenizer.T9SubPath>?
    ) {
        if (suffixes == null) return
        val pfxInc = if (token.isPrefixOnly) 1 else 0
        for (sfx in suffixes) {
            out.add(
                PinyinTokenizer.T9SubPath(
                    cost = edgeCost + sfx.cost,
                    prefixCount = pfxInc + sfx.prefixCount,
                    tokens = listOf(token) + sfx.tokens
                )
            )
        }
    }
}

private object QuanpinPathSegmenter {

    private data class QuanpinSubPath(
        val cost: Int,
        val prefixCount: Int,
        val zeroInitialCount: Int,
        val tokens: List<SyllableToken>
    )

    private data class ChunkStepContext(
        val raw: String,
        val start: Int,
        val end: Int,
        val i: Int
    ) {
        val len: Int get() = end - start
    }

    fun tokenizePaths(raw: String, maxPaths: Int): List<List<SyllableToken>> {
        if (raw.isEmpty()) return emptyList()
        val normalized = raw.lowercase()
        var combined: List<QuanpinSubPath> = listOf(
            QuanpinSubPath(cost = 0, prefixCount = 0, zeroInitialCount = 0, tokens = emptyList())
        )
        var segStart = 0
        while (segStart < normalized.length) {
            if (normalized[segStart] == '\'') {
                segStart++
                continue
            }
            var segEnd = segStart
            while (segEnd < normalized.length && normalized[segEnd] != '\'') {
                segEnd++
            }
            val chunkPaths = segmentChunkPaths(normalized, segStart, segEnd, maxPaths)
            if (chunkPaths.isNotEmpty()) {
                combined = mergeChunkPaths(combined, chunkPaths, maxPaths)
            }
            segStart = segEnd
        }
        return combined.map { it.tokens }.filter { it.isNotEmpty() }
    }

    private fun mergeChunkPaths(
        prefixPaths: List<QuanpinSubPath>,
        chunkPaths: List<QuanpinSubPath>,
        maxPaths: Int
    ): List<QuanpinSubPath> {
        val next = ArrayList<QuanpinSubPath>(prefixPaths.size * chunkPaths.size)
        for (left in prefixPaths) {
            for (right in chunkPaths) {
                next.add(
                    QuanpinSubPath(
                        cost = left.cost + right.cost,
                        prefixCount = left.prefixCount + right.prefixCount,
                        zeroInitialCount = left.zeroInitialCount + right.zeroInitialCount,
                        tokens = left.tokens + right.tokens
                    )
                )
            }
        }
        return sortAndDedupe(next, maxPaths)
    }

    private fun sortAndDedupe(paths: List<QuanpinSubPath>, maxPaths: Int): List<QuanpinSubPath> {
        val sorted = paths.sortedWith(
            compareBy<QuanpinSubPath> { it.cost }
                .thenBy { it.prefixCount }
                .thenBy { it.zeroInitialCount }
                .thenBy { it.tokens.size }
        )
        val out = ArrayList<QuanpinSubPath>(minOf(sorted.size, maxPaths))
        val seenKeys = HashSet<String>(minOf(sorted.size, maxPaths) * 2)
        for (path in sorted) {
            val key = path.tokens.joinToString("|") { tok ->
                "${tok.rawStart}-${tok.rawEnd}:${tok.isPrefixOnly}:${tok.display}"
            }
            if (seenKeys.add(key)) {
                out.add(path)
                if (out.size >= maxPaths) break
            }
        }
        return out
    }

    private fun segmentChunkPaths(raw: String, start: Int, end: Int, maxPaths: Int): List<QuanpinSubPath> {
        val len = end - start
        if (len <= 0) return emptyList()

        val pathsAt = arrayOfNulls<List<QuanpinSubPath>>(len + 1)
        pathsAt[len] = listOf(QuanpinSubPath(cost = 0, prefixCount = 0, zeroInitialCount = 0, tokens = emptyList()))

        for (i in len - 1 downTo 0) {
            val candidates = ArrayList<QuanpinSubPath>(16)
            val ctx = ChunkStepContext(raw = raw, start = start, end = end, i = i)
            collectFullSyllableEdges(ctx, pathsAt, candidates)
            collectDigraphOrPairPrefixEdge(ctx, pathsAt, candidates)
            collectTailPrefixEdge(ctx, pathsAt, candidates)
            collectSingleCharPrefixEdge(ctx, pathsAt, candidates)
            pathsAt[i] = sortAndDedupe(candidates, maxPaths)
        }
        return pathsAt[0].orEmpty()
    }

    private fun collectFullSyllableEdges(
        ctx: ChunkStepContext,
        pathsAt: Array<List<QuanpinSubPath>?>,
        out: MutableList<QuanpinSubPath>
    ) {
        val maxL = minOf(6, ctx.len - ctx.i)
        for (l in maxL downTo 1) {
            val sub = ctx.raw.substring(ctx.start + ctx.i, ctx.start + ctx.i + l)
            val resolved = PinyinTokenizer.resolveQuanpinSyllable(sub) ?: continue
            val suffixes = pathsAt[ctx.i + l] ?: continue
            val firstChar = resolved.first().firstOrNull()
            val zeroInitial = if (ctx.i > 0 && (firstChar == 'a' || firstChar == 'e' || firstChar == 'o')) 1 else 0
            val tok = SyllableToken(
                options = resolved,
                rawStart = ctx.start + ctx.i,
                rawEnd = ctx.start + ctx.i + l,
                isPrefixOnly = false
            )
            appendSubPaths(out, tok, edgeCost = 10, zeroInitialBonus = zeroInitial, suffixes = suffixes)
        }
    }

    private fun collectDigraphOrPairPrefixEdge(
        ctx: ChunkStepContext,
        pathsAt: Array<List<QuanpinSubPath>?>,
        out: MutableList<QuanpinSubPath>
    ) {
        if (ctx.i + 2 > ctx.len) return
        val sub2 = ctx.raw.substring(ctx.start + ctx.i, ctx.start + ctx.i + 2)
        val isDigraphOrTail = sub2 == "zh" ||
            sub2 == "ch" ||
            sub2 == "sh" ||
            (ctx.i + 2 == ctx.len && PinyinTokenizer.isSyllablePrefix(sub2))
        if (!isDigraphOrTail) return
        val suffixes = pathsAt[ctx.i + 2] ?: return
        val tok = SyllableToken(
            options = PinyinTokenizer.expandPrefixSyllables(sub2),
            rawStart = ctx.start + ctx.i,
            rawEnd = ctx.start + ctx.i + 2,
            isPrefixOnly = true
        )
        val edgeCost = if (ctx.i + 2 == ctx.len) 80 else 300
        appendSubPaths(out, tok, edgeCost = edgeCost, zeroInitialBonus = 0, suffixes = suffixes)
    }

    private fun collectTailPrefixEdge(
        ctx: ChunkStepContext,
        pathsAt: Array<List<QuanpinSubPath>?>,
        out: MutableList<QuanpinSubPath>
    ) {
        val tailLen = ctx.len - ctx.i
        if (tailLen !in 3..5) return
        val tail = ctx.raw.substring(ctx.start + ctx.i, ctx.end)
        if (!PinyinTokenizer.isSyllablePrefix(tail)) return
        val suffixes = pathsAt[ctx.len] ?: return
        val tok = SyllableToken(
            options = PinyinTokenizer.expandPrefixSyllables(tail),
            rawStart = ctx.start + ctx.i,
            rawEnd = ctx.end,
            isPrefixOnly = true
        )
        appendSubPaths(out, tok, edgeCost = 90, zeroInitialBonus = 0, suffixes = suffixes)
    }

    private fun collectSingleCharPrefixEdge(
        ctx: ChunkStepContext,
        pathsAt: Array<List<QuanpinSubPath>?>,
        out: MutableList<QuanpinSubPath>
    ) {
        val suffixes = pathsAt[ctx.i + 1] ?: return
        val singleSub = ctx.raw.substring(ctx.start + ctx.i, ctx.start + ctx.i + 1)
        val tok = SyllableToken(
            options = PinyinTokenizer.expandPrefixSyllables(singleSub),
            rawStart = ctx.start + ctx.i,
            rawEnd = ctx.start + ctx.i + 1,
            isPrefixOnly = true
        )
        val edgeCost = if (ctx.i + 1 == ctx.len) 100 else 320
        appendSubPaths(out, tok, edgeCost = edgeCost, zeroInitialBonus = 0, suffixes = suffixes)
    }

    private fun appendSubPaths(
        out: MutableList<QuanpinSubPath>,
        token: SyllableToken,
        edgeCost: Int,
        zeroInitialBonus: Int,
        suffixes: List<QuanpinSubPath>
    ) {
        val pfxInc = if (token.isPrefixOnly) 1 else 0
        for (sfx in suffixes) {
            out.add(
                QuanpinSubPath(
                    cost = edgeCost + sfx.cost,
                    prefixCount = pfxInc + sfx.prefixCount,
                    zeroInitialCount = zeroInitialBonus + sfx.zeroInitialCount,
                    tokens = listOf(token) + sfx.tokens
                )
            )
        }
    }
}

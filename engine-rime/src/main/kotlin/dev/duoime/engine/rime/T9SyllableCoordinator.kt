// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.engine.rime

/**
 * Pure-JVM T9 syllable-rail coordinator layered over `duoime_t9.schema.yaml`'s dual `a-z` + `2-9`
 * `derive` speller.
 *
 * Maintains raw T9 digits alongside progressively locked prefix syllables (`lockedSyllables`),
 * projecting unlocked prefix options for the next syllable and generating the exact Rime replay
 * sequence (e.g. `6262` -> lock `"ma"` -> `"ma'62"` -> lock `"ma"` -> `"ma'ma"` -> `"妈妈"`).
 */
internal class T9SyllableCoordinator {
    private val rawDigits = StringBuilder()
    private val locked = ArrayList<String>()
    private var hasConfirmedNativePrefix: Boolean = false

    val isActive: Boolean
        get() = rawDigits.isNotEmpty() || hasConfirmedNativePrefix

    val canReplayCleanly: Boolean
        get() = !hasConfirmedNativePrefix

    val lockedSyllables: List<String>
        get() = if (hasConfirmedNativePrefix) emptyList() else locked.toList()

    fun appendDigit(digit: Char): Boolean {
        if (digit !in '2'..'9') return false
        rawDigits.append(digit)
        return true
    }

    fun appendDelimiter(): Boolean {
        if (rawDigits.isEmpty() || rawDigits.last() == '\'') return false
        rawDigits.append('\'')
        return true
    }

    fun backspace(): Boolean {
        if (rawDigits.isEmpty()) return false
        rawDigits.deleteCharAt(rawDigits.length - 1)
        trimInvalidLockedPrefix()
        return true
    }

    fun clear() {
        rawDigits.clear()
        locked.clear()
        hasConfirmedNativePrefix = false
    }

    fun currentOptions(): List<String> {
        if (hasConfirmedNativePrefix) return emptyList()
        val remaining = remainingDigitPrefix()
        if (remaining.isEmpty()) return emptyList()
        val seen = LinkedHashSet<String>()
        val maxLen = minOf(remaining.length, MAX_SYLLABLE_LENGTH)
        // Prefer exact complete syllables matching prefixes of the remaining digits (longest first,
        // down to 2-digit then 1-digit), followed by completion prefixes when still mid-syllable.
        for (len in maxLen downTo 1) {
            val slice = remaining.substring(0, len)
            T9PinyinSyllableTable.exactByDigits[slice]?.let { seen.addAll(it) }
        }
        appendPrefixCompletions(remaining, seen)
        return seen.take(MAX_RAIL_OPTIONS)
    }

    private fun appendPrefixCompletions(remaining: String, seen: MutableSet<String>) {
        if (seen.size >= MAX_RAIL_OPTIONS || remaining.length > MAX_SYLLABLE_LENGTH) return
        val candidates = T9PinyinSyllableTable.prefixByDigits[remaining] ?: return
        for (syl in candidates) {
            if (seen.size >= MAX_RAIL_OPTIONS) break
            seen.add(syl)
        }
    }

    fun lockSyllableByIndex(index: Int): Boolean {
        if (index == -1) {
            return unlockFrom(0)
        }
        val option = currentOptions().getOrNull(index) ?: return false
        return lockSyllable(option)
    }

    fun lockSyllable(syllable: String): Boolean {
        val normalized = syllable.trim().lowercase()
        if (normalized !in T9PinyinSyllableTable.validSyllables) return false
        val expectedDigits = T9PinyinSyllableTable.toT9Digits(normalized)
        val remaining = remainingDigitPrefix()
        if (remaining.isEmpty()) return false
        if (!remaining.startsWith(expectedDigits) && !expectedDigits.startsWith(remaining)) {
            return false
        }
        // If user tapped a longer completion option (e.g. "ni" while only "6" typed), complete digits.
        if (expectedDigits.length > remaining.length) {
            val missing = expectedDigits.substring(remaining.length)
            rawDigits.append(missing)
        }
        locked.add(normalized)
        trimInvalidLockedPrefix()
        return true
    }

    fun unlockFrom(fromIndex: Int): Boolean {
        if (locked.isEmpty()) return false
        val safeIndex = fromIndex.coerceAtLeast(0)
        if (safeIndex >= locked.size) return false
        while (locked.size > safeIndex) {
            locked.removeAt(locked.lastIndex)
        }
        return true
    }

    fun buildReplaySequence(): String {
        trimInvalidLockedPrefix()
        if (rawDigits.isEmpty()) return ""
        val cursor = lockedCursorIndex()
        val tail = rawDigits.substring(cursor).trimStart('\'')
        if (locked.isEmpty()) return rawDigits.toString()
        if (tail.isEmpty()) return locked.joinToString("'")
        return "${locked.joinToString("'")}'$tail"
    }

    /**
     * Re-synchronizes local T9 digit/lock state after Rime handles candidate selection or commit.
     * - Full commit (`!postSnapshot.isComposing`): clears all state cleanly.
     * - Partial selection leaving remaining composition: derives trailing uncommitted digits from
     *   `postSnapshot.preedit` (for confirmed Hanzi prefix + trailing digits/pinyin) or
     *   `postSnapshot.rawInput` so the rail reflects only uncommitted syllables.
     */
    fun syncAfterSelection(committedText: String?, postSnapshot: RimeNativeSnapshot?) {
        if (postSnapshot == null || !postSnapshot.isComposing) {
            clear()
            return
        }
        if (committedText.isNullOrEmpty() && hasNonAsciiConfirmedPrefix(postSnapshot.preedit)) {
            hasConfirmedNativePrefix = true
            return
        }
        hasConfirmedNativePrefix = false
        val trailingAscii = extractTrailingUncommittedInput(postSnapshot.preedit, postSnapshot.rawInput)
        if (trailingAscii.isEmpty()) {
            clear()
            return
        }
        val (nextDigits, nextLocked) = parseTrailingSegments(trailingAscii)
        rawDigits.clear()
        rawDigits.append(nextDigits)
        locked.clear()
        locked.addAll(nextLocked)
        trimInvalidLockedPrefix()
    }

    private fun hasNonAsciiConfirmedPrefix(preedit: String): Boolean = preedit.any { ch ->
        ch !in 'a'..'z' && ch !in 'A'..'Z' && ch !in '0'..'9' && ch != '\'' && ch != ' '
    }

    private fun parseTrailingSegments(trailingAscii: String): Pair<String, List<String>> {
        val nextDigits = StringBuilder()
        val nextLocked = ArrayList<String>()
        for (segment in trailingAscii.split('\'')) {
            if (segment.isEmpty()) continue
            if (isValidLockedSyllable(segment)) {
                nextLocked.add(segment)
                nextDigits.append(T9PinyinSyllableTable.toT9Digits(segment))
            } else {
                appendDigitsFromSegment(segment, nextDigits)
            }
        }
        return nextDigits.toString() to nextLocked
    }

    private fun isValidLockedSyllable(segment: String): Boolean =
        segment.all { it in 'a'..'z' } && segment in T9PinyinSyllableTable.validSyllables

    private fun appendDigitsFromSegment(segment: String, target: StringBuilder) {
        for (ch in segment) {
            val digit = if (ch in '2'..'9') ch else T9PinyinSyllableTable.charToDigit(ch)
            if (digit != null) target.append(digit)
        }
    }

    private fun trimInvalidLockedPrefix() {
        var cursor = 0
        var validCount = 0
        for (syl in locked) {
            cursor = skipDelimiters(cursor)
            val code = T9PinyinSyllableTable.toT9Digits(syl)
            if (code.isEmpty() || cursor + code.length > rawDigits.length) break
            if (!matchesAt(cursor, code)) break
            cursor += code.length
            validCount++
        }
        while (locked.size > validCount) {
            locked.removeAt(locked.lastIndex)
        }
    }

    private fun lockedCursorIndex(): Int {
        var cursor = 0
        for (syl in locked) {
            cursor = skipDelimiters(cursor)
            cursor += T9PinyinSyllableTable.toT9Digits(syl).length
        }
        return cursor.coerceAtMost(rawDigits.length)
    }

    private fun remainingDigitPrefix(): String {
        val start = skipDelimiters(lockedCursorIndex())
        if (start >= rawDigits.length) return ""
        val sb = StringBuilder()
        var idx = start
        while (idx < rawDigits.length && rawDigits[idx] != '\'') {
            sb.append(rawDigits[idx])
            idx++
        }
        return sb.toString()
    }

    private fun skipDelimiters(from: Int): Int {
        var idx = from
        while (idx < rawDigits.length && rawDigits[idx] == '\'') {
            idx++
        }
        return idx
    }

    private fun matchesAt(offset: Int, digits: String): Boolean {
        for (i in digits.indices) {
            if (rawDigits[offset + i] != digits[i]) return false
        }
        return true
    }

    private fun extractTrailingUncommittedInput(preedit: String, rawInput: String): String {
        val trimmedPreedit = preedit.trim()
        if (trimmedPreedit.isNotEmpty()) {
            var idx = trimmedPreedit.length
            while (idx > 0) {
                val ch = trimmedPreedit[idx - 1]
                if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '2'..'9' || ch == '\'' || ch == ' ') {
                    idx--
                } else {
                    break
                }
            }
            val tail = trimmedPreedit.substring(idx).trim().replace(' ', '\'').lowercase()
            if (tail.isNotEmpty()) return tail
        }
        return rawInput.trim().lowercase()
    }

    companion object {
        private const val MAX_SYLLABLE_LENGTH: Int = 6
        private const val MAX_RAIL_OPTIONS: Int = 12
    }
}

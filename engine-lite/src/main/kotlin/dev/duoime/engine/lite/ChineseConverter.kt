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

/**
 * Lightweight Simplified -> Traditional Chinese converter using phrase-first greedy matching
 * backed by OpenCC-derived `s2t.tsv`.
 */
class ChineseConverter(
    private val charMap: Map<Char, String> = emptyMap(),
    private val phraseMap: Map<String, String> = emptyMap(),
    private val maxPhraseLen: Int = 4
) {
    fun toTraditional(simplified: String): String {
        if (simplified.isEmpty() || (charMap.isEmpty() && phraseMap.isEmpty())) return simplified
        phraseMap[simplified]?.let { return it }

        val sb = StringBuilder(simplified.length)
        var i = 0
        while (i < simplified.length) {
            val matchedLen = matchLongestPhrase(simplified, i, sb)
            if (matchedLen > 0) {
                i += matchedLen
            } else {
                val c = simplified[i]
                sb.append(charMap[c] ?: c)
                i++
            }
        }
        return sb.toString()
    }

    private fun matchLongestPhrase(text: String, start: Int, out: StringBuilder): Int {
        val upper = minOf(maxPhraseLen, text.length - start)
        for (len in upper downTo 2) {
            val trad = phraseMap[text.substring(start, start + len)]
            if (trad != null) {
                out.append(trad)
                return len
            }
        }
        return 0
    }

    companion object {
        fun fromStream(inputStream: InputStream): ChineseConverter {
            val chars = HashMap<Char, String>(2600)
            val phrases = HashMap<String, String>(1024)
            var maxLen = 2
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val simp = line.substring(0, tab)
                    val trad = line.substring(tab + 1).trim()
                    if (simp.isEmpty() || trad.isEmpty()) continue
                    if (simp.length == 1) {
                        chars[simp[0]] = trad
                    } else {
                        phrases[simp] = trad
                        if (simp.length > maxLen) maxLen = simp.length
                    }
                }
            }
            return ChineseConverter(chars, phrases, maxLen)
        }
    }
}

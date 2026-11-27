// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.layout

import dev.duoime.core.model.FieldKind
import dev.duoime.core.model.Language
import dev.duoime.core.model.SymbolTabId

/**
 * Metadata for a symbol category tab inside `DuoSymbolPanel`.
 */
data class SymbolTabDescriptor(
    val id: SymbolTabId,
    val label: String
)

/**
 * Pure-JVM symbol catalog providing context-aware tab ordering and curated symbol tiles for
 * Chinese, English, and terminal/CLI contexts.
 */
object SymbolCatalog {
    private val tabLabels: Map<SymbolTabId, String> = mapOf(
        SymbolTabId.COMMON to "常用",
        SymbolTabId.CHINESE to "中文",
        SymbolTabId.CLI to "终端",
        SymbolTabId.ENGLISH to "英文",
        SymbolTabId.PAIR to "成对",
        SymbolTabId.MATH_UNIT to "数理",
        SymbolTabId.SEQUENCE to "序号",
        SymbolTabId.ARROW_SPECIAL to "箭头",
        SymbolTabId.GREEK_PHONETIC to "希腊"
    )

    private val commonChinese = listOf(
        "，", "。", "？", "！", "、", "：", "；", "“", "”", "（", "）", "《",
        "》", "—", "…", "·", "【", "】", "‘", "’", "@", "#", "￥", "%",
        "&", "*", "-", "+", "=", "/", "~", "_"
    )

    private val commonEnglish = listOf(
        ",", ".", "?", "!", ":", ";", "'", "\"", "(", ")", "-", "_",
        "/", "@", "#", "$", "%", "&", "*", "+", "=", "[", "]", "{",
        "}", "<", ">", "\\", "|", "~", "`", "^"
    )

    private val cliSymbols = listOf(
        "|", "-", "_", "/", "~", "$", "&", ">", "<", ";", ":", "=",
        "\"", "'", "`", "\\", "*", "?", ".", ",", "(", ")", "[", "]",
        "{", "}", "#", "@", "!", "%", "^", "+"
    )

    private val chinesePunctuation = listOf(
        "，", "。", "、", "；", "：", "？", "！", "…", "—", "～", "·", "￥",
        "“", "”", "‘", "’", "（", "）", "【", "】", "《", "》", "「", "」",
        "『", "』", "〈", "〉", "〔", "〕", "｜", "‖"
    )

    private val englishPunctuation = listOf(
        ".", ",", "!", "?", ";", ":", "'", "\"", "-", "_", "/", "\\",
        "|", "@", "#", "$", "%", "^", "&", "*", "(", ")", "[", "]",
        "{", "}", "<", ">", "+", "=", "~", "`"
    )

    private val pairSymbols = listOf(
        "()", "[]", "{}", "<>", "\"\"", "''", "``", "（）",
        "【】", "《》", "“”", "‘’", "「」", "『』", "〔〕", "〈〉"
    )

    private val mathUnitSymbols = listOf(
        "+", "-", "×", "÷", "=", "≠", "≈", "≤", "≥", "±", "∞", "√",
        "%", "‰", "°", "℃", "℉", "π", "∑", "∏", "∫", "∂", "∆", "∈",
        "∉", "⊂", "∪", "∩", "∀", "∃", "²", "³"
    )

    private val sequenceSymbols = listOf(
        "①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩", "Ⅰ", "Ⅱ",
        "Ⅲ", "Ⅳ", "Ⅴ", "Ⅵ", "Ⅶ", "Ⅷ", "Ⅸ", "Ⅹ", "⒈", "⒉", "⒊", "⒋"
    )

    private val arrowSpecialSymbols = listOf(
        "←", "→", "↑", "↓", "↔", "↕", "↖", "↗", "↘", "↙", "⇒", "⇐",
        "⇔", "✓", "✗", "★", "☆", "●", "○", "■", "□", "▲", "△", "※"
    )

    private val greekPhoneticSymbols = listOf(
        "α", "β", "γ", "δ", "ε", "ζ", "η", "θ", "λ", "μ", "ν", "ξ",
        "π", "ρ", "σ", "τ", "φ", "χ", "ψ", "ω", "Δ", "Θ", "Λ", "Ω"
    )

    fun orderedTabsFor(fieldKind: FieldKind, language: Language): List<SymbolTabDescriptor> {
        val ids = when {
            fieldKind == FieldKind.TERMINAL -> listOf(
                SymbolTabId.CLI,
                SymbolTabId.COMMON,
                SymbolTabId.ENGLISH,
                SymbolTabId.PAIR,
                SymbolTabId.CHINESE,
                SymbolTabId.MATH_UNIT,
                SymbolTabId.ARROW_SPECIAL,
                SymbolTabId.SEQUENCE,
                SymbolTabId.GREEK_PHONETIC
            )
            language == Language.CHINESE -> listOf(
                SymbolTabId.COMMON,
                SymbolTabId.CHINESE,
                SymbolTabId.ENGLISH,
                SymbolTabId.PAIR,
                SymbolTabId.CLI,
                SymbolTabId.MATH_UNIT,
                SymbolTabId.SEQUENCE,
                SymbolTabId.ARROW_SPECIAL,
                SymbolTabId.GREEK_PHONETIC
            )
            else -> listOf(
                SymbolTabId.COMMON,
                SymbolTabId.ENGLISH,
                SymbolTabId.PAIR,
                SymbolTabId.CLI,
                SymbolTabId.CHINESE,
                SymbolTabId.MATH_UNIT,
                SymbolTabId.SEQUENCE,
                SymbolTabId.ARROW_SPECIAL,
                SymbolTabId.GREEK_PHONETIC
            )
        }
        return ids.map { id -> SymbolTabDescriptor(id = id, label = tabLabels[id] ?: id.name) }
    }

    fun symbolsFor(tab: SymbolTabId, fieldKind: FieldKind, language: Language): List<String> = when (tab) {
        SymbolTabId.COMMON -> when {
            fieldKind == FieldKind.TERMINAL -> cliSymbols
            language == Language.CHINESE -> commonChinese
            else -> commonEnglish
        }
        SymbolTabId.CLI -> cliSymbols
        SymbolTabId.CHINESE -> chinesePunctuation
        SymbolTabId.ENGLISH -> englishPunctuation
        SymbolTabId.PAIR -> pairSymbols
        SymbolTabId.MATH_UNIT -> mathUnitSymbols
        SymbolTabId.SEQUENCE -> sequenceSymbols
        SymbolTabId.ARROW_SPECIAL -> arrowSpecialSymbols
        SymbolTabId.GREEK_PHONETIC -> greekPhoneticSymbols
    }
}

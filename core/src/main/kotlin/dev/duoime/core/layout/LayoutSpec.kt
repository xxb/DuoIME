// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.layout

import dev.duoime.core.model.FeedbackZone
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyAction

/**
 * Immutable descriptor for a single key in a keyboard row.
 */
data class KeySpec(
    val id: String,
    val label: String,
    val secondaryLabel: String? = null,
    val action: KeyAction,
    val secondaryAction: KeyAction? = null,
    val weight: Float = 1f,
    val zone: FeedbackZone = FeedbackZone.LETTER,
    val repeatable: Boolean = false
) {
    /**
     * Resolves the action triggered by a long-press on this key, or `null` if none is configured.
     */
    fun resolveSecondaryAction(): KeyAction? = secondaryAction ?: secondaryLabel?.let { KeyAction.Text(it) }
}

/**
 * Immutable horizontal row of keys sized proportionally by [KeySpec.weight].
 */
data class KeyRowSpec(val keys: List<KeySpec>) {
    val totalWeight: Float
        get() = keys.fold(0f) { acc, key -> acc + key.weight }
}

/**
 * Immutable complete keyboard layout specification.
 */
data class LayoutSpec(
    val id: String,
    val mode: InputMode,
    val rows: List<KeyRowSpec>
)

/**
 * Built-in M1 keyboard layout definitions (26-key QWERTY, 9-key T9, Symbols v0, and default toolbar).
 */
object BuiltInLayouts {
    private val digitsRow = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0')
    private val qwertyRow1 = listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p')
    private val qwertyRow2 = listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l')
    private val qwertyRow2Hints = listOf("@", "#", "$", "%", "&", "-", "+", "(", ")")
    private val qwertyRow3 = listOf('z', 'x', 'c', 'v', 'b', 'n', 'm')
    private val qwertyRow3Hints = listOf("*", "\"", "'", ":", ";", "!", "?")
    private val symbolsRow2 = listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/")
    private val symbolsRow3 = listOf("*", "\"", "'", ":", ";", "!", "?", "~", "_")

    fun qwerty(includeNumberRow: Boolean = false, shifted: Boolean = false, englishMode: Boolean = false): LayoutSpec {
        val mode = if (englishMode) InputMode.ENGLISH else InputMode.PINYIN_26
        val rows = ArrayList<KeyRowSpec>()
        if (includeNumberRow) {
            rows.add(KeyRowSpec(digitsRow.map { digitTextKey(it) }))
        }
        rows.add(
            KeyRowSpec(
                qwertyRow1.mapIndexed { idx, ch ->
                    val hint = if (includeNumberRow) null else digitsRow[idx].toString()
                    letterKey(ch, shifted, englishMode, secondaryLabel = hint)
                }
            )
        )
        rows.add(
            KeyRowSpec(
                qwertyRow2.mapIndexed { idx, ch ->
                    letterKey(ch, shifted, englishMode, secondaryLabel = qwertyRow2Hints[idx])
                }
            )
        )
        rows.add(
            KeyRowSpec(
                buildList {
                    add(
                        KeySpec(
                            id = "shift",
                            label = if (shifted) "SHIFT" else "Shift",
                            action = KeyAction.Shift,
                            weight = 1.5f,
                            zone = FeedbackZone.ACTION
                        )
                    )
                    qwertyRow3.forEachIndexed { idx, ch ->
                        add(letterKey(ch, shifted, englishMode, secondaryLabel = qwertyRow3Hints[idx]))
                    }
                    add(backspaceKey(weight = 1.5f))
                }
            )
        )
        rows.add(qwertyBottomRow(englishMode))

        val idSuffix = buildString {
            append(if (englishMode) "en" else "pinyin")
            if (includeNumberRow) append("_num")
            if (shifted) append("_shift")
        }
        return LayoutSpec(id = "qwerty_$idSuffix", mode = mode, rows = rows)
    }

    fun t9(): LayoutSpec = LayoutSpec(
        id = "t9_pinyin",
        mode = InputMode.PINYIN_T9,
        rows = listOf(
            KeyRowSpec(
                listOf(
                    KeySpec(
                        id = "t9_sym",
                        label = "符",
                        action = KeyAction.SwitchMode(InputMode.SYMBOLS),
                        weight = 1f,
                        zone = FeedbackZone.ACTION
                    ),
                    t9DigitKey(digit = '1', letters = ",."),
                    t9DigitKey(digit = '2', letters = "ABC"),
                    t9DigitKey(digit = '3', letters = "DEF"),
                    backspaceKey(weight = 1f)
                )
            ),
            KeyRowSpec(
                listOf(
                    KeySpec(
                        id = "t9_en",
                        label = "英",
                        action = KeyAction.SwitchMode(InputMode.ENGLISH),
                        weight = 1f,
                        zone = FeedbackZone.ACTION
                    ),
                    t9DigitKey(digit = '4', letters = "GHI"),
                    t9DigitKey(digit = '5', letters = "JKL"),
                    t9DigitKey(digit = '6', letters = "MNO"),
                    symbolKey(id = "t9_comma", label = "，", weight = 1f)
                )
            ),
            KeyRowSpec(
                listOf(
                    KeySpec(
                        id = "t9_26",
                        label = "全",
                        action = KeyAction.SwitchMode(InputMode.PINYIN_26),
                        weight = 1f,
                        zone = FeedbackZone.ACTION
                    ),
                    t9DigitKey(digit = '7', letters = "PQRS"),
                    t9DigitKey(digit = '8', letters = "TUV"),
                    t9DigitKey(digit = '9', letters = "WXYZ"),
                    symbolKey(id = "t9_period", label = "。", weight = 1f)
                )
            ),
            KeyRowSpec(
                listOf(
                    KeySpec(
                        id = "t9_variant",
                        label = "简/繁",
                        action = KeyAction.SwitchVariant,
                        weight = 1.2f,
                        zone = FeedbackZone.ACTION
                    ),
                    KeySpec(
                        id = "t9_space",
                        label = "空格",
                        action = KeyAction.Space,
                        weight = 2.6f,
                        zone = FeedbackZone.SPACE
                    ),
                    digitTextKey(digit = '0', weight = 1f),
                    enterKey(weight = 1.2f)
                )
            )
        )
    )

    fun symbolsV0(): LayoutSpec = LayoutSpec(
        id = "symbols_v0",
        mode = InputMode.SYMBOLS,
        rows = listOf(
            KeyRowSpec(digitsRow.map { digitTextKey(it) }),
            KeyRowSpec(symbolsRow2.mapIndexed { idx, sym -> symbolKey(id = "sym_r2_$idx", label = sym) }),
            KeyRowSpec(
                buildList {
                    symbolsRow3.forEachIndexed { idx, sym -> add(symbolKey(id = "sym_r3_$idx", label = sym)) }
                    add(backspaceKey(weight = 1.5f))
                }
            ),
            KeyRowSpec(
                listOf(
                    KeySpec(
                        id = "sym_back",
                        label = "返回",
                        action = KeyAction.SwitchMode(InputMode.SYMBOLS),
                        weight = 1.5f,
                        zone = FeedbackZone.ACTION
                    ),
                    symbolKey(id = "sym_comma", label = ",", weight = 1f),
                    KeySpec(
                        id = "sym_space",
                        label = "Space",
                        action = KeyAction.Space,
                        weight = 4f,
                        zone = FeedbackZone.SPACE
                    ),
                    symbolKey(id = "sym_dot", label = ".", weight = 1f),
                    enterKey(weight = 1.5f)
                )
            )
        )
    )

    fun defaultToolbarKeys(): List<KeySpec> = listOf(
        KeySpec(id = "tb_swap", label = "换屏", action = KeyAction.SwapScreen, zone = FeedbackZone.TOOLBAR),
        KeySpec(id = "tb_height", label = "高度", action = KeyAction.CycleHeight, zone = FeedbackZone.TOOLBAR),
        KeySpec(id = "tb_variant", label = "简/繁", action = KeyAction.SwitchVariant, zone = FeedbackZone.TOOLBAR),
        KeySpec(
            id = "tb_t9",
            label = "九键",
            action = KeyAction.SwitchMode(InputMode.PINYIN_T9),
            zone = FeedbackZone.TOOLBAR
        ),
        KeySpec(id = "tb_ime", label = "输入法", action = KeyAction.SwitchIme, zone = FeedbackZone.TOOLBAR),
        KeySpec(id = "tb_dismiss", label = "收起", action = KeyAction.Dismiss, zone = FeedbackZone.TOOLBAR)
    )

    private fun qwertyBottomRow(englishMode: Boolean): KeyRowSpec {
        val nextMode = if (englishMode) InputMode.PINYIN_26 else InputMode.ENGLISH
        val toggleLabel = if (englishMode) "中" else "EN"
        return KeyRowSpec(
            listOf(
                KeySpec(
                    id = "mode_sym",
                    label = "123",
                    action = KeyAction.SwitchMode(InputMode.SYMBOLS),
                    weight = 1.5f,
                    zone = FeedbackZone.ACTION
                ),
                KeySpec(
                    id = "mode_lang",
                    label = toggleLabel,
                    action = KeyAction.SwitchMode(nextMode),
                    weight = 1.2f,
                    zone = FeedbackZone.ACTION
                ),
                symbolKey(id = "comma", label = if (englishMode) "," else "，", weight = 1f),
                KeySpec(
                    id = "space",
                    label = if (englishMode) "English" else "拼音",
                    action = KeyAction.Space,
                    weight = 3.8f,
                    zone = FeedbackZone.SPACE
                ),
                symbolKey(id = "period", label = if (englishMode) "." else "。", weight = 1f),
                enterKey(weight = 1.5f)
            )
        )
    }

    private fun letterKey(
        baseChar: Char,
        shifted: Boolean,
        englishMode: Boolean,
        secondaryLabel: String? = null
    ): KeySpec {
        val displayChar = if (shifted) baseChar.uppercaseChar() else baseChar
        val action = if (englishMode) {
            KeyAction.Text(displayChar.toString())
        } else {
            KeyAction.Engine(keysym = KeyCodes.keysymForChar(displayChar))
        }
        return KeySpec(
            id = "key_$baseChar",
            label = displayChar.toString(),
            secondaryLabel = secondaryLabel,
            action = action,
            zone = FeedbackZone.LETTER
        )
    }

    private fun t9DigitKey(digit: Char, letters: String): KeySpec = KeySpec(
        id = "t9_$digit",
        label = letters,
        secondaryLabel = digit.toString(),
        action = KeyAction.Engine(keysym = KeyCodes.keysymForChar(digit)),
        weight = 1.4f,
        zone = FeedbackZone.DIGIT
    )

    private fun digitTextKey(digit: Char, weight: Float = 1f): KeySpec = KeySpec(
        id = "digit_$digit",
        label = digit.toString(),
        action = KeyAction.Text(digit.toString()),
        weight = weight,
        zone = FeedbackZone.DIGIT
    )

    private fun symbolKey(id: String, label: String, weight: Float = 1f): KeySpec = KeySpec(
        id = id,
        label = label,
        action = KeyAction.Text(label),
        weight = weight,
        zone = FeedbackZone.SYMBOL
    )

    private fun backspaceKey(weight: Float): KeySpec = KeySpec(
        id = "backspace",
        label = "⌫",
        action = KeyAction.Backspace,
        weight = weight,
        zone = FeedbackZone.BACKSPACE,
        repeatable = true
    )

    private fun enterKey(weight: Float): KeySpec = KeySpec(
        id = "enter",
        label = "↵",
        action = KeyAction.Enter,
        weight = weight,
        zone = FeedbackZone.ENTER
    )
}

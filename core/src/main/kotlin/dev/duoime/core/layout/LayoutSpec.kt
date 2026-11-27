// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.layout

import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.FeedbackZone
import dev.duoime.core.model.FieldKind
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.KeyboardPanel
import dev.duoime.core.model.LangKeyPlacement
import dev.duoime.core.model.Language

/**
 * Immutable descriptor for a single key (or embedded widget slot when [isSlot] is `true`) in a
 * keyboard row.
 */
data class KeySpec(
    val id: String,
    val label: String,
    val secondaryLabel: String? = null,
    val action: KeyAction,
    val secondaryAction: KeyAction? = null,
    val weight: Float = 1f,
    val zone: FeedbackZone = FeedbackZone.LETTER,
    val repeatable: Boolean = false,
    val rowSpan: Int = 1,
    val isSlot: Boolean = false,
    val enabled: Boolean = true,
    val highlighted: Boolean = false,
    val allowLongPress: Boolean = true
) {
    /**
     * Resolves the action triggered by a long-press on this key, or `null` if long-press is
     * disabled ([allowLongPress] == `false`) or no secondary symbol/action is configured.
     */
    fun resolveSecondaryAction(): KeyAction? {
        if (!allowLongPress) return null
        return secondaryAction ?: secondaryLabel?.let { KeyAction.Text(it) }
    }
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
 *
 * When [columnWeights] is `null`, each row lays out independently by key weight (e.g. QWERTY).
 * When [columnWeights] is non-null (e.g. 5×4 T9 and Numpad), keys align onto shared column tracks
 * supporting multi-row spans ([KeySpec.rowSpan]) and embedded widget slots ([KeySpec.isSlot]).
 */
data class LayoutSpec(
    val id: String,
    val mode: InputMode,
    val rows: List<KeyRowSpec>,
    val panel: KeyboardPanel = KeyboardPanel.PRIMARY,
    val columnWeights: List<Float>? = null
)

/**
 * Built-in zero-duplication keyboard layout definitions (26-key QWERTY with left `中/英`,
 * 5×4 T9 with Col-0 vertical rail slot, shared 5×4 field-aware Numpad, and Symbols fallback).
 */
object BuiltInLayouts {
    const val T9_LEFT_SLOT_ID: String = "t9_left_slot"

    private val fiveColumnTracks = listOf(1f, 1f, 1f, 1f, 1f)
    private val digitsRow = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0')
    private val qwertyRow1 = listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p')
    private val qwertyRow2 = listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l')
    private val qwertyRow2Hints = listOf("@", "#", "$", "%", "&", "-", "+", "(", ")")
    private val qwertyRow3 = listOf('z', 'x', 'c', 'v', 'b', 'n', 'm')
    private val qwertyRow3Hints = listOf("*", "\"", "'", ":", ";", "!", "?")
    private val symbolsRow2 = listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/")
    private val symbolsRow3 = listOf("*", "\"", "'", ":", ";", "!", "?", "~", "_")

    @Suppress("LongParameterList")
    fun qwerty(
        includeNumberRow: Boolean = false,
        shifted: Boolean = false,
        englishMode: Boolean = false,
        variant: ChineseVariant = ChineseVariant.SIMPLIFIED,
        fieldKind: FieldKind = FieldKind.TEXT,
        canToggleLanguage: Boolean = true,
        langKeyPlacement: LangKeyPlacement = LangKeyPlacement.GRID
    ): LayoutSpec {
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
                            zone = FeedbackZone.ACTION,
                            highlighted = shifted,
                            allowLongPress = false
                        )
                    )
                    qwertyRow3.forEachIndexed { idx, ch ->
                        add(letterKey(ch, shifted, englishMode, secondaryLabel = qwertyRow3Hints[idx]))
                    }
                    add(backspaceKey(weight = 1.5f))
                }
            )
        )
        rows.add(
            qwertyBottomRow(
                englishMode = englishMode,
                variant = variant,
                fieldKind = fieldKind,
                canToggleLanguage = canToggleLanguage,
                langKeyPlacement = langKeyPlacement
            )
        )

        val idSuffix = buildString {
            append(if (englishMode) "en" else "pinyin")
            if (includeNumberRow) append("_num")
            if (shifted) append("_shift")
            if (variant == ChineseVariant.TRADITIONAL) append("_trad")
            if (!canToggleLanguage) append("_nolang")
            if (langKeyPlacement == LangKeyPlacement.HOST) append("_host")
            if (fieldKind == FieldKind.URI || fieldKind == FieldKind.EMAIL) {
                append('_').append(fieldKind.name.lowercase())
            }
        }
        return LayoutSpec(id = "qwerty_$idSuffix", mode = mode, rows = rows, panel = KeyboardPanel.PRIMARY)
    }

    fun t9(
        isComposing: Boolean = false,
        variant: ChineseVariant = ChineseVariant.SIMPLIFIED,
        canToggleLanguage: Boolean = true,
        langKeyPlacement: LangKeyPlacement = LangKeyPlacement.GRID,
        language: Language = Language.CHINESE
    ): LayoutSpec {
        val leftSlot = KeySpec(
            id = T9_LEFT_SLOT_ID,
            label = "",
            action = KeyAction.ToggleToolbar,
            weight = 1f,
            zone = FeedbackZone.ACTION,
            rowSpan = 3,
            isSlot = true,
            enabled = false,
            allowLongPress = false
        )
        val key1 = if (isComposing) {
            KeySpec(
                id = "t9_1",
                label = "分词",
                secondaryLabel = "'",
                action = KeyAction.Engine(keysym = KeyCodes.KEYSYM_APOSTROPHE),
                weight = 1f,
                zone = FeedbackZone.ACTION,
                allowLongPress = false
            )
        } else {
            KeySpec(
                id = "t9_1",
                label = "、",
                secondaryLabel = "1",
                action = KeyAction.Text("、"),
                secondaryAction = KeyAction.Text("1"),
                weight = 1f,
                zone = FeedbackZone.SYMBOL,
                allowLongPress = true
            )
        }
        val row1RightKey = if (isComposing) {
            KeySpec(
                id = "t9_clear",
                label = "重输",
                action = KeyAction.ClearPreedit,
                weight = 1f,
                zone = FeedbackZone.ACTION,
                allowLongPress = false
            )
        } else {
            symbolKey(id = "t9_bang", label = "！", weight = 1f)
        }

        val row3Keys = buildT9BottomRow(
            isComposing = isComposing,
            variant = variant,
            canToggleLanguage = canToggleLanguage,
            langKeyPlacement = langKeyPlacement,
            language = language
        )

        val idSuffix = buildString {
            append(if (isComposing) "comp" else "idle")
            if (variant == ChineseVariant.TRADITIONAL) append("_trad")
            if (!canToggleLanguage) append("_nolang")
            if (langKeyPlacement == LangKeyPlacement.HOST) append("_host")
            if (language == Language.ENGLISH) append("_en")
        }
        return LayoutSpec(
            id = "t9_$idSuffix",
            mode = InputMode.PINYIN_T9,
            panel = KeyboardPanel.PRIMARY,
            columnWeights = fiveColumnTracks,
            rows = listOf(
                KeyRowSpec(
                    listOf(
                        leftSlot,
                        key1,
                        t9DigitKey(digit = '2', letters = "ABC", isComposing = isComposing),
                        t9DigitKey(digit = '3', letters = "DEF", isComposing = isComposing),
                        backspaceKey(weight = 1f)
                    )
                ),
                KeyRowSpec(
                    listOf(
                        t9DigitKey(digit = '4', letters = "GHI", isComposing = isComposing),
                        t9DigitKey(digit = '5', letters = "JKL", isComposing = isComposing),
                        t9DigitKey(digit = '6', letters = "MNO", isComposing = isComposing),
                        row1RightKey
                    )
                ),
                KeyRowSpec(
                    listOf(
                        t9DigitKey(digit = '7', letters = "PQRS", isComposing = isComposing),
                        t9DigitKey(digit = '8', letters = "TUV", isComposing = isComposing),
                        t9DigitKey(digit = '9', letters = "WXYZ", isComposing = isComposing),
                        enterKey(weight = 1f, rowSpan = 2)
                    )
                ),
                KeyRowSpec(row3Keys)
            )
        )
    }

    private fun buildT9BottomRow(
        isComposing: Boolean,
        variant: ChineseVariant,
        canToggleLanguage: Boolean,
        langKeyPlacement: LangKeyPlacement,
        language: Language
    ): List<KeySpec> = buildList {
        add(
            KeySpec(
                id = "t9_sym",
                label = "符号",
                action = KeyAction.OpenPanel(KeyboardPanel.SYMBOL),
                weight = 1f,
                zone = FeedbackZone.ACTION,
                allowLongPress = false
            )
        )
        if (langKeyPlacement == LangKeyPlacement.GRID) {
            add(
                KeySpec(
                    id = "t9_num",
                    label = "123",
                    action = KeyAction.OpenPanel(KeyboardPanel.NUMBER),
                    weight = 0.80f,
                    zone = FeedbackZone.ACTION,
                    allowLongPress = false
                )
            )
            add(
                langToggleKey(
                    id = "t9_lang",
                    weight = 0.75f,
                    language = language,
                    variant = variant,
                    canToggleLanguage = canToggleLanguage
                )
            )
            add(t9SpaceKey(weight = 1.45f, isComposing = isComposing))
        } else {
            add(
                KeySpec(
                    id = "t9_num",
                    label = "123",
                    action = KeyAction.OpenPanel(KeyboardPanel.NUMBER),
                    weight = 0.85f,
                    zone = FeedbackZone.ACTION,
                    allowLongPress = false
                )
            )
            add(t9SpaceKey(weight = 2.15f, isComposing = isComposing))
        }
    }

    fun numberPad(fieldKind: FieldKind = FieldKind.TEXT, mode: InputMode = InputMode.ENGLISH): LayoutSpec {
        val (op0, op1, op2, col4Punct) = when (fieldKind) {
            FieldKind.PHONE -> NumberPadOperators("+", "*", "#", "(")
            FieldKind.DATETIME -> NumberPadOperators(":", "-", "/", ".")
            else -> NumberPadOperators("+", "-", "*", "/")
        }
        val isForcedNumeric = fieldKind in FORCED_NUMERIC_FIELDS
        val idSuffix = buildString {
            append(fieldKind.name.lowercase())
            append('_').append(mode.name.lowercase())
        }
        return LayoutSpec(
            id = "numpad_$idSuffix",
            mode = mode,
            panel = KeyboardPanel.NUMBER,
            columnWeights = fiveColumnTracks,
            rows = listOf(
                KeyRowSpec(
                    listOf(
                        symbolKey(id = "num_op0", label = op0, weight = 1f),
                        digitTextKey('1', weight = 1f),
                        digitTextKey('2', weight = 1f),
                        digitTextKey('3', weight = 1f),
                        backspaceKey(weight = 1f)
                    )
                ),
                KeyRowSpec(
                    listOf(
                        symbolKey(id = "num_op1", label = op1, weight = 1f),
                        digitTextKey('4', weight = 1f),
                        digitTextKey('5', weight = 1f),
                        digitTextKey('6', weight = 1f),
                        KeySpec(
                            id = "num_space",
                            label = "␣",
                            action = KeyAction.Space,
                            weight = 1f,
                            zone = FeedbackZone.SPACE,
                            allowLongPress = false
                        )
                    )
                ),
                KeyRowSpec(
                    listOf(
                        symbolKey(id = "num_op2", label = op2, weight = 1f),
                        digitTextKey('7', weight = 1f),
                        digitTextKey('8', weight = 1f),
                        digitTextKey('9', weight = 1f),
                        symbolKey(id = "num_col4_punct", label = col4Punct, weight = 1f)
                    )
                ),
                KeyRowSpec(
                    listOf(
                        KeySpec(
                            id = "num_sym",
                            label = "符号",
                            action = KeyAction.OpenPanel(KeyboardPanel.SYMBOL),
                            weight = 1f,
                            zone = FeedbackZone.ACTION,
                            allowLongPress = false
                        ),
                        KeySpec(
                            id = "num_back",
                            label = "返回",
                            action = KeyAction.ClosePanel,
                            weight = 1f,
                            zone = FeedbackZone.ACTION,
                            enabled = !isForcedNumeric,
                            allowLongPress = false
                        ),
                        digitTextKey('0', weight = 1f),
                        symbolKey(id = "num_dot", label = ".", weight = 1f),
                        enterKey(weight = 1f)
                    )
                )
            )
        )
    }

    fun numberPadV0(mode: InputMode = InputMode.ENGLISH): LayoutSpec = numberPad(FieldKind.TEXT, mode)

    fun symbolsV0(mode: InputMode = InputMode.PINYIN_26): LayoutSpec = LayoutSpec(
        id = "symbols_v0_${mode.name.lowercase()}",
        mode = mode,
        panel = KeyboardPanel.SYMBOL,
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
                        action = KeyAction.ClosePanel,
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
        KeySpec(id = "tb_drawer", label = "☰", action = KeyAction.ToggleDrawer, zone = FeedbackZone.TOOLBAR),
        KeySpec(id = "tb_swap", label = "换屏", action = KeyAction.SwapScreen, zone = FeedbackZone.TOOLBAR),
        KeySpec(id = "tb_dismiss", label = "收起", action = KeyAction.Dismiss, zone = FeedbackZone.TOOLBAR)
    )

    private data class NumberPadOperators(
        val op0: String,
        val op1: String,
        val op2: String,
        val col4Punct: String
    )

    private val FORCED_NUMERIC_FIELDS: Set<FieldKind> = setOf(
        FieldKind.NUMBER,
        FieldKind.DECIMAL,
        FieldKind.SIGNED_NUMBER,
        FieldKind.PHONE,
        FieldKind.DATETIME
    )

    private fun qwertyBottomRow(
        englishMode: Boolean,
        variant: ChineseVariant,
        fieldKind: FieldKind,
        canToggleLanguage: Boolean,
        langKeyPlacement: LangKeyPlacement
    ): KeyRowSpec {
        val leftPunct = when {
            fieldKind == FieldKind.URI -> "/"
            fieldKind == FieldKind.EMAIL -> "@"
            englishMode -> ","
            else -> "，"
        }
        val rightPunct = if (!englishMode && fieldKind != FieldKind.URI && fieldKind != FieldKind.EMAIL) "。" else "."
        val spaceWeight = if (langKeyPlacement == LangKeyPlacement.HOST) 5.1f else 3.9f
        val language = if (englishMode) Language.ENGLISH else Language.CHINESE

        return KeyRowSpec(
            buildList {
                add(
                    KeySpec(
                        id = "mode_sym",
                        label = "符号",
                        secondaryLabel = "123",
                        action = KeyAction.OpenPanel(KeyboardPanel.SYMBOL),
                        secondaryAction = KeyAction.OpenPanel(KeyboardPanel.NUMBER),
                        weight = 1.4f,
                        zone = FeedbackZone.ACTION,
                        allowLongPress = true
                    )
                )
                if (langKeyPlacement == LangKeyPlacement.GRID) {
                    add(
                        langToggleKey(
                            id = "mode_lang",
                            weight = 1.2f,
                            language = language,
                            variant = variant,
                            canToggleLanguage = canToggleLanguage
                        )
                    )
                }
                add(symbolKey(id = "comma", label = leftPunct, weight = 1.0f))
                add(
                    KeySpec(
                        id = "space",
                        label = if (englishMode) "English" else "拼音",
                        action = KeyAction.Space,
                        weight = spaceWeight,
                        zone = FeedbackZone.SPACE,
                        allowLongPress = false
                    )
                )
                add(symbolKey(id = "period", label = rightPunct, weight = 1.0f))
                add(enterKey(weight = 1.5f))
            }
        )
    }

    private fun langToggleKey(
        id: String,
        weight: Float,
        language: Language,
        variant: ChineseVariant,
        canToggleLanguage: Boolean
    ): KeySpec = KeySpec(
        id = id,
        label = "中/英",
        secondaryLabel = if (variant == ChineseVariant.TRADITIONAL) "繁" else null,
        action = KeyAction.ToggleLanguage,
        weight = weight,
        zone = FeedbackZone.ACTION,
        enabled = canToggleLanguage,
        highlighted = language == Language.CHINESE,
        allowLongPress = false
    )

    private fun t9SpaceKey(weight: Float, isComposing: Boolean): KeySpec = KeySpec(
        id = "t9_space",
        label = "空格",
        secondaryLabel = if (isComposing) null else "0",
        action = KeyAction.Space,
        secondaryAction = if (isComposing) null else KeyAction.Text("0"),
        weight = weight,
        zone = FeedbackZone.SPACE,
        allowLongPress = !isComposing
    )

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

    private fun t9DigitKey(digit: Char, letters: String, isComposing: Boolean): KeySpec = KeySpec(
        id = "t9_$digit",
        label = letters,
        secondaryLabel = digit.toString(),
        action = KeyAction.Engine(keysym = KeyCodes.keysymForChar(digit)),
        secondaryAction = if (isComposing) null else KeyAction.Text(digit.toString()),
        weight = 1f,
        zone = FeedbackZone.DIGIT,
        allowLongPress = !isComposing
    )

    private fun digitTextKey(digit: Char, weight: Float = 1f): KeySpec = KeySpec(
        id = "digit_$digit",
        label = digit.toString(),
        action = KeyAction.Text(digit.toString()),
        weight = weight,
        zone = FeedbackZone.DIGIT,
        allowLongPress = false
    )

    private fun symbolKey(id: String, label: String, weight: Float = 1f): KeySpec = KeySpec(
        id = id,
        label = label,
        action = KeyAction.Text(label),
        weight = weight,
        zone = FeedbackZone.SYMBOL,
        allowLongPress = false
    )

    private fun backspaceKey(weight: Float): KeySpec = KeySpec(
        id = "backspace",
        label = "⌫",
        action = KeyAction.Backspace,
        weight = weight,
        zone = FeedbackZone.BACKSPACE,
        repeatable = true,
        allowLongPress = false
    )

    private fun enterKey(weight: Float, rowSpan: Int = 1): KeySpec = KeySpec(
        id = "enter",
        label = "⏎",
        action = KeyAction.Enter,
        weight = weight,
        zone = FeedbackZone.ENTER,
        rowSpan = rowSpan,
        allowLongPress = false
    )
}

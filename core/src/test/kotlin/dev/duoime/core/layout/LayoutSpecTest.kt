// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.layout

import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.FieldKind
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.KeyboardPanel
import dev.duoime.core.model.LangKeyPlacement
import dev.duoime.core.model.Language
import dev.duoime.core.model.SymbolTabId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutSpecTest {
    @Test
    fun qwertySupportsNumberRowShiftLeftLangKeyHostOmissionAndFieldPunctuation() {
        val base = BuiltInLayouts.qwerty()
        assertEquals(InputMode.PINYIN_26, base.mode)
        assertEquals(4, base.rows.size)
        assertEquals(10, base.rows[0].keys.size)
        assertEquals("q", base.rows[0].keys[0].label)
        assertEquals(KeyAction.Engine(KeyCodes.keysymForChar('q')), base.rows[0].keys[0].action)

        // Bottom row: 6 keys with LEFT 中/英, 符号 secondary 123, ZERO standalone [123] key
        val bottom = base.rows.last().keys
        assertEquals(6, bottom.size)
        assertEquals("符号", bottom[0].label)
        assertEquals("123", bottom[0].secondaryLabel)
        assertEquals(KeyAction.OpenPanel(KeyboardPanel.SYMBOL), bottom[0].action)
        assertEquals(KeyAction.OpenPanel(KeyboardPanel.NUMBER), bottom[0].resolveSecondaryAction())
        assertEquals("中/英", bottom[1].label)
        assertEquals(KeyAction.ToggleLanguage, bottom[1].action)
        assertTrue(bottom[1].highlighted)
        assertNull(bottom[1].resolveSecondaryAction())
        assertEquals("，", bottom[2].label)
        assertEquals("拼音", bottom[3].label)
        assertEquals(3.9f, bottom[3].weight, 0.001f)
        assertEquals("。", bottom[4].label)
        assertEquals(KeyAction.Enter, bottom[5].action)

        // HOST placement omits 中/英 and widens Space to 5.1f
        val hostBottom = BuiltInLayouts.qwerty(langKeyPlacement = LangKeyPlacement.HOST).rows.last().keys
        assertEquals(5, hostBottom.size)
        assertTrue(hostBottom.none { it.action == KeyAction.ToggleLanguage })
        assertEquals(5.1f, hostBottom.first { it.action == KeyAction.Space }.weight, 0.001f)

        // URI & EMAIL left punctuation + Traditional badge + password disabled lang key
        val uriBottom = BuiltInLayouts.qwerty(
            englishMode = true,
            fieldKind = FieldKind.URI
        ).rows.last().keys
        assertEquals("/", uriBottom[2].label)
        assertEquals(".", uriBottom[4].label)

        val emailBottom = BuiltInLayouts.qwerty(
            englishMode = true,
            fieldKind = FieldKind.EMAIL,
            variant = ChineseVariant.TRADITIONAL,
            canToggleLanguage = false
        ).rows.last().keys
        assertEquals("@", emailBottom[2].label)
        assertEquals("繁", emailBottom[1].secondaryLabel)
        assertFalse(emailBottom[1].enabled)
        assertNull(emailBottom[1].resolveSecondaryAction())

        val withNumbersShiftedEn = BuiltInLayouts.qwerty(
            includeNumberRow = true,
            shifted = true,
            englishMode = true
        )
        assertEquals(InputMode.ENGLISH, withNumbersShiftedEn.mode)
        assertEquals(5, withNumbersShiftedEn.rows.size)
        assertEquals("1", withNumbersShiftedEn.rows[0].keys[0].label)
        assertEquals("Q", withNumbersShiftedEn.rows[1].keys[0].label)
        assertEquals(KeyAction.Text("Q"), withNumbersShiftedEn.rows[1].keys[0].action)
    }

    @Test
    fun t9FiveByFourLayoutSwitchesCleanlyBetweenIdleAndComposingStates() {
        val idle = BuiltInLayouts.t9(isComposing = false)
        assertEquals(InputMode.PINYIN_T9, idle.mode)
        assertEquals(listOf(1f, 1f, 1f, 1f, 1f), idle.columnWeights)
        assertEquals(4, idle.rows.size)

        // Row 0 Col 0 is t9_left_slot (rowSpan=3, isSlot=true)
        val slot = idle.rows[0].keys[0]
        assertEquals(BuiltInLayouts.T9_LEFT_SLOT_ID, slot.id)
        assertTrue(slot.isSlot)
        assertEquals(3, slot.rowSpan)

        // Idle key 1 = "、" with long-press "1"; digits 2..9 allow long-press; row 1 right = "！"
        val idleKey1 = idle.rows[0].keys[1]
        assertEquals("、", idleKey1.label)
        assertEquals(KeyAction.Text("、"), idleKey1.action)
        assertEquals(KeyAction.Text("1"), idleKey1.resolveSecondaryAction())
        assertEquals(KeyAction.Text("2"), idle.rows[0].keys[2].resolveSecondaryAction())
        assertEquals("！", idle.rows[1].keys.last().label)
        assertEquals(2, idle.rows[2].keys.last().rowSpan)

        // Row 3 GRID: [符号 1.0, 123 0.80, 中/英 0.75, 空格 1.45 (secondary "0")]
        val row3Grid = idle.rows[3].keys
        assertEquals(4, row3Grid.size)
        assertEquals(KeyAction.OpenPanel(KeyboardPanel.SYMBOL), row3Grid[0].action)
        assertEquals(KeyAction.OpenPanel(KeyboardPanel.NUMBER), row3Grid[1].action)
        assertEquals(KeyAction.ToggleLanguage, row3Grid[2].action)
        assertEquals(KeyAction.Text("0"), row3Grid[3].resolveSecondaryAction())

        // Composing state: key 1 = "分词" (apostrophe, no long-press); row 1 right = "重输" (ClearPreedit);
        // digits 2..9 and space disable long-press
        val comp = BuiltInLayouts.t9(isComposing = true)
        val compKey1 = comp.rows[0].keys[1]
        assertEquals("分词", compKey1.label)
        assertEquals(KeyAction.Engine(KeyCodes.KEYSYM_APOSTROPHE), compKey1.action)
        assertNull(compKey1.resolveSecondaryAction())
        assertNull(comp.rows[0].keys[2].resolveSecondaryAction())
        assertEquals(KeyAction.ClearPreedit, comp.rows[1].keys.last().action)
        assertNull(comp.rows[3].keys.last().resolveSecondaryAction())

        // HOST placement omits 中/英 and gives [符号 1.0, 123 0.85, 空格 2.15]
        val hostRow3 = BuiltInLayouts.t9(langKeyPlacement = LangKeyPlacement.HOST).rows[3].keys
        assertEquals(3, hostRow3.size)
        assertEquals(0.85f, hostRow3[1].weight, 0.001f)
        assertEquals(2.15f, hostRow3[2].weight, 0.001f)
    }

    @Test
    fun numberPadAdaptsOperatorsToFieldKindAndDisablesBackOnForcedNumericFields() {
        val textPad = BuiltInLayouts.numberPad(FieldKind.TEXT)
        assertEquals(KeyboardPanel.NUMBER, textPad.panel)
        assertEquals(listOf(1f, 1f, 1f, 1f, 1f), textPad.columnWeights)
        assertEquals(listOf("+", "-", "*"), listOf(0, 1, 2).map { textPad.rows[it].keys[0].label })
        assertEquals("/", textPad.rows[2].keys[4].label)
        assertTrue(textPad.rows[3].keys[1].enabled)

        val phonePad = BuiltInLayouts.numberPad(FieldKind.PHONE)
        assertEquals(listOf("+", "*", "#"), listOf(0, 1, 2).map { phonePad.rows[it].keys[0].label })
        assertEquals("(", phonePad.rows[2].keys[4].label)
        assertFalse(phonePad.rows[3].keys[1].enabled)

        val datePad = BuiltInLayouts.numberPad(FieldKind.DATETIME)
        assertEquals(listOf(":", "-", "/"), listOf(0, 1, 2).map { datePad.rows[it].keys[0].label })
        assertEquals(".", datePad.rows[2].keys[4].label)
        assertFalse(datePad.rows[3].keys[1].enabled)

        val ids = textPad.rows.flatMap { it.keys }.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun symbolCatalogOrdersContextAwareTabsAndSymbolsCleanly() {
        val termTabs = SymbolCatalog.orderedTabsFor(FieldKind.TERMINAL, Language.ENGLISH)
        assertEquals(SymbolTabId.CLI, termTabs.first().id)
        assertEquals("|", SymbolCatalog.symbolsFor(SymbolTabId.COMMON, FieldKind.TERMINAL, Language.ENGLISH).first())

        val cnTabs = SymbolCatalog.orderedTabsFor(FieldKind.TEXT, Language.CHINESE)
        assertEquals(SymbolTabId.COMMON, cnTabs[0].id)
        assertEquals(SymbolTabId.CHINESE, cnTabs[1].id)
        assertEquals("，", SymbolCatalog.symbolsFor(SymbolTabId.COMMON, FieldKind.TEXT, Language.CHINESE).first())

        val enTabs = SymbolCatalog.orderedTabsFor(FieldKind.TEXT, Language.ENGLISH)
        assertEquals(SymbolTabId.COMMON, enTabs[0].id)
        assertEquals(SymbolTabId.ENGLISH, enTabs[1].id)
        assertEquals(",", SymbolCatalog.symbolsFor(SymbolTabId.COMMON, FieldKind.TEXT, Language.ENGLISH).first())
    }

    @Test
    fun keysymConversionsRoundTripAsciiAndUnicode() {
        assertEquals(0x61, KeyCodes.keysymForChar('a'))
        assertEquals('a', KeyCodes.charForKeysym(0x61))
        assertEquals(KeyCodes.KEYSYM_APOSTROPHE, KeyCodes.keysymForChar('\''))
        assertEquals(KeyCodes.KEYSYM_RETURN, KeyCodes.keysymForChar('\n'))
        assertEquals(KeyCodes.KEYSYM_BACKSPACE, KeyCodes.keysymForChar('\b'))
        assertNull(KeyCodes.charForKeysym(KeyCodes.KEYSYM_RETURN))

        val cjkKeysym = KeyCodes.keysymForChar('中')
        assertEquals('中', KeyCodes.charForKeysym(cjkKeysym))
        assertEquals(0x00e9, KeyCodes.keysymForChar('é'))
        assertEquals('é', KeyCodes.charForKeysym(0x00e9))
        assertEquals(KeyCodes.KEYSYM_NO_SYMBOL, KeyCodes.keysymForChar('\u0000'))
        assertNull(KeyCodes.charForKeysym(KeyCodes.KEYSYM_NO_SYMBOL))
        assertNull(KeyCodes.charForKeysym(0x0100_0000))
    }
}

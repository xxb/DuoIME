// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.layout

import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.KeyboardPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutSpecTest {
    @Test
    fun qwertySupportsOptionalNumberRowShiftAndEnglishMode() {
        val base = BuiltInLayouts.qwerty()
        assertEquals(InputMode.PINYIN_26, base.mode)
        assertEquals(4, base.rows.size)
        assertEquals(10, base.rows[0].keys.size)
        assertEquals("q", base.rows[0].keys[0].label)
        assertEquals(KeyAction.Engine(KeyCodes.keysymForChar('q')), base.rows[0].keys[0].action)

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
    fun t9LayoutExposesNinePinyinDigitKeysAndCoreControls() {
        val t9 = BuiltInLayouts.t9()
        assertEquals(InputMode.PINYIN_T9, t9.mode)
        assertEquals(4, t9.rows.size)
        val engineKeysyms = t9.rows.flatMap { it.keys }
            .mapNotNull { (it.action as? KeyAction.Engine)?.keysym }
        assertEquals(('1'..'9').map { KeyCodes.keysymForChar(it) }, engineKeysyms)
        assertTrue(t9.rows.all { it.totalWeight > 0f })
    }

    @Test
    fun symbolsV0NumberPadV0AndToolbarAreWellFormed() {
        val symbols = BuiltInLayouts.symbolsV0()
        assertEquals(KeyboardPanel.SYMBOL, symbols.panel)
        assertEquals(InputMode.PINYIN_26, symbols.mode)
        assertEquals(4, symbols.rows.size)
        val ids = symbols.rows.flatMap { it.keys }.map { it.id }
        assertEquals(ids.size, ids.toSet().size)

        val numPad = BuiltInLayouts.numberPadV0()
        assertEquals(KeyboardPanel.NUMBER, numPad.panel)
        assertEquals(4, numPad.rows.size)
        val numIds = numPad.rows.flatMap { it.keys }.map { it.id }
        assertEquals(numIds.size, numIds.toSet().size)
        assertTrue(numPad.rows.all { it.totalWeight > 0f })

        val toolbar = BuiltInLayouts.defaultToolbarKeys()
        assertTrue(toolbar.any { it.action == KeyAction.SwapScreen })
        assertTrue(toolbar.any { it.action == KeyAction.CycleHeight })
    }

    @Test
    fun keysymConversionsRoundTripAsciiAndUnicode() {
        assertEquals(0x61, KeyCodes.keysymForChar('a'))
        assertEquals('a', KeyCodes.charForKeysym(0x61))
        assertEquals(KeyCodes.KEYSYM_RETURN, KeyCodes.keysymForChar('\n'))
        assertEquals(KeyCodes.KEYSYM_BACKSPACE, KeyCodes.keysymForChar('\b'))
        assertNull(KeyCodes.charForKeysym(KeyCodes.KEYSYM_RETURN))

        val cjkKeysym = KeyCodes.keysymForChar('中')
        assertEquals('中', KeyCodes.charForKeysym(cjkKeysym))

        // X11 Latin-1 (0xA0..0xFF) passes directly in both directions
        assertEquals(0x00e9, KeyCodes.keysymForChar('é'))
        assertEquals('é', KeyCodes.charForKeysym(0x00e9))
        assertEquals(0x00a9, KeyCodes.keysymForChar('©'))
        assertEquals('©', KeyCodes.charForKeysym(0x00a9))

        // NUL and bare UNICODE_KEYSYM_OFFSET are handled safely without producing '\u0000'
        assertEquals(KeyCodes.KEYSYM_NO_SYMBOL, KeyCodes.keysymForChar('\u0000'))
        assertNull(KeyCodes.charForKeysym(KeyCodes.KEYSYM_NO_SYMBOL))
        assertNull(KeyCodes.charForKeysym(0x0100_0000))
    }
}

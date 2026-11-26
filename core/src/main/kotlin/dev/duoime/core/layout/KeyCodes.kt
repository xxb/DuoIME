// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.layout

/**
 * Pure-JVM keycode constants (matching Android `KeyEvent.KEYCODE_*`) and X11/Rime keysym helpers.
 */
object KeyCodes {
    const val KEYCODE_BACK: Int = 4
    const val KEYCODE_DPAD_UP: Int = 19
    const val KEYCODE_DPAD_DOWN: Int = 20
    const val KEYCODE_DPAD_LEFT: Int = 21
    const val KEYCODE_DPAD_RIGHT: Int = 22
    const val KEYCODE_TAB: Int = 61
    const val KEYCODE_SPACE: Int = 62
    const val KEYCODE_ENTER: Int = 66
    const val KEYCODE_DEL: Int = 67
    const val KEYCODE_ESCAPE: Int = 111

    const val KEYSYM_SPACE: Int = 0x0020
    const val KEYSYM_BACKSPACE: Int = 0xff08
    const val KEYSYM_TAB: Int = 0xff09
    const val KEYSYM_RETURN: Int = 0xff0d
    const val KEYSYM_ESCAPE: Int = 0xff1b

    private const val ASCII_MIN: Int = 0x20
    private const val ASCII_MAX: Int = 0x7e
    private const val UNICODE_KEYSYM_OFFSET: Int = 0x0100_0000
    private const val MAX_BMP_CHAR: Int = 0xffff

    /**
     * Converts a character to its X11 / librime keysym value.
     */
    fun keysymForChar(ch: Char): Int {
        val code = ch.code
        return when {
            code in ASCII_MIN..ASCII_MAX -> code
            ch == '\n' || ch == '\r' -> KEYSYM_RETURN
            ch == '\t' -> KEYSYM_TAB
            ch == '\b' -> KEYSYM_BACKSPACE
            else -> UNICODE_KEYSYM_OFFSET or code
        }
    }

    /**
     * Converts a printable ASCII or Unicode BMP keysym back to a [Char], or `null` for non-character keysyms.
     */
    fun charForKeysym(keysym: Int): Char? = when {
        keysym in ASCII_MIN..ASCII_MAX -> keysym.toChar()
        keysym > UNICODE_KEYSYM_OFFSET && (keysym - UNICODE_KEYSYM_OFFSET) <= MAX_BMP_CHAR ->
            (keysym - UNICODE_KEYSYM_OFFSET).toChar()
        else -> null
    }
}

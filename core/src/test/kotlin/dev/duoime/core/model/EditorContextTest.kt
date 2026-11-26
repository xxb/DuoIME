// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorContextTest {

    @Test
    fun multilineAndUnspecifiedOptionsResolveToNewline() {
        val unspecified = EditorContext(
            inputType = EditorContext.TYPE_CLASS_TEXT or EditorContext.TYPE_TEXT_FLAG_MULTI_LINE,
            imeOptions = EditorContext.IME_ACTION_UNSPECIFIED
        )
        assertEquals(EnterBehavior.Newline, unspecified.resolveEnterBehavior())

        val none = EditorContext(
            inputType = EditorContext.TYPE_CLASS_TEXT or EditorContext.TYPE_TEXT_FLAG_MULTI_LINE,
            imeOptions = EditorContext.IME_ACTION_NONE
        )
        assertEquals(EnterBehavior.Newline, none.resolveEnterBehavior())
    }

    @Test
    fun noEnterActionFlagForcesNewlineEvenWhenActionBitsAreSet() {
        val multiLineChat = EditorContext(
            inputType = EditorContext.TYPE_CLASS_TEXT or EditorContext.TYPE_TEXT_FLAG_MULTI_LINE,
            imeOptions = EditorContext.IME_ACTION_SEND or EditorContext.IME_FLAG_NO_ENTER_ACTION
        )
        assertEquals(EnterBehavior.Newline, multiLineChat.resolveEnterBehavior())

        val notesDone = EditorContext(
            inputType = EditorContext.TYPE_CLASS_TEXT or EditorContext.TYPE_TEXT_FLAG_IME_MULTI_LINE,
            imeOptions = EditorContext.IME_ACTION_DONE or EditorContext.IME_FLAG_NO_ENTER_ACTION
        )
        assertEquals(EnterBehavior.Newline, notesDone.resolveEnterBehavior())
    }

    @Test
    fun explicitSearchAndSendActionsMaskOutHighFlags() {
        val search = EditorContext(
            inputType = EditorContext.TYPE_CLASS_TEXT,
            imeOptions = EditorContext.IME_ACTION_SEARCH or EditorContext.IME_FLAG_NO_EXTRACT_UI
        )
        assertEquals(EnterBehavior.EditorAction(EditorContext.IME_ACTION_SEARCH), search.resolveEnterBehavior())

        val send = EditorContext(
            inputType = EditorContext.TYPE_CLASS_TEXT,
            imeOptions = EditorContext.IME_ACTION_SEND or EditorContext.IME_FLAG_NO_EXTRACT_UI
        )
        assertEquals(EnterBehavior.EditorAction(EditorContext.IME_ACTION_SEND), send.resolveEnterBehavior())
    }
}

// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.ime

import dev.duoime.core.layout.KeyCodes
import dev.duoime.core.model.SinkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputConnectionSinkTest {

    private class FakeBridge : ImeConnectionBridge {
        val log = mutableListOf<String>()

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            log.add("commit:$text:$newCursorPosition")
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            log.add("compose:$text:$newCursorPosition")
            return true
        }

        override fun finishComposingText(): Boolean {
            log.add("finishComposing")
            return true
        }

        override fun sendDownUpKeyEvent(keyCode: Int, metaState: Int): Boolean {
            log.add("key:$keyCode:$metaState")
            return true
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            log.add("editorAction:$editorAction")
            return true
        }

        override fun performPrivateCommand(action: String, payload: String): Boolean {
            log.add("privateCmd:$action:$payload")
            return true
        }
    }

    @Test
    fun composingLifecycleClearsOnBackspaceAndFlushesOldConnectionOnEditorSwitch() {
        val firstBridge = FakeBridge()
        var current: ImeConnectionBridge? = firstBridge
        val sink = InputConnectionSink({ current })

        // Start composing then backspace to empty -> clears composing text cleanly without committing pinyin
        sink.setComposing("ni'hao")
        sink.setComposing(null)
        assertEquals(listOf("compose:ni'hao:1", "compose::1", "finishComposing"), firstBridge.log)

        // Commit replaces composing span so subsequent clear is a clean no-op
        firstBridge.log.clear()
        sink.setComposing("ni'hao")
        sink.commitText("你好")
        sink.setComposing(null)
        assertEquals(listOf("compose:ni'hao:1", "commit:你好:1"), firstBridge.log)

        // Editor switch mid-composition flushes finishComposingText on the OLD InputConnection
        firstBridge.log.clear()
        sink.setComposing("shi'jie")
        val secondBridge = FakeBridge()
        sink.finishComposingOnEditorSwitch(firstBridge)
        current = secondBridge
        assertEquals(listOf("compose:shi'jie:1", "finishComposing"), firstBridge.log)
        assertTrue(secondBridge.log.isEmpty())
    }

    @Test
    fun selectionEchoCounterPreventsStaleResetAllowsExternalTapAndDecaysAfterInactivity() {
        var nowMs = 1_000L
        val bridge = FakeBridge()
        val sink = InputConnectionSink(connectionProvider = { bridge }, clockMs = { nowMs })

        // (a) Rapid commit ("你") + immediate compose ("h") + stale (-1) echo from commit -> NO reset
        sink.commitText("你")
        nowMs += 20L
        sink.setComposing("h")
        nowMs += 15L
        assertTrue("Stale echo from commitText must be consumed without reset", sink.consumeSelectionEcho())
        assertTrue("Echo from setComposing must be consumed without reset", sink.consumeSelectionEcho())

        // (b) Idle composing + external cursor tap outside composing range -> counter is 0 -> RESET
        nowMs += 50L
        assertFalse("No pending echoes -> must treat as external selection move", sink.consumeSelectionEcho())
        assertTrue(
            EditorInfoMapper.shouldResetComposingOnSelectionUpdate(
                isComposing = true,
                newSelStart = 0,
                newSelEnd = 0,
                candidatesStart = 1,
                candidatesEnd = 2
            )
        )
        sink.markComposingExternallyCleared()
        bridge.log.clear()
        sink.setComposing(null)
        assertTrue("Clearing externally-dropped composing must be a no-op", bridge.log.isEmpty())

        // (c) Batched editor drops one echo -> counter decays after 300 ms so external tap still resets
        nowMs = 2_000L
        sink.commitText("好")
        sink.setComposing("a")
        nowMs += 10L
        assertTrue("First batched echo consumed", sink.consumeSelectionEcho())
        // Second echo never arrives; user taps elsewhere 310 ms after last write -> counter decays
        nowMs = 2_310L
        assertFalse("Pending echo counter must auto-decay after 300ms", sink.consumeSelectionEcho())
    }

    @Test
    fun deleteBackwardUsesKeycodeDelUniformlyAndRespectsCapabilitiesAndNullIc() {
        val bridge = FakeBridge()
        var current: ImeConnectionBridge? = bridge
        val sink = InputConnectionSink({ current })

        // Standard IME uses KEYCODE_DEL without blocking getSelectedText IPC
        sink.deleteBackward(2)
        sink.deleteBackward(0)
        sink.performEditorAction(3)
        assertEquals(
            listOf("key:${KeyCodes.KEYCODE_DEL}:0", "key:${KeyCodes.KEYCODE_DEL}:0", "editorAction:3"),
            bridge.log
        )

        // Terminal sink disables composing and still sends KEYCODE_DEL + raw keys + private commands
        bridge.log.clear()
        sink.updateCapabilities(SinkCapabilities.TERMINAL)
        sink.setComposing("ls")
        sink.commitText("ls")
        sink.deleteBackward(1)
        sink.sendKey(KeyCodes.KEYCODE_ENTER)
        sink.sendHostCommand("board", "open")
        assertEquals(
            listOf(
                "commit:ls:1",
                "key:${KeyCodes.KEYCODE_DEL}:0",
                "key:${KeyCodes.KEYCODE_ENTER}:0",
                "privateCmd:dev.duoime.cmd.board:open"
            ),
            bridge.log
        )

        // Null InputConnection never throws
        current = null
        sink.commitText("ignored")
        sink.setComposing("ignored")
        sink.deleteBackward(1)
        sink.sendKey(KeyCodes.KEYCODE_ENTER)
        sink.performEditorAction(6)
    }
}

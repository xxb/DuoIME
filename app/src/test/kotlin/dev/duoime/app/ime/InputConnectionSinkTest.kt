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
    fun dispatchDownUpKeyEventsAlwaysSendsActionUpWhenActionDownReturnsFalse() {
        val events = mutableListOf<String>()
        val resultWhenDownFalse = dispatchDownUpKeyEvents(
            sendDown = {
                events.add("DOWN")
                false
            },
            sendUp = {
                events.add("UP")
                true
            }
        )
        assertFalse("Combined result must reflect failed DOWN", resultWhenDownFalse)
        assertEquals("ACTION_UP must still be dispatched when ACTION_DOWN returns false", listOf("DOWN", "UP"), events)

        events.clear()
        val resultWhenBothTrue = dispatchDownUpKeyEvents(
            sendDown = {
                events.add("DOWN")
                true
            },
            sendUp = {
                events.add("UP")
                true
            }
        )
        assertTrue(resultWhenBothTrue)
        assertEquals(listOf("DOWN", "UP"), events)
    }

    @Test
    fun composingLifecycleClearsOnBackspaceAndFlushesOldConnectionExactlyOnceAcrossRapidSwitch() {
        val bridgeA = FakeBridge()
        var current: ImeConnectionBridge? = bridgeA
        val sink = InputConnectionSink({ current })

        // Start composing then backspace to empty -> clears composing text cleanly without committing pinyin
        sink.setComposing("ni'hao")
        sink.setComposing(null)
        assertEquals(listOf("compose:ni'hao:1", "compose::1", "finishComposing"), bridgeA.log)

        // Commit replaces composing span so subsequent clear is a clean no-op
        bridgeA.log.clear()
        sink.setComposing("ni'hao")
        sink.commitText("你好")
        sink.setComposing(null)
        assertEquals(listOf("compose:ni'hao:1", "commit:你好:1"), bridgeA.log)

        // Start A, compose, then rapid onFinishInputView + onFinishInput + onStartInput(B) -> A gets finish ONCE
        bridgeA.log.clear()
        sink.setComposing("shi'jie")
        val bridgeB = FakeBridge()
        sink.finishComposingOnEditorSwitch(bridgeA)
        sink.finishComposingOnEditorSwitch(bridgeA)
        current = bridgeB
        sink.onStartInput(0, 0)
        assertEquals(listOf("compose:shi'jie:1", "finishComposing"), bridgeA.log)
        assertTrue("New connection B must not receive A's finishComposingText", bridgeB.log.isEmpty())
    }

    @Test
    fun selectionTrackerThroughSinkHandlesRapidCommitComposeDelRepeatAndExternalTap() {
        var nowMs = 1_000L
        val bridge = FakeBridge()
        val sink = InputConnectionSink(connectionProvider = { bridge }, clockMs = { nowMs })
        sink.onStartInput(initialSelStart = 0, initialSelEnd = 0)

        // (a) Rapid commit ("你") + immediate compose ("h") + stale (-1) echo from commit -> NO reset
        sink.commitText("你")
        nowMs += 20L
        sink.setComposing("h")
        nowMs += 15L
        assertFalse(
            "Stale echo from commitText must match queued prediction without reset",
            sink.shouldResetOnSelectionUpdate(
                isComposing = true,
                newSelStart = 1,
                newSelEnd = 1,
                candidatesStart = -1,
                candidatesEnd = -1
            )
        )
        assertFalse(
            "Echo from setComposing must match queued prediction without reset",
            sink.shouldResetOnSelectionUpdate(
                isComposing = true,
                newSelStart = 2,
                newSelEnd = 2,
                candidatesStart = 1,
                candidatesEnd = 2
            )
        )

        // (b) External cursor tap outside composing range -> RESET
        nowMs += 50L
        assertTrue(
            "External tap outside composing range must reset",
            sink.shouldResetOnSelectionUpdate(
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

        // (c) 50x KEYCODE_DEL repeat followed by re-baseline + compose + tap -> resets without window extension
        repeat(50) {
            nowMs += 55L
            sink.deleteBackward(1)
        }
        assertFalse(sink.shouldResetOnSelectionUpdate(false, 0, 0, -1, -1))
        sink.setComposing("a")
        nowMs += 10L
        assertTrue(
            "Tap after 50x DEL + compose must reset immediately",
            sink.shouldResetOnSelectionUpdate(true, 0, 0, -1, -1)
        )
    }

    @Test
    fun deleteBackwardUsesKeycodeDelUniformlyAndRespectsCapabilitiesAndNullIc() {
        val bridge = FakeBridge()
        var current: ImeConnectionBridge? = bridge
        val sink = InputConnectionSink({ current })

        sink.deleteBackward(2)
        sink.deleteBackward(0)
        sink.performEditorAction(3)
        assertEquals(
            listOf("key:${KeyCodes.KEYCODE_DEL}:0", "key:${KeyCodes.KEYCODE_DEL}:0", "editorAction:3"),
            bridge.log
        )

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

        current = null
        sink.commitText("ignored")
        sink.setComposing("ignored")
        sink.deleteBackward(1)
        sink.sendKey(KeyCodes.KEYCODE_ENTER)
        sink.performEditorAction(6)
    }
}

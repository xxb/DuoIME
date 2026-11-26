// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.ime

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import dev.duoime.core.engine.InputSink
import dev.duoime.core.layout.KeyCodes
import dev.duoime.core.model.SinkCapabilities

/**
 * Thin, JVM-testable bridge over Android's [InputConnection].
 */
interface ImeConnectionBridge {
    fun commitText(text: CharSequence, newCursorPosition: Int): Boolean

    fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean

    fun finishComposingText(): Boolean

    fun sendDownUpKeyEvent(keyCode: Int, metaState: Int): Boolean

    fun performEditorAction(editorAction: Int): Boolean

    fun performPrivateCommand(action: String, payload: String): Boolean
}

class AndroidInputConnectionBridge(private val ic: InputConnection) : ImeConnectionBridge {
    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean =
        ic.commitText(text, newCursorPosition)

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean =
        ic.setComposingText(text, newCursorPosition)

    override fun finishComposingText(): Boolean = ic.finishComposingText()

    override fun sendDownUpKeyEvent(keyCode: Int, metaState: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState, -1, 0, flags)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState, -1, 0, flags)
        return ic.sendKeyEvent(down) && ic.sendKeyEvent(up)
    }

    override fun performEditorAction(editorAction: Int): Boolean = ic.performEditorAction(editorAction)

    override fun performPrivateCommand(action: String, payload: String): Boolean {
        val bundle = Bundle().apply { putString(PAYLOAD_KEY, payload) }
        return ic.performPrivateCommand(action, bundle)
    }

    companion object {
        const val PAYLOAD_KEY: String = "payload"
    }
}

/**
 * [InputSink] backed by the active Android [InputConnection] via [ImeConnectionBridge].
 *
 * - Tracks active composing spans accurately so `commitText` clears local composing state,
 *   backspace-to-empty removes the span cleanly without committing raw pinyin, and external
 *   cursor jumps call [markComposingExternallyCleared] without clobbering the new cursor position.
 * - Maintains a LatinIME-style pending selection-echo counter ([consumeSelectionEcho]) with a
 *   300 ms inactivity auto-decay so asynchronous `onUpdateSelection` echoes from rapid commit+type
 *   bursts never trigger spurious engine resets.
 * - Switching editors invokes [finishComposingOnEditorSwitch] on the previous [InputConnection]
 *   before binding to the next editor.
 * - Uses `KEYCODE_DEL` key events for [deleteBackward] (no blocking synchronous `getSelectedText`
 *   IPC; handles selections, grapheme/emoji clusters, WebViews, Flutter, and terminals uniformly).
 */
class InputConnectionSink(
    private val connectionProvider: () -> ImeConnectionBridge?,
    private val clockMs: () -> Long = { System.nanoTime() / NANOS_PER_MS }
) : InputSink {

    @Volatile
    override var capabilities: SinkCapabilities = SinkCapabilities.STANDARD_IME

    private var hasActiveComposing: Boolean = false
    private var pendingSelectionEchoes: Int = 0
    private var lastWriteTimeMs: Long = 0L

    fun updateCapabilities(newCapabilities: SinkCapabilities) {
        capabilities = newCapabilities
    }

    /**
     * Evaluates whether an incoming `InputMethodService.onUpdateSelection` callback is an expected
     * asynchronous echo of our own recent `InputConnection` writes.
     *
     * Returns `true` (and decrements the counter) when the echo should be ignored, or `false` when
     * no recent writes are pending (or [SELECTION_ECHO_DECAY_MS] has elapsed since the last write)
     * so the caller should treat the callback as an external cursor/selection change.
     */
    fun consumeSelectionEcho(): Boolean {
        if (pendingSelectionEchoes <= 0) return false
        val elapsed = clockMs() - lastWriteTimeMs
        if (elapsed >= SELECTION_ECHO_DECAY_MS) {
            pendingSelectionEchoes = 0
            return false
        }
        pendingSelectionEchoes--
        return true
    }

    /**
     * Marks the composing span as already dropped by the editor (e.g. cursor tap in
     * `onUpdateSelection`) so a subsequent `controller.reset()` does not clobber text at the new
     * cursor location.
     */
    fun markComposingExternallyCleared() {
        hasActiveComposing = false
        pendingSelectionEchoes = 0
    }

    /**
     * Flushes any active composing span on [previousConnection] when the focused editor changes or
     * the input view hides, and clears pending echo tracking for the previous editor.
     */
    fun finishComposingOnEditorSwitch(previousConnection: ImeConnectionBridge?) {
        if (hasActiveComposing) {
            previousConnection?.finishComposingText()
            hasActiveComposing = false
        }
        pendingSelectionEchoes = 0
    }

    override fun commitText(text: CharSequence) {
        val ic = connectionProvider() ?: return
        ic.commitText(text, 1)
        hasActiveComposing = false
        recordIcWrite()
    }

    override fun setComposing(text: CharSequence?) {
        if (!capabilities.composing) return
        val ic = connectionProvider() ?: return
        if (text.isNullOrEmpty()) {
            if (hasActiveComposing) {
                ic.setComposingText("", 1)
                ic.finishComposingText()
                hasActiveComposing = false
                recordIcWrite()
            }
        } else {
            ic.setComposingText(text, 1)
            hasActiveComposing = true
            recordIcWrite()
        }
    }

    override fun deleteBackward(count: Int) {
        if (count <= 0) return
        val ic = connectionProvider() ?: return
        repeat(count) {
            ic.sendDownUpKeyEvent(KeyCodes.KEYCODE_DEL, 0)
            recordIcWrite()
        }
    }

    override fun sendKey(keyCode: Int, metaState: Int) {
        val ic = connectionProvider() ?: return
        ic.sendDownUpKeyEvent(keyCode, metaState)
        if (keyCode == KeyCodes.KEYCODE_DEL || keyCode == KeyCodes.KEYCODE_ENTER) {
            recordIcWrite()
        }
    }

    override fun performEditorAction(action: Int) {
        val ic = connectionProvider() ?: return
        ic.performEditorAction(action)
    }

    override fun sendHostCommand(id: String, payload: String): Boolean {
        val ic = connectionProvider() ?: return false
        return ic.performPrivateCommand("$PRIVATE_CMD_PREFIX$id", payload)
    }

    private fun recordIcWrite() {
        val now = clockMs()
        if (pendingSelectionEchoes > 0 && now - lastWriteTimeMs >= SELECTION_ECHO_DECAY_MS) {
            pendingSelectionEchoes = 0
        }
        pendingSelectionEchoes++
        lastWriteTimeMs = now
    }

    companion object {
        const val PRIVATE_CMD_PREFIX: String = "dev.duoime.cmd."
        const val SELECTION_ECHO_DECAY_MS: Long = 300L
        private const val NANOS_PER_MS: Long = 1_000_000L
    }
}

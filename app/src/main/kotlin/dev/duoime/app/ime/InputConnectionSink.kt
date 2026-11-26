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

/**
 * Dispatches paired `ACTION_DOWN` and `ACTION_UP` key events without short-circuiting so
 * `ACTION_UP` is always sent even when the target reports `false` on `ACTION_DOWN`.
 */
internal fun dispatchDownUpKeyEvents(sendDown: () -> Boolean, sendUp: () -> Boolean): Boolean {
    val downSent = sendDown()
    val upSent = sendUp()
    return downSent && upSent
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
        val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState, -1, 0, flags)
        val upEvent = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState, -1, 0, flags)
        return dispatchDownUpKeyEvents(
            sendDown = { ic.sendKeyEvent(downEvent) },
            sendUp = { ic.sendKeyEvent(upEvent) }
        )
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
 * - Uses [SelectionTracker] (LatinIME-style expected-state queue) to distinguish our own
 *   asynchronous `onUpdateSelection` echoes from external cursor/text edits with zero counter drift.
 * - Switching editors invokes [finishComposingOnEditorSwitch] on the previous [InputConnection]
 *   before binding to the next editor.
 * - Uses `KEYCODE_DEL` key events for [deleteBackward] (no blocking synchronous `getSelectedText`
 *   IPC; handles selections, grapheme/emoji clusters, WebViews, Flutter, and terminals uniformly).
 */
class InputConnectionSink(
    private val connectionProvider: () -> ImeConnectionBridge?,
    clockMs: () -> Long = { System.nanoTime() / NANOS_PER_MS }
) : InputSink {

    @Volatile
    override var capabilities: SinkCapabilities = SinkCapabilities.STANDARD_IME

    private val selectionTracker = SelectionTracker(clockMs)
    private var hasActiveComposing: Boolean = false

    fun updateCapabilities(newCapabilities: SinkCapabilities) {
        capabilities = newCapabilities
    }

    fun onStartInput(initialSelStart: Int, initialSelEnd: Int) {
        selectionTracker.onStartInput(initialSelStart, initialSelEnd)
    }

    /**
     * Evaluates an incoming `InputMethodService.onUpdateSelection` callback against our expected
     * selection queue and returns `true` iff active composition should be reset.
     */
    fun shouldResetOnSelectionUpdate(
        isComposing: Boolean,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ): Boolean = selectionTracker.onUpdateSelection(
        isComposing = isComposing,
        newSelStart = newSelStart,
        newSelEnd = newSelEnd,
        candidatesStart = candidatesStart,
        candidatesEnd = candidatesEnd
    )

    /**
     * Marks the composing span as already dropped by the editor (e.g. cursor tap in
     * `onUpdateSelection`) so a subsequent `controller.reset()` does not clobber text at the new
     * cursor location.
     */
    fun markComposingExternallyCleared() {
        hasActiveComposing = false
        selectionTracker.clearPredictions()
    }

    /**
     * Flushes any active composing span on [previousConnection] when the focused editor changes or
     * the input view hides, and clears pending selection predictions for the previous editor.
     */
    fun finishComposingOnEditorSwitch(previousConnection: ImeConnectionBridge?) {
        if (hasActiveComposing) {
            previousConnection?.finishComposingText()
            hasActiveComposing = false
            selectionTracker.recordFinishComposing()
        }
        selectionTracker.clearPredictions()
    }

    override fun commitText(text: CharSequence) {
        val ic = connectionProvider() ?: return
        ic.commitText(text, 1)
        hasActiveComposing = false
        selectionTracker.recordCommitText(text)
    }

    override fun setComposing(text: CharSequence?) {
        if (!capabilities.composing) return
        val ic = connectionProvider() ?: return
        if (text.isNullOrEmpty()) {
            if (hasActiveComposing) {
                ic.setComposingText("", 1)
                ic.finishComposingText()
                hasActiveComposing = false
                selectionTracker.recordClearComposing()
            }
        } else {
            ic.setComposingText(text, 1)
            hasActiveComposing = true
            selectionTracker.recordSetComposing(text)
        }
    }

    override fun deleteBackward(count: Int) {
        if (count <= 0) return
        val ic = connectionProvider() ?: return
        repeat(count) {
            ic.sendDownUpKeyEvent(KeyCodes.KEYCODE_DEL, 0)
            selectionTracker.recordUnpredictableEdit()
        }
    }

    override fun sendKey(keyCode: Int, metaState: Int) {
        val ic = connectionProvider() ?: return
        ic.sendDownUpKeyEvent(keyCode, metaState)
        if (keyCode == KeyCodes.KEYCODE_DEL || keyCode == KeyCodes.KEYCODE_ENTER) {
            selectionTracker.recordUnpredictableEdit()
        }
    }

    override fun performEditorAction(action: Int) {
        val ic = connectionProvider() ?: return
        ic.performEditorAction(action)
        selectionTracker.recordUnpredictableEdit()
    }

    override fun sendHostCommand(id: String, payload: String): Boolean {
        val ic = connectionProvider() ?: return false
        return ic.performPrivateCommand("$PRIVATE_CMD_PREFIX$id", payload)
    }

    companion object {
        const val PRIVATE_CMD_PREFIX: String = "dev.duoime.cmd."
        private const val NANOS_PER_MS: Long = 1_000_000L
    }
}

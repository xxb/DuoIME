// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.embed

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Immutable
import dev.duoime.core.engine.InputSink
import dev.duoime.core.layout.KeyCodes
import dev.duoime.core.model.SinkCapabilities

/**
 * Snapshot of the current editor selection read from an embedded [InputConnection].
 */
@Immutable
data class EmbedSelectionSnapshot(
    val selStart: Int,
    val selEnd: Int
)

/**
 * Thin, pure-JVM-testable bridge over an Android [InputConnection] used by [EmbedInputConnectionSink].
 */
interface EmbedConnectionBridge {
    fun commitText(text: CharSequence, newCursorPosition: Int): Boolean

    fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean

    fun finishComposingText(): Boolean

    fun sendDownUpKeyEvent(keyCode: Int, metaState: Int): Boolean

    fun performEditorAction(editorAction: Int): Boolean

    fun performPrivateCommand(action: String, payload: String): Boolean

    fun readSelectionSnapshot(): EmbedSelectionSnapshot? = null
}

/**
 * Android [InputConnection] implementation of [EmbedConnectionBridge] dispatching paired
 * `ACTION_DOWN` and `ACTION_UP` key events without short-circuiting.
 */
class AndroidEmbedConnectionBridge(private val ic: InputConnection) : EmbedConnectionBridge {
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
        val downSent = ic.sendKeyEvent(downEvent)
        val upSent = ic.sendKeyEvent(upEvent)
        return downSent && upSent
    }

    override fun performEditorAction(editorAction: Int): Boolean = ic.performEditorAction(editorAction)

    override fun performPrivateCommand(action: String, payload: String): Boolean {
        val bundle = Bundle().apply { putString(PAYLOAD_KEY, payload) }
        return ic.performPrivateCommand(action, bundle)
    }

    override fun readSelectionSnapshot(): EmbedSelectionSnapshot? {
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        val selStart = extracted.selectionStart
        val selEnd = extracted.selectionEnd
        if (selStart < 0 || selEnd < 0) return null
        val offset = extracted.startOffset.coerceAtLeast(0)
        return EmbedSelectionSnapshot(
            selStart = offset + selStart,
            selEnd = offset + selEnd
        )
    }

    companion object {
        const val PAYLOAD_KEY: String = "payload"
    }
}

/**
 * Lightweight, JVM-testable [InputSink] for embedded hosts (`DuoImeTextInputHost` and third-party
 * Compose apps) wrapping an [EmbedConnectionBridge] with expected-state selection synchronization.
 */
class EmbedInputConnectionSink(
    private val connectionProvider: () -> EmbedConnectionBridge?,
    initialCapabilities: SinkCapabilities = SinkCapabilities.STANDARD_IME,
    clockMs: () -> Long = { System.nanoTime() / NANOS_PER_MS }
) : InputSink {

    private constructor(
        bridge: EmbedConnectionBridge,
        capabilities: SinkCapabilities,
        clockMs: () -> Long
    ) : this(
        connectionProvider = { bridge },
        initialCapabilities = capabilities,
        clockMs = clockMs
    )

    constructor(
        inputConnection: InputConnection,
        capabilities: SinkCapabilities = SinkCapabilities.STANDARD_IME
    ) : this(
        bridge = AndroidEmbedConnectionBridge(inputConnection),
        capabilities = capabilities,
        clockMs = { System.nanoTime() / NANOS_PER_MS }
    )

    private val stateLock = Any()
    private val selectionTracker = EmbedSelectionTracker(clockMs)

    @Volatile
    override var capabilities: SinkCapabilities = initialCapabilities

    @Volatile
    private var activeComposing: Boolean = false

    internal val hasActiveComposing: Boolean
        get() = activeComposing

    fun updateCapabilities(newCapabilities: SinkCapabilities) {
        capabilities = newCapabilities
    }

    fun onStartInput(initialSelStart: Int, initialSelEnd: Int) {
        synchronized(stateLock) {
            activeComposing = false
            selectionTracker.onStartInput(initialSelStart, initialSelEnd)
        }
    }

    /**
     * Seeds the selection baseline from non-negative [EditorInfo] indices, falling back to a live
     * [EmbedConnectionBridge.readSelectionSnapshot] query when the editor omitted initial indices.
     */
    fun seedInitialSelection(
        initialSelStart: Int = SelectionState.NO_COMPOSING,
        initialSelEnd: Int = SelectionState.NO_COMPOSING
    ) {
        if (initialSelStart >= 0 && initialSelEnd >= 0) {
            onStartInput(initialSelStart, initialSelEnd)
            return
        }
        val snapshot = connectionProvider()?.readSelectionSnapshot()
        if (snapshot != null) {
            onStartInput(snapshot.selStart, snapshot.selEnd)
        } else {
            onStartInput(SelectionState.NO_COMPOSING, SelectionState.NO_COMPOSING)
        }
    }

    /**
     * Marks any local composing span as already superseded by an external editor change so a
     * subsequent `controller.reset()` will not clobber text at the moved cursor.
     */
    fun markComposingExternallyCleared() {
        synchronized(stateLock) {
            activeComposing = false
            selectionTracker.clearPredictions()
        }
    }

    /**
     * Polls the editor's current selection and returns `true` iff an external selection/text
     * change invalidated active composition (clearing local composing state without emitting
     * stale `setComposingText("")` or `finishComposingText()` writes).
     */
    fun syncExternalSelection(isComposing: Boolean): Boolean {
        val snapshot = connectionProvider()?.readSelectionSnapshot() ?: return false
        return synchronized(stateLock) {
            val shouldReset = selectionTracker.onObservedSelection(
                isComposing = isComposing,
                selStart = snapshot.selStart,
                selEnd = snapshot.selEnd
            )
            if (shouldReset) {
                activeComposing = false
                selectionTracker.clearPredictions()
            }
            shouldReset
        }
    }

    /**
     * Flushes any active composing span when the intercepted text input session disconnects.
     */
    fun finishComposingOnDisconnect() {
        val shouldFinish = synchronized(stateLock) {
            if (!activeComposing) {
                selectionTracker.clearPredictions()
                return
            }
            activeComposing = false
            selectionTracker.recordFinishComposing()
            selectionTracker.clearPredictions()
            true
        }
        if (shouldFinish) {
            connectionProvider()?.finishComposingText()
        }
    }

    override fun commitText(text: CharSequence) {
        val ic = connectionProvider() ?: return
        synchronized(stateLock) {
            activeComposing = false
            selectionTracker.recordCommitText(text)
        }
        ic.commitText(text, 1)
    }

    override fun setComposing(text: CharSequence?) {
        if (!capabilities.composing) return
        val ic = connectionProvider() ?: return
        if (text.isNullOrEmpty()) {
            val shouldClear = synchronized(stateLock) {
                if (!activeComposing) return
                activeComposing = false
                selectionTracker.recordClearComposing()
                true
            }
            if (shouldClear) {
                ic.setComposingText("", 1)
                ic.finishComposingText()
            }
        } else {
            val preComposeSnapshot = if (!activeComposing) ic.readSelectionSnapshot() else null
            synchronized(stateLock) {
                if (!activeComposing && preComposeSnapshot != null) {
                    selectionTracker.onObservedSelection(
                        isComposing = false,
                        selStart = preComposeSnapshot.selStart,
                        selEnd = preComposeSnapshot.selEnd
                    )
                }
                activeComposing = true
                selectionTracker.recordSetComposing(text)
            }
            ic.setComposingText(text, 1)
        }
    }

    override fun deleteBackward(count: Int) {
        if (count <= 0) return
        val ic = connectionProvider() ?: return
        repeat(count) {
            synchronized(stateLock) {
                selectionTracker.recordUnpredictableEdit()
            }
            ic.sendDownUpKeyEvent(KeyCodes.KEYCODE_DEL, 0)
        }
    }

    override fun sendKey(keyCode: Int, metaState: Int) {
        val ic = connectionProvider() ?: return
        synchronized(stateLock) {
            selectionTracker.recordUnpredictableEdit()
        }
        ic.sendDownUpKeyEvent(keyCode, metaState)
    }

    override fun performEditorAction(action: Int) {
        val ic = connectionProvider() ?: return
        synchronized(stateLock) {
            selectionTracker.recordUnpredictableEdit()
        }
        ic.performEditorAction(action)
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

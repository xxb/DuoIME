// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.embed

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import dev.duoime.core.engine.InputSink
import dev.duoime.core.layout.KeyCodes
import dev.duoime.core.model.SinkCapabilities

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

    companion object {
        const val PAYLOAD_KEY: String = "payload"
    }
}

/**
 * Lightweight, JVM-testable [InputSink] for embedded hosts (`DuoImeTextInputHost` and third-party
 * Compose apps) wrapping an [EmbedConnectionBridge].
 */
class EmbedInputConnectionSink(
    private val connectionProvider: () -> EmbedConnectionBridge?,
    initialCapabilities: SinkCapabilities = SinkCapabilities.STANDARD_IME
) : InputSink {

    private constructor(
        bridge: EmbedConnectionBridge,
        capabilities: SinkCapabilities
    ) : this(
        connectionProvider = { bridge },
        initialCapabilities = capabilities
    )

    constructor(
        inputConnection: InputConnection,
        capabilities: SinkCapabilities = SinkCapabilities.STANDARD_IME
    ) : this(
        bridge = AndroidEmbedConnectionBridge(inputConnection),
        capabilities = capabilities
    )

    @Volatile
    override var capabilities: SinkCapabilities = initialCapabilities

    @Volatile
    private var hasActiveComposing: Boolean = false

    fun updateCapabilities(newCapabilities: SinkCapabilities) {
        capabilities = newCapabilities
    }

    /**
     * Flushes any active composing span when the intercepted text input session disconnects.
     */
    fun finishComposingOnDisconnect() {
        if (!hasActiveComposing) return
        connectionProvider()?.finishComposingText()
        hasActiveComposing = false
    }

    override fun commitText(text: CharSequence) {
        val ic = connectionProvider() ?: return
        ic.commitText(text, 1)
        hasActiveComposing = false
    }

    override fun setComposing(text: CharSequence?) {
        if (!capabilities.composing) return
        val ic = connectionProvider() ?: return
        if (text.isNullOrEmpty()) {
            if (hasActiveComposing) {
                ic.setComposingText("", 1)
                ic.finishComposingText()
                hasActiveComposing = false
            }
        } else {
            ic.setComposingText(text, 1)
            hasActiveComposing = true
        }
    }

    override fun deleteBackward(count: Int) {
        if (count <= 0) return
        val ic = connectionProvider() ?: return
        repeat(count) {
            ic.sendDownUpKeyEvent(KeyCodes.KEYCODE_DEL, 0)
        }
    }

    override fun sendKey(keyCode: Int, metaState: Int) {
        val ic = connectionProvider() ?: return
        ic.sendDownUpKeyEvent(keyCode, metaState)
    }

    override fun performEditorAction(action: Int) {
        val ic = connectionProvider() ?: return
        ic.performEditorAction(action)
    }

    override fun sendHostCommand(id: String, payload: String): Boolean {
        val ic = connectionProvider() ?: return false
        return ic.performPrivateCommand("$PRIVATE_CMD_PREFIX$id", payload)
    }

    companion object {
        const val PRIVATE_CMD_PREFIX: String = "dev.duoime.cmd."
    }
}

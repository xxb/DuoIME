// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.engine

import dev.duoime.core.model.SinkCapabilities

/**
 * Output boundary for committing text, key events, images, and host commands.
 * Backed by `InputConnection` in `:app` and by host-supplied callbacks in `:duoime-embed`.
 */
interface InputSink {
    val capabilities: SinkCapabilities

    fun commitText(text: CharSequence)

    fun setComposing(text: CharSequence?)

    fun deleteBackward(count: Int = 1)

    fun sendKey(keyCode: Int, metaState: Int = 0)

    fun performEditorAction(action: Int)

    /**
     * Commits an exported image (e.g., whiteboard sketch) identified by a URI string.
     * Kept as [String] in pure-JVM `:core` and converted to `android.net.Uri` in Android modules.
     */
    fun commitImage(uri: String, mime: String, label: String? = null): Boolean = false

    /**
     * Sends an opaque extension command to the host app (`performPrivateCommand` or embed callback).
     */
    fun sendHostCommand(id: String, payload: String): Boolean = false
}

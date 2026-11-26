// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.model

import dev.duoime.core.protocol.ExtensionConfig

/**
 * Platform-independent snapshot of the focused editor or embedded host context.
 */
data class EditorContext(
    val packageName: String? = null,
    val inputType: Int = 0,
    val imeOptions: Int = 0,
    val incognito: Boolean = false,
    val isPassword: Boolean = false,
    val extension: ExtensionConfig? = null
)

/**
 * Capabilities supported by an [dev.duoime.core.engine.InputSink] implementation.
 * For example, terminal hosts disable inline composing and accept raw navigation/control keys.
 */
data class SinkCapabilities(
    val composing: Boolean = true,
    val imageMimeTypes: List<String> = emptyList(),
    val rawKeys: Boolean = false
) {
    companion object {
        val STANDARD_IME: SinkCapabilities = SinkCapabilities()
        val TERMINAL: SinkCapabilities = SinkCapabilities(composing = false, rawKeys = true)
    }
}

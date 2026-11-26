/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
package dev.duoime.embed

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.duoime.engine.lite.LiteEngineMetadata
import dev.duoime.ui.DuoKeyboardPlaceholder

/**
 * Bootstrap embed entry point for `:duoime-embed`.
 * Both `:app` (system IME) and external Compose host apps render through this module.
 */
object DuoImeEmbed {
    val defaultEngineId: String = LiteEngineMetadata.ENGINE_ID
}

@Composable
fun DuoImeEmbedHost(modifier: Modifier = Modifier) {
    DuoKeyboardPlaceholder(
        statusLabel = "DuoIME Embed Ready (${DuoImeEmbed.defaultEngineId})",
        modifier = modifier
    )
}

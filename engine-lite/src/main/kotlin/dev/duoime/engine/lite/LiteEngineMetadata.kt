/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
package dev.duoime.engine.lite

import dev.duoime.core.DuoImeCoreInfo

/**
 * Bootstrap descriptor for `:engine-lite` (pure-JVM fallback and M1 input engine).
 */
object LiteEngineMetadata {
    const val ENGINE_ID: String = "lite"
    val protocolKey: String = DuoImeCoreInfo.EXTENSION_SCHEMA_KEY
}

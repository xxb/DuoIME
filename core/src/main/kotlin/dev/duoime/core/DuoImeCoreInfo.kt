/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
package dev.duoime.core

/**
 * Bootstrap metadata for the pure-JVM `:core` module.
 * Domain models, engine/sink contracts, layouts, and `DisplayPlacementPolicy` land in M1-2.
 */
object DuoImeCoreInfo {
    const val EXTENSION_SCHEMA_KEY: String = "dev.duoime.ext.v1"
    const val MIN_OVERLAY_HEIGHT_FRACTION: Float = 0.60f
    const val MAX_OVERLAY_HEIGHT_FRACTION: Float = 0.90f
}

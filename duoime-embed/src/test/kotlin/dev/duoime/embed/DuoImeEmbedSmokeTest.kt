/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
package dev.duoime.embed

import org.junit.Assert.assertEquals
import org.junit.Test

class DuoImeEmbedSmokeTest {
    @Test
    fun defaultEngineIsLite() {
        assertEquals("lite", DuoImeEmbed.defaultEngineId)
    }
}

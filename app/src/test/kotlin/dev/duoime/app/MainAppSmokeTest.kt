/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
package dev.duoime.app

import dev.duoime.embed.DuoImeEmbed
import org.junit.Assert.assertEquals
import org.junit.Test

class MainAppSmokeTest {
    @Test
    fun appUsesEmbedDefaultEngine() {
        assertEquals("DuoIME", MainAppInfo.APP_TITLE)
        assertEquals("lite", DuoImeEmbed.defaultEngineId)
    }
}

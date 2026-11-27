/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
package dev.duoime.app

import dev.duoime.app.ime.ActiveEngineRuntime
import dev.duoime.app.ime.ActiveEngineStatus
import dev.duoime.app.ime.TAG_FALLBACK_LITE
import dev.duoime.app.ime.TAG_FALLBACK_PASSTHROUGH_PREFIX
import dev.duoime.app.ime.resolveInitialSchemaId
import dev.duoime.app.ime.resolveStatusFromUiState
import dev.duoime.app.settings.DuoImeSettings
import dev.duoime.app.settings.EngineBackend
import dev.duoime.core.engine.EngineSchemas
import dev.duoime.core.model.ChineseScheme
import dev.duoime.core.model.Language
import dev.duoime.embed.DuoImeEmbed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainAppSmokeTest {
    @Test
    fun appUsesEmbedDefaultEngine() {
        assertEquals("DuoIME", MainAppInfo.APP_TITLE)
        assertEquals("lite", DuoImeEmbed.defaultEngineId)
    }

    @Test
    fun initialSchemaIdMapsLanguageAndChineseSchemeAccurately() {
        assertEquals(
            EngineSchemas.PINYIN_26,
            resolveInitialSchemaId(
                DuoImeSettings(preferredLanguage = Language.CHINESE, chineseScheme = ChineseScheme.PINYIN_26)
            )
        )
        assertEquals(
            EngineSchemas.T9_PINYIN,
            resolveInitialSchemaId(
                DuoImeSettings(preferredLanguage = Language.CHINESE, chineseScheme = ChineseScheme.PINYIN_T9)
            )
        )
        assertEquals(
            EngineSchemas.DOUBLE_PINYIN_FLYPY,
            resolveInitialSchemaId(
                DuoImeSettings(preferredLanguage = Language.CHINESE, chineseScheme = ChineseScheme.XIAOHE_SHUANGPIN)
            )
        )
        assertEquals(
            EngineSchemas.ENGLISH,
            resolveInitialSchemaId(
                DuoImeSettings(preferredLanguage = Language.ENGLISH, chineseScheme = ChineseScheme.PINYIN_T9)
            )
        )
    }

    @Test
    fun activeEngineStatusResolvesFallbackAndPassthroughCleanly() {
        val rimeRunning = ActiveEngineStatus(
            requestedBackend = EngineBackend.RIME,
            activeRuntime = ActiveEngineRuntime.RIME,
            rimeVersion = "1.18.0"
        )
        assertFalse(rimeRunning.isFallback)

        val liteFallback = resolveStatusFromUiState(
            current = ActiveEngineStatus(EngineBackend.RIME, ActiveEngineRuntime.STARTING),
            requestedBackend = EngineBackend.RIME,
            isEngineReady = true,
            isUsingFallback = true,
            issueTag = TAG_FALLBACK_LITE
        )
        assertEquals(ActiveEngineRuntime.LITE, liteFallback.activeRuntime)
        assertTrue(liteFallback.isFallback)

        val passThrough = resolveStatusFromUiState(
            current = ActiveEngineStatus(EngineBackend.LITE, ActiveEngineRuntime.STARTING),
            requestedBackend = EngineBackend.LITE,
            isEngineReady = true,
            isUsingFallback = true,
            issueTag = "$TAG_FALLBACK_PASSTHROUGH_PREFIX IllegalStateException"
        )
        assertEquals(ActiveEngineRuntime.PASS_THROUGH, passThrough.activeRuntime)
        assertTrue(passThrough.isFallback)
    }
}

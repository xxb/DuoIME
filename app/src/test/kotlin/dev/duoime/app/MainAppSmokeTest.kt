/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
package dev.duoime.app

import dev.duoime.app.ime.ActiveEngineRuntime
import dev.duoime.app.ime.ActiveEngineStatus
import dev.duoime.app.ime.DuoImeEngineStatusRegistry
import dev.duoime.app.ime.TAG_FALLBACK_LITE
import dev.duoime.app.ime.TAG_FALLBACK_PASSTHROUGH_PREFIX
import dev.duoime.app.ime.resolveInitialSchemaId
import dev.duoime.app.ime.resolveModeSchemaIds
import dev.duoime.app.ime.resolveSelectedRimeSchemaId
import dev.duoime.app.ime.resolveStatusFromUiState
import dev.duoime.app.settings.DuoImeSettings
import dev.duoime.app.settings.EngineBackend
import dev.duoime.core.engine.EngineSchemas
import dev.duoime.core.model.ChineseScheme
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.Language
import dev.duoime.embed.DuoImeEmbed
import dev.duoime.embed.EngineIssue
import dev.duoime.embed.EngineIssueKind
import dev.duoime.embed.KeyboardUiState
import dev.duoime.engine.rime.RimeEngineMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
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

        // Custom Rime schema maps PINYIN_26 on RIME backend and falls back cleanly on LITE
        val customRime = DuoImeSettings(
            engineBackend = EngineBackend.RIME,
            preferredLanguage = Language.CHINESE,
            chineseScheme = ChineseScheme.PINYIN_26,
            customRimeSchemaId = "rime_ice"
        )
        assertEquals("rime_ice", resolveInitialSchemaId(customRime))
        assertEquals("rime_ice", resolveModeSchemaIds(customRime)[InputMode.PINYIN_26])
        assertEquals("rime_ice", resolveSelectedRimeSchemaId(customRime))

        val customLite = customRime.copy(engineBackend = EngineBackend.LITE)
        assertEquals(EngineSchemas.PINYIN_26, resolveModeSchemaIds(customLite)[InputMode.PINYIN_26])

        val t9WithCustomSaved = customRime.copy(chineseScheme = ChineseScheme.PINYIN_T9)
        assertEquals(EngineSchemas.T9_PINYIN, resolveInitialSchemaId(t9WithCustomSaved))
        assertEquals(RimeEngineMetadata.SCHEMA_T9_PINYIN, resolveSelectedRimeSchemaId(t9WithCustomSaved))
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

    @Test
    fun statusSyncUsesInjectedSinkWithoutReadingGlobalRegistry() {
        val prevGlobal = DuoImeEngineStatusRegistry.status.value
        try {
            val expectedFallback = ActiveEngineStatus(
                requestedBackend = EngineBackend.RIME,
                activeRuntime = ActiveEngineRuntime.LITE,
                fallbackTag = TAG_FALLBACK_LITE,
                rimeVersion = null
            )
            // Seed global registry with the target fallback state; an isolated factory must still emit!
            DuoImeEngineStatusRegistry.update(expectedFallback)
            val emitted = mutableListOf<ActiveEngineStatus>()
            val factory = dev.duoime.app.ime.DuoImeEngineSessionFactory(
                rimeEngineCreator = { error("not invoked in sync test") },
                statusSink = { emitted.add(it) }
            )
            factory.prepare(DuoImeSettings(engineBackend = EngineBackend.RIME))
            factory.syncUiState(
                KeyboardUiState(
                    isEngineReady = true,
                    isUsingFallbackEngine = true,
                    engineIssue = EngineIssue(EngineIssueKind.PERSISTENT, TAG_FALLBACK_LITE)
                )
            )
            assertEquals(2, emitted.size)
            assertEquals(ActiveEngineRuntime.STARTING, emitted[0].activeRuntime)
            assertEquals(expectedFallback, emitted[1])
            // Duplicate sync with identical state must not emit a third time
            factory.syncUiState(
                KeyboardUiState(
                    isEngineReady = true,
                    isUsingFallbackEngine = true,
                    engineIssue = EngineIssue(EngineIssueKind.PERSISTENT, TAG_FALLBACK_LITE)
                )
            )
            assertEquals(2, emitted.size)
        } finally {
            DuoImeEngineStatusRegistry.update(prevGlobal)
        }
    }

    @Test
    fun rimeBootstrapFailureInvokesFailureCallbackAndRethrows() = runTest {
        val logged = mutableListOf<Throwable>()
        val boom = IllegalStateException("librime createSession returned 0")
        val factory = dev.duoime.app.ime.DuoImeEngineSessionFactory(
            rimeEngineCreator = { throw boom },
            statusSink = {},
            onRimeFailure = { logged.add(it) }
        )
        val plan = factory.prepare(DuoImeSettings(engineBackend = EngineBackend.RIME))
        val caught = runCatching { plan.primaryFactory() }.exceptionOrNull()
        assertEquals(boom, caught)
        assertEquals(listOf<Throwable>(boom), logged)

        // CancellationException propagates without invoking failure logging callback
        val cancelLogged = mutableListOf<Throwable>()
        val cancelFactory = dev.duoime.app.ime.DuoImeEngineSessionFactory(
            rimeEngineCreator = { throw CancellationException("cancelled") },
            statusSink = {},
            onRimeFailure = { cancelLogged.add(it) }
        )
        val cancelPlan = cancelFactory.prepare(DuoImeSettings(engineBackend = EngineBackend.RIME))
        val cancelCaught = runCatching { cancelPlan.primaryFactory() }.exceptionOrNull()
        assertTrue(cancelCaught is CancellationException)
        assertTrue(cancelLogged.isEmpty())
    }
}

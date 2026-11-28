// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.ime

import android.content.Context
import android.util.Log
import dev.duoime.app.settings.DuoImeSettings
import dev.duoime.app.settings.EngineBackend
import dev.duoime.core.engine.EngineSchemas
import dev.duoime.core.engine.InputEngine
import dev.duoime.core.model.ChineseScheme
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.Language
import dev.duoime.embed.KeyboardController
import dev.duoime.embed.KeyboardUiState
import dev.duoime.engine.rime.RimeEngineMetadata
import dev.duoime.engine.rime.RimeInputEngine
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live runtime status of the IME engine active inside [DuoImeService].
 */
internal enum class ActiveEngineRuntime {
    NOT_RUNNING,
    STARTING,
    RIME,
    LITE,
    PASS_THROUGH
}

/**
 * Process-local snapshot of the requested engine backend vs. the actual running engine so
 * `MainActivity` can display live status and fallback state without IPC or permissions.
 */
internal data class ActiveEngineStatus(
    val requestedBackend: EngineBackend = EngineBackend.RIME,
    val activeRuntime: ActiveEngineRuntime = ActiveEngineRuntime.NOT_RUNNING,
    val fallbackTag: String? = null,
    val rimeVersion: String? = null
) {
    val isFallback: Boolean
        get() = fallbackTag != null ||
            (
                requestedBackend == EngineBackend.RIME &&
                    activeRuntime != ActiveEngineRuntime.RIME &&
                    activeRuntime != ActiveEngineRuntime.STARTING &&
                    activeRuntime != ActiveEngineRuntime.NOT_RUNNING
                )
}

internal object DuoImeEngineStatusRegistry {
    private val _status = MutableStateFlow(ActiveEngineStatus())
    val status: StateFlow<ActiveEngineStatus> = _status.asStateFlow()

    fun update(status: ActiveEngineStatus) {
        _status.value = status
    }

    fun reset() {
        _status.value = ActiveEngineStatus()
    }
}

internal fun resolveModeSchemaIds(settings: DuoImeSettings): Map<InputMode, String> {
    val customId = settings.customRimeSchemaId?.trim()?.takeIf { it.isNotEmpty() }
    return if (settings.engineBackend == EngineBackend.RIME && customId != null) {
        EngineSchemas.DEFAULT_MODE_MAP + (InputMode.PINYIN_26 to customId)
    } else {
        EngineSchemas.DEFAULT_MODE_MAP
    }
}

internal fun resolveSelectedRimeSchemaId(settings: DuoImeSettings): String {
    val customId = settings.customRimeSchemaId?.trim()?.takeIf { it.isNotEmpty() }
    if (customId != null && settings.chineseScheme == ChineseScheme.PINYIN_26) {
        return customId
    }
    return when (settings.chineseScheme) {
        ChineseScheme.PINYIN_26 -> RimeEngineMetadata.SCHEMA_PINYIN_26
        ChineseScheme.PINYIN_T9 -> RimeEngineMetadata.SCHEMA_T9_PINYIN
        ChineseScheme.XIAOHE_SHUANGPIN -> RimeEngineMetadata.SCHEMA_FLYPY
    }
}

internal fun resolveInitialSchemaId(settings: DuoImeSettings): String {
    if (settings.preferredLanguage == Language.ENGLISH) {
        return EngineSchemas.ENGLISH
    }
    return when (settings.chineseScheme) {
        ChineseScheme.PINYIN_26 -> settings.customRimeSchemaId?.trim()?.takeIf { it.isNotEmpty() }
            ?: EngineSchemas.PINYIN_26
        ChineseScheme.PINYIN_T9 -> EngineSchemas.T9_PINYIN
        ChineseScheme.XIAOHE_SHUANGPIN -> EngineSchemas.DOUBLE_PINYIN_FLYPY
    }
}

internal fun resolveStatusFromUiState(
    current: ActiveEngineStatus,
    requestedBackend: EngineBackend,
    isEngineReady: Boolean,
    isUsingFallback: Boolean,
    issueTag: String?
): ActiveEngineStatus {
    if (!isEngineReady) return current
    if (issueTag != null && issueTag.startsWith(TAG_FALLBACK_PASSTHROUGH_PREFIX)) {
        return ActiveEngineStatus(
            requestedBackend = requestedBackend,
            activeRuntime = ActiveEngineRuntime.PASS_THROUGH,
            fallbackTag = issueTag,
            rimeVersion = null
        )
    }
    if (isUsingFallback && requestedBackend == EngineBackend.RIME) {
        return ActiveEngineStatus(
            requestedBackend = requestedBackend,
            activeRuntime = ActiveEngineRuntime.LITE,
            fallbackTag = issueTag ?: TAG_FALLBACK_LITE,
            rimeVersion = null
        )
    }
    return current
}

internal data class EngineBootstrapPlan(
    val primaryFactory: suspend () -> InputEngine,
    val fallbackFactory: (suspend () -> InputEngine)?
)

/**
 * Builds primary & fallback engine factories for [DuoImeService] and updates
 * [DuoImeEngineStatusRegistry] strictly in generation order.
 */
internal class DuoImeEngineSessionFactory internal constructor(
    private val rimeEngineCreator: (DuoImeSettings) -> RimeInputEngine,
    private val statusSink: (ActiveEngineStatus) -> Unit = DuoImeEngineStatusRegistry::update,
    private val onRimeFailure: (Throwable) -> Unit = {}
) {
    constructor(
        appContext: Context,
        distributionVersion: String,
        statusSink: (ActiveEngineStatus) -> Unit = DuoImeEngineStatusRegistry::update
    ) : this(
        rimeEngineCreator = { settings ->
            RimeInputEngine.create(
                context = appContext,
                distributionVersion = distributionVersion,
                initialSchemaId = resolveInitialSchemaId(settings),
                initialVariant = settings.chineseVariant,
                initialIncognito = settings.alwaysIncognito
            )
        },
        statusSink = statusSink,
        onRimeFailure = { err ->
            Log.w(LOG_TAG, "Rime bootstrap failed (${err::class.simpleName}): ${err.message.orEmpty()}")
        }
    )

    private val generation = AtomicLong(0L)

    @Volatile
    private var requestedBackend: EngineBackend = EngineBackend.RIME

    @Volatile
    private var currentStatus: ActiveEngineStatus = ActiveEngineStatus()

    fun prepare(settings: DuoImeSettings): EngineBootstrapPlan {
        val gen = generation.incrementAndGet()
        val backend = settings.engineBackend
        requestedBackend = backend
        publishIfLatest(
            gen = gen,
            status = ActiveEngineStatus(
                requestedBackend = backend,
                activeRuntime = ActiveEngineRuntime.STARTING
            )
        )
        return when (backend) {
            EngineBackend.RIME -> EngineBootstrapPlan(
                primaryFactory = {
                    val engine = createRimeEngineLoggingFailures(settings)
                    publishIfLatest(
                        gen = gen,
                        status = ActiveEngineStatus(
                            requestedBackend = EngineBackend.RIME,
                            activeRuntime = ActiveEngineRuntime.RIME,
                            fallbackTag = null,
                            rimeVersion = engine.rimeVersion
                        )
                    )
                    engine
                },
                fallbackFactory = {
                    val lite = KeyboardController.DEFAULT_ENGINE_FACTORY()
                    publishIfLatest(
                        gen = gen,
                        status = ActiveEngineStatus(
                            requestedBackend = EngineBackend.RIME,
                            activeRuntime = ActiveEngineRuntime.LITE,
                            fallbackTag = TAG_FALLBACK_LITE,
                            rimeVersion = null
                        )
                    )
                    lite
                }
            )
            EngineBackend.LITE -> EngineBootstrapPlan(
                primaryFactory = {
                    val lite = KeyboardController.DEFAULT_ENGINE_FACTORY()
                    publishIfLatest(
                        gen = gen,
                        status = ActiveEngineStatus(
                            requestedBackend = EngineBackend.LITE,
                            activeRuntime = ActiveEngineRuntime.LITE,
                            fallbackTag = null,
                            rimeVersion = null
                        )
                    )
                    lite
                },
                fallbackFactory = null
            )
        }
    }

    fun syncUiState(uiState: KeyboardUiState) {
        val current = currentStatus
        val resolved = resolveStatusFromUiState(
            current = current,
            requestedBackend = requestedBackend,
            isEngineReady = uiState.isEngineReady,
            isUsingFallback = uiState.isUsingFallbackEngine,
            issueTag = uiState.engineIssue?.tag
        )
        if (resolved != current) {
            currentStatus = resolved
            statusSink(resolved)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun createRimeEngineLoggingFailures(settings: DuoImeSettings): RimeInputEngine = try {
        rimeEngineCreator(settings)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (err: Throwable) {
        onRimeFailure(err)
        throw err
    }

    private fun publishIfLatest(gen: Long, status: ActiveEngineStatus) {
        if (generation.get() == gen) {
            currentStatus = status
            statusSink(status)
        }
    }
}

private const val LOG_TAG: String = "DuoImeEngine"
internal const val TAG_FALLBACK_LITE: String = "Fallback: RimeUnavailable"
internal const val TAG_FALLBACK_PASSTHROUGH_PREFIX: String = "Plain:"

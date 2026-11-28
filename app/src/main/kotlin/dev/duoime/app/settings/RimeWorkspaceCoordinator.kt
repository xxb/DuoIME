// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.settings

import android.content.Context
import android.net.Uri
import dev.duoime.app.BuildConfig
import dev.duoime.embed.KeyboardController
import dev.duoime.engine.rime.RimeImportFailureReason
import dev.duoime.engine.rime.RimeImportResult
import dev.duoime.engine.rime.RimeInputEngine
import dev.duoime.engine.rime.RimeNativeExecutor
import dev.duoime.engine.rime.RimeSchemaInfo
import dev.duoime.engine.rime.RimeStagingResult
import dev.duoime.engine.rime.RimeWorkspaceDeployer
import dev.duoime.engine.rime.RimeWorkspaceInstaller
import dev.duoime.engine.rime.RimeWorkspaceManager
import dev.duoime.engine.rime.RimeWorkspacePaths
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class RimeWorkspaceBusyOp {
    IMPORTING,
    DEPLOYING,
    RESETTING
}

internal sealed interface RimeWorkspaceFeedback {
    data class Imported(val customSchemaCount: Int, val fileCount: Int) : RimeWorkspaceFeedback

    data object Deployed : RimeWorkspaceFeedback

    data object ResetComplete : RimeWorkspaceFeedback

    data class Failed(
        val reason: RimeImportFailureReason,
        val detail: String,
        val rolledBack: Boolean
    ) : RimeWorkspaceFeedback
}

internal data class RimeWorkspaceUiState(
    val schemas: List<RimeSchemaInfo> = emptyList(),
    val hasCustomFiles: Boolean = false,
    val busyOp: RimeWorkspaceBusyOp? = null,
    val feedback: RimeWorkspaceFeedback? = null
) {
    val isBusy: Boolean
        get() = busyOp != null
}

/**
 * Abstracted backend seam so [RimeWorkspaceCoordinatorEngine] can be thoroughly unit-tested on
 * pure JVM without Android `Context` or native `.so` loading.
 */
internal interface RimeWorkspaceHost {
    suspend fun ensureWorkspacePaths(): RimeWorkspacePaths

    suspend fun listLiveSchemas(): List<RimeSchemaInfo>?

    suspend fun <T> runTransaction(block: (RimeWorkspacePaths, RimeWorkspaceDeployer) -> T): T

    fun selectSchemaPreference(schemaId: String?)
}

internal class RimeWorkspaceCoordinatorEngine(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val operationMutex = Mutex()
    private val _uiState = MutableStateFlow(RimeWorkspaceUiState())
    val uiState: StateFlow<RimeWorkspaceUiState> = _uiState.asStateFlow()

    @Suppress("TooGenericExceptionCaught")
    suspend fun refreshSchemas(host: RimeWorkspaceHost) = operationMutex.withLock {
        try {
            val paths = withContext(ioDispatcher) { host.ensureWorkspacePaths() }
            val liveSchemas = runCatching { host.listLiveSchemas() }.getOrNull()
            val schemas = if (!liveSchemas.isNullOrEmpty()) {
                liveSchemas
            } else {
                withContext(ioDispatcher) { RimeWorkspaceManager.discoverInstalledSchemasFromDisk(paths) }
            }
            val hasCustom = withContext(ioDispatcher) { RimeWorkspaceManager.hasCustomUserFiles(paths) }
            _uiState.value = _uiState.value.copy(
                schemas = schemas,
                hasCustomFiles = hasCustom
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Keep prior schema state on transient refresh error.
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun importStaged(
        host: RimeWorkspaceHost,
        stageToDir: suspend (File) -> RimeStagingResult
    ): RimeImportResult = operationMutex.withLock {
        _uiState.value = _uiState.value.copy(busyOp = RimeWorkspaceBusyOp.IMPORTING, feedback = null)
        var stageDir: File? = null
        try {
            val paths = withContext(ioDispatcher) { host.ensureWorkspacePaths() }
            val workspaceRoot = paths.userDir.parentFile
                ?: return@withLock finishFailure(
                    RimeImportFailureReason.IO_ERROR,
                    "Invalid Rime workspace directory",
                    rolledBack = false
                )
            val createdStage = File(workspaceRoot, ".saf_stage_${System.nanoTime()}")
            stageDir = createdStage

            // Phase 1: stream SAF I/O strictly on Dispatchers.IO without touching live IME session
            val staged = withContext(ioDispatcher) { stageToDir(createdStage) }
            if (staged is RimeStagingResult.Failure) {
                return@withLock finishFailure(staged.reason, staged.detail, rolledBack = false)
            }

            // Phase 2: brief exclusive native swap + deploy transaction
            val result = host.runTransaction { txPaths, deployer ->
                RimeWorkspaceManager.commitStagedWorkspace(txPaths, staged as RimeStagingResult.Success, deployer)
            }
            val hasCustom = withContext(ioDispatcher) { RimeWorkspaceManager.hasCustomUserFiles(paths) }
            when (result) {
                is RimeImportResult.Success -> {
                    val autoSelect = result.importedCustomSchemaIds.firstOrNull()
                    if (autoSelect != null) {
                        host.selectSchemaPreference(autoSelect)
                    }
                    _uiState.value = RimeWorkspaceUiState(
                        schemas = result.schemas,
                        hasCustomFiles = hasCustom,
                        busyOp = null,
                        feedback = RimeWorkspaceFeedback.Imported(
                            customSchemaCount = result.importedCustomSchemaIds.size,
                            fileCount = result.fileCount
                        )
                    )
                }
                is RimeImportResult.Failure -> {
                    val refreshed = withContext(ioDispatcher) {
                        RimeWorkspaceManager.discoverInstalledSchemasFromDisk(paths)
                    }
                    _uiState.value = RimeWorkspaceUiState(
                        schemas = refreshed,
                        hasCustomFiles = hasCustom,
                        busyOp = null,
                        feedback = RimeWorkspaceFeedback.Failed(result.reason, result.detail, result.rolledBack)
                    )
                }
            }
            result
        } catch (cancelled: CancellationException) {
            _uiState.value = _uiState.value.copy(busyOp = null)
            throw cancelled
        } catch (err: Throwable) {
            finishFailure(
                reason = RimeImportFailureReason.DEPLOY_FAILED,
                detail = err.message ?: err::class.simpleName.orEmpty(),
                rolledBack = false
            )
        } finally {
            stageDir?.let { dir ->
                withContext(ioDispatcher) {
                    if (dir.exists()) dir.deleteRecursively()
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun redeployWorkspace(host: RimeWorkspaceHost): Boolean = operationMutex.withLock {
        _uiState.value = _uiState.value.copy(busyOp = RimeWorkspaceBusyOp.DEPLOYING, feedback = null)
        try {
            val paths = withContext(ioDispatcher) { host.ensureWorkspacePaths() }
            val deployed = host.runTransaction { txPaths, deployer ->
                RimeWorkspaceManager.redeployWorkspace(txPaths, deployer)
            }
            val hasCustom = withContext(ioDispatcher) { RimeWorkspaceManager.hasCustomUserFiles(paths) }
            if (!deployed.isNullOrEmpty()) {
                _uiState.value = RimeWorkspaceUiState(
                    schemas = deployed,
                    hasCustomFiles = hasCustom,
                    busyOp = null,
                    feedback = RimeWorkspaceFeedback.Deployed
                )
                true
            } else {
                _uiState.value = _uiState.value.copy(
                    hasCustomFiles = hasCustom,
                    busyOp = null,
                    feedback = RimeWorkspaceFeedback.Failed(
                        reason = RimeImportFailureReason.DEPLOY_FAILED,
                        detail = "Rime workspace deploy returned no schemas",
                        rolledBack = false
                    )
                )
                false
            }
        } catch (cancelled: CancellationException) {
            _uiState.value = _uiState.value.copy(busyOp = null)
            throw cancelled
        } catch (err: Throwable) {
            _uiState.value = _uiState.value.copy(
                busyOp = null,
                feedback = RimeWorkspaceFeedback.Failed(
                    reason = RimeImportFailureReason.DEPLOY_FAILED,
                    detail = err.message ?: err::class.simpleName.orEmpty(),
                    rolledBack = false
                )
            )
            false
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun resetWorkspace(host: RimeWorkspaceHost): RimeImportResult = operationMutex.withLock {
        _uiState.value = _uiState.value.copy(busyOp = RimeWorkspaceBusyOp.RESETTING, feedback = null)
        try {
            val paths = withContext(ioDispatcher) { host.ensureWorkspacePaths() }
            val result = host.runTransaction { txPaths, deployer ->
                RimeWorkspaceManager.resetToStarterWorkspace(txPaths, deployer)
            }
            val hasCustom = withContext(ioDispatcher) { RimeWorkspaceManager.hasCustomUserFiles(paths) }
            when (result) {
                is RimeImportResult.Success -> {
                    host.selectSchemaPreference(null)
                    _uiState.value = RimeWorkspaceUiState(
                        schemas = result.schemas,
                        hasCustomFiles = hasCustom,
                        busyOp = null,
                        feedback = RimeWorkspaceFeedback.ResetComplete
                    )
                }
                is RimeImportResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        hasCustomFiles = hasCustom,
                        busyOp = null,
                        feedback = RimeWorkspaceFeedback.Failed(result.reason, result.detail, result.rolledBack)
                    )
                }
            }
            result
        } catch (cancelled: CancellationException) {
            _uiState.value = _uiState.value.copy(busyOp = null)
            throw cancelled
        } catch (err: Throwable) {
            finishFailure(
                reason = RimeImportFailureReason.DEPLOY_FAILED,
                detail = err.message ?: err::class.simpleName.orEmpty(),
                rolledBack = false
            )
        }
    }

    fun clearFeedback() {
        _uiState.value = _uiState.value.copy(feedback = null)
    }

    private fun finishFailure(
        reason: RimeImportFailureReason,
        detail: String,
        rolledBack: Boolean
    ): RimeImportResult.Failure {
        _uiState.value = _uiState.value.copy(
            busyOp = null,
            feedback = RimeWorkspaceFeedback.Failed(reason, detail, rolledBack)
        )
        return RimeImportResult.Failure(reason, detail, rolledBack)
    }
}

/**
 * Process-wide coordinator bridging `MainActivity` Rime workspace actions and the live
 * [dev.duoime.app.ime.DuoImeService] [KeyboardController] (falling back cleanly to
 * [RimeNativeExecutor.runOfflineTransaction] when the IME service is not running or on Lite).
 *
 * Lock safety: `operationMutex` is NEVER acquired during `DuoImeService` engine bootstrap or
 * `reloadEngine`, eliminating any AB-BA deadlock cycle with `KeyboardController`'s actor.
 */
internal object RimeWorkspaceCoordinator {
    private val activeControllerRef = AtomicReference<KeyboardController?>(null)
    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = RimeWorkspaceCoordinatorEngine()

    val uiState: StateFlow<RimeWorkspaceUiState> = engine.uiState

    fun attachController(controller: KeyboardController) {
        activeControllerRef.set(controller)
    }

    fun detachController(controller: KeyboardController) {
        activeControllerRef.compareAndSet(controller, null)
    }

    fun refreshSchemas(context: Context) {
        val appContext = context.applicationContext
        coordinatorScope.launch {
            engine.refreshSchemas(AndroidRimeWorkspaceHost(appContext))
        }
    }

    fun importZip(context: Context, uri: Uri) {
        val appContext = context.applicationContext
        coordinatorScope.launch {
            engine.importStaged(AndroidRimeWorkspaceHost(appContext)) { stageDir ->
                RimeSafImporter.stageZipUri(appContext, uri, stageDir)
            }
        }
    }

    fun importTree(context: Context, treeUri: Uri) {
        val appContext = context.applicationContext
        coordinatorScope.launch {
            engine.importStaged(AndroidRimeWorkspaceHost(appContext)) { stageDir ->
                RimeSafImporter.stageTreeUri(appContext, treeUri, stageDir)
            }
        }
    }

    fun redeployWorkspace(context: Context) {
        val appContext = context.applicationContext
        coordinatorScope.launch {
            engine.redeployWorkspace(AndroidRimeWorkspaceHost(appContext))
        }
    }

    fun resetWorkspace(context: Context) {
        val appContext = context.applicationContext
        coordinatorScope.launch {
            engine.resetWorkspace(AndroidRimeWorkspaceHost(appContext))
        }
    }

    fun selectSchema(context: Context, schemaId: String) {
        DuoImePreferences(context.applicationContext).updateRimeSchemaSelection(schemaId)
    }

    fun clearFeedback() = engine.clearFeedback()

    private class AndroidRimeWorkspaceHost(
        private val appContext: Context
    ) : RimeWorkspaceHost {
        override suspend fun ensureWorkspacePaths(): RimeWorkspacePaths =
            RimeWorkspaceInstaller.installStarterAssets(appContext)

        override suspend fun listLiveSchemas(): List<RimeSchemaInfo>? {
            val controller = activeControllerRef.get() ?: return null
            return try {
                controller.withActiveEngine { activeEngine ->
                    (activeEngine as? RimeInputEngine)?.listSchemas(includeIncognito = false)
                }
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                null
            }
        }

        override suspend fun <T> runTransaction(block: (RimeWorkspacePaths, RimeWorkspaceDeployer) -> T): T {
            val controller = activeControllerRef.get()
            if (controller != null) {
                val liveAttempt = try {
                    controller.withActiveEngine { activeEngine ->
                        if (activeEngine is RimeInputEngine) {
                            LiveAttempt.Ran(activeEngine.runWorkspaceTransaction(block))
                        } else {
                            LiveAttempt.NotRime
                        }
                    }
                } catch (cancelled: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw cancelled
                    LiveAttempt.NotRime
                }
                if (liveAttempt is LiveAttempt.Ran) {
                    @Suppress("UNCHECKED_CAST")
                    return liveAttempt.value as T
                }
            }
            return withContext(Dispatchers.IO) {
                RimeNativeExecutor.runOfflineTransaction(
                    context = appContext,
                    distributionVersion = BuildConfig.VERSION_NAME,
                    block = block
                )
            }
        }

        override fun selectSchemaPreference(schemaId: String?) {
            DuoImePreferences(appContext).updateRimeSchemaSelection(schemaId)
        }
    }

    private sealed interface LiveAttempt {
        data class Ran(val value: Any?) : LiveAttempt

        data object NotRime : LiveAttempt
    }
}

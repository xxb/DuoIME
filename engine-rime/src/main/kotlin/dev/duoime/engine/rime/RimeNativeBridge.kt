// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.engine.rime

import android.content.Context
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Structured snapshot parsed from `JniRimeNativeBridge.nativeGetSnapshot`'s 14-slot `Object[]`.
 */
internal data class RimeNativeSnapshot(
    val schemaId: String = "",
    val schemaName: String = "",
    val isComposing: Boolean = false,
    val isAsciiMode: Boolean = false,
    val isTraditional: Boolean = false,
    val preedit: String = "",
    val commitTextPreview: String = "",
    val pageIndex: Int = 0,
    val isLastPage: Boolean = true,
    val highlightedIndex: Int = 0,
    val candidateTexts: List<String> = emptyList(),
    val candidateComments: List<String?> = emptyList(),
    val rawInput: String = "",
    val caretPos: Int = 0
)

/**
 * Thrown by [RimeNativeExecutor.runOfflineTransaction] when a live `RimeInputEngine` already holds
 * the native runtime, preventing unsynchronized disk mutations while a session is active.
 */
public class RimeWorkspaceBusyException(message: String) : IllegalStateException(message)

/**
 * Pure-JVM seam over the native `librime` bridge so `:engine-rime` unit tests run cleanly on JVM
 * without Android NDK or `.so` loading.
 */
internal interface RimeNativeBridge {
    fun acquireRuntime(paths: RimeWorkspacePaths, distributionVersion: String): Boolean

    fun acquireExclusiveRuntime(paths: RimeWorkspacePaths, distributionVersion: String): Boolean =
        acquireRuntime(paths, distributionVersion)

    fun releaseRuntime()

    fun deployWorkspace(): Boolean

    fun createSession(): Long

    fun destroySession(sessionId: Long): Boolean

    fun processKey(sessionId: Long, keysym: Int, mask: Int): Boolean

    fun getCommitText(sessionId: Long): String?

    fun getSnapshot(sessionId: Long): RimeNativeSnapshot?

    fun selectCandidateOnCurrentPage(sessionId: Long, indexOnPage: Int): Boolean

    fun changePage(sessionId: Long, backward: Boolean): Boolean

    fun selectSchema(sessionId: Long, schemaId: String): Boolean

    fun getCurrentSchema(sessionId: Long): String?

    fun getOption(sessionId: Long, optionName: String): Boolean

    fun setOption(sessionId: Long, optionName: String, value: Boolean)

    fun clearComposition(sessionId: Long)

    fun commitComposition(sessionId: Long): Boolean

    fun getSchemaList(): List<Pair<String, String>>

    fun getVersion(): String
}

/**
 * Low-level 1:1 mirror of the 18 JNI methods exported by `libduoime_rime.so`, factored behind an
 * internal interface so [RefCountedRimeBridge] can be exhaustively unit-tested on pure JVM.
 */
internal interface RawRimeCalls {
    fun ensureLoaded()

    fun nativeInitialize(
        sharedDataDir: String,
        userDataDir: String,
        prebuiltDataDir: String?,
        stagingDir: String?,
        distributionVersion: String
    ): Boolean

    fun nativeFinalize()

    fun nativeDeployWorkspace(): Boolean

    fun nativeCreateSession(): Long

    fun nativeDestroySession(sessionId: Long): Boolean

    fun nativeProcessKey(sessionId: Long, keysym: Int, mask: Int): Boolean

    fun nativeGetCommitText(sessionId: Long): String?

    fun nativeGetSnapshot(sessionId: Long): Array<Any?>?

    fun nativeSelectCandidateOnCurrentPage(sessionId: Long, indexOnPage: Int): Boolean

    fun nativeChangePage(sessionId: Long, backward: Boolean): Boolean

    fun nativeSelectSchema(sessionId: Long, schemaId: String): Boolean

    fun nativeGetCurrentSchema(sessionId: Long): String?

    fun nativeGetOption(sessionId: Long, optionName: String): Boolean

    fun nativeSetOption(sessionId: Long, optionName: String, value: Boolean)

    fun nativeClearComposition(sessionId: Long)

    fun nativeCommitComposition(sessionId: Long): Boolean

    fun nativeGetSchemaList(): Array<String>?

    fun nativeGetVersion(): String
}

/**
 * Reference-counted native bridge wrapper guaranteeing `nativeFinalize()` is NEVER invoked while
 * another active `RimeInputEngine` session exists (for example, during `reloadEngine` overlap).
 */
internal class RefCountedRimeBridge(
    private val raw: RawRimeCalls
) : RimeNativeBridge {
    private val lock = Any()
    private var activeInstances: Int = 0
    private var activeWorkspaceKey: String? = null
    private var exclusiveLease: Boolean = false

    internal val activeInstanceCount: Int
        get() = synchronized(lock) { activeInstances }

    override fun acquireRuntime(paths: RimeWorkspacePaths, distributionVersion: String): Boolean =
        acquireLocked(paths, distributionVersion, exclusive = false)

    override fun acquireExclusiveRuntime(paths: RimeWorkspacePaths, distributionVersion: String): Boolean =
        acquireLocked(paths, distributionVersion, exclusive = true)

    private fun acquireLocked(paths: RimeWorkspacePaths, distributionVersion: String, exclusive: Boolean): Boolean =
        synchronized(lock) {
            raw.ensureLoaded()
            if (activeInstances > 0 && (exclusive || exclusiveLease)) {
                return false
            }
            val key = workspaceKey(paths)
            if (activeInstances == 0) {
                val ok = raw.nativeInitialize(
                    sharedDataDir = paths.sharedDir.absolutePath,
                    userDataDir = paths.userDir.absolutePath,
                    prebuiltDataDir = paths.prebuiltDir.absolutePath,
                    stagingDir = paths.stagingDir.absolutePath,
                    distributionVersion = distributionVersion
                )
                if (!ok) {
                    return false
                }
                activeInstances = 1
                activeWorkspaceKey = key
                exclusiveLease = exclusive
                true
            } else {
                check(activeWorkspaceKey == key) {
                    "Cannot initialize second Rime workspace while another session is active"
                }
                activeInstances++
                true
            }
        }

    override fun releaseRuntime() {
        synchronized(lock) {
            if (activeInstances <= 0) return
            activeInstances--
            if (activeInstances == 0) {
                activeWorkspaceKey = null
                exclusiveLease = false
                raw.nativeFinalize()
            }
        }
    }

    override fun deployWorkspace(): Boolean = synchronized(lock) {
        if (activeInstances != 1) return false
        raw.nativeDeployWorkspace()
    }

    override fun createSession(): Long = synchronized(lock) { raw.nativeCreateSession() }

    override fun destroySession(sessionId: Long): Boolean = synchronized(lock) { raw.nativeDestroySession(sessionId) }

    override fun processKey(sessionId: Long, keysym: Int, mask: Int): Boolean =
        synchronized(lock) { raw.nativeProcessKey(sessionId, keysym, mask) }

    override fun getCommitText(sessionId: Long): String? = synchronized(lock) { raw.nativeGetCommitText(sessionId) }

    override fun getSnapshot(sessionId: Long): RimeNativeSnapshot? =
        synchronized(lock) { parseSnapshotArray(raw.nativeGetSnapshot(sessionId)) }

    override fun selectCandidateOnCurrentPage(sessionId: Long, indexOnPage: Int): Boolean =
        synchronized(lock) { raw.nativeSelectCandidateOnCurrentPage(sessionId, indexOnPage) }

    override fun changePage(sessionId: Long, backward: Boolean): Boolean =
        synchronized(lock) { raw.nativeChangePage(sessionId, backward) }

    override fun selectSchema(sessionId: Long, schemaId: String): Boolean =
        synchronized(lock) { raw.nativeSelectSchema(sessionId, schemaId) }

    override fun getCurrentSchema(sessionId: Long): String? =
        synchronized(lock) { raw.nativeGetCurrentSchema(sessionId) }

    override fun getOption(sessionId: Long, optionName: String): Boolean =
        synchronized(lock) { raw.nativeGetOption(sessionId, optionName) }

    override fun setOption(sessionId: Long, optionName: String, value: Boolean) {
        synchronized(lock) { raw.nativeSetOption(sessionId, optionName, value) }
    }

    override fun clearComposition(sessionId: Long) {
        synchronized(lock) { raw.nativeClearComposition(sessionId) }
    }

    override fun commitComposition(sessionId: Long): Boolean =
        synchronized(lock) { raw.nativeCommitComposition(sessionId) }

    override fun getSchemaList(): List<Pair<String, String>> = synchronized(lock) {
        parseSchemaListArray(raw.nativeGetSchemaList())
    }

    override fun getVersion(): String = synchronized(lock) { raw.nativeGetVersion() }

    private fun workspaceKey(paths: RimeWorkspacePaths): String = listOf(
        paths.sharedDir.canonicalPath,
        paths.userDir.canonicalPath,
        paths.prebuiltDir.canonicalPath,
        paths.stagingDir.canonicalPath
    ).joinToString("|")
}

private const val SNAPSHOT_FIELD_COUNT: Int = 14
private const val SLOT_SCHEMA_ID: Int = 0
private const val SLOT_SCHEMA_NAME: Int = 1
private const val SLOT_IS_COMPOSING: Int = 2
private const val SLOT_IS_ASCII_MODE: Int = 3
private const val SLOT_IS_TRADITIONAL: Int = 4
private const val SLOT_PREEDIT: Int = 5
private const val SLOT_COMMIT_PREVIEW: Int = 6
private const val SLOT_PAGE_INDEX: Int = 7
private const val SLOT_IS_LAST_PAGE: Int = 8
private const val SLOT_HIGHLIGHTED_INDEX: Int = 9
private const val SLOT_CANDIDATE_TEXTS: Int = 10
private const val SLOT_CANDIDATE_COMMENTS: Int = 11
private const val SLOT_RAW_INPUT: Int = 12
private const val SLOT_CARET_POS: Int = 13

internal fun parseSnapshotArray(raw: Array<Any?>?): RimeNativeSnapshot? {
    if (raw == null || raw.size < SNAPSHOT_FIELD_COUNT) return null
    val rawTexts = raw[SLOT_CANDIDATE_TEXTS] as? Array<*>
    val rawComments = raw[SLOT_CANDIDATE_COMMENTS] as? Array<*>
    val texts = rawTexts?.map { (it as? String).orEmpty() }.orEmpty()
    val comments = List(texts.size) { idx ->
        (rawComments?.getOrNull(idx) as? String)?.takeIf { it.isNotEmpty() }
    }
    return RimeNativeSnapshot(
        schemaId = (raw[SLOT_SCHEMA_ID] as? String).orEmpty(),
        schemaName = (raw[SLOT_SCHEMA_NAME] as? String).orEmpty(),
        isComposing = raw[SLOT_IS_COMPOSING] as? Boolean ?: false,
        isAsciiMode = raw[SLOT_IS_ASCII_MODE] as? Boolean ?: false,
        isTraditional = raw[SLOT_IS_TRADITIONAL] as? Boolean ?: false,
        preedit = (raw[SLOT_PREEDIT] as? String).orEmpty(),
        commitTextPreview = (raw[SLOT_COMMIT_PREVIEW] as? String).orEmpty(),
        pageIndex = (raw[SLOT_PAGE_INDEX] as? Int ?: 0).coerceAtLeast(0),
        isLastPage = raw[SLOT_IS_LAST_PAGE] as? Boolean ?: true,
        highlightedIndex = (raw[SLOT_HIGHLIGHTED_INDEX] as? Int ?: 0).coerceAtLeast(0),
        candidateTexts = texts,
        candidateComments = comments,
        rawInput = (raw[SLOT_RAW_INPUT] as? String).orEmpty(),
        caretPos = (raw[SLOT_CARET_POS] as? Int ?: 0).coerceAtLeast(0)
    )
}

internal fun parseSchemaListArray(raw: Array<String>?): List<Pair<String, String>> {
    if (raw == null || raw.size < 2) return emptyList()
    val pairs = ArrayList<Pair<String, String>>(raw.size / 2)
    var i = 0
    while (i + 1 < raw.size) {
        val id = raw[i]
        val name = raw[i + 1]
        if (id.isNotEmpty()) {
            pairs.add(id to name)
        }
        i += 2
    }
    return pairs
}

/**
 * Single dedicated daemon thread confining all process-wide `librime` JNI calls to one OS thread.
 */
public object RimeNativeExecutor {
    private val nativeThreadId = ThreadLocal<Boolean>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            nativeThreadId.set(true)
            runnable.run()
        }, "duoime-rime-native").apply {
            isDaemon = true
        }
    }

    internal fun <T> runBlocking(block: () -> T): T {
        if (nativeThreadId.get() == true) {
            return block()
        }
        return try {
            executor.submit(Callable { block() }).get()
        } catch (err: java.util.concurrent.ExecutionException) {
            throw err.cause ?: err
        }
    }

    /**
     * Executes a Rime workspace transaction offline (when no [RimeInputEngine] session is active)
     * on the dedicated native thread by acquiring the shared `librime` runtime, running [block]
     * with a [RimeWorkspaceDeployer], and releasing the runtime in `finally`.
     */
    public fun <T> runOfflineTransaction(
        context: Context,
        distributionVersion: String = "0.1.0",
        workspaceRoot: File = RimeWorkspaceInstaller.defaultWorkspaceRoot(context),
        block: (RimeWorkspacePaths, RimeWorkspaceDeployer) -> T
    ): T {
        val paths = RimeWorkspaceInstaller.installStarterAssets(context, workspaceRoot)
        return runOfflineTransaction(
            bridge = JniRimeNativeBridge.sharedBridge,
            paths = paths,
            distributionVersion = distributionVersion,
            block = block
        )
    }

    internal fun <T> runOfflineTransaction(
        bridge: RimeNativeBridge,
        paths: RimeWorkspacePaths,
        distributionVersion: String,
        block: (RimeWorkspacePaths, RimeWorkspaceDeployer) -> T
    ): T = runBlocking {
        if (!bridge.acquireExclusiveRuntime(paths, distributionVersion)) {
            throw RimeWorkspaceBusyException(
                "Cannot run offline Rime workspace transaction while an active runtime session exists"
            )
        }
        try {
            val deployer = RimeWorkspaceDeployer {
                val deployed = bridge.deployWorkspace()
                if (!deployed) {
                    null
                } else {
                    RimeWorkspaceInstaller.markWorkspaceDeployed(paths)
                    toRimeSchemaInfos(bridge.getSchemaList(), includeIncognito = false)
                }
            }
            block(paths, deployer)
        } finally {
            runCatching { bridge.releaseRuntime() }
        }
    }
}

/**
 * Exact JNI binding class matching `Java_dev_duoime_engine_rime_JniRimeNativeBridge_*` in
 * `native/rime/jni/duoime_rime_jni.cc`. Retained by `engine-rime/consumer-rules.pro`.
 */
internal object JniRimeNativeBridge {
    private const val NATIVE_LIB_NAME: String = "duoime_rime"
    private val loadLock = Any()

    @Volatile
    private var loaded: Boolean = false

    val sharedBridge: RimeNativeBridge = RefCountedRimeBridge(
        object : RawRimeCalls {
            override fun ensureLoaded() = ensureLibraryLoaded()

            override fun nativeInitialize(
                sharedDataDir: String,
                userDataDir: String,
                prebuiltDataDir: String?,
                stagingDir: String?,
                distributionVersion: String
            ): Boolean = JniRimeNativeBridge.nativeInitialize(
                sharedDataDir,
                userDataDir,
                prebuiltDataDir,
                stagingDir,
                distributionVersion
            )

            override fun nativeFinalize() = JniRimeNativeBridge.nativeFinalize()

            override fun nativeDeployWorkspace(): Boolean = JniRimeNativeBridge.nativeDeployWorkspace()

            override fun nativeCreateSession(): Long = JniRimeNativeBridge.nativeCreateSession()

            override fun nativeDestroySession(sessionId: Long): Boolean =
                JniRimeNativeBridge.nativeDestroySession(sessionId)

            override fun nativeProcessKey(sessionId: Long, keysym: Int, mask: Int): Boolean =
                JniRimeNativeBridge.nativeProcessKey(sessionId, keysym, mask)

            override fun nativeGetCommitText(sessionId: Long): String? =
                JniRimeNativeBridge.nativeGetCommitText(sessionId)

            override fun nativeGetSnapshot(sessionId: Long): Array<Any?>? =
                JniRimeNativeBridge.nativeGetSnapshot(sessionId)

            override fun nativeSelectCandidateOnCurrentPage(sessionId: Long, indexOnPage: Int): Boolean =
                JniRimeNativeBridge.nativeSelectCandidateOnCurrentPage(sessionId, indexOnPage)

            override fun nativeChangePage(sessionId: Long, backward: Boolean): Boolean =
                JniRimeNativeBridge.nativeChangePage(sessionId, backward)

            override fun nativeSelectSchema(sessionId: Long, schemaId: String): Boolean =
                JniRimeNativeBridge.nativeSelectSchema(sessionId, schemaId)

            override fun nativeGetCurrentSchema(sessionId: Long): String? =
                JniRimeNativeBridge.nativeGetCurrentSchema(sessionId)

            override fun nativeGetOption(sessionId: Long, optionName: String): Boolean =
                JniRimeNativeBridge.nativeGetOption(sessionId, optionName)

            override fun nativeSetOption(sessionId: Long, optionName: String, value: Boolean) =
                JniRimeNativeBridge.nativeSetOption(sessionId, optionName, value)

            override fun nativeClearComposition(sessionId: Long) = JniRimeNativeBridge.nativeClearComposition(sessionId)

            override fun nativeCommitComposition(sessionId: Long): Boolean =
                JniRimeNativeBridge.nativeCommitComposition(sessionId)

            override fun nativeGetSchemaList(): Array<String>? = JniRimeNativeBridge.nativeGetSchemaList()

            override fun nativeGetVersion(): String = JniRimeNativeBridge.nativeGetVersion()
        }
    )

    private fun ensureLibraryLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            System.loadLibrary(NATIVE_LIB_NAME)
            loaded = true
        }
    }

    @JvmStatic
    external fun nativeInitialize(
        sharedDataDir: String,
        userDataDir: String,
        prebuiltDataDir: String?,
        stagingDir: String?,
        distributionVersion: String
    ): Boolean

    @JvmStatic
    external fun nativeFinalize()

    @JvmStatic
    external fun nativeDeployWorkspace(): Boolean

    @JvmStatic
    external fun nativeCreateSession(): Long

    @JvmStatic
    external fun nativeDestroySession(sessionId: Long): Boolean

    @JvmStatic
    external fun nativeProcessKey(sessionId: Long, keysym: Int, mask: Int): Boolean

    @JvmStatic
    external fun nativeGetCommitText(sessionId: Long): String?

    @JvmStatic
    external fun nativeGetSnapshot(sessionId: Long): Array<Any?>?

    @JvmStatic
    external fun nativeSelectCandidateOnCurrentPage(sessionId: Long, indexOnPage: Int): Boolean

    @JvmStatic
    external fun nativeChangePage(sessionId: Long, backward: Boolean): Boolean

    @JvmStatic
    external fun nativeSelectSchema(sessionId: Long, schemaId: String): Boolean

    @JvmStatic
    external fun nativeGetCurrentSchema(sessionId: Long): String?

    @JvmStatic
    external fun nativeGetOption(sessionId: Long, optionName: String): Boolean

    @JvmStatic
    external fun nativeSetOption(sessionId: Long, optionName: String, value: Boolean)

    @JvmStatic
    external fun nativeClearComposition(sessionId: Long)

    @JvmStatic
    external fun nativeCommitComposition(sessionId: Long): Boolean

    @JvmStatic
    external fun nativeGetSchemaList(): Array<String>?

    @JvmStatic
    external fun nativeGetVersion(): String
}

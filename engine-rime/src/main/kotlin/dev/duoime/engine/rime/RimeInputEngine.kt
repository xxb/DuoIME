// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.engine.rime

import android.content.Context
import dev.duoime.core.engine.EngineKeyEvent
import dev.duoime.core.engine.EngineOptions
import dev.duoime.core.engine.EngineSchemas
import dev.duoime.core.engine.InputEngine
import dev.duoime.core.layout.KeyCodes
import dev.duoime.core.model.CandidateItem
import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyProcessResult
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class RimeInitialConfig(
    val schemaId: String = EngineSchemas.PINYIN_26,
    val variant: ChineseVariant = ChineseVariant.SIMPLIFIED,
    val incognito: Boolean = false
)

/**
 * [InputEngine] backed by DuoIME's self-built Option A `librime` + `librime-lua` + OpenCC native
 * runtime (`libduoime_rime.so`).
 *
 * - Confines all native calls onto one dedicated single-thread native executor.
 * - Maps logical schemas (`pinyin_26`, `t9_pinyin`, `double_pinyin_flypy`, `english`) onto
 *   DuoIME's clean-room starter schemas and transparently swaps to `*_incognito` companion schemas
 *   (`enable_user_dict: false`) whenever `INCOGNITO` or `DISABLE_USER_DICT` is active.
 * - Coordinates progressive T9 syllable locking over `duoime_t9`'s dual `a-z` + `2-9` speller
 *   while degrading cleanly with an empty rail on custom user schemas.
 */
public class RimeInputEngine internal constructor(
    private val bridge: RimeNativeBridge,
    private val paths: RimeWorkspacePaths,
    private val distributionVersion: String,
    initialConfig: RimeInitialConfig = RimeInitialConfig(),
    private val runNative: (() -> Any?) -> Any? = { block -> RimeNativeExecutor.runBlocking(block) }
) : InputEngine {

    private var sessionId: Long = 0L
    private var closed: Boolean = false
    private var logicalSchemaId: String = canonicalLogicalSchema(initialConfig.schemaId)
    private var lastChineseLogicalSchemaId: String =
        if (logicalSchemaId == EngineSchemas.ENGLISH) EngineSchemas.PINYIN_26 else logicalSchemaId
    private var traditional: Boolean = initialConfig.variant == ChineseVariant.TRADITIONAL
    private var incognitoOption: Boolean = initialConfig.incognito
    private var disableUserDictOption: Boolean = false
    private var currentNativeSchemaId: String = ""
    private val t9Coordinator = T9SyllableCoordinator()

    private val _state = MutableStateFlow(EngineSnapshot.EMPTY)
    public override val state: StateFlow<EngineSnapshot> = _state.asStateFlow()

    public val rimeVersion: String

    init {
        rimeVersion = invokeNative {
            check(bridge.acquireRuntime(paths, distributionVersion)) {
                "Failed to initialize librime runtime"
            }
            var createdSession = 0L
            try {
                if (paths.assetsUpdated || !File(paths.stagingDir, DEFAULT_YAML_FILE).isFile) {
                    check(bridge.deployWorkspace()) { "Failed to deploy Rime starter workspace" }
                }
                createdSession = bridge.createSession()
                check(createdSession != 0L) { "librime createSession failed (returned 0)" }
                sessionId = createdSession
                check(applyLogicalSchemaLocked(logicalSchemaId)) {
                    "Failed to select initial Rime schema: $logicalSchemaId"
                }
                publishSnapshotLocked()
                bridge.getVersion()
            } catch (err: Throwable) {
                if (createdSession != 0L) {
                    runCatching { bridge.destroySession(createdSession) }
                }
                sessionId = 0L
                runCatching { bridge.releaseRuntime() }
                throw err
            }
        }
    }

    public override fun onKey(event: EngineKeyEvent): KeyProcessResult = invokeNative {
        if (closed || sessionId == 0L) return@invokeNative KeyProcessResult.PassThrough
        if ((event.mask and KeyCodes.MASK_RELEASE) != 0) {
            return@invokeNative KeyProcessResult.Consumed
        }
        if ((event.mask and PASSTHROUGH_MODIFIER_MASK) != 0) {
            return@invokeNative KeyProcessResult.PassThrough
        }
        if (logicalSchemaId == EngineSchemas.ENGLISH) {
            return@invokeNative KeyProcessResult.PassThrough
        }
        val result = if (isDuoImeT9SchemaActive()) {
            handleT9KeyLocked(event)
        } else {
            processStandardKeyLocked(event.keysym, event.mask)
        }
        publishSnapshotLocked()
        result
    }

    public override fun selectCandidate(indexOnPage: Int): KeyProcessResult = invokeNative {
        if (closed || sessionId == 0L || indexOnPage < 0) {
            return@invokeNative KeyProcessResult.PassThrough
        }
        if (!bridge.selectCandidateOnCurrentPage(sessionId, indexOnPage)) {
            return@invokeNative KeyProcessResult.PassThrough
        }
        val commit = bridge.getCommitText(sessionId)
        val snap = bridge.getSnapshot(sessionId)
        if (isDuoImeT9SchemaActive()) {
            t9Coordinator.syncAfterSelection(commit, snap)
        }
        publishSnapshotLocked(snap)
        if (!commit.isNullOrEmpty()) KeyProcessResult.CommitText(commit) else KeyProcessResult.Consumed
    }

    public override fun selectSyllable(index: Int): Boolean = invokeNative {
        if (closed || sessionId == 0L || !isDuoImeT9SchemaActive() ||
            !t9Coordinator.isActive || !t9Coordinator.canReplayCleanly
        ) {
            return@invokeNative false
        }
        if (!t9Coordinator.lockSyllableByIndex(index)) {
            return@invokeNative false
        }
        replayT9ToRimeLocked()
        publishSnapshotLocked()
        true
    }

    public override fun unlockSyllablesFrom(index: Int): Boolean = invokeNative {
        if (closed || sessionId == 0L || !isDuoImeT9SchemaActive() ||
            !t9Coordinator.isActive || !t9Coordinator.canReplayCleanly
        ) {
            return@invokeNative false
        }
        if (!t9Coordinator.unlockFrom(index)) {
            return@invokeNative false
        }
        replayT9ToRimeLocked()
        publishSnapshotLocked()
        true
    }

    public override fun page(forward: Boolean): Boolean = invokeNative {
        if (closed || sessionId == 0L || logicalSchemaId == EngineSchemas.ENGLISH) {
            return@invokeNative false
        }
        val beforeSnap = _state.value
        if (!beforeSnap.isComposing) return@invokeNative false
        if (forward && !beforeSnap.hasNextPage) return@invokeNative false
        if (!forward && !beforeSnap.hasPrevPage) return@invokeNative false

        val changed = bridge.changePage(sessionId, backward = !forward)
        val afterNative = bridge.getSnapshot(sessionId)
        publishSnapshotLocked(afterNative)
        changed && afterNative != null && afterNative.pageIndex != beforeSnap.pageIndex
    }

    public override fun setOption(name: String, on: Boolean): Unit = invokeNative {
        if (closed || sessionId == 0L) return@invokeNative
        when (name.trim().lowercase()) {
            EngineOptions.TRADITIONALIZATION -> {
                traditional = on
                if (logicalSchemaId != EngineSchemas.ENGLISH) {
                    bridge.setOption(sessionId, OPTION_TRADITIONALIZATION, on)
                }
            }
            EngineOptions.ASCII_MODE -> {
                val target = if (on) EngineSchemas.ENGLISH else lastChineseLogicalSchemaId
                applyLogicalSchemaLocked(target)
            }
            EngineOptions.INCOGNITO -> {
                val prev = effectiveIncognito()
                incognitoOption = on
                if (prev != effectiveIncognito() && logicalSchemaId != EngineSchemas.ENGLISH) {
                    applyLogicalSchemaLocked(logicalSchemaId)
                }
            }
            EngineOptions.DISABLE_USER_DICT -> {
                val prev = effectiveIncognito()
                disableUserDictOption = on
                if (prev != effectiveIncognito() && logicalSchemaId != EngineSchemas.ENGLISH) {
                    applyLogicalSchemaLocked(logicalSchemaId)
                }
            }
            else -> {
                bridge.setOption(sessionId, name, on)
            }
        }
        publishSnapshotLocked()
    }

    public override fun selectSchema(id: String): Boolean = invokeNative {
        if (closed || sessionId == 0L) return@invokeNative false
        val canonical = canonicalLogicalSchema(id)
        val ok = applyLogicalSchemaLocked(canonical)
        publishSnapshotLocked()
        ok
    }

    public override fun reset(): Unit = invokeNative {
        if (closed || sessionId == 0L) return@invokeNative
        t9Coordinator.clear()
        bridge.clearComposition(sessionId)
        publishSnapshotLocked()
    }

    /**
     * Triggers a synchronous Rime workspace re-deploy (for M2-2 schema reload / user import),
     * destroying the active native session prior to deploy and recreating it cleanly afterward so
     * no session handle remains bound to stale deployed tables.
     *
     * Exception-safe: if session recreation fails or throws, closes this engine and releases its
     * runtime refcount so no open sessionless engine or leaked native handle remains.
     */
    public fun deployWorkspace(): Boolean = invokeNative {
        if (closed || sessionId == 0L) return@invokeNative false
        t9Coordinator.clear()
        val previousSession = sessionId
        sessionId = 0L
        runCatching { bridge.clearComposition(previousSession) }
        runCatching { bridge.destroySession(previousSession) }

        var sessionRestored = false
        try {
            val (deployed, deployError) = invokeBridgeDeploySafely()
            val recreated = bridge.createSession()
            if (recreated == 0L) {
                if (deployError != null) throw deployError
                return@invokeNative false
            }
            sessionId = recreated
            val schemaOk = applyLogicalSchemaLocked(logicalSchemaId)
            publishSnapshotLocked()
            sessionRestored = true
            if (deployError != null) throw deployError
            deployed && schemaOk
        } finally {
            if (!sessionRestored && !closed) {
                closeLocked()
            }
        }
    }

    public override fun close(): Unit = invokeNative {
        closeLocked()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun invokeBridgeDeploySafely(): Pair<Boolean, Throwable?> = try {
        bridge.deployWorkspace() to null
    } catch (err: Throwable) {
        false to err
    }

    private fun closeLocked() {
        if (closed) return
        closed = true
        val sid = sessionId
        sessionId = 0L
        t9Coordinator.clear()
        if (sid != 0L) {
            runCatching { bridge.destroySession(sid) }
        }
        runCatching { bridge.releaseRuntime() }
        _state.value = EngineSnapshot.EMPTY
    }

    private fun handleT9KeyLocked(event: EngineKeyEvent): KeyProcessResult {
        if (!t9Coordinator.canReplayCleanly) {
            val res = processStandardKeyLocked(event.keysym, event.mask)
            val commitText = (res as? KeyProcessResult.CommitText)?.text
            t9Coordinator.syncAfterSelection(commitText, bridge.getSnapshot(sessionId))
            return res
        }
        return when (event.keysym) {
            KeyCodes.KEYSYM_BACKSPACE -> {
                if (!t9Coordinator.isActive) return KeyProcessResult.PassThrough
                t9Coordinator.backspace()
                replayT9ToRimeLocked()
                KeyProcessResult.Consumed
            }
            KeyCodes.KEYSYM_SPACE, KeyCodes.KEYSYM_RETURN -> {
                if (!t9Coordinator.isActive) return KeyProcessResult.PassThrough
                val res = processStandardKeyLocked(KeyCodes.KEYSYM_SPACE, 0)
                val commitText = (res as? KeyProcessResult.CommitText)?.text
                t9Coordinator.syncAfterSelection(commitText, bridge.getSnapshot(sessionId))
                res
            }
            KeyCodes.KEYSYM_ESCAPE -> {
                if (!t9Coordinator.isActive) return KeyProcessResult.PassThrough
                t9Coordinator.clear()
                bridge.clearComposition(sessionId)
                KeyProcessResult.Consumed
            }
            else -> handleT9CharLocked(event)
        }
    }

    private fun handleT9CharLocked(event: EngineKeyEvent): KeyProcessResult {
        val ch = KeyCodes.charForKeysym(event.keysym)
            ?: return processStandardKeyLocked(event.keysym, event.mask)
        return when {
            ch in '2'..'9' -> {
                t9Coordinator.appendDigit(ch)
                replayT9ToRimeLocked()
                KeyProcessResult.Consumed
            }
            (ch == '\'' || ch == '1') && t9Coordinator.isActive -> {
                t9Coordinator.appendDelimiter()
                replayT9ToRimeLocked()
                KeyProcessResult.Consumed
            }
            ch == '0' && t9Coordinator.isActive -> {
                val res = processStandardKeyLocked(KeyCodes.KEYSYM_SPACE, 0)
                val commitText = (res as? KeyProcessResult.CommitText)?.text
                t9Coordinator.syncAfterSelection(commitText, bridge.getSnapshot(sessionId))
                res
            }
            else -> {
                val res = processStandardKeyLocked(event.keysym, event.mask)
                val commitText = (res as? KeyProcessResult.CommitText)?.text
                t9Coordinator.syncAfterSelection(commitText, bridge.getSnapshot(sessionId))
                res
            }
        }
    }

    private fun replayT9ToRimeLocked() {
        bridge.clearComposition(sessionId)
        val seq = t9Coordinator.buildReplaySequence()
        for (ch in seq) {
            bridge.processKey(sessionId, KeyCodes.keysymForChar(ch), 0)
        }
    }

    private fun processStandardKeyLocked(keysym: Int, mask: Int): KeyProcessResult {
        val handled = bridge.processKey(sessionId, keysym, mask)
        val commit = if (handled) bridge.getCommitText(sessionId) else null
        return when {
            !commit.isNullOrEmpty() -> KeyProcessResult.CommitText(commit)
            handled -> KeyProcessResult.Consumed
            else -> KeyProcessResult.PassThrough
        }
    }

    private fun applyLogicalSchemaLocked(canonicalId: String): Boolean {
        t9Coordinator.clear()
        bridge.clearComposition(sessionId)
        if (canonicalId == EngineSchemas.ENGLISH) {
            logicalSchemaId = EngineSchemas.ENGLISH
            bridge.setOption(sessionId, EngineOptions.ASCII_MODE, true)
            return true
        }
        val nativeTarget = resolveNativeSchemaId(canonicalId, effectiveIncognito())
        val selected = bridge.selectSchema(sessionId, nativeTarget)
        if (!selected) {
            return false
        }
        logicalSchemaId = canonicalId
        lastChineseLogicalSchemaId = canonicalId
        currentNativeSchemaId = nativeTarget
        bridge.setOption(sessionId, EngineOptions.ASCII_MODE, false)
        bridge.setOption(sessionId, OPTION_TRADITIONALIZATION, traditional)
        return true
    }

    private fun publishSnapshotLocked(preFetched: RimeNativeSnapshot? = null) {
        if (sessionId == 0L) {
            _state.value = EngineSnapshot.EMPTY
            return
        }
        if (logicalSchemaId == EngineSchemas.ENGLISH) {
            _state.value = EngineSnapshot(
                mode = InputMode.ENGLISH,
                variant = currentVariant(),
                schemaId = EngineSchemas.ENGLISH,
                isComposing = false
            )
            return
        }
        val nativeSnap = preFetched ?: bridge.getSnapshot(sessionId)
        _state.value = buildChineseSnapshot(nativeSnap)
    }

    private fun buildChineseSnapshot(nativeSnap: RimeNativeSnapshot?): EngineSnapshot {
        val isT9 = isDuoImeT9SchemaActive()
        val items = buildCandidateItems(nativeSnap)
        val isComposing = nativeSnap?.isComposing == true
        val preedit = resolvePreeditText(nativeSnap, isComposing)
        val pageIndex = nativeSnap?.pageIndex ?: 0
        val highlighted = resolveHighlightedIndex(items, nativeSnap?.highlightedIndex ?: 0)
        return EngineSnapshot(
            mode = if (isT9) InputMode.PINYIN_T9 else InputMode.PINYIN_26,
            variant = resolveEffectiveVariant(nativeSnap),
            schemaId = logicalSchemaId,
            preedit = preedit,
            composingText = preedit,
            candidates = items,
            highlightedIndex = highlighted,
            pageIndex = pageIndex,
            hasPrevPage = isComposing && pageIndex > 0,
            hasNextPage = isComposing && items.isNotEmpty() && nativeSnap?.isLastPage == false,
            syllables = if (isT9 && isComposing) t9Coordinator.currentOptions() else emptyList(),
            selectedSyllableIndex = -1,
            isComposing = isComposing,
            lockedSyllables = if (isT9 && isComposing) t9Coordinator.lockedSyllables else emptyList()
        )
    }

    private fun buildCandidateItems(nativeSnap: RimeNativeSnapshot?): List<CandidateItem> =
        nativeSnap?.candidateTexts?.mapIndexed { idx, text ->
            CandidateItem(
                text = text,
                comment = nativeSnap.candidateComments.getOrNull(idx),
                indexOnPage = idx
            )
        }.orEmpty()

    private fun resolvePreeditText(nativeSnap: RimeNativeSnapshot?, isComposing: Boolean): String {
        if (!isComposing || nativeSnap == null) return ""
        return nativeSnap.preedit.ifEmpty { nativeSnap.rawInput }
    }

    private fun resolveEffectiveVariant(nativeSnap: RimeNativeSnapshot?): ChineseVariant =
        if (nativeSnap?.isTraditional == true || traditional) {
            ChineseVariant.TRADITIONAL
        } else {
            ChineseVariant.SIMPLIFIED
        }

    private fun resolveHighlightedIndex(items: List<CandidateItem>, rawIndex: Int): Int =
        if (items.isEmpty()) 0 else rawIndex.coerceIn(0, items.lastIndex)

    private fun isDuoImeT9SchemaActive(): Boolean = logicalSchemaId == EngineSchemas.T9_PINYIN &&
        (
            currentNativeSchemaId == RimeEngineMetadata.SCHEMA_T9_PINYIN ||
                currentNativeSchemaId == "${RimeEngineMetadata.SCHEMA_T9_PINYIN}${RimeEngineMetadata.INCOGNITO_SUFFIX}"
            )

    private fun effectiveIncognito(): Boolean = incognitoOption || disableUserDictOption

    private fun currentVariant(): ChineseVariant =
        if (traditional) ChineseVariant.TRADITIONAL else ChineseVariant.SIMPLIFIED

    @Suppress("UNCHECKED_CAST")
    private fun <T> invokeNative(block: () -> T): T = runNative { block() } as T

    public companion object {
        private const val DEFAULT_YAML_FILE: String = "default.yaml"
        private const val OPTION_TRADITIONALIZATION: String = EngineOptions.TRADITIONALIZATION
        private const val PASSTHROUGH_MODIFIER_MASK: Int =
            KeyCodes.MASK_CONTROL or KeyCodes.MASK_ALT or KeyCodes.MASK_SUPER

        internal fun canonicalLogicalSchema(rawId: String): String = when (rawId.trim().lowercase()) {
            "pinyin_26", "pinyin", "quanpin", RimeEngineMetadata.SCHEMA_PINYIN_26 -> EngineSchemas.PINYIN_26
            "t9_pinyin", "t9", RimeEngineMetadata.SCHEMA_T9_PINYIN -> EngineSchemas.T9_PINYIN
            "double_pinyin_flypy", "xiaohe", "xiaohe_shuangpin", RimeEngineMetadata.SCHEMA_FLYPY ->
                EngineSchemas.DOUBLE_PINYIN_FLYPY
            "english", "en" -> EngineSchemas.ENGLISH
            else -> rawId.trim()
        }

        internal fun resolveNativeSchemaId(logicalId: String, incognito: Boolean): String {
            val base = when (logicalId) {
                EngineSchemas.PINYIN_26 -> RimeEngineMetadata.SCHEMA_PINYIN_26
                EngineSchemas.T9_PINYIN -> RimeEngineMetadata.SCHEMA_T9_PINYIN
                EngineSchemas.DOUBLE_PINYIN_FLYPY -> RimeEngineMetadata.SCHEMA_FLYPY
                else -> return logicalId
            }
            return if (incognito) "$base${RimeEngineMetadata.INCOGNITO_SUFFIX}" else base
        }

        /**
         * Installs bundled permissive starter assets into app-private storage and initializes a
         * [RimeInputEngine] backed by `libduoime_rime.so`.
         *
         * **Threading:** Performs blocking asset extraction & native Rime deployment. Never invoke
         * on the Android main thread. Throws cleanly (e.g., [UnsatisfiedLinkError]) if the native
         * library is absent so `KeyboardController` falls back to `:engine-lite`.
         */
        @Suppress("LongParameterList")
        public fun create(
            context: Context,
            distributionVersion: String = "0.1.0",
            initialSchemaId: String = EngineSchemas.PINYIN_26,
            initialVariant: ChineseVariant = ChineseVariant.SIMPLIFIED,
            initialIncognito: Boolean = false,
            workspaceRoot: File = File(context.applicationContext.filesDir, RimeEngineMetadata.ASSET_ROOT_PATH)
        ): RimeInputEngine {
            val paths = RimeWorkspaceInstaller.installStarterAssets(context, workspaceRoot)
            return RimeInputEngine(
                bridge = JniRimeNativeBridge.sharedBridge,
                paths = paths,
                distributionVersion = distributionVersion,
                initialConfig = RimeInitialConfig(
                    schemaId = initialSchemaId,
                    variant = initialVariant,
                    incognito = initialIncognito
                )
            )
        }
    }
}

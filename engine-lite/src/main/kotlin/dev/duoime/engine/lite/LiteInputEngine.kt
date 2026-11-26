// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.engine.lite

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure-JVM [InputEngine] implementation backed by [LocalChineseInputEngine] and bundled
 * permissive dictionaries (`:engine-lite`). Serves as the M1 input brain and permanent fallback.
 *
 * **Threading:** [LiteInputEngine] is **single-thread confined** (not thread-safe). Callers must
 * serialize all engine calls on one dedicated dispatcher/thread. Constructing an instance via
 * [createDefault] triggers blocking dictionary I/O (~100–300 ms on Android) and must never run
 * on the main thread.
 */
class LiteInputEngine(
    private val localEngine: LocalChineseInputEngine,
    initialSchemaId: String = SCHEMA_PINYIN_26
) : InputEngine {

    private var activeSchemaId: String = canonicalSchemaId(initialSchemaId) ?: SCHEMA_PINYIN_26
    private var lastChineseSchemaId: String =
        if (activeSchemaId == SCHEMA_ENGLISH) SCHEMA_PINYIN_26 else activeSchemaId

    private val _state = MutableStateFlow(toCoreSnapshot(localEngine.snapshot))
    override val state: StateFlow<EngineSnapshot> = _state.asStateFlow()

    val localSnapshot: LiteEngineSnapshot
        get() = localEngine.snapshot

    init {
        selectSchema(activeSchemaId)
    }

    override fun onKey(event: EngineKeyEvent): KeyProcessResult {
        if ((event.mask and KeyCodes.MASK_RELEASE) != 0) {
            return KeyProcessResult.Consumed
        }
        if ((event.mask and PASSTHROUGH_MODIFIER_MASK) != 0) {
            return KeyProcessResult.PassThrough
        }
        val result = when (event.keysym) {
            KeyCodes.KEYSYM_BACKSPACE ->
                if (localEngine.onBackspace()) KeyProcessResult.Consumed else KeyProcessResult.PassThrough
            KeyCodes.KEYSYM_SPACE -> convertResult(localEngine.onSpace())
            KeyCodes.KEYSYM_RETURN -> convertResult(localEngine.onEnter())
            KeyCodes.KEYSYM_ESCAPE -> handleEscape()
            else -> handleCharacterKey(event)
        }
        publishSnapshot()
        return result
    }

    override fun selectCandidate(indexOnPage: Int): KeyProcessResult {
        val snap = localEngine.snapshot
        if (indexOnPage !in snap.pageCandidates.indices) {
            return KeyProcessResult.PassThrough
        }
        val globalIndex = snap.pageStartIndex + indexOnPage
        val res = localEngine.selectCandidate(globalIndex)
        publishSnapshot()
        return if (res != null) convertResult(res) else KeyProcessResult.PassThrough
    }

    override fun selectSyllable(index: Int): Boolean {
        if (index == -1) {
            return selectT9Syllable(null)
        }
        val syllable = _state.value.syllables.getOrNull(index) ?: return false
        return selectT9Syllable(syllable)
    }

    override fun page(forward: Boolean): Boolean {
        val changed = if (forward) localEngine.nextCandidatePage() else localEngine.prevCandidatePage()
        if (changed) {
            publishSnapshot()
        }
        return changed
    }

    override fun setOption(name: String, on: Boolean) {
        when (name.trim().lowercase()) {
            OPTION_TRADITIONALIZATION -> {
                localEngine.setChineseVariant(
                    if (on) ChineseVariant.TRADITIONAL else ChineseVariant.SIMPLIFIED
                )
            }
            OPTION_ASCII_MODE -> {
                val targetSchema = if (on) SCHEMA_ENGLISH else lastChineseSchemaId
                applySchema(targetSchema)
            }
            OPTION_INCOGNITO, OPTION_DISABLE_USER_DICT -> {
                localEngine.setLearningEnabled(!on)
            }
            OPTION_COMPACT_SINGLE_ROW_PAGING -> {
                localEngine.setCompactSingleRowPaging(on)
            }
        }
        publishSnapshot()
    }

    override fun selectSchema(id: String): Boolean {
        val canonical = canonicalSchemaId(id) ?: return false
        applySchema(canonical)
        publishSnapshot()
        return true
    }

    override fun reset() {
        localEngine.clearPreEdit()
        publishSnapshot()
    }

    fun selectT9Syllable(syllable: String?): Boolean {
        val changed = localEngine.selectSyllableFilter(syllable)
        if (changed) publishSnapshot()
        return changed
    }

    fun unlockT9SyllablesFrom(index: Int): Boolean {
        val changed = localEngine.unlockT9SyllablesFrom(index)
        if (changed) publishSnapshot()
        return changed
    }

    fun setCandidateWindowRows(rows: Int): Boolean {
        val changed = localEngine.setCandidateWindowRows(rows)
        if (changed) publishSnapshot()
        return changed
    }

    fun setCandidateRowWidthBudget(collapsedMaxUnits: Float? = null, expandedMaxUnits: Float? = null) {
        localEngine.setCandidateRowWidthBudget(collapsedMaxUnits, expandedMaxUnits)
        publishSnapshot()
    }

    private fun handleEscape(): KeyProcessResult {
        if (!localEngine.snapshot.hasPreEdit) return KeyProcessResult.PassThrough
        localEngine.clearPreEdit()
        return KeyProcessResult.Consumed
    }

    private fun handleCharacterKey(event: EngineKeyEvent): KeyProcessResult {
        val ch = KeyCodes.charForKeysym(event.keysym) ?: return KeyProcessResult.PassThrough
        if (Character.isISOControl(ch)) return KeyProcessResult.PassThrough
        return convertResult(localEngine.onCharacter(ch))
    }

    private fun applySchema(canonicalId: String) {
        activeSchemaId = canonicalId
        if (canonicalId != SCHEMA_ENGLISH) {
            lastChineseSchemaId = canonicalId
        }
        val mode = when (canonicalId) {
            SCHEMA_PINYIN_26 -> LiteSchemaMode.QUANPIN
            SCHEMA_T9_PINYIN -> LiteSchemaMode.T9_PINYIN
            SCHEMA_XIAOHE -> LiteSchemaMode.XIAOHE_SHUANGPIN
            SCHEMA_ENGLISH -> LiteSchemaMode.ENGLISH
            else -> LiteSchemaMode.QUANPIN
        }
        localEngine.setMode(mode)
    }

    private fun publishSnapshot() {
        _state.value = toCoreSnapshot(localEngine.snapshot)
    }

    private fun convertResult(result: LiteKeyResult): KeyProcessResult = when (result) {
        is LiteKeyResult.Consumed -> KeyProcessResult.Consumed
        is LiteKeyResult.CommitText -> KeyProcessResult.CommitText(result.text)
        is LiteKeyResult.PassThrough -> KeyProcessResult.PassThrough
    }

    private fun toCoreSnapshot(snap: LiteEngineSnapshot): EngineSnapshot {
        val mode = when (snap.mode) {
            LiteSchemaMode.QUANPIN, LiteSchemaMode.XIAOHE_SHUANGPIN -> InputMode.PINYIN_26
            LiteSchemaMode.T9_PINYIN -> InputMode.PINYIN_T9
            LiteSchemaMode.ENGLISH -> InputMode.ENGLISH
        }
        val pageCands = snap.pageCandidates
        val items = pageCands.mapIndexed { idx, cand ->
            CandidateItem(
                text = cand.text,
                comment = cand.pinyin.takeIf { it.isNotEmpty() },
                indexOnPage = idx
            )
        }
        val pageHighlight = if (pageCands.isEmpty()) {
            0
        } else {
            (snap.highlightedIndex - snap.pageStartIndex).coerceIn(0, pageCands.lastIndex)
        }
        val selectedSyllableIdx = snap.activeSyllableFilter
            ?.let { snap.t9SyllableOptions.indexOf(it) }
            ?: -1

        return EngineSnapshot(
            mode = mode,
            variant = snap.variant,
            schemaId = activeSchemaId,
            preedit = snap.formattedPreEdit,
            composingText = if (snap.hasPreEdit) snap.formattedPreEdit else "",
            candidates = items,
            highlightedIndex = pageHighlight,
            pageIndex = snap.candidatePage,
            hasPrevPage = snap.hasPrevPage,
            hasNextPage = snap.hasNextPage,
            syllables = snap.t9SyllableOptions,
            selectedSyllableIndex = selectedSyllableIdx,
            isComposing = snap.hasPreEdit
        )
    }

    companion object {
        private const val PASSTHROUGH_MODIFIER_MASK: Int =
            KeyCodes.MASK_CONTROL or KeyCodes.MASK_ALT or KeyCodes.MASK_SUPER

        const val SCHEMA_PINYIN_26: String = EngineSchemas.PINYIN_26
        const val SCHEMA_T9_PINYIN: String = EngineSchemas.T9_PINYIN
        const val SCHEMA_XIAOHE: String = EngineSchemas.DOUBLE_PINYIN_FLYPY
        const val SCHEMA_ENGLISH: String = EngineSchemas.ENGLISH

        const val OPTION_TRADITIONALIZATION: String = EngineOptions.TRADITIONALIZATION
        const val OPTION_ASCII_MODE: String = EngineOptions.ASCII_MODE
        const val OPTION_INCOGNITO: String = EngineOptions.INCOGNITO
        const val OPTION_DISABLE_USER_DICT: String = EngineOptions.DISABLE_USER_DICT
        const val OPTION_COMPACT_SINGLE_ROW_PAGING: String = "compact_single_row_paging"

        fun canonicalSchemaId(id: String): String? = when (id.trim().lowercase()) {
            "pinyin_26", "pinyin", "quanpin" -> SCHEMA_PINYIN_26
            "t9_pinyin", "t9" -> SCHEMA_T9_PINYIN
            "double_pinyin_flypy", "xiaohe", "xiaohe_shuangpin" -> SCHEMA_XIAOHE
            "english", "en" -> SCHEMA_ENGLISH
            else -> null
        }

        /**
         * Creates a [LiteInputEngine] backed by [LiteBundledResources.defaultBundle].
         *
         * **Threading:** First invocation triggers blocking classpath I/O + ~0.9 MB dictionary parse
         * (~100–300 ms on Android). **Never call on the main thread** — prewarm on `Dispatchers.Default`
         * and confine subsequent calls on the returned instance to a single thread.
         */
        fun createDefault(
            learningStore: UserLearningStore? = null,
            initialSchemaId: String = SCHEMA_PINYIN_26,
            initialVariant: ChineseVariant = ChineseVariant.SIMPLIFIED,
            bundle: LiteDictionaryBundle = LiteBundledResources.defaultBundle()
        ): LiteInputEngine {
            val local = LocalChineseInputEngine(
                dictionary = bundle.pinyinDictionary,
                converter = bundle.chineseConverter,
                learningStore = learningStore,
                initialMode = LiteSchemaMode.QUANPIN,
                initialVariant = initialVariant,
                englishLexicon = bundle.englishLexicon
            )
            return LiteInputEngine(localEngine = local, initialSchemaId = initialSchemaId)
        }
    }
}

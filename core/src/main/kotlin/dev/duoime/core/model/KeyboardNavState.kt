// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.model

import dev.duoime.core.engine.EngineSchemas

/**
 * Logical input language orthogonal to [ChineseScheme] and [KeyboardPanel].
 */
enum class Language {
    CHINESE,
    ENGLISH
}

/**
 * Chinese input scheme orthogonal to [Language] (`T9 -> EN -> CN` preserves `PINYIN_T9`).
 */
enum class ChineseScheme {
    PINYIN_26,
    PINYIN_T9,
    XIAOHE_SHUANGPIN
}

/**
 * Active keyboard surface panel orthogonal to [Language] and [ChineseScheme].
 */
enum class KeyboardPanel {
    PRIMARY,
    NUMBER,
    SYMBOL
}

/**
 * Semantic editor field classification derived from `EditorInfo.inputType` and sandboxed
 * host [dev.duoime.core.protocol.ExtensionConfig].
 */
enum class FieldKind {
    TEXT,
    NUMBER,
    DECIMAL,
    SIGNED_NUMBER,
    PHONE,
    DATETIME,
    PASSWORD,
    URI,
    EMAIL,
    TERMINAL
}

/**
 * Symbol panel category tabs.
 */
enum class SymbolTabId {
    COMMON,
    CHINESE,
    CLI,
    ENGLISH,
    PAIR,
    MATH_UNIT,
    SEQUENCE,
    ARROW_SPECIAL,
    GREEK_PHONETIC
}

/**
 * Candidate bar browsing style preference/override.
 */
enum class CandidatePaging {
    SCROLL,
    PAGED
}

/**
 * Placement preference/override for the language toggle key.
 */
enum class LangKeyPlacement {
    GRID,
    HOST,
    TOOLBAR
}

/**
 * Top bar display mode.
 */
enum class ToolbarMode {
    FULL,
    CANDIDATE_ONLY,
    NONE
}

/**
 * Resolves the effective [LangKeyPlacement] given the active [ToolbarMode].
 * When [LangKeyPlacement.TOOLBAR] is requested while [toolbarMode] is not [ToolbarMode.FULL],
 * falls back safely to [LangKeyPlacement.GRID] so the language toggle remains reachable.
 */
fun resolveEffectiveLangKeyPlacement(requested: LangKeyPlacement, toolbarMode: ToolbarMode): LangKeyPlacement =
    if (requested == LangKeyPlacement.TOOLBAR && toolbarMode != ToolbarMode.FULL) {
        LangKeyPlacement.GRID
    } else {
        requested
    }

/**
 * Quick-pin actions configurable in the toolbar/drawer.
 */
enum class QuickPin {
    SCHEME_SWITCH,
    SWAP_SCREEN,
    VARIANT,
    NUMBER_ROW,
    HEIGHT,
    SWITCH_IME
}

/**
 * Shift modifier state for alphabetic layouts.
 */
enum class ShiftState {
    OFF,
    ONCE,
    LOCKED
}

/**
 * Layer 1 (Persisted User Preferences): orthogonal user preferences never silently clobbered
 * by temporary panels, password editors, or constrained fields.
 */
data class KeyboardPrefs(
    val language: Language = Language.CHINESE,
    val chineseScheme: ChineseScheme = ChineseScheme.PINYIN_26,
    val variant: ChineseVariant = ChineseVariant.SIMPLIFIED,
    val includeNumberRow: Boolean = false
)

/**
 * Layer 2 (Transient Editor Session State): ephemeral panel, lock, tab, shift, drawer, and
 * per-editor language override state.
 */
data class SessionState(
    val panel: KeyboardPanel = KeyboardPanel.PRIMARY,
    val panelLocked: Boolean = false,
    val symbolTab: SymbolTabId = SymbolTabId.COMMON,
    val shiftState: ShiftState = ShiftState.OFF,
    val languageOverride: Language? = null,
    val drawerOpen: Boolean = false
)

/**
 * Layer 3 (Editor/Host Constraints): immutable constraints derived from the active editor and
 * host extension config; clamps derived state without mutating [KeyboardPrefs].
 */
data class InputConstraints(
    val kind: FieldKind = FieldKind.TEXT,
    val allowChinese: Boolean = true,
    val forcedPanel: KeyboardPanel? = null,
    val forceNumberRow: Boolean = false,
    val noLearning: Boolean = false,
    val initialLanguage: Language? = null,
    val defaultSymbolTab: SymbolTabId = SymbolTabId.COMMON,
    val langKeyPlacementOverride: LangKeyPlacement? = null,
    val candidatePagingOverride: CandidatePaging? = null
)

/**
 * Single source of truth derived purely from [KeyboardPrefs], [SessionState], and
 * [InputConstraints].
 */
data class DerivedKeyboardState(
    val effectiveLanguage: Language,
    val effectiveChineseScheme: ChineseScheme,
    val activeMode: InputMode,
    val activeSchemaId: String,
    val panel: KeyboardPanel,
    val panelLocked: Boolean,
    val symbolTab: SymbolTabId,
    val includeNumberRow: Boolean,
    val shiftState: ShiftState,
    val shifted: Boolean,
    val drawerOpen: Boolean,
    val canToggleLanguage: Boolean,
    val langKeyPlacement: LangKeyPlacement?,
    val candidatePaging: CandidatePaging?,
    val fieldKind: FieldKind
)

/**
 * Pure transition result produced by [KeyboardNavReducer].
 */
data class KeyboardNavUpdate(
    val prefs: KeyboardPrefs,
    val session: SessionState
)

/**
 * Pure derivation function computing [DerivedKeyboardState] from the 3 orthogonal state layers.
 */
fun deriveKeyboardState(
    prefs: KeyboardPrefs,
    session: SessionState,
    constraints: InputConstraints = InputConstraints(),
    modeSchemaIds: Map<InputMode, String> = EngineSchemas.DEFAULT_MODE_MAP,
    chineseSchemeSchemaIds: Map<ChineseScheme, String> = DEFAULT_CHINESE_SCHEME_SCHEMA_IDS
): DerivedKeyboardState {
    val effectiveLanguage = if (!constraints.allowChinese) {
        Language.ENGLISH
    } else {
        session.languageOverride ?: constraints.initialLanguage ?: prefs.language
    }
    val canToggleLanguage = constraints.allowChinese
    val panel = when {
        constraints.forcedPanel == null -> session.panel
        session.panel == KeyboardPanel.SYMBOL -> KeyboardPanel.SYMBOL
        else -> constraints.forcedPanel
    }
    val panelLocked = if (panel == KeyboardPanel.PRIMARY) false else session.panelLocked
    val activeMode = when (effectiveLanguage) {
        Language.ENGLISH -> InputMode.ENGLISH
        Language.CHINESE -> when (prefs.chineseScheme) {
            ChineseScheme.PINYIN_T9 -> InputMode.PINYIN_T9
            ChineseScheme.PINYIN_26, ChineseScheme.XIAOHE_SHUANGPIN -> InputMode.PINYIN_26
        }
    }
    val activeSchemaId = resolveActiveSchemaId(
        effectiveLanguage = effectiveLanguage,
        chineseScheme = prefs.chineseScheme,
        modeSchemaIds = modeSchemaIds,
        chineseSchemeSchemaIds = chineseSchemeSchemaIds
    )
    return DerivedKeyboardState(
        effectiveLanguage = effectiveLanguage,
        effectiveChineseScheme = prefs.chineseScheme,
        activeMode = activeMode,
        activeSchemaId = activeSchemaId,
        panel = panel,
        panelLocked = panelLocked,
        symbolTab = session.symbolTab,
        includeNumberRow = prefs.includeNumberRow || constraints.forceNumberRow,
        shiftState = session.shiftState,
        shifted = session.shiftState != ShiftState.OFF,
        drawerOpen = session.drawerOpen,
        canToggleLanguage = canToggleLanguage,
        langKeyPlacement = constraints.langKeyPlacementOverride,
        candidatePaging = constraints.candidatePagingOverride,
        fieldKind = constraints.kind
    )
}

private val DEFAULT_CHINESE_SCHEME_SCHEMA_IDS: Map<ChineseScheme, String> = mapOf(
    ChineseScheme.XIAOHE_SHUANGPIN to EngineSchemas.DOUBLE_PINYIN_FLYPY
)

private fun resolveActiveSchemaId(
    effectiveLanguage: Language,
    chineseScheme: ChineseScheme,
    modeSchemaIds: Map<InputMode, String>,
    chineseSchemeSchemaIds: Map<ChineseScheme, String>
): String = when (effectiveLanguage) {
    Language.ENGLISH -> modeSchemaIds[InputMode.ENGLISH] ?: EngineSchemas.ENGLISH
    Language.CHINESE -> when (chineseScheme) {
        ChineseScheme.PINYIN_T9 -> modeSchemaIds[InputMode.PINYIN_T9] ?: EngineSchemas.T9_PINYIN
        ChineseScheme.PINYIN_26 -> modeSchemaIds[InputMode.PINYIN_26] ?: EngineSchemas.PINYIN_26
        ChineseScheme.XIAOHE_SHUANGPIN ->
            chineseSchemeSchemaIds[ChineseScheme.XIAOHE_SHUANGPIN]
                ?: modeSchemaIds[InputMode.PINYIN_26]
                ?: EngineSchemas.PINYIN_26
    }
}

/**
 * Pure reducer enforcing DuoIME's orthogonal navigation invariants across [KeyboardPrefs],
 * [SessionState], and [InputConstraints].
 */
object KeyboardNavReducer {
    fun toggleLanguage(prefs: KeyboardPrefs, session: SessionState, constraints: InputConstraints): KeyboardNavUpdate {
        if (!constraints.allowChinese) {
            return KeyboardNavUpdate(prefs, session)
        }
        val currentLanguage = deriveKeyboardState(prefs, session, constraints).effectiveLanguage
        val targetLanguage = if (currentLanguage == Language.CHINESE) Language.ENGLISH else Language.CHINESE
        return setLanguage(prefs, session, constraints, targetLanguage)
    }

    fun setLanguage(
        prefs: KeyboardPrefs,
        session: SessionState,
        constraints: InputConstraints,
        language: Language
    ): KeyboardNavUpdate {
        if (!constraints.allowChinese && language == Language.CHINESE) {
            return KeyboardNavUpdate(prefs, session)
        }
        val nextPrefs = if (isUnconstrainedTextField(constraints)) {
            prefs.copy(language = language)
        } else {
            prefs
        }
        val basePanel = constraints.forcedPanel ?: KeyboardPanel.PRIMARY
        val nextSession = session.copy(
            panel = basePanel,
            panelLocked = false,
            drawerOpen = false,
            shiftState = ShiftState.OFF,
            languageOverride = language
        )
        return KeyboardNavUpdate(nextPrefs, nextSession)
    }

    fun selectChineseScheme(
        prefs: KeyboardPrefs,
        session: SessionState,
        constraints: InputConstraints,
        scheme: ChineseScheme
    ): KeyboardNavUpdate {
        val nextPrefs = if (constraints.allowChinese && isUnconstrainedTextField(constraints)) {
            prefs.copy(chineseScheme = scheme, language = Language.CHINESE)
        } else {
            prefs.copy(chineseScheme = scheme)
        }
        val basePanel = constraints.forcedPanel ?: KeyboardPanel.PRIMARY
        val nextSession = if (constraints.allowChinese) {
            session.copy(
                panel = basePanel,
                panelLocked = false,
                drawerOpen = false,
                shiftState = ShiftState.OFF,
                languageOverride = Language.CHINESE
            )
        } else {
            session.copy(drawerOpen = false)
        }
        return KeyboardNavUpdate(nextPrefs, nextSession)
    }

    fun cycleChineseScheme(
        prefs: KeyboardPrefs,
        session: SessionState,
        constraints: InputConstraints
    ): KeyboardNavUpdate {
        val entries = ChineseScheme.entries
        val nextScheme = entries[(prefs.chineseScheme.ordinal + 1) % entries.size]
        return selectChineseScheme(prefs, session, constraints, nextScheme)
    }

    fun openPanel(
        prefs: KeyboardPrefs,
        session: SessionState,
        constraints: InputConstraints,
        targetPanel: KeyboardPanel,
        tab: SymbolTabId? = null
    ): KeyboardNavUpdate {
        val basePanel = constraints.forcedPanel ?: KeyboardPanel.PRIMARY
        val currentPanel = deriveKeyboardState(prefs, session, constraints).panel
        if (targetPanel == KeyboardPanel.PRIMARY || currentPanel == targetPanel) {
            return KeyboardNavUpdate(
                prefs = prefs,
                session = session.copy(
                    panel = basePanel,
                    panelLocked = false,
                    drawerOpen = false
                )
            )
        }
        val nextTab = when {
            tab != null -> tab
            targetPanel == KeyboardPanel.SYMBOL -> constraints.defaultSymbolTab
            else -> session.symbolTab
        }
        return KeyboardNavUpdate(
            prefs = prefs,
            session = session.copy(
                panel = targetPanel,
                panelLocked = targetPanel == KeyboardPanel.NUMBER,
                symbolTab = nextTab,
                drawerOpen = false
            )
        )
    }

    fun closePanel(prefs: KeyboardPrefs, session: SessionState, constraints: InputConstraints): KeyboardNavUpdate {
        val basePanel = constraints.forcedPanel ?: KeyboardPanel.PRIMARY
        return KeyboardNavUpdate(
            prefs = prefs,
            session = session.copy(
                panel = basePanel,
                panelLocked = false,
                drawerOpen = false
            )
        )
    }

    fun togglePanelLock(prefs: KeyboardPrefs, session: SessionState, constraints: InputConstraints): KeyboardNavUpdate {
        val currentPanel = deriveKeyboardState(prefs, session, constraints).panel
        if (currentPanel == KeyboardPanel.PRIMARY) {
            return KeyboardNavUpdate(prefs, session.copy(panelLocked = false))
        }
        return KeyboardNavUpdate(prefs, session.copy(panelLocked = !session.panelLocked))
    }

    fun selectSymbolTab(prefs: KeyboardPrefs, session: SessionState, tab: SymbolTabId): KeyboardNavUpdate =
        KeyboardNavUpdate(prefs, session.copy(symbolTab = tab))

    fun toggleDrawer(prefs: KeyboardPrefs, session: SessionState): KeyboardNavUpdate =
        KeyboardNavUpdate(prefs, session.copy(drawerOpen = !session.drawerOpen))

    fun toggleShift(prefs: KeyboardPrefs, session: SessionState): KeyboardNavUpdate {
        val nextShift = if (session.shiftState == ShiftState.OFF) ShiftState.ONCE else ShiftState.OFF
        return KeyboardNavUpdate(prefs, session.copy(shiftState = nextShift))
    }

    fun consumeOneShotShift(prefs: KeyboardPrefs, session: SessionState): KeyboardNavUpdate =
        if (session.shiftState == ShiftState.ONCE) {
            KeyboardNavUpdate(prefs, session.copy(shiftState = ShiftState.OFF))
        } else {
            KeyboardNavUpdate(prefs, session)
        }

    fun enterEditor(
        prefs: KeyboardPrefs,
        session: SessionState,
        constraints: InputConstraints,
        rememberedLanguage: Language?,
        restarting: Boolean
    ): KeyboardNavUpdate {
        if (restarting) {
            val clampedPanel = when {
                constraints.forcedPanel == null -> session.panel
                session.panel == KeyboardPanel.SYMBOL -> KeyboardPanel.SYMBOL
                else -> constraints.forcedPanel
            }
            val clampedSession = session.copy(
                panel = clampedPanel,
                panelLocked = if (clampedPanel == KeyboardPanel.PRIMARY) false else session.panelLocked,
                languageOverride = if (!constraints.allowChinese) Language.ENGLISH else session.languageOverride
            )
            return KeyboardNavUpdate(prefs, clampedSession)
        }
        val languageOverride = if (!constraints.allowChinese) {
            Language.ENGLISH
        } else {
            constraints.initialLanguage ?: rememberedLanguage
        }
        return KeyboardNavUpdate(
            prefs = prefs,
            session = SessionState(
                panel = KeyboardPanel.PRIMARY,
                panelLocked = false,
                symbolTab = constraints.defaultSymbolTab,
                shiftState = ShiftState.OFF,
                languageOverride = languageOverride,
                drawerOpen = false
            )
        )
    }

    private fun isUnconstrainedTextField(constraints: InputConstraints): Boolean = constraints.kind == FieldKind.TEXT &&
        constraints.allowChinese &&
        constraints.initialLanguage == null &&
        constraints.forcedPanel == null &&
        !constraints.noLearning
}

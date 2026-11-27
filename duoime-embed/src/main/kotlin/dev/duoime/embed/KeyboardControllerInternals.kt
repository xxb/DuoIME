// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.embed

import dev.duoime.core.engine.EngineKeyEvent
import dev.duoime.core.engine.EngineSchemas
import dev.duoime.core.engine.InputEngine
import dev.duoime.core.engine.InputSink
import dev.duoime.core.engine.PassThroughEngine
import dev.duoime.core.layout.BuiltInLayouts
import dev.duoime.core.layout.KeyCodes
import dev.duoime.core.layout.LayoutSpec
import dev.duoime.core.model.ChineseScheme
import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.DerivedKeyboardState
import dev.duoime.core.model.EditorContext
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.core.model.EnterBehavior
import dev.duoime.core.model.FieldKind
import dev.duoime.core.model.InputConstraints
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.KeyProcessResult
import dev.duoime.core.model.KeyboardPanel
import dev.duoime.core.model.KeyboardPrefs
import dev.duoime.core.model.LangKeyPlacement
import dev.duoime.core.model.Language
import dev.duoime.core.model.SymbolTabId
import dev.duoime.core.model.ToolbarMode
import dev.duoime.core.model.resolveEffectiveLangKeyPlacement
import dev.duoime.core.protocol.ExtensionToolbarButton
import kotlinx.coroutines.CancellationException

internal sealed interface NavCommand {
    data object ToggleLanguage : NavCommand

    data class SetLanguage(val language: Language) : NavCommand

    data class SelectChineseScheme(val scheme: ChineseScheme) : NavCommand

    data object CycleChineseScheme : NavCommand

    data class SetVariant(val variant: ChineseVariant) : NavCommand

    data class OpenPanel(val panel: KeyboardPanel, val tab: SymbolTabId?) : NavCommand

    data object ClosePanel : NavCommand

    data object TogglePanelLock : NavCommand

    data object ToggleDrawer : NavCommand

    data class SelectSymbolTab(val tab: SymbolTabId) : NavCommand

    data class SetNumberRow(val enabled: Boolean) : NavCommand
}

internal sealed interface ControllerEvent {
    data class Action(val action: KeyAction) : ControllerEvent

    data class Nav(val command: NavCommand) : ControllerEvent

    data class SelectCandidate(val indexOnPage: Int) : ControllerEvent

    data class SelectWindowCandidate(val globalIndex: Int) : ControllerEvent

    data object LoadMoreCandidates : ControllerEvent

    data class SelectSyllable(val index: Int) : ControllerEvent

    data class UnlockSyllablesFrom(val fromIndex: Int) : ControllerEvent

    data class Page(val forward: Boolean) : ControllerEvent

    data class ExtensionButton(val button: ExtensionToolbarButton) : ControllerEvent

    data class EditorChanged(val context: EditorContext, val restarting: Boolean) : ControllerEvent

    data class ConstraintsChanged(val constraints: InputConstraints, val restarting: Boolean) : ControllerEvent

    data class PrefsChanged(val prefs: KeyboardPrefs) : ControllerEvent

    data class HostStateChanged(
        val isOverlay: Boolean? = null,
        val memoryKeyUpdate: Boolean = false,
        val languageMemoryKey: String? = null
    ) : ControllerEvent

    data class HostPolicyChanged(
        val langKeyPlacement: LangKeyPlacement,
        val initialSymbolTab: SymbolTabId,
        val toolbarMode: ToolbarMode = ToolbarMode.FULL
    ) : ControllerEvent

    data object DismissEngineError : ControllerEvent

    data object Reset : ControllerEvent
}

internal data class EngineBootstrapResult(
    val engine: InputEngine,
    val schemaIds: Map<InputMode, String>,
    val issue: EngineIssue?,
    val forcedPlainEnglish: Boolean
)

@Suppress("TooGenericExceptionCaught")
internal suspend fun bootstrapControllerEngine(
    configuredSchemas: Map<InputMode, String>,
    engineFactory: suspend () -> InputEngine,
    fallbackEngineFactory: (suspend () -> InputEngine)?
): EngineBootstrapResult {
    val primaryError = try {
        return EngineBootstrapResult(
            engine = engineFactory(),
            schemaIds = configuredSchemas,
            issue = null,
            forcedPlainEnglish = false
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        error
    }

    val primaryTag = errorClassTag(primaryError)
    if (fallbackEngineFactory != null && fallbackEngineFactory !== engineFactory) {
        try {
            val fallbackEngine = fallbackEngineFactory()
            return EngineBootstrapResult(
                engine = fallbackEngine,
                schemaIds = EngineSchemas.DEFAULT_MODE_MAP,
                issue = EngineIssue(EngineIssueKind.PERSISTENT, "Fallback: $primaryTag"),
                forcedPlainEnglish = false
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fallbackError: Throwable) {
            return EngineBootstrapResult(
                engine = PassThroughEngine(),
                schemaIds = configuredSchemas,
                issue = EngineIssue(
                    EngineIssueKind.PERSISTENT,
                    "Plain: $primaryTag/${errorClassTag(fallbackError)}"
                ),
                forcedPlainEnglish = true
            )
        }
    }

    return EngineBootstrapResult(
        engine = PassThroughEngine(),
        schemaIds = configuredSchemas,
        issue = EngineIssue(EngineIssueKind.PERSISTENT, "Plain: $primaryTag"),
        forcedPlainEnglish = true
    )
}

internal fun errorClassTag(error: Throwable): String = error::class.simpleName ?: "Throwable"

internal fun selectEngineSchemaWithFallback(
    engine: InputEngine,
    derived: DerivedKeyboardState,
    activeSchemaIds: Map<InputMode, String>
) {
    val selected = engine.selectSchema(derived.activeSchemaId)
    if (!selected &&
        derived.effectiveLanguage == Language.CHINESE &&
        derived.effectiveChineseScheme == ChineseScheme.XIAOHE_SHUANGPIN
    ) {
        val fallback = activeSchemaIds[InputMode.PINYIN_26] ?: EngineSchemas.PINYIN_26
        if (fallback != derived.activeSchemaId) {
            engine.selectSchema(fallback)
        }
    }
}

internal fun applyHostPolicyDefaults(
    base: InputConstraints,
    editorContext: EditorContext,
    hostLangKeyPlacement: LangKeyPlacement,
    hostInitialSymbolTab: SymbolTabId,
    hostToolbarMode: ToolbarMode = ToolbarMode.FULL
): InputConstraints {
    val requestedPlacement = base.langKeyPlacementOverride ?: hostLangKeyPlacement
    val resolvedPlacement = resolveEffectiveLangKeyPlacement(requestedPlacement, hostToolbarMode)
    val hasExplicitEditorTab = base.kind == FieldKind.TERMINAL ||
        base.defaultSymbolTab != SymbolTabId.COMMON ||
        editorContext.extension?.defaultSymbolTab != null
    val resolvedTab = if (hasExplicitEditorTab) base.defaultSymbolTab else hostInitialSymbolTab
    return base.copy(
        langKeyPlacementOverride = resolvedPlacement,
        defaultSymbolTab = resolvedTab
    )
}

internal fun resolveKeyboardLayout(
    derived: DerivedKeyboardState,
    prefs: KeyboardPrefs,
    isComposing: Boolean = false
): LayoutSpec = when (derived.panel) {
    KeyboardPanel.SYMBOL -> BuiltInLayouts.symbolsV0(mode = derived.activeMode)
    KeyboardPanel.NUMBER -> BuiltInLayouts.numberPad(fieldKind = derived.fieldKind, mode = derived.activeMode)
    KeyboardPanel.PRIMARY -> when (derived.activeMode) {
        InputMode.PINYIN_26 -> BuiltInLayouts.qwerty(
            includeNumberRow = derived.includeNumberRow,
            shifted = derived.shifted,
            englishMode = false,
            variant = prefs.variant,
            fieldKind = derived.fieldKind,
            canToggleLanguage = derived.canToggleLanguage,
            langKeyPlacement = derived.langKeyPlacement ?: LangKeyPlacement.GRID
        )
        InputMode.ENGLISH -> BuiltInLayouts.qwerty(
            includeNumberRow = derived.includeNumberRow,
            shifted = derived.shifted,
            englishMode = true,
            variant = prefs.variant,
            fieldKind = derived.fieldKind,
            canToggleLanguage = derived.canToggleLanguage,
            langKeyPlacement = derived.langKeyPlacement ?: LangKeyPlacement.GRID
        )
        InputMode.PINYIN_T9 -> BuiltInLayouts.t9(
            isComposing = isComposing,
            variant = prefs.variant,
            canToggleLanguage = derived.canToggleLanguage,
            langKeyPlacement = derived.langKeyPlacement ?: LangKeyPlacement.GRID,
            language = derived.effectiveLanguage
        )
    }
}

/**
 * Encapsulates engine-to-sink text/composing dispatch ordering (`commitText` strictly before
 * `setComposing`), pass-through handling, raw-Latin commits, and terminal Enter safety.
 */
internal class EngineSinkDispatcher(
    private val withSink: suspend ((InputSink) -> Unit) -> Unit
) {
    suspend fun syncComposingOnly(snapshot: EngineSnapshot) {
        withSink { sink ->
            if (sink.capabilities.composing) {
                sink.setComposing(snapshot.composingText.takeIf { snapshot.isComposing })
            }
        }
    }

    suspend fun applyEngineResult(result: KeyProcessResult, snapshot: EngineSnapshot) {
        withSink { sink ->
            if (result is KeyProcessResult.CommitText) {
                sink.commitText(result.text)
            }
            if (sink.capabilities.composing) {
                sink.setComposing(snapshot.composingText.takeIf { snapshot.isComposing })
            }
        }
    }

    suspend fun flushComposition(engine: InputEngine) {
        if (!engine.state.value.isComposing) return
        val spaceResult = engine.onKey(EngineKeyEvent(KeyCodes.KEYSYM_SPACE))
        if (spaceResult is KeyProcessResult.CommitText) {
            applyEngineResult(spaceResult, engine.state.value)
        }
        val after = engine.state.value
        if (after.isComposing) {
            val rawComposing = after.composingText
            engine.reset()
            withSink { sink ->
                if (rawComposing.isNotEmpty()) {
                    sink.commitText(rawComposing)
                }
                if (sink.capabilities.composing) {
                    sink.setComposing(null)
                }
            }
        } else if (spaceResult !is KeyProcessResult.CommitText) {
            syncComposingOnly(after)
        }
    }

    suspend fun commitRawComposingLatin(engine: InputEngine) {
        val snap = engine.state.value
        if (!snap.isComposing) return
        val rawLatin = if (snap.mode != InputMode.PINYIN_T9) {
            val enterResult = engine.onKey(EngineKeyEvent(KeyCodes.KEYSYM_RETURN))
            if (enterResult is KeyProcessResult.CommitText) {
                enterResult.text
            } else {
                snap.composingText.ifEmpty { snap.preedit }.replace("'", "")
            }
        } else {
            snap.composingText.ifEmpty { snap.preedit }.replace("'", "")
        }
        if (engine.state.value.isComposing) {
            engine.reset()
        }
        withSink { sink ->
            if (rawLatin.isNotEmpty()) {
                sink.commitText(rawLatin)
            }
            if (sink.capabilities.composing) {
                sink.setComposing(null)
            }
        }
    }

    suspend fun dispatchEngineKey(engine: InputEngine, event: EngineKeyEvent) {
        val wasComposing = engine.state.value.isComposing
        val isChord = (event.mask and CHORD_MODIFIER_MASK) != 0
        val result = engine.onKey(event)
        when (result) {
            is KeyProcessResult.PassThrough -> applyPassThrough(engine, wasComposing, isChord) { sink ->
                if (!isChord) {
                    KeyCodes.charForKeysym(event.keysym)?.let { ch ->
                        if (!Character.isISOControl(ch)) {
                            sink.commitText(ch.toString())
                        }
                    }
                }
            }
            else -> applyEngineResult(result, engine.state.value)
        }
    }

    suspend fun dispatchBackspace(engine: InputEngine) {
        val wasComposing = engine.state.value.isComposing
        val result = engine.onKey(EngineKeyEvent(KeyCodes.KEYSYM_BACKSPACE))
        when (result) {
            is KeyProcessResult.PassThrough -> applyPassThrough(engine, wasComposing) { sink ->
                sink.deleteBackward(1)
            }
            else -> applyEngineResult(result, engine.state.value)
        }
    }

    suspend fun dispatchSpace(engine: InputEngine) {
        val wasComposing = engine.state.value.isComposing
        val result = engine.onKey(EngineKeyEvent(KeyCodes.KEYSYM_SPACE))
        when (result) {
            is KeyProcessResult.PassThrough -> applyPassThrough(engine, wasComposing) { sink ->
                sink.commitText(" ")
            }
            else -> applyEngineResult(result, engine.state.value)
        }
    }

    suspend fun dispatchEnter(engine: InputEngine, constraints: InputConstraints, editorContext: EditorContext) {
        val snapBefore = engine.state.value
        val wasComposing = snapBefore.isComposing
        if (wasComposing && constraints.kind == FieldKind.TERMINAL) {
            if (snapBefore.mode == InputMode.PINYIN_T9) {
                commitRawComposingLatin(engine)
            } else {
                val result = engine.onKey(EngineKeyEvent(KeyCodes.KEYSYM_RETURN))
                if (result is KeyProcessResult.PassThrough) {
                    commitRawComposingLatin(engine)
                } else {
                    applyEngineResult(result, engine.state.value)
                }
            }
            return
        }

        val result = engine.onKey(EngineKeyEvent(KeyCodes.KEYSYM_RETURN))
        when (result) {
            is KeyProcessResult.PassThrough -> applyPassThrough(engine, wasComposing) { sink ->
                if (sink.capabilities.rawKeys || constraints.kind == FieldKind.TERMINAL) {
                    sink.sendKey(KeyCodes.KEYCODE_ENTER)
                } else {
                    when (val behavior = editorContext.resolveEnterBehavior()) {
                        EnterBehavior.Newline -> sink.sendKey(KeyCodes.KEYCODE_ENTER)
                        is EnterBehavior.EditorAction -> sink.performEditorAction(behavior.actionId)
                    }
                }
            }
            else -> applyEngineResult(result, engine.state.value)
        }
    }

    private suspend fun applyPassThrough(
        engine: InputEngine,
        wasComposing: Boolean,
        isChord: Boolean = false,
        defaultAction: (InputSink) -> Unit
    ) {
        val snap = engine.state.value
        if (snap.isComposing) {
            val rawComposing = snap.composingText
            engine.reset()
            withSink { sink ->
                if (!isChord && rawComposing.isNotEmpty()) {
                    sink.commitText(rawComposing)
                }
                if (sink.capabilities.composing) {
                    sink.setComposing(null)
                }
                defaultAction(sink)
            }
        } else {
            withSink { sink ->
                if (wasComposing && sink.capabilities.composing) {
                    sink.setComposing(null)
                }
                defaultAction(sink)
            }
        }
    }

    companion object {
        private const val CHORD_MODIFIER_MASK: Int =
            KeyCodes.MASK_CONTROL or KeyCodes.MASK_ALT or KeyCodes.MASK_SUPER
    }
}

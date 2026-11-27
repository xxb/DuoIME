// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.embed

import androidx.compose.runtime.Stable
import dev.duoime.core.engine.EngineKeyEvent
import dev.duoime.core.engine.EngineOptions
import dev.duoime.core.engine.InputEngine
import dev.duoime.core.engine.InputSink
import dev.duoime.core.model.ChineseScheme
import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.DerivedKeyboardState
import dev.duoime.core.model.EditorContext
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.InputConstraints
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.KeyProcessResult
import dev.duoime.core.model.KeyboardNavReducer
import dev.duoime.core.model.KeyboardNavUpdate
import dev.duoime.core.model.KeyboardPanel
import dev.duoime.core.model.KeyboardPrefs
import dev.duoime.core.model.LangKeyPlacement
import dev.duoime.core.model.Language
import dev.duoime.core.model.SessionState
import dev.duoime.core.model.SymbolTabId
import dev.duoime.core.model.ToolbarMode
import dev.duoime.core.model.deriveKeyboardState
import dev.duoime.core.model.toInputConstraints
import dev.duoime.core.policy.DisplayPlacementPolicy
import dev.duoime.core.protocol.ExtensionActionType
import dev.duoime.core.protocol.ExtensionToolbarButton
import dev.duoime.engine.lite.LiteInputEngine
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hoisted, single-actor keyboard controller shared by `:app` (`InputMethodService`) and external
 * Compose embedders.
 *
 * Manages DuoIME's 3-layer orthogonal state ([KeyboardPrefs], [SessionState], [InputConstraints])
 * derived cleanly via [deriveKeyboardState], serializes every input event through a single FIFO
 * actor channel, recovers safely from per-event engine failures, and enforces strict sink ordering
 * (`commitText` before `setComposing`).
 */
@Stable
class KeyboardController(
    scope: CoroutineScope,
    private val config: KeyboardControllerConfig = KeyboardControllerConfig(),
    private val engineFactory: suspend () -> InputEngine = DEFAULT_ENGINE_FACTORY,
    private val hostCallbacks: KeyboardHostCallbacks = KeyboardHostCallbacks(),
    private val fallbackEngineFactory: (suspend () -> InputEngine)? = DEFAULT_ENGINE_FACTORY
) {
    private val activeSinkRef = AtomicReference<InputSink?>(null)
    private val eventChannel = Channel<ControllerEvent>(Channel.UNLIMITED)
    private val sinkDispatcher = EngineSinkDispatcher { block -> withSink(block) }
    private val actorJob: Job

    private var activeSchemaIds: Map<InputMode, String> = config.modeSchemaIds
    private var activeChineseSchemeSchemaIds: Map<ChineseScheme, String> = config.chineseSchemeSchemaIds
    private var engineIssue: EngineIssue? = null
    private var isUsingFallbackEngine: Boolean = false
    private var prefs: KeyboardPrefs = config.initialPrefs
    private var session: SessionState = SessionState()
    private var constraints: InputConstraints = InputConstraints()
    private var hostLangKeyPlacement: LangKeyPlacement = LangKeyPlacement.TOOLBAR
    private var hostInitialSymbolTab: SymbolTabId = SymbolTabId.COMMON
    private var hostToolbarMode: ToolbarMode = ToolbarMode.FULL
    private var languageMemoryKey: String? = config.languageMemoryKey
    private val languageMemory = LanguageMemoryStore()
    private val candidateWindow = CandidateWindowTracker()
    private var lastSelectedSchemaId: String? = null
    private var heightFraction: Float = DisplayPlacementPolicy.clampHeightFraction(config.initialHeightFraction)
    private var isOverlayMode: Boolean = config.initialOverlayMode
    private var editorContext: EditorContext = EditorContext()

    private val _uiState = MutableStateFlow(buildUiState(isEngineReady = false, snapshot = EngineSnapshot.EMPTY))
    val uiState: StateFlow<KeyboardUiState> = _uiState.asStateFlow()

    init {
        actorJob = scope.launch(config.engineDispatcher) {
            var currentEngine: InputEngine? = null
            try {
                val boot = bootstrapControllerEngine(
                    configuredSchemas = activeSchemaIds,
                    configuredChineseSchemeSchemas = activeChineseSchemeSchemaIds,
                    engineFactory = engineFactory,
                    fallbackEngineFactory = fallbackEngineFactory
                )
                var activeEngine = applyBootstrappedEngine(boot)
                currentEngine = activeEngine
                for (event in eventChannel) {
                    if (event is ControllerEvent.ReloadEngine) {
                        activeEngine = handleReloadEngine(activeEngine, event)
                        currentEngine = activeEngine
                    } else {
                        processEventSafely(activeEngine, event)
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    closeEngineSafely(currentEngine)
                }
            }
        }
    }

    fun attachSink(sink: InputSink) = activeSinkRef.set(sink)

    fun detachSink(sink: InputSink) {
        activeSinkRef.compareAndSet(sink, null)
    }

    fun onKeyPressDown(feedbackKey: FeedbackKey) = hostCallbacks.onFeedbackKey(feedbackKey)

    fun onKeyRepeat(feedbackKey: FeedbackKey) = hostCallbacks.onRepeatFeedbackKey(feedbackKey)

    fun dispatchKeyAction(action: KeyAction) {
        eventChannel.trySend(ControllerEvent.Action(action))
    }

    fun toggleLanguage() = sendNav(NavCommand.ToggleLanguage)

    fun setLanguage(language: Language) = sendNav(NavCommand.SetLanguage(language))

    fun setChineseScheme(scheme: ChineseScheme) = sendNav(NavCommand.SelectChineseScheme(scheme))

    fun cycleChineseScheme() = sendNav(NavCommand.CycleChineseScheme)

    fun setVariant(variant: ChineseVariant) = sendNav(NavCommand.SetVariant(variant))

    fun openPanel(panel: KeyboardPanel, tab: SymbolTabId? = null) = sendNav(NavCommand.OpenPanel(panel, tab))

    fun closePanel() = sendNav(NavCommand.ClosePanel)

    fun togglePanelLock() = sendNav(NavCommand.TogglePanelLock)

    fun toggleDrawer() = sendNav(NavCommand.ToggleDrawer)

    fun updateConstraints(constraints: InputConstraints, restarting: Boolean = false) {
        eventChannel.trySend(ControllerEvent.ConstraintsChanged(constraints, restarting))
    }

    fun updatePrefs(prefs: KeyboardPrefs) {
        eventChannel.trySend(ControllerEvent.PrefsChanged(prefs))
    }

    fun setLanguageMemoryKey(key: String?) {
        eventChannel.trySend(ControllerEvent.HostStateChanged(memoryKeyUpdate = true, languageMemoryKey = key))
    }

    fun selectCandidate(indexOnPage: Int) {
        eventChannel.trySend(ControllerEvent.SelectCandidate(indexOnPage))
    }

    fun selectWindowCandidate(globalIndex: Int) {
        eventChannel.trySend(ControllerEvent.SelectWindowCandidate(globalIndex))
    }

    fun loadMoreCandidates() {
        eventChannel.trySend(ControllerEvent.LoadMoreCandidates)
    }

    fun selectSyllable(index: Int) {
        eventChannel.trySend(ControllerEvent.SelectSyllable(index))
    }

    fun unlockSyllablesFrom(fromIndex: Int) {
        eventChannel.trySend(ControllerEvent.UnlockSyllablesFrom(fromIndex))
    }

    fun pageCandidates(forward: Boolean) {
        eventChannel.trySend(ControllerEvent.Page(forward))
    }

    fun dispatchExtensionButton(button: ExtensionToolbarButton) {
        eventChannel.trySend(ControllerEvent.ExtensionButton(button))
    }

    fun updateEditorContext(context: EditorContext, restarting: Boolean = false) {
        eventChannel.trySend(ControllerEvent.EditorChanged(context, restarting))
    }

    fun updateOverlayMode(isOverlay: Boolean) {
        eventChannel.trySend(ControllerEvent.HostStateChanged(isOverlay = isOverlay))
    }

    fun updateHostPolicy(
        langKeyPlacement: LangKeyPlacement = LangKeyPlacement.TOOLBAR,
        initialSymbolTab: SymbolTabId = SymbolTabId.COMMON,
        toolbarMode: ToolbarMode = ToolbarMode.FULL
    ) {
        eventChannel.trySend(ControllerEvent.HostPolicyChanged(langKeyPlacement, initialSymbolTab, toolbarMode))
    }

    fun setIncludeNumberRow(enabled: Boolean) = sendNav(NavCommand.SetNumberRow(enabled))

    fun dismissEngineError() {
        eventChannel.trySend(ControllerEvent.DismissEngineError)
    }

    fun reset() {
        eventChannel.trySend(ControllerEvent.Reset)
    }

    fun reloadEngine(
        engineFactory: suspend () -> InputEngine,
        fallbackEngineFactory: (suspend () -> InputEngine)? = this.fallbackEngineFactory,
        modeSchemaIds: Map<InputMode, String> = config.modeSchemaIds,
        chineseSchemeSchemaIds: Map<ChineseScheme, String> = config.chineseSchemeSchemaIds
    ) {
        eventChannel.trySend(
            ControllerEvent.ReloadEngine(
                engineFactory = engineFactory,
                fallbackEngineFactory = fallbackEngineFactory,
                modeSchemaIds = modeSchemaIds,
                chineseSchemeSchemaIds = chineseSchemeSchemaIds
            )
        )
    }

    fun close() {
        eventChannel.close()
        actorJob.cancel()
    }

    private fun applyBootstrappedEngine(boot: EngineBootstrapResult): InputEngine {
        activeSchemaIds = boot.schemaIds
        activeChineseSchemeSchemaIds = boot.chineseSchemeSchemaIds
        engineIssue = boot.issue
        isUsingFallbackEngine = boot.usedFallback
        if (boot.forcedPlainEnglish) {
            prefs = prefs.copy(language = Language.ENGLISH)
            session = session.copy(languageOverride = Language.ENGLISH)
        }
        val engine = boot.engine
        val initialDerived = currentDerived()
        if (initialDerived.activeSchemaId != engine.state.value.schemaId) {
            selectEngineSchemaWithFallback(engine, initialDerived, activeSchemaIds)
        }
        lastSelectedSchemaId = initialDerived.activeSchemaId
        engine.setOption(EngineOptions.TRADITIONALIZATION, prefs.variant == ChineseVariant.TRADITIONAL)
        applyEnginePrivacyOptions(engine, effectiveConstraints(), editorContext)
        candidateWindow.reset()
        publishState(engine)
        return engine
    }

    private suspend fun handleReloadEngine(
        previousEngine: InputEngine,
        event: ControllerEvent.ReloadEngine
    ): InputEngine {
        resetEngineSafely(previousEngine)
        sinkDispatcher.syncComposingOnly(EngineSnapshot.EMPTY)
        candidateWindow.reset()
        val boot = bootstrapControllerEngine(
            configuredSchemas = event.modeSchemaIds,
            configuredChineseSchemeSchemas = event.chineseSchemeSchemaIds,
            engineFactory = event.engineFactory,
            fallbackEngineFactory = event.fallbackEngineFactory
        )
        val nextEngine = applyBootstrappedEngine(boot)
        if (nextEngine !== previousEngine) {
            withContext(NonCancellable) {
                closeEngineSafely(previousEngine)
            }
        }
        return nextEngine
    }

    private fun sendNav(command: NavCommand) {
        eventChannel.trySend(ControllerEvent.Nav(command))
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun processEventSafely(engine: InputEngine, event: ControllerEvent) {
        try {
            processEvent(engine, event)
            if (engineIssue?.kind == EngineIssueKind.TRANSIENT && isEngineInputEvent(event)) {
                engineIssue = null
                publishState(engine)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            recoverFromEventFailure(engine, error)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun recoverFromEventFailure(engine: InputEngine, error: Throwable) {
        if (engineIssue?.kind != EngineIssueKind.PERSISTENT) {
            engineIssue = EngineIssue(EngineIssueKind.TRANSIENT, "Recovered: ${errorClassTag(error)}")
        }
        resetEngineSafely(engine)
        try {
            sinkDispatcher.syncComposingOnly(EngineSnapshot.EMPTY)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Best-effort composing cleanup during recovery.
        }
        publishState(engine)
    }

    private suspend fun processEvent(engine: InputEngine, event: ControllerEvent) {
        when (event) {
            is ControllerEvent.Action -> handleKeyAction(engine, event.action)
            is ControllerEvent.Nav -> handleNavCommand(engine, event.command)
            is ControllerEvent.SelectCandidate -> handleSelectCandidate(engine, event.indexOnPage)
            is ControllerEvent.SelectWindowCandidate -> handleSelectWindowCandidate(engine, event.globalIndex)
            is ControllerEvent.LoadMoreCandidates -> {
                val prevHasMore = candidateWindow.hasMore
                if (candidateWindow.loadMore(engine) || candidateWindow.hasMore != prevHasMore) {
                    publishState(engine)
                }
            }
            is ControllerEvent.SelectSyllable -> handleSelectSyllable(engine, event.index)
            is ControllerEvent.UnlockSyllablesFrom -> handleUnlockSyllablesFrom(engine, event.fromIndex)
            is ControllerEvent.Page -> if (engine.page(event.forward)) publishState(engine)
            is ControllerEvent.ExtensionButton -> handleExtensionButton(engine, event.button)
            else -> processLifecycleEvent(engine, event)
        }
    }

    private suspend fun processLifecycleEvent(engine: InputEngine, event: ControllerEvent) {
        when (event) {
            is ControllerEvent.EditorChanged -> {
                editorContext = event.context
                applyConstraintsTransition(engine, event.context.toInputConstraints(), event.restarting)
            }
            is ControllerEvent.ConstraintsChanged ->
                applyConstraintsTransition(engine, event.constraints, event.restarting)
            is ControllerEvent.PrefsChanged -> handlePrefsChanged(engine, event.prefs)
            is ControllerEvent.HostStateChanged -> handleHostStateChanged(engine, event)
            is ControllerEvent.HostPolicyChanged -> handleHostPolicyChanged(engine, event)
            is ControllerEvent.DismissEngineError -> {
                engineIssue = null
                publishState(engine)
            }
            is ControllerEvent.Reset -> {
                engine.reset()
                sinkDispatcher.syncComposingOnly(engine.state.value)
                publishState(engine)
            }
            else -> Unit
        }
    }

    private suspend fun handleSelectSyllable(engine: InputEngine, index: Int) {
        if (engine.selectSyllable(index)) {
            sinkDispatcher.syncComposingOnly(engine.state.value)
            publishState(engine)
        }
    }

    private suspend fun handleUnlockSyllablesFrom(engine: InputEngine, fromIndex: Int) {
        if (engine.unlockSyllablesFrom(fromIndex)) {
            sinkDispatcher.syncComposingOnly(engine.state.value)
            publishState(engine)
        }
    }

    private fun handleHostStateChanged(engine: InputEngine, event: ControllerEvent.HostStateChanged) {
        if (event.isOverlay != null) {
            isOverlayMode = event.isOverlay
        }
        if (event.memoryKeyUpdate) {
            languageMemoryKey = event.languageMemoryKey
        }
        publishState(engine)
    }

    private fun handleHostPolicyChanged(engine: InputEngine, event: ControllerEvent.HostPolicyChanged) {
        val prevTab = hostInitialSymbolTab
        hostLangKeyPlacement = event.langKeyPlacement
        hostInitialSymbolTab = event.initialSymbolTab
        hostToolbarMode = event.toolbarMode
        if (session.panel != KeyboardPanel.SYMBOL &&
            session.symbolTab == prevTab &&
            editorContext.extension?.defaultSymbolTab == null
        ) {
            session = session.copy(symbolTab = effectiveConstraints().defaultSymbolTab)
        }
        publishState(engine)
    }

    private suspend fun applyConstraintsTransition(
        engine: InputEngine,
        newConstraints: InputConstraints,
        restarting: Boolean
    ) {
        val before = currentDerived()
        constraints = newConstraints
        val effective = effectiveConstraints(newConstraints)
        val remembered = if (!restarting && LanguageMemoryStore.isEligibleForMemory(effective, editorContext)) {
            languageMemory.get(resolveMemoryKey(editorContext))
        } else {
            null
        }
        val updated = KeyboardNavReducer.enterEditor(prefs, session, effective, remembered, restarting)
        prefs = updated.prefs
        session = updated.session
        val after = currentDerived()

        applyEnginePrivacyOptions(engine, effective, editorContext)
        if (!restarting) {
            engine.reset()
            syncEngineSchemaIfChanged(engine, after)
            sinkDispatcher.syncComposingOnly(engine.state.value)
        } else {
            syncSchemaWithComposingCleanup(engine, before, after)
        }
        publishState(engine)
    }

    private suspend fun handlePrefsChanged(engine: InputEngine, updatedPrefs: KeyboardPrefs) {
        val before = currentDerived()
        val previousVariant = prefs.variant
        prefs = updatedPrefs
        val after = currentDerived()
        if (updatedPrefs.variant != previousVariant || engine.state.value.variant != updatedPrefs.variant) {
            engine.setOption(EngineOptions.TRADITIONALIZATION, prefs.variant == ChineseVariant.TRADITIONAL)
        }
        syncSchemaWithComposingCleanup(engine, before, after)
        publishState(engine)
    }

    private suspend fun handleNavCommand(engine: InputEngine, command: NavCommand) {
        when (command) {
            is NavCommand.ToggleLanguage -> applyNavTransition(engine, recordMemory = true) { p, s, c ->
                KeyboardNavReducer.toggleLanguage(p, s, c)
            }
            is NavCommand.SetLanguage -> applyNavTransition(engine, recordMemory = true) { p, s, c ->
                KeyboardNavReducer.setLanguage(p, s, c, command.language)
            }
            is NavCommand.SelectChineseScheme -> applyNavTransition(engine, recordMemory = true) { p, s, c ->
                KeyboardNavReducer.selectChineseScheme(p, s, c, command.scheme)
            }
            is NavCommand.CycleChineseScheme -> applyNavTransition(engine, recordMemory = true) { p, s, c ->
                KeyboardNavReducer.cycleChineseScheme(p, s, c)
            }
            is NavCommand.SetVariant -> applyVariantChange(engine, command.variant)
            is NavCommand.OpenPanel -> applyNavTransition(engine, recordMemory = false) { p, s, c ->
                KeyboardNavReducer.openPanel(p, s, c, command.panel, command.tab)
            }
            is NavCommand.ClosePanel -> applyNavTransition(engine, recordMemory = false) { p, s, c ->
                KeyboardNavReducer.closePanel(p, s, c)
            }
            is NavCommand.TogglePanelLock -> applyNavTransition(engine, recordMemory = false) { p, s, c ->
                KeyboardNavReducer.togglePanelLock(p, s, c)
            }
            is NavCommand.ToggleDrawer -> applyNavTransition(engine, recordMemory = false) { p, s, _ ->
                KeyboardNavReducer.toggleDrawer(p, s)
            }
            is NavCommand.SelectSymbolTab -> applyNavTransition(engine, recordMemory = false) { p, s, _ ->
                KeyboardNavReducer.selectSymbolTab(p, s, command.tab)
            }
            is NavCommand.SetNumberRow -> {
                val previousPrefs = prefs
                prefs = prefs.copy(includeNumberRow = command.enabled)
                publishState(engine)
                notifyPrefsChangedIfMutated(previousPrefs)
            }
        }
    }

    private suspend fun applyNavTransition(
        engine: InputEngine,
        recordMemory: Boolean,
        transition: (KeyboardPrefs, SessionState, InputConstraints) -> KeyboardNavUpdate
    ) {
        val previousPrefs = prefs
        val before = currentDerived()
        val effective = effectiveConstraints()
        val updated = transition(prefs, session, effective)
        prefs = updated.prefs
        session = updated.session
        val after = currentDerived()

        syncSchemaWithComposingCleanup(engine, before, after)
        if (recordMemory && LanguageMemoryStore.isEligibleForMemory(effective, editorContext)) {
            languageMemory.put(resolveMemoryKey(editorContext), after.effectiveLanguage)
        }
        publishState(engine)
        notifyPrefsChangedIfMutated(previousPrefs)
    }

    private suspend fun syncSchemaWithComposingCleanup(
        engine: InputEngine,
        before: DerivedKeyboardState,
        after: DerivedKeyboardState
    ) {
        if (before.effectiveLanguage == Language.CHINESE && after.effectiveLanguage == Language.ENGLISH) {
            sinkDispatcher.commitRawComposingLatin(engine)
        } else if (before.activeSchemaId != after.activeSchemaId && engine.state.value.isComposing) {
            engine.reset()
            sinkDispatcher.syncComposingOnly(engine.state.value)
        }
        syncEngineSchemaIfChanged(engine, after)
    }

    private suspend fun applyVariantChange(engine: InputEngine, variant: ChineseVariant) {
        val previousPrefs = prefs
        prefs = prefs.copy(variant = variant)
        engine.setOption(EngineOptions.TRADITIONALIZATION, variant == ChineseVariant.TRADITIONAL)
        publishState(engine)
        notifyPrefsChangedIfMutated(previousPrefs)
    }

    private suspend fun notifyPrefsChangedIfMutated(previousPrefs: KeyboardPrefs) {
        if (prefs != previousPrefs) {
            val updated = prefs
            withContext(config.sinkDispatcher) {
                hostCallbacks.onPrefsChanged(updated)
            }
        }
    }

    private suspend fun handleKeyAction(engine: InputEngine, action: KeyAction) {
        when (action) {
            is KeyAction.Text -> handleDirectText(engine, action.text)
            is KeyAction.CommitPanelSymbol -> handleDirectText(engine, action.text, keepPanelOpen = action.keepOpen)
            is KeyAction.Code -> withSink { it.sendKey(action.keyCode, action.metaState) }
            is KeyAction.Engine -> {
                sinkDispatcher.dispatchEngineKey(engine, EngineKeyEvent(action.keysym, action.mask))
                publishState(engine)
            }
            is KeyAction.Custom -> withSink { it.sendHostCommand(action.id, "") }
            is KeyAction.Backspace -> {
                sinkDispatcher.dispatchBackspace(engine)
                publishState(engine)
            }
            is KeyAction.Enter -> {
                sinkDispatcher.dispatchEnter(engine, constraints, editorContext)
                publishState(engine)
            }
            is KeyAction.Space -> {
                sinkDispatcher.dispatchSpace(engine)
                publishState(engine)
            }
            is KeyAction.Shift -> applyNavTransition(engine, recordMemory = false) { p, s, _ ->
                KeyboardNavReducer.toggleShift(p, s)
            }
            is KeyAction.SelectSyllable -> handleSelectSyllable(engine, action.index)
            is KeyAction.UnlockSyllablesFrom -> handleUnlockSyllablesFrom(engine, action.fromIndex)
            else -> handleNavOrHostKeyAction(engine, action)
        }
    }

    private suspend fun handleNavOrHostKeyAction(engine: InputEngine, action: KeyAction) {
        when (action) {
            is KeyAction.SwitchMode -> handleLegacySwitchMode(engine, action.targetMode)
            is KeyAction.ToggleLanguage -> handleNavCommand(engine, NavCommand.ToggleLanguage)
            is KeyAction.SelectChineseScheme ->
                handleNavCommand(engine, NavCommand.SelectChineseScheme(action.scheme))
            is KeyAction.OpenPanel -> handleNavCommand(engine, NavCommand.OpenPanel(action.panel, action.tab))
            is KeyAction.ClosePanel -> handleNavCommand(engine, NavCommand.ClosePanel)
            is KeyAction.TogglePanelLock -> handleNavCommand(engine, NavCommand.TogglePanelLock)
            is KeyAction.ClearPreedit -> {
                engine.reset()
                sinkDispatcher.syncComposingOnly(engine.state.value)
                publishState(engine)
            }
            is KeyAction.ToggleDrawer -> handleNavCommand(engine, NavCommand.ToggleDrawer)
            is KeyAction.ToggleNumberRow ->
                handleNavCommand(engine, NavCommand.SetNumberRow(!prefs.includeNumberRow))
            is KeyAction.SelectSymbolTab -> handleNavCommand(engine, NavCommand.SelectSymbolTab(action.tab))
            is KeyAction.SwitchVariant -> {
                val nextVariant = if (prefs.variant == ChineseVariant.TRADITIONAL) {
                    ChineseVariant.SIMPLIFIED
                } else {
                    ChineseVariant.TRADITIONAL
                }
                applyVariantChange(engine, nextVariant)
            }
            else -> handleHostKeyAction(engine, action)
        }
    }

    private suspend fun handleLegacySwitchMode(engine: InputEngine, targetMode: InputMode) {
        handleNavCommand(engine, legacySwitchModeToNavCommand(targetMode))
    }

    private suspend fun handleHostKeyAction(engine: InputEngine, action: KeyAction) {
        when (action) {
            is KeyAction.SwapScreen -> withContext(config.sinkDispatcher) { hostCallbacks.onSwapScreenRequested() }
            is KeyAction.CycleHeight -> cycleHeightPreset(engine)
            is KeyAction.ToggleToolbar -> Unit
            is KeyAction.SwitchIme -> withContext(config.sinkDispatcher) { hostCallbacks.onSwitchImeRequested() }
            is KeyAction.Dismiss -> withContext(config.sinkDispatcher) { hostCallbacks.onDismissRequested() }
            else -> Unit
        }
    }

    private suspend fun handleDirectText(engine: InputEngine, text: String, keepPanelOpen: Boolean = false) {
        val beforeDerived = currentDerived()
        sinkDispatcher.flushComposition(engine)
        withSink { it.commitText(text) }
        if (beforeDerived.panel != KeyboardPanel.PRIMARY) {
            if (!keepPanelOpen && !beforeDerived.panelLocked) {
                session = KeyboardNavReducer.closePanel(prefs, session, effectiveConstraints()).session
            }
        } else if (beforeDerived.effectiveLanguage == Language.ENGLISH) {
            session = KeyboardNavReducer.consumeOneShotShift(prefs, session).session
        }
        publishState(engine)
    }

    private suspend fun handleSelectCandidate(engine: InputEngine, indexOnPage: Int) {
        val result = engine.selectCandidate(indexOnPage)
        val snap = engine.state.value
        candidateWindow.syncWithSnapshot(snap, forceReset = true)
        if (result !is KeyProcessResult.PassThrough) {
            sinkDispatcher.applyEngineResult(result, snap)
        }
        publishState(engine)
    }

    private suspend fun handleSelectWindowCandidate(engine: InputEngine, globalIndex: Int) {
        val result = candidateWindow.selectWindowCandidate(engine, globalIndex)
        val snap = engine.state.value
        if (result !is KeyProcessResult.PassThrough) {
            sinkDispatcher.applyEngineResult(result, snap)
        }
        publishState(engine)
    }

    private suspend fun handleExtensionButton(engine: InputEngine, button: ExtensionToolbarButton) {
        when (button.actionType) {
            ExtensionActionType.SEND_TEXT -> handleDirectText(engine, button.value)
            ExtensionActionType.SEND_KEY -> {
                val keyCode = button.value.toIntOrNull() ?: return
                withSink { it.sendKey(keyCode) }
            }
            ExtensionActionType.HOST_COMMAND -> withSink { it.sendHostCommand(button.id, button.value) }
        }
    }

    private suspend fun cycleHeightPreset(engine: InputEngine) {
        heightFraction = nextHeightPresetFraction(heightFraction, HEIGHT_PRESETS)
        publishState(engine)
        withContext(config.sinkDispatcher) {
            hostCallbacks.onHeightFractionChanged(heightFraction)
        }
    }

    private fun syncEngineSchemaIfChanged(engine: InputEngine, derived: DerivedKeyboardState) {
        if (derived.activeSchemaId == lastSelectedSchemaId) return
        selectEngineSchemaWithFallback(engine, derived, activeSchemaIds)
        lastSelectedSchemaId = derived.activeSchemaId
    }

    private suspend inline fun withSink(crossinline block: (InputSink) -> Unit) {
        val sink = activeSinkRef.get() ?: return
        withContext(config.sinkDispatcher) {
            block(sink)
        }
    }

    private fun resolveMemoryKey(context: EditorContext): String? =
        LanguageMemoryStore.normalizeKey(languageMemoryKey ?: context.packageName)

    private fun effectiveConstraints(base: InputConstraints = constraints): InputConstraints =
        applyHostPolicyDefaults(base, editorContext, hostLangKeyPlacement, hostInitialSymbolTab, hostToolbarMode)

    private fun currentDerived(): DerivedKeyboardState = deriveKeyboardState(
        prefs = prefs,
        session = session,
        constraints = effectiveConstraints(),
        modeSchemaIds = activeSchemaIds,
        chineseSchemeSchemaIds = activeChineseSchemeSchemaIds
    )

    private fun publishState(engine: InputEngine) {
        val snap = engine.state.value
        candidateWindow.syncWithSnapshot(snap)
        _uiState.value = buildUiState(isEngineReady = true, snapshot = snap)
    }

    private fun buildUiState(isEngineReady: Boolean, snapshot: EngineSnapshot): KeyboardUiState {
        val effective = effectiveConstraints()
        val derived = deriveKeyboardState(
            prefs = prefs,
            session = session,
            constraints = effective,
            modeSchemaIds = activeSchemaIds,
            chineseSchemeSchemaIds = activeChineseSchemeSchemaIds
        )
        return KeyboardUiState(
            isEngineReady = isEngineReady,
            engineIssue = engineIssue,
            isUsingFallbackEngine = isUsingFallbackEngine,
            snapshot = snapshot,
            activeLayout = resolveKeyboardLayout(derived, prefs, isComposing = snapshot.isComposing),
            shifted = derived.shifted,
            includeNumberRow = derived.includeNumberRow,
            heightFraction = heightFraction,
            isOverlayMode = isOverlayMode,
            editorContext = editorContext,
            prefs = prefs,
            session = session,
            constraints = effective,
            derived = derived,
            windowCandidates = candidateWindow.items,
            hasMoreWindowCandidates = candidateWindow.hasMore
        )
    }

    companion object {
        val HEIGHT_PRESETS: List<Float> = listOf(0.60f, 0.75f, 0.90f)
        val DEFAULT_ENGINE_FACTORY: suspend () -> InputEngine = { LiteInputEngine.createDefault() }
    }
}

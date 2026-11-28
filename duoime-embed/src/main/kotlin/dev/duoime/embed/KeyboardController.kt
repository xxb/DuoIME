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
import kotlinx.coroutines.CompletableDeferred
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
                var activeEngine = applyInitialBootSafely(boot)
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
                    cancelQueuedEngineRequests(eventChannel)
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

    fun dispatchKeyAction(action: KeyAction) = sendEvent(ControllerEvent.Action(action))

    fun toggleLanguage() = sendNav(NavCommand.ToggleLanguage)

    fun setLanguage(language: Language) = sendNav(NavCommand.SetLanguage(language))

    fun setChineseScheme(scheme: ChineseScheme) = sendNav(NavCommand.SelectChineseScheme(scheme))

    fun cycleChineseScheme() = sendNav(NavCommand.CycleChineseScheme)

    fun setVariant(variant: ChineseVariant) = sendNav(NavCommand.SetVariant(variant))

    fun openPanel(panel: KeyboardPanel, tab: SymbolTabId? = null) = sendNav(NavCommand.OpenPanel(panel, tab))

    fun closePanel() = sendNav(NavCommand.ClosePanel)

    fun togglePanelLock() = sendNav(NavCommand.TogglePanelLock)

    fun toggleDrawer() = sendNav(NavCommand.ToggleDrawer)

    fun updateConstraints(constraints: InputConstraints, restarting: Boolean = false) =
        sendEvent(ControllerEvent.ConstraintsChanged(constraints, restarting))

    fun updatePrefs(prefs: KeyboardPrefs) = sendEvent(ControllerEvent.PrefsChanged(prefs))

    fun setLanguageMemoryKey(key: String?) =
        sendEvent(ControllerEvent.HostStateChanged(memoryKeyUpdate = true, languageMemoryKey = key))

    fun selectCandidate(indexOnPage: Int) = sendEvent(ControllerEvent.SelectCandidate(indexOnPage))

    fun selectWindowCandidate(globalIndex: Int) = sendEvent(ControllerEvent.SelectWindowCandidate(globalIndex))

    fun loadMoreCandidates() = sendEvent(ControllerEvent.LoadMoreCandidates)

    fun selectSyllable(index: Int) = sendEvent(ControllerEvent.SelectSyllable(index))

    fun unlockSyllablesFrom(fromIndex: Int) = sendEvent(ControllerEvent.UnlockSyllablesFrom(fromIndex))

    fun pageCandidates(forward: Boolean) = sendEvent(ControllerEvent.Page(forward))

    fun dispatchExtensionButton(button: ExtensionToolbarButton) = sendEvent(ControllerEvent.ExtensionButton(button))

    fun updateEditorContext(context: EditorContext, restarting: Boolean = false) =
        sendEvent(ControllerEvent.EditorChanged(context, restarting))

    fun updateOverlayMode(isOverlay: Boolean) = sendEvent(ControllerEvent.HostStateChanged(isOverlay = isOverlay))

    fun updateHostPolicy(
        langKeyPlacement: LangKeyPlacement = LangKeyPlacement.TOOLBAR,
        initialSymbolTab: SymbolTabId = SymbolTabId.COMMON,
        toolbarMode: ToolbarMode = ToolbarMode.FULL
    ) = sendEvent(ControllerEvent.HostPolicyChanged(langKeyPlacement, initialSymbolTab, toolbarMode))

    fun setIncludeNumberRow(enabled: Boolean) = sendNav(NavCommand.SetNumberRow(enabled))

    fun setIncognito(enabled: Boolean) = sendNav(NavCommand.SetIncognito(enabled))

    fun toggleIncognito() = sendNav(NavCommand.ToggleIncognito)

    fun updateSchemaMappings(
        modeSchemaIds: Map<InputMode, String>,
        chineseSchemeSchemaIds: Map<ChineseScheme, String>? = null
    ) = sendEvent(ControllerEvent.UpdateSchemaMappings(modeSchemaIds, chineseSchemeSchemaIds))

    /**
     * Runs [action] exclusively on the controller's single engine actor thread with the currently
     * active [InputEngine], then resets candidate pagination, syncs composing state, updates
     * tracked schema state, and republishes [uiState].
     *
     * Never invoke [withActiveEngine] or [reloadEngine] synchronously from inside [action].
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> withActiveEngine(action: (InputEngine) -> T): T {
        val result = CompletableDeferred<Any?>()
        if (eventChannel.trySend(ControllerEvent.RunWithEngine(action, result)).isFailure) {
            val err = CancellationException("KeyboardController is closed")
            result.cancel(err)
            throw err
        }
        return result.await() as T
    }

    fun dismissEngineError() = sendEvent(ControllerEvent.DismissEngineError)

    fun reset() = sendEvent(ControllerEvent.Reset)

    fun reloadEngine(
        engineFactory: suspend () -> InputEngine,
        fallbackEngineFactory: (suspend () -> InputEngine)? = this.fallbackEngineFactory,
        modeSchemaIds: Map<InputMode, String> = config.modeSchemaIds,
        chineseSchemeSchemaIds: Map<ChineseScheme, String> = config.chineseSchemeSchemaIds
    ) = sendEvent(
        ControllerEvent.ReloadEngine(engineFactory, fallbackEngineFactory, modeSchemaIds, chineseSchemeSchemaIds)
    )

    fun close() {
        eventChannel.close()
        actorJob.cancel()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun applyInitialBootSafely(boot: EngineBootstrapResult): InputEngine = try {
        applyBootstrappedEngine(boot)
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) { closeEngineSafely(boot.engine) }
        throw cancelled
    } catch (error: Throwable) {
        withContext(NonCancellable) { closeEngineSafely(boot.engine) }
        applyBootstrappedEngine(
            passThroughFallbackResult(activeSchemaIds, activeChineseSchemeSchemaIds, error)
        )
    }

    private fun applyBootstrappedEngine(boot: EngineBootstrapResult): InputEngine {
        val nextPrefs = if (boot.forcedPlainEnglish) prefs.copy(language = Language.ENGLISH) else prefs
        val nextSession = if (boot.forcedPlainEnglish) session.copy(languageOverride = Language.ENGLISH) else session
        val effective = effectiveConstraints()
        val initialDerived = deriveKeyboardState(
            prefs = nextPrefs,
            session = nextSession,
            constraints = effective,
            modeSchemaIds = boot.schemaIds,
            chineseSchemeSchemaIds = boot.chineseSchemeSchemaIds
        )
        configureBootstrappedEngine(boot, initialDerived, nextPrefs, effective, editorContext)
        val engine = boot.engine

        activeSchemaIds = boot.schemaIds
        activeChineseSchemeSchemaIds = boot.chineseSchemeSchemaIds
        engineIssue = boot.issue
        isUsingFallbackEngine = boot.usedFallback
        prefs = nextPrefs
        session = nextSession
        lastSelectedSchemaId = initialDerived.activeSchemaId
        candidateWindow.reset()
        publishState(engine)
        return engine
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun handleReloadEngine(
        previousEngine: InputEngine,
        event: ControllerEvent.ReloadEngine
    ): InputEngine {
        resetEngineSafely(previousEngine)
        clearSinkComposingSafely(sinkDispatcher)
        candidateWindow.reset()
        val boot = bootstrapControllerEngine(
            configuredSchemas = event.modeSchemaIds,
            configuredChineseSchemeSchemas = event.chineseSchemeSchemaIds,
            engineFactory = event.engineFactory,
            fallbackEngineFactory = event.fallbackEngineFactory
        )
        return try {
            val nextEngine = applyBootstrappedEngine(boot)
            if (nextEngine !== previousEngine) {
                withContext(NonCancellable) { closeEngineSafely(previousEngine) }
            }
            nextEngine
        } catch (cancelled: CancellationException) {
            if (boot.engine !== previousEngine) {
                withContext(NonCancellable) { closeEngineSafely(boot.engine) }
            }
            throw cancelled
        } catch (error: Throwable) {
            if (boot.engine !== previousEngine) {
                withContext(NonCancellable) { closeEngineSafely(boot.engine) }
            }
            engineIssue = EngineIssue(EngineIssueKind.PERSISTENT, "Reload: ${errorClassTag(error)}")
            resetEngineSafely(previousEngine)
            publishState(previousEngine)
            previousEngine
        }
    }

    private fun sendEvent(event: ControllerEvent) {
        eventChannel.trySend(event)
    }

    private fun sendNav(command: NavCommand) = sendEvent(ControllerEvent.Nav(command))

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
        clearSinkComposingSafely(sinkDispatcher)
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
            is ControllerEvent.UpdateSchemaMappings -> handleUpdateSchemaMappings(engine, event)
            is ControllerEvent.RunWithEngine -> handleRunWithEngine(engine, event)
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
        val remembered = if (!restarting &&
            !prefs.incognito &&
            LanguageMemoryStore.isEligibleForMemory(effective, editorContext)
        ) {
            languageMemory.get(resolveMemoryKey(editorContext))
        } else {
            null
        }
        val updated = KeyboardNavReducer.enterEditor(prefs, session, effective, remembered, restarting)
        prefs = updated.prefs
        session = updated.session
        val after = currentDerived()
        val prevSnap = engine.state.value

        applyEnginePrivacyOptions(engine, prefs, effective, editorContext)
        val afterPrivacySnap = engine.state.value
        if (!restarting) {
            engine.reset()
            syncEngineSchemaIfChanged(engine, after)
            sinkDispatcher.syncComposingOnly(engine.state.value)
        } else {
            val privacyChangedComposing = composingChangedAfterPrivacy(prevSnap, afterPrivacySnap)
            syncSchemaWithComposingCleanup(engine, before, after)
            if (privacyChangedComposing) {
                sinkDispatcher.syncComposingOnly(engine.state.value)
            }
        }
        publishState(engine)
    }

    private suspend fun handlePrefsChanged(engine: InputEngine, updatedPrefs: KeyboardPrefs) {
        val before = currentDerived()
        val previousVariant = prefs.variant
        val previousIncognito = prefs.incognito
        val prevSnap = engine.state.value
        prefs = updatedPrefs
        val after = currentDerived()
        if (updatedPrefs.variant != previousVariant || engine.state.value.variant != updatedPrefs.variant) {
            engine.setOption(EngineOptions.TRADITIONALIZATION, prefs.variant == ChineseVariant.TRADITIONAL)
        }
        val privacyChangedComposing = if (
            updatedPrefs.incognito != previousIncognito || after.effectiveIncognito != before.effectiveIncognito
        ) {
            applyEnginePrivacyOptions(engine, prefs, effectiveConstraints(), editorContext)
            composingChangedAfterPrivacy(prevSnap, engine.state.value)
        } else {
            false
        }
        syncSchemaWithComposingCleanup(engine, before, after)
        if (privacyChangedComposing) {
            sinkDispatcher.syncComposingOnly(engine.state.value)
        }
        publishState(engine)
    }

    private suspend fun handleUpdateSchemaMappings(engine: InputEngine, event: ControllerEvent.UpdateSchemaMappings) {
        val before = currentDerived()
        activeSchemaIds = event.modeSchemaIds
        event.chineseSchemeSchemaIds?.let { activeChineseSchemeSchemaIds = it }
        val after = currentDerived()
        syncSchemaWithComposingCleanup(engine, before, after)
        publishState(engine)
    }

    private suspend fun handleRunWithEngine(engine: InputEngine, event: ControllerEvent.RunWithEngine) {
        val outcome = runEngineActionSafely(engine, event) ?: return
        candidateWindow.reset()
        val afterSnap = engine.state.value
        syncSinkComposingSafely(sinkDispatcher, afterSnap)
        lastSelectedSchemaId = afterSnap.schemaId.ifEmpty { null }
        publishState(engine)
        completeRunWithEngine(event, outcome)
    }

    private suspend fun handleNavCommand(engine: InputEngine, command: NavCommand) {
        val previousPrefs = prefs
        when (command) {
            is NavCommand.SetVariant -> {
                prefs = prefs.copy(variant = command.variant)
                engine.setOption(EngineOptions.TRADITIONALIZATION, command.variant == ChineseVariant.TRADITIONAL)
            }
            is NavCommand.SetNumberRow -> prefs = prefs.copy(includeNumberRow = command.enabled)
            is NavCommand.SetIncognito -> applyIncognitoUpdate(
                engine,
                KeyboardNavReducer.setIncognito(prefs, session, effectiveConstraints(), command.enabled)
            )
            is NavCommand.ToggleIncognito -> applyIncognitoUpdate(
                engine,
                KeyboardNavReducer.toggleIncognito(prefs, session, effectiveConstraints())
            )
            else -> applyReducedNavCommand(engine, command)
        }
        publishState(engine)
        notifyPrefsChangedIfMutated(previousPrefs)
    }

    private suspend fun applyReducedNavCommand(engine: InputEngine, command: NavCommand) {
        val before = currentDerived()
        val effective = effectiveConstraints()
        val outcome = reduceNavTransitionCommand(command, prefs, session, effective) ?: return
        prefs = outcome.update.prefs
        session = outcome.update.session
        val after = currentDerived()
        syncSchemaWithComposingCleanup(engine, before, after)
        if (outcome.recordMemory && !prefs.incognito && LanguageMemoryStore.isEligibleForMemory(effective, editorContext)) {
            languageMemory.put(resolveMemoryKey(editorContext), after.effectiveLanguage)
        }
    }

    private suspend fun applyIncognitoUpdate(engine: InputEngine, update: KeyboardNavUpdate) {
        val previousPrefs = prefs
        val prevSnap = engine.state.value
        prefs = update.prefs
        session = update.session
        if (prefs != previousPrefs) {
            applyEnginePrivacyOptions(engine, prefs, effectiveConstraints(), editorContext)
            if (composingChangedAfterPrivacy(prevSnap, engine.state.value)) {
                sinkDispatcher.syncComposingOnly(engine.state.value)
            }
        }
    }

    private suspend fun syncSchemaWithComposingCleanup(
        engine: InputEngine,
        before: DerivedKeyboardState,
        after: DerivedKeyboardState
    ) {
        if (before.effectiveLanguage == Language.CHINESE && after.effectiveLanguage == Language.ENGLISH) {
            sinkDispatcher.commitRawComposingLatin(engine)
        } else if ((before.activeSchemaId != after.activeSchemaId || after.activeSchemaId != lastSelectedSchemaId) &&
            engine.state.value.isComposing
        ) {
            engine.reset()
            sinkDispatcher.syncComposingOnly(engine.state.value)
        }
        syncEngineSchemaIfChanged(engine, after)
    }

    private suspend fun notifyPrefsChangedIfMutated(previousPrefs: KeyboardPrefs) {
        if (prefs != previousPrefs) {
            val updated = prefs
            withContext(config.sinkDispatcher) { hostCallbacks.onPrefsChanged(updated) }
        }
    }

    private suspend fun handleKeyAction(engine: InputEngine, action: KeyAction) {
        mapKeyActionToNavCommand(action, prefs)?.let { navCommand ->
            handleNavCommand(engine, navCommand)
            return
        }
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
            is KeyAction.SelectSyllable -> handleSelectSyllable(engine, action.index)
            is KeyAction.UnlockSyllablesFrom -> handleUnlockSyllablesFrom(engine, action.fromIndex)
            is KeyAction.ClearPreedit -> {
                engine.reset()
                sinkDispatcher.syncComposingOnly(engine.state.value)
                publishState(engine)
            }
            else -> handleHostKeyAction(engine, action)
        }
    }

    private suspend fun handleHostKeyAction(engine: InputEngine, action: KeyAction) {
        when (action) {
            is KeyAction.SwapScreen -> withContext(config.sinkDispatcher) { hostCallbacks.onSwapScreenRequested() }
            is KeyAction.CycleHeight -> cycleHeightPreset(engine)
            is KeyAction.SwitchIme -> withContext(config.sinkDispatcher) { hostCallbacks.onSwitchImeRequested() }
            is KeyAction.Dismiss -> withContext(config.sinkDispatcher) { hostCallbacks.onDismissRequested() }
            else -> Unit
        }
    }

    private suspend fun handleDirectText(engine: InputEngine, text: String, keepPanelOpen: Boolean = false) {
        val beforeDerived = currentDerived()
        sinkDispatcher.flushComposition(engine)
        withSink { it.commitText(text) }
        session = resolvePostDirectTextSession(beforeDerived, prefs, session, effectiveConstraints(), keepPanelOpen)
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

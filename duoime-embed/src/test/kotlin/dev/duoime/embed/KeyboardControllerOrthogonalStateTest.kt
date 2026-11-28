// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.embed

import dev.duoime.core.engine.EngineKeyEvent
import dev.duoime.core.engine.EngineOptions
import dev.duoime.core.engine.EngineSchemas
import dev.duoime.core.engine.InputEngine
import dev.duoime.core.engine.InputSink
import dev.duoime.core.layout.KeyCodes
import dev.duoime.core.model.ChineseScheme
import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.EditorContext
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.core.model.FieldKind
import dev.duoime.core.model.InputConstraints
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.KeyProcessResult
import dev.duoime.core.model.KeyboardPanel
import dev.duoime.core.model.KeyboardPrefs
import dev.duoime.core.model.Language
import dev.duoime.core.model.SinkCapabilities
import dev.duoime.core.model.SymbolTabId
import dev.duoime.core.protocol.ExtensionConfig
import dev.duoime.engine.lite.LiteInputEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardControllerOrthogonalStateTest {

    private class RecordingSink(
        override val capabilities: SinkCapabilities = SinkCapabilities.STANDARD_IME
    ) : InputSink {
        val events = mutableListOf<String>()

        override fun commitText(text: CharSequence) {
            events.add("commit:$text")
        }

        override fun setComposing(text: CharSequence?) {
            events.add("compose:${text ?: "<clear>"}")
        }

        override fun deleteBackward(count: Int) {
            events.add("delete:$count")
        }

        override fun sendKey(keyCode: Int, metaState: Int) {
            events.add("key:$keyCode:$metaState")
        }

        override fun performEditorAction(action: Int) {
            events.add("action:$action")
        }

        override fun sendHostCommand(id: String, payload: String): Boolean {
            events.add("host:$id:$payload")
            return true
        }
    }

    private class ComposingPassThroughEngine(
        initialComposing: String = "git"
    ) : InputEngine {
        private val _state = MutableStateFlow(
            EngineSnapshot(
                preedit = initialComposing,
                composingText = initialComposing,
                isComposing = true
            )
        )
        override val state: StateFlow<EngineSnapshot> = _state

        override fun onKey(event: EngineKeyEvent): KeyProcessResult = KeyProcessResult.PassThrough

        override fun selectCandidate(indexOnPage: Int): KeyProcessResult = KeyProcessResult.PassThrough

        override fun page(forward: Boolean): Boolean = false

        override fun setOption(name: String, on: Boolean) = Unit

        override fun selectSchema(id: String): Boolean = true

        override fun reset() {
            _state.value = EngineSnapshot.EMPTY
        }
    }

    private fun typeEngineChars(controller: KeyboardController, text: String) {
        for (ch in text) {
            controller.dispatchKeyAction(KeyAction.Engine(KeyCodes.keysymForChar(ch)))
        }
    }

    @Test
    fun t9ToEnglishToChinesePreservesT9AndPasswordRestoresCleanly() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = RecordingSink()
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        controller.attachSink(sink)
        advanceUntilIdle()

        controller.setChineseScheme(ChineseScheme.PINYIN_T9)
        controller.setVariant(ChineseVariant.TRADITIONAL)
        advanceUntilIdle()
        assertEquals(InputMode.PINYIN_T9, controller.uiState.value.activeLayout.mode)
        assertEquals(ChineseVariant.TRADITIONAL, controller.uiState.value.snapshot.variant)

        // Toggle T9 -> EN -> CN: PINYIN_T9 and TRADITIONAL stay intact
        controller.toggleLanguage()
        advanceUntilIdle()
        assertEquals(Language.ENGLISH, controller.uiState.value.derived.effectiveLanguage)
        assertEquals(InputMode.ENGLISH, controller.uiState.value.activeLayout.mode)
        assertEquals(ChineseScheme.PINYIN_T9, controller.uiState.value.prefs.chineseScheme)

        controller.toggleLanguage()
        advanceUntilIdle()
        assertEquals(Language.CHINESE, controller.uiState.value.derived.effectiveLanguage)
        assertEquals(InputMode.PINYIN_T9, controller.uiState.value.activeLayout.mode)
        assertEquals(ChineseVariant.TRADITIONAL, controller.uiState.value.prefs.variant)

        // Enter PASSWORD -> ENGLISH + number row forced + canToggleLanguage=false; prefs stay CHINESE+T9
        controller.updateEditorContext(EditorContext(packageName = "dev.auth", isPassword = true))
        advanceUntilIdle()
        val pwdState = controller.uiState.value
        assertEquals(InputMode.ENGLISH, pwdState.activeLayout.mode)
        assertTrue(pwdState.includeNumberRow)
        assertFalse(pwdState.derived.canToggleLanguage)
        assertEquals(Language.CHINESE, pwdState.prefs.language)
        assertEquals(ChineseScheme.PINYIN_T9, pwdState.prefs.chineseScheme)

        // Attempting toggleLanguage inside password is a no-op
        controller.toggleLanguage()
        advanceUntilIdle()
        assertEquals(Language.ENGLISH, controller.uiState.value.derived.effectiveLanguage)

        // Leaving PASSWORD to TEXT automatically restores CHINESE + PINYIN_T9 via derived constraints
        controller.updateEditorContext(EditorContext(packageName = "dev.notes", fieldKind = FieldKind.TEXT))
        advanceUntilIdle()
        val restored = controller.uiState.value
        assertEquals(Language.CHINESE, restored.derived.effectiveLanguage)
        assertEquals(InputMode.PINYIN_T9, restored.activeLayout.mode)
        assertFalse(restored.includeNumberRow)
        controller.close()
    }

    @Test
    fun midComposingLanguageSwitchPanelOverlayAndClearPreeditBehaveCleanly() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = RecordingSink(SinkCapabilities.STANDARD_IME)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        controller.attachSink(sink)
        advanceUntilIdle()

        // 1) CN -> EN while composing commits raw Latin text and clears composing
        typeEngineChars(controller, "nihao")
        advanceUntilIdle()
        assertTrue(controller.uiState.value.snapshot.isComposing)
        sink.events.clear()

        controller.toggleLanguage()
        advanceUntilIdle()
        assertEquals(Language.ENGLISH, controller.uiState.value.derived.effectiveLanguage)
        assertFalse(controller.uiState.value.snapshot.isComposing)
        assertEquals(listOf("commit:nihao", "compose:<clear>"), sink.events)

        // 2) OpenPanel preserves active preedit; typing symbol commits primary candidate then symbol & closes panel
        controller.toggleLanguage()
        advanceUntilIdle()
        typeEngineChars(controller, "nihao")
        advanceUntilIdle()
        sink.events.clear()

        controller.openPanel(KeyboardPanel.SYMBOL, SymbolTabId.CHINESE)
        advanceUntilIdle()
        assertEquals(KeyboardPanel.SYMBOL, controller.uiState.value.derived.panel)
        assertFalse(controller.uiState.value.derived.panelLocked)
        assertEquals(SymbolTabId.CHINESE, controller.uiState.value.derived.symbolTab)
        assertTrue("OpenPanel must preserve active preedit", controller.uiState.value.snapshot.isComposing)

        controller.dispatchKeyAction(KeyAction.Text("！"))
        advanceUntilIdle()
        assertEquals(listOf("commit:你好", "compose:<clear>", "commit:！"), sink.events)
        assertEquals(KeyboardPanel.PRIMARY, controller.uiState.value.derived.panel)

        // 3) Open NUMBER panel (locked by default): typing digit commits candidate + digit & STAYS on NUMBER
        typeEngineChars(controller, "nihao")
        advanceUntilIdle()
        sink.events.clear()
        controller.openPanel(KeyboardPanel.NUMBER)
        advanceUntilIdle()
        assertEquals(KeyboardPanel.NUMBER, controller.uiState.value.derived.panel)
        assertTrue(controller.uiState.value.derived.panelLocked)
        assertTrue(controller.uiState.value.snapshot.isComposing)

        controller.dispatchKeyAction(KeyAction.Text("5"))
        advanceUntilIdle()
        assertEquals(listOf("commit:你好", "compose:<clear>", "commit:5"), sink.events)
        assertEquals(KeyboardPanel.NUMBER, controller.uiState.value.derived.panel)

        // Unlocking panel causes next direct char to return to PRIMARY
        controller.togglePanelLock()
        controller.dispatchKeyAction(KeyAction.Text("6"))
        advanceUntilIdle()
        assertEquals(KeyboardPanel.PRIMARY, controller.uiState.value.derived.panel)

        // 4) ClearPreedit resets active composition cleanly
        typeEngineChars(controller, "nihao")
        advanceUntilIdle()
        assertTrue(controller.uiState.value.snapshot.isComposing)
        sink.events.clear()
        controller.dispatchKeyAction(KeyAction.ClearPreedit)
        advanceUntilIdle()
        assertFalse(controller.uiState.value.snapshot.isComposing)
        assertEquals(listOf("compose:<clear>"), sink.events)
        controller.close()
    }

    @Test
    fun terminalComposingEnterCommitsRawLettersWithoutSendingKeycodeEnter() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = RecordingSink(SinkCapabilities.TERMINAL)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        controller.attachSink(sink)
        advanceUntilIdle()

        // Enter TERMINAL field: defaults to ENGLISH + CLI symbol tab + allowChinese
        controller.updateEditorContext(EditorContext(packageName = "dev.term", fieldKind = FieldKind.TERMINAL))
        advanceUntilIdle()
        assertEquals(Language.ENGLISH, controller.uiState.value.derived.effectiveLanguage)
        assertEquals(SymbolTabId.CLI, controller.uiState.value.derived.symbolTab)

        // Switch to Chinese in terminal and compose "nihao"
        controller.setLanguage(Language.CHINESE)
        advanceUntilIdle()
        typeEngineChars(controller, "nihao")
        advanceUntilIdle()
        assertTrue(controller.uiState.value.snapshot.isComposing)
        sink.events.clear()

        // 1st Enter while composing commits raw letters WITHOUT KEYCODE_ENTER
        controller.dispatchKeyAction(KeyAction.Enter)
        advanceUntilIdle()
        assertFalse(controller.uiState.value.snapshot.isComposing)
        assertEquals(listOf("commit:nihao"), sink.events)

        // 2nd idle Enter sends KEYCODE_ENTER
        sink.events.clear()
        controller.dispatchKeyAction(KeyAction.Enter)
        advanceUntilIdle()
        assertEquals(listOf("key:${KeyCodes.KEYCODE_ENTER}:0"), sink.events)
        controller.close()

        // Even with a PassThrough-on-Enter composing engine, TERMINAL still suppresses KEYCODE_ENTER
        val ptSink = RecordingSink(SinkCapabilities.STANDARD_IME)
        val ptController = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { ComposingPassThroughEngine("ls") }
        )
        ptController.attachSink(ptSink)
        ptController.updateConstraints(
            InputConstraints(
                kind = FieldKind.TERMINAL,
                allowChinese = true,
                noLearning = true,
                initialLanguage = Language.CHINESE,
                defaultSymbolTab = SymbolTabId.CLI
            ),
            restarting = true
        )
        advanceUntilIdle()
        ptSink.events.clear()
        ptController.dispatchKeyAction(KeyAction.Enter)
        advanceUntilIdle()
        assertEquals(listOf("commit:ls", "compose:<clear>"), ptSink.events)

        ptSink.events.clear()
        ptController.dispatchKeyAction(KeyAction.Enter)
        advanceUntilIdle()
        assertEquals(listOf("key:${KeyCodes.KEYCODE_ENTER}:0"), ptSink.events)
        ptController.close()
    }

    @Test
    fun languageMemoryLruEvictsAt32AndSkipsPrivacyForcedFieldsAndBlankKeys() = runTest {
        // Direct LRU unit verification: 32-entry cap + recency refresh + blank/null key ignore
        val store = LanguageMemoryStore(maxEntries = 32)
        store.put(null, Language.ENGLISH)
        store.put("   ", Language.ENGLISH)
        assertEquals(0, store.size)

        for (i in 1..32) {
            store.put("app.$i", Language.ENGLISH)
        }
        assertEquals(32, store.size)
        // Touch app.1 so app.2 becomes least-recently-used
        assertEquals(Language.ENGLISH, store.get("app.1"))
        store.put("app.33", Language.CHINESE)
        assertEquals(32, store.size)
        assertEquals(Language.ENGLISH, store.get("app.1"))
        assertNull("Oldest untouched entry app.2 must be evicted", store.get("app.2"))
        assertEquals(Language.CHINESE, store.get("app.33"))

        // End-to-end controller verification with languageMemoryKey & privacy/non-text skips
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        advanceUntilIdle()

        // Record ENGLISH for app.en and CHINESE for app.cn
        controller.updateEditorContext(EditorContext(packageName = "app.en", fieldKind = FieldKind.TEXT))
        controller.setLanguage(Language.ENGLISH)
        controller.updateEditorContext(EditorContext(packageName = "app.cn", fieldKind = FieldKind.TEXT))
        controller.setLanguage(Language.CHINESE)
        advanceUntilIdle()

        // Returning to app.en recalls ENGLISH; incognito app.en ignores & never overwrites memory
        controller.updateEditorContext(EditorContext(packageName = "app.en", fieldKind = FieldKind.TEXT))
        advanceUntilIdle()
        assertEquals(Language.ENGLISH, controller.uiState.value.derived.effectiveLanguage)

        controller.updateEditorContext(
            EditorContext(packageName = "app.en", fieldKind = FieldKind.TEXT, incognito = true)
        )
        advanceUntilIdle()
        // Incognito must NOT read app.en's ENGLISH (falls back to prefs.language = CHINESE)
        assertEquals(Language.CHINESE, controller.uiState.value.derived.effectiveLanguage)
        controller.setLanguage(Language.CHINESE)
        advanceUntilIdle()

        // Non-incognito app.en still recalls ENGLISH cleanly
        controller.updateEditorContext(EditorContext(packageName = "app.en", fieldKind = FieldKind.TEXT))
        advanceUntilIdle()
        assertEquals(Language.ENGLISH, controller.uiState.value.derived.effectiveLanguage)

        // Forced non-text fields (NUMBER, URI, EMAIL, PASSWORD) never write memory
        listOf(FieldKind.NUMBER, FieldKind.URI, FieldKind.EMAIL, FieldKind.PASSWORD).forEach { kind ->
            controller.updateEditorContext(
                EditorContext(
                    packageName = "app.cn",
                    fieldKind = kind,
                    isPassword = kind == FieldKind.PASSWORD
                )
            )
            advanceUntilIdle()
            assertEquals(Language.ENGLISH, controller.uiState.value.derived.effectiveLanguage)
        }
        controller.updateEditorContext(EditorContext(packageName = "app.cn", fieldKind = FieldKind.TEXT))
        advanceUntilIdle()
        assertEquals(Language.CHINESE, controller.uiState.value.derived.effectiveLanguage)

        // Host-hinted TEXT field (initialLanguage != null) must NOT pollute sibling TEXT memory under same packageName
        controller.updateEditorContext(
            EditorContext(
                packageName = "app.cn",
                fieldKind = FieldKind.TEXT,
                extension = ExtensionConfig(initialLanguage = Language.ENGLISH)
            )
        )
        advanceUntilIdle()
        assertEquals(Language.ENGLISH, controller.uiState.value.derived.effectiveLanguage)
        controller.setLanguage(Language.ENGLISH)
        advanceUntilIdle()
        controller.updateEditorContext(EditorContext(packageName = "app.cn", fieldKind = FieldKind.TEXT))
        advanceUntilIdle()
        assertEquals(Language.CHINESE, controller.uiState.value.derived.effectiveLanguage)

        // Custom languageMemoryKey isolates panes inside the same package
        controller.setLanguageMemoryKey("pane_english")
        controller.updateEditorContext(EditorContext(packageName = "app.cn", fieldKind = FieldKind.TEXT))
        controller.setLanguage(Language.ENGLISH)
        controller.setLanguageMemoryKey("pane_chinese")
        controller.updateEditorContext(EditorContext(packageName = "app.cn", fieldKind = FieldKind.TEXT))
        controller.setLanguage(Language.CHINESE)
        controller.setLanguageMemoryKey("pane_english")
        controller.updateEditorContext(EditorContext(packageName = "app.cn", fieldKind = FieldKind.TEXT))
        advanceUntilIdle()
        assertEquals(Language.ENGLISH, controller.uiState.value.derived.effectiveLanguage)
        controller.close()
    }

    @Test
    fun programmaticApisCycleSchemeDrawerConstraintsPrefsAndRestartingWork() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        advanceUntilIdle()

        // cycleChineseScheme: PINYIN_26 -> PINYIN_T9 -> XIAOHE_SHUANGPIN -> PINYIN_26
        controller.cycleChineseScheme()
        advanceUntilIdle()
        assertEquals(ChineseScheme.PINYIN_T9, controller.uiState.value.derived.effectiveChineseScheme)
        assertEquals(EngineSchemas.T9_PINYIN, controller.uiState.value.derived.activeSchemaId)

        controller.cycleChineseScheme()
        advanceUntilIdle()
        assertEquals(ChineseScheme.XIAOHE_SHUANGPIN, controller.uiState.value.derived.effectiveChineseScheme)
        assertEquals(EngineSchemas.DOUBLE_PINYIN_FLYPY, controller.uiState.value.derived.activeSchemaId)

        controller.cycleChineseScheme()
        advanceUntilIdle()
        assertEquals(ChineseScheme.PINYIN_26, controller.uiState.value.derived.effectiveChineseScheme)

        // toggleDrawer + openPanel + restarting=true keeps open panel
        controller.toggleDrawer()
        advanceUntilIdle()
        assertTrue(controller.uiState.value.derived.drawerOpen)
        controller.openPanel(KeyboardPanel.SYMBOL, SymbolTabId.PAIR)
        advanceUntilIdle()
        assertFalse(controller.uiState.value.derived.drawerOpen)
        assertEquals(KeyboardPanel.SYMBOL, controller.uiState.value.derived.panel)
        assertEquals(SymbolTabId.PAIR, controller.uiState.value.derived.symbolTab)

        controller.updateConstraints(InputConstraints(kind = FieldKind.TEXT), restarting = true)
        advanceUntilIdle()
        assertEquals(KeyboardPanel.SYMBOL, controller.uiState.value.derived.panel)
        assertEquals(SymbolTabId.PAIR, controller.uiState.value.derived.symbolTab)

        controller.closePanel()
        advanceUntilIdle()
        assertEquals(KeyboardPanel.PRIMARY, controller.uiState.value.derived.panel)

        // updatePrefs updates scheme, variant, and number row cleanly
        controller.updatePrefs(
            KeyboardPrefs(
                language = Language.CHINESE,
                chineseScheme = ChineseScheme.XIAOHE_SHUANGPIN,
                variant = ChineseVariant.TRADITIONAL,
                includeNumberRow = true
            )
        )
        advanceUntilIdle()
        assertEquals(ChineseScheme.XIAOHE_SHUANGPIN, controller.uiState.value.derived.effectiveChineseScheme)
        assertEquals(ChineseVariant.TRADITIONAL, controller.uiState.value.snapshot.variant)
        assertTrue(controller.uiState.value.includeNumberRow)

        // NUMBER constraint forces numberPadV0 layout
        controller.updateConstraints(
            InputConstraints(kind = FieldKind.NUMBER, allowChinese = false, forcedPanel = KeyboardPanel.NUMBER)
        )
        advanceUntilIdle()
        assertEquals(KeyboardPanel.NUMBER, controller.uiState.value.activeLayout.panel)
        assertEquals(Language.ENGLISH, controller.uiState.value.derived.effectiveLanguage)
        controller.close()
    }

    @Test
    fun midComposingChineseSchemeSwitchClearsSinkComposingSpanAndSwitchVariantUsesPrefs() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = RecordingSink(SinkCapabilities.STANDARD_IME)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        controller.attachSink(sink)
        advanceUntilIdle()

        // Compose "ni" in PINYIN_26, then switch to PINYIN_T9 -> must clear sink composing span
        typeEngineChars(controller, "ni")
        advanceUntilIdle()
        assertTrue(controller.uiState.value.snapshot.isComposing)
        sink.events.clear()

        controller.setChineseScheme(ChineseScheme.PINYIN_T9)
        advanceUntilIdle()
        assertFalse(controller.uiState.value.snapshot.isComposing)
        assertEquals(InputMode.PINYIN_T9, controller.uiState.value.activeLayout.mode)
        assertEquals(listOf("compose:<clear>"), sink.events)

        // Compose "64" in PINYIN_T9, then switch via updatePrefs -> must also clear sink composing span
        typeEngineChars(controller, "64")
        advanceUntilIdle()
        assertTrue(controller.uiState.value.snapshot.isComposing)
        sink.events.clear()

        controller.updatePrefs(controller.uiState.value.prefs.copy(chineseScheme = ChineseScheme.XIAOHE_SHUANGPIN))
        advanceUntilIdle()
        assertFalse(controller.uiState.value.snapshot.isComposing)
        assertEquals(ChineseScheme.XIAOHE_SHUANGPIN, controller.uiState.value.derived.effectiveChineseScheme)
        assertEquals(listOf("compose:<clear>"), sink.events)
        controller.close()

        // SwitchVariant toggles from Layer 1 prefs.variant even when engine ignores setOption
        val stubController = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { ComposingPassThroughEngine("") }
        )
        advanceUntilIdle()
        assertEquals(ChineseVariant.SIMPLIFIED, stubController.uiState.value.prefs.variant)
        stubController.dispatchKeyAction(KeyAction.SwitchVariant)
        advanceUntilIdle()
        assertEquals(ChineseVariant.TRADITIONAL, stubController.uiState.value.prefs.variant)
        stubController.dispatchKeyAction(KeyAction.SwitchVariant)
        advanceUntilIdle()
        assertEquals(ChineseVariant.SIMPLIFIED, stubController.uiState.value.prefs.variant)
        stubController.close()
    }

    @Test
    fun initialTraditionalizationSyncsUnconditionallyAndUpdatePrefsCorrectsVariantDrift() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val preTraditionalEngine = LiteInputEngine.createDefault(initialVariant = ChineseVariant.TRADITIONAL)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(
                engineDispatcher = dispatcher,
                sinkDispatcher = dispatcher,
                initialPrefs = KeyboardPrefs(variant = ChineseVariant.SIMPLIFIED)
            ),
            engineFactory = { preTraditionalEngine }
        )
        advanceUntilIdle()

        // Default SIMPLIFIED prefs must sync pre-configured TRADITIONAL engine down to SIMPLIFIED at init
        assertEquals(ChineseVariant.SIMPLIFIED, controller.uiState.value.prefs.variant)
        assertEquals(ChineseVariant.SIMPLIFIED, controller.uiState.value.snapshot.variant)

        // First SwitchVariant flips cleanly to TRADITIONAL
        controller.dispatchKeyAction(KeyAction.SwitchVariant)
        advanceUntilIdle()
        assertEquals(ChineseVariant.TRADITIONAL, controller.uiState.value.prefs.variant)
        assertEquals(ChineseVariant.TRADITIONAL, controller.uiState.value.snapshot.variant)

        // Simulate engine drifting back to TRADITIONAL while prefs are updated to SIMPLIFIED, then
        // re-syncing even if prefs.variant already equals SIMPLIFIED
        controller.updatePrefs(controller.uiState.value.prefs.copy(variant = ChineseVariant.SIMPLIFIED))
        advanceUntilIdle()
        assertEquals(ChineseVariant.SIMPLIFIED, controller.uiState.value.snapshot.variant)

        preTraditionalEngine.setOption(dev.duoime.core.engine.EngineOptions.TRADITIONALIZATION, true)
        assertEquals(ChineseVariant.TRADITIONAL, preTraditionalEngine.state.value.variant)
        controller.updatePrefs(controller.uiState.value.prefs.copy(variant = ChineseVariant.SIMPLIFIED))
        advanceUntilIdle()
        assertEquals(ChineseVariant.SIMPLIFIED, controller.uiState.value.snapshot.variant)
        controller.close()
    }

    @Test
    fun onPrefsChangedCallbackFiresOnUserMutationsAndNeverLoopsOnExternalUpdatePrefs() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val persistedPrefs = mutableListOf<KeyboardPrefs>()
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() },
            hostCallbacks = KeyboardHostCallbacks(onPrefsChanged = { persistedPrefs.add(it) })
        )
        advanceUntilIdle()
        assertTrue(persistedPrefs.isEmpty())

        // User mutations fire onPrefsChanged
        controller.setChineseScheme(ChineseScheme.PINYIN_T9)
        controller.toggleLanguage()
        controller.setVariant(ChineseVariant.TRADITIONAL)
        controller.dispatchKeyAction(KeyAction.ToggleNumberRow)
        advanceUntilIdle()
        assertEquals(4, persistedPrefs.size)
        assertEquals(
            KeyboardPrefs(
                language = Language.ENGLISH,
                chineseScheme = ChineseScheme.PINYIN_T9,
                variant = ChineseVariant.TRADITIONAL,
                includeNumberRow = true
            ),
            persistedPrefs.last()
        )

        // External updatePrefs must NOT invoke onPrefsChanged (prevents echo loops)
        persistedPrefs.clear()
        controller.updatePrefs(KeyboardPrefs(language = Language.CHINESE, chineseScheme = ChineseScheme.PINYIN_26))
        advanceUntilIdle()
        assertTrue("External updatePrefs must not trigger onPrefsChanged", persistedPrefs.isEmpty())

        // Constrained non-text/incognito language toggles do not mutate prefs and do not fire onPrefsChanged
        controller.updateEditorContext(EditorContext(packageName = "dev.browser", fieldKind = FieldKind.URI))
        controller.toggleLanguage()
        controller.updateEditorContext(EditorContext(packageName = "dev.auth", isPassword = true))
        controller.toggleLanguage()
        advanceUntilIdle()
        assertTrue(persistedPrefs.isEmpty())
        controller.close()
    }

    @Test
    fun xiaoheShuangpinCnToEnCommitsRawKeystrokesInsteadOfDecodedPinyin() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = RecordingSink(SinkCapabilities.STANDARD_IME)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        controller.attachSink(sink)
        advanceUntilIdle()

        controller.setChineseScheme(ChineseScheme.XIAOHE_SHUANGPIN)
        advanceUntilIdle()
        assertEquals(EngineSchemas.DOUBLE_PINYIN_FLYPY, controller.uiState.value.derived.activeSchemaId)

        typeEngineChars(controller, "nihc")
        advanceUntilIdle()
        assertTrue(controller.uiState.value.snapshot.isComposing)
        assertEquals("你好", controller.uiState.value.snapshot.candidates.firstOrNull()?.text)
        sink.events.clear()

        // Switching CN -> EN must commit the exact raw Shuangpin keystrokes "nihc", NOT decoded "nihao"
        controller.toggleLanguage()
        advanceUntilIdle()
        assertEquals(Language.ENGLISH, controller.uiState.value.derived.effectiveLanguage)
        assertFalse(controller.uiState.value.snapshot.isComposing)
        assertEquals(listOf("commit:nihc", "compose:<clear>"), sink.events)
        controller.close()
    }

    @Test
    fun orthogonalIncognitoTogglePrefsSyncAndNoLearningLockWorkEndToEnd() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = RecordingSink(SinkCapabilities.STANDARD_IME)
        val optionLog = mutableListOf<Pair<String, Boolean>>()
        val persistedPrefs = mutableListOf<KeyboardPrefs>()
        val privacyClearingEngine = object : InputEngine {
            private val _state = MutableStateFlow(
                EngineSnapshot(
                    schemaId = EngineSchemas.PINYIN_26,
                    preedit = "ni",
                    composingText = "ni",
                    isComposing = true
                )
            )
            override val state: StateFlow<EngineSnapshot> = _state

            override fun onKey(event: EngineKeyEvent): KeyProcessResult = KeyProcessResult.Consumed

            override fun selectCandidate(indexOnPage: Int): KeyProcessResult = KeyProcessResult.PassThrough

            override fun page(forward: Boolean): Boolean = false

            override fun setOption(name: String, on: Boolean) {
                optionLog.add(name to on)
                if (name == EngineOptions.INCOGNITO) {
                    _state.value = _state.value.copy(preedit = "", composingText = "", isComposing = false)
                }
            }

            override fun selectSchema(id: String): Boolean {
                _state.value = _state.value.copy(schemaId = id)
                return true
            }

            override fun reset() {
                _state.value = _state.value.copy(preedit = "", composingText = "", isComposing = false)
            }
        }
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { privacyClearingEngine },
            hostCallbacks = KeyboardHostCallbacks(onPrefsChanged = { persistedPrefs.add(it) })
        )
        controller.attachSink(sink)
        advanceUntilIdle()
        optionLog.clear()
        sink.events.clear()

        // ToggleIncognito via drawer keeps drawer open, sends INCOGNITO=true, and notifies host prefs
        controller.toggleDrawer()
        advanceUntilIdle()
        assertTrue(controller.uiState.value.derived.drawerOpen)
        controller.dispatchKeyAction(KeyAction.ToggleIncognito)
        advanceUntilIdle()
        assertTrue(controller.uiState.value.prefs.incognito)
        assertTrue(controller.uiState.value.derived.effectiveIncognito)
        assertTrue(controller.uiState.value.derived.canToggleIncognito)
        assertTrue("ToggleIncognito must keep drawer open", controller.uiState.value.derived.drawerOpen)
        assertTrue(EngineOptions.INCOGNITO to true in optionLog)
        assertEquals(listOf(KeyboardPrefs(incognito = true)), persistedPrefs)

        // Password field locks canToggleIncognito=false & guards ToggleIncognito / setIncognito
        controller.updateEditorContext(EditorContext(packageName = "dev.auth", isPassword = true))
        advanceUntilIdle()
        assertTrue(controller.uiState.value.derived.effectiveIncognito)
        assertFalse(controller.uiState.value.derived.canToggleIncognito)
        persistedPrefs.clear()
        controller.setIncognito(false)
        controller.dispatchKeyAction(KeyAction.ToggleIncognito)
        advanceUntilIdle()
        assertTrue(controller.uiState.value.prefs.incognito)
        assertTrue(persistedPrefs.isEmpty())

        // Leaving password restores canToggleIncognito=true; external updatePrefs clears composing on privacy swap
        controller.updateEditorContext(EditorContext(packageName = "dev.notes", fieldKind = FieldKind.TEXT))
        advanceUntilIdle()
        assertTrue(controller.uiState.value.derived.canToggleIncognito)
        controller.withActiveEngine {
            // Re-seed composing state before external prefs change
            privacyClearingEngine.selectSchema(EngineSchemas.PINYIN_26)
        }
        optionLog.clear()
        controller.updatePrefs(controller.uiState.value.prefs.copy(incognito = false))
        advanceUntilIdle()
        assertFalse(controller.uiState.value.prefs.incognito)
        assertFalse(controller.uiState.value.derived.effectiveIncognito)
        assertTrue(EngineOptions.INCOGNITO to false in optionLog)
        controller.close()
    }

    @Test
    fun updateSchemaMappingsAndWithActiveEngineHandleSuccessExceptionsAndCloseCancellation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = RecordingSink(SinkCapabilities.STANDARD_IME)
        val selectedSchemas = mutableListOf<String>()
        val schemaEngine = object : InputEngine {
            private val _state = MutableStateFlow(
                EngineSnapshot(
                    schemaId = EngineSchemas.PINYIN_26,
                    preedit = "ni",
                    composingText = "ni",
                    isComposing = true
                )
            )
            override val state: StateFlow<EngineSnapshot> = _state

            override fun onKey(event: EngineKeyEvent): KeyProcessResult = KeyProcessResult.Consumed

            override fun selectCandidate(indexOnPage: Int): KeyProcessResult = KeyProcessResult.PassThrough

            override fun page(forward: Boolean): Boolean = false

            override fun setOption(name: String, on: Boolean) = Unit

            override fun selectSchema(id: String): Boolean {
                selectedSchemas.add(id)
                if (id == "missing_custom") return false
                _state.value = _state.value.copy(schemaId = id)
                return true
            }

            override fun reset() {
                _state.value = _state.value.copy(preedit = "", composingText = "", isComposing = false)
            }
        }
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { schemaEngine }
        )
        controller.attachSink(sink)
        advanceUntilIdle()
        selectedSchemas.clear()
        sink.events.clear()

        // updateSchemaMappings switches active schema to custom ID and clears composing span
        controller.updateSchemaMappings(
            modeSchemaIds = EngineSchemas.DEFAULT_MODE_MAP + (InputMode.PINYIN_26 to "custom_rime_ice")
        )
        advanceUntilIdle()
        assertEquals("custom_rime_ice", controller.uiState.value.derived.activeSchemaId)
        assertEquals(listOf("custom_rime_ice"), selectedSchemas)
        assertTrue("compose:<clear>" in sink.events)

        // Unsupported custom schema falls back cleanly to EngineSchemas.PINYIN_26
        selectedSchemas.clear()
        controller.updateSchemaMappings(
            modeSchemaIds = EngineSchemas.DEFAULT_MODE_MAP + (InputMode.PINYIN_26 to "missing_custom")
        )
        advanceUntilIdle()
        assertEquals(listOf("missing_custom", EngineSchemas.PINYIN_26), selectedSchemas)

        // withActiveEngine executes exclusively on actor thread, returns result, and survives exceptions
        val value = controller.withActiveEngine { engine ->
            engine.selectSchema("custom_rime_ice")
            "ok:${engine.state.value.schemaId}"
        }
        assertEquals("ok:custom_rime_ice", value)
        assertEquals("custom_rime_ice", controller.uiState.value.snapshot.schemaId)

        val caught = runCatching {
            controller.withActiveEngine<Unit> { error("boom") }
        }.exceptionOrNull()
        assertTrue("Expected IllegalStateException, got $caught", caught is IllegalStateException)
        assertEquals("boom", caught?.message)
        // Controller stays healthy after caller-caught exception in withActiveEngine
        assertEquals("still_alive", controller.withActiveEngine { "still_alive" })

        // Closing controller cancels queued and subsequent withActiveEngine calls with CancellationException
        val closedController = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        val pendingCall = async { closedController.withActiveEngine { "never" } }
        closedController.close()
        advanceUntilIdle()
        val queuedErr = runCatching { pendingCall.await() }.exceptionOrNull()
        assertTrue(
            "Expected CancellationException for queued call on close, got $queuedErr",
            queuedErr is CancellationException
        )

        val postCloseErr = runCatching { closedController.withActiveEngine { "never" } }.exceptionOrNull()
        assertTrue(
            "Expected CancellationException after close, got $postCloseErr",
            postCloseErr is CancellationException
        )

        // Closing controller mid-handleRunWithEngine (while suspended in sink sync) must still cancel awaiting caller
        var closeOnSinkDispatch = false
        lateinit var midCloseController: KeyboardController
        val closingSinkDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                if (closeOnSinkDispatch) {
                    closeOnSinkDispatch = false
                    midCloseController.close()
                }
                dispatcher.dispatch(context, block)
            }
        }
        midCloseController = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = closingSinkDispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        midCloseController.attachSink(RecordingSink())
        advanceUntilIdle()
        closeOnSinkDispatch = true
        val inFlight = async { midCloseController.withActiveEngine { "ran_action" } }
        advanceUntilIdle()
        val inFlightErr = runCatching { inFlight.await() }.exceptionOrNull()
        assertTrue(
            "In-flight withActiveEngine must cancel instead of hanging when closed mid-sync, got $inFlightErr",
            inFlightErr is CancellationException
        )
        controller.close()
    }
}

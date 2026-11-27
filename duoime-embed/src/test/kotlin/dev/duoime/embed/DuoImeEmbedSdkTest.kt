// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.embed

import dev.duoime.core.layout.KeyCodes
import dev.duoime.core.model.CandidatePaging
import dev.duoime.core.model.EditorContext
import dev.duoime.core.model.FieldKind
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.KeyboardPanel
import dev.duoime.core.model.LangKeyPlacement
import dev.duoime.core.model.QuickPin
import dev.duoime.core.model.SinkCapabilities
import dev.duoime.core.model.SymbolTabId
import dev.duoime.core.protocol.ExtensionConfig
import dev.duoime.engine.lite.LiteInputEngine
import dev.duoime.ui.theme.DuoThemePreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DuoImeEmbedSdkTest {

    private class RecordingEmbedBridge : EmbedConnectionBridge {
        val calls = mutableListOf<String>()

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            calls += "commit:$text:$newCursorPosition"
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            calls += "compose:$text:$newCursorPosition"
            return true
        }

        override fun finishComposingText(): Boolean {
            calls += "finish"
            return true
        }

        override fun sendDownUpKeyEvent(keyCode: Int, metaState: Int): Boolean {
            calls += "key:$keyCode:$metaState"
            return true
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            calls += "action:$editorAction"
            return true
        }

        override fun performPrivateCommand(action: String, payload: String): Boolean {
            calls += "cmd:$action:$payload"
            return true
        }
    }

    @Test
    fun embedInputConnectionSinkHandlesComposingCommitDeleteKeysAndDisconnectCleanup() {
        val bridge = RecordingEmbedBridge()
        val sink = EmbedInputConnectionSink(connectionProvider = { bridge })

        sink.setComposing("ni")
        sink.setComposing(null)
        sink.setComposing("hao")
        sink.commitText("好")
        // Already committed -> disconnect must NOT send redundant finishComposingText
        sink.finishComposingOnDisconnect()

        sink.setComposing("shi")
        sink.finishComposingOnDisconnect()

        sink.deleteBackward(2)
        sink.sendKey(KeyCodes.KEYCODE_ENTER, 0)
        sink.performEditorAction(EditorContext.IME_ACTION_SEND)
        assertTrue(sink.sendHostCommand("esc", "1"))

        assertEquals(
            listOf(
                "compose:ni:1",
                "compose::1",
                "finish",
                "compose:hao:1",
                "commit:好:1",
                "compose:shi:1",
                "finish",
                "key:${KeyCodes.KEYCODE_DEL}:0",
                "key:${KeyCodes.KEYCODE_DEL}:0",
                "key:${KeyCodes.KEYCODE_ENTER}:0",
                "action:${EditorContext.IME_ACTION_SEND}",
                "cmd:${EmbedInputConnectionSink.PRIVATE_CMD_PREFIX}esc:1"
            ),
            bridge.calls
        )

        // Terminal capabilities suppress setComposing
        bridge.calls.clear()
        sink.updateCapabilities(SinkCapabilities.TERMINAL)
        sink.setComposing("ls")
        sink.finishComposingOnDisconnect()
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun chromePolicyAndHostChromeResolveCapabilitiesAndEinkPagedDefault() {
        assertEquals(listOf(QuickPin.SCHEME_SWITCH), ChromePolicy.EMBED_DEFAULT.pins)
        assertEquals(listOf(QuickPin.SCHEME_SWITCH, QuickPin.SWAP_SCREEN), ChromePolicy.APP_DEFAULT.pins)

        val darkPolicy = ChromePolicy(themePreset = DuoThemePreset.DARK)
        val einkPolicy = ChromePolicy(themePreset = DuoThemePreset.EINK)
        val explicitScrollOnEink = ChromePolicy(
            themePreset = DuoThemePreset.EINK,
            candidatePaging = CandidatePaging.SCROLL
        )

        assertEquals(CandidatePaging.SCROLL, darkPolicy.resolvedCandidatePaging(derivedOverride = null))
        assertEquals(CandidatePaging.PAGED, darkPolicy.resolvedCandidatePaging(derivedOverride = CandidatePaging.PAGED))
        assertEquals(CandidatePaging.PAGED, einkPolicy.resolvedCandidatePaging(derivedOverride = null))
        assertEquals(
            CandidatePaging.SCROLL,
            einkPolicy.resolvedCandidatePaging(derivedOverride = CandidatePaging.SCROLL)
        )
        assertEquals(
            CandidatePaging.SCROLL,
            explicitScrollOnEink.resolvedCandidatePaging(derivedOverride = CandidatePaging.PAGED)
        )

        assertFalse(HostChrome.NONE.canSwapScreen)
        assertFalse(HostChrome.NONE.canSwitchIme)
        assertFalse(HostChrome.NONE.showDismiss)

        val partial = HostChrome(onDismiss = {})
        assertFalse(partial.canSwapScreen)
        assertFalse(partial.canSwitchIme)
        assertTrue(partial.showDismiss)

        val full = HostChrome(onSwapScreen = {}, onSwitchIme = {}, onDismiss = {})
        assertTrue(full.canSwapScreen)
        assertTrue(full.canSwitchIme)
        assertTrue(full.showDismiss)
    }

    @Test
    fun updateHostPolicySetsDefaultsWithoutOverridingExplicitEditorExtensionOverrides() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        advanceUntilIdle()

        // Host policy sets HOST lang key placement and CLI default symbol tab
        controller.updateHostPolicy(
            langKeyPlacement = LangKeyPlacement.HOST,
            initialSymbolTab = SymbolTabId.CLI
        )
        advanceUntilIdle()
        assertEquals(LangKeyPlacement.HOST, controller.uiState.value.derived.langKeyPlacement)
        assertEquals(SymbolTabId.CLI, controller.uiState.value.derived.symbolTab)
        assertTrue(
            "HOST placement must omit ToggleLanguage on QWERTY",
            controller.uiState.value.activeLayout.rows.flattenKeys().none { it.action == KeyAction.ToggleLanguage }
        )

        // Opening SYMBOL panel picks up host policy's initialSymbolTab
        controller.openPanel(KeyboardPanel.SYMBOL)
        advanceUntilIdle()
        assertEquals(SymbolTabId.CLI, controller.uiState.value.derived.symbolTab)
        controller.closePanel()

        // Explicit dev.duoime.ext.v1 EditorInfo overrides MUST win over host policy
        controller.updateEditorContext(
            EditorContext(
                packageName = "dev.example.host",
                fieldKind = FieldKind.TEXT,
                extension = ExtensionConfig(
                    langKeyPlacement = LangKeyPlacement.GRID,
                    defaultSymbolTab = SymbolTabId.PAIR
                )
            )
        )
        advanceUntilIdle()
        assertEquals(LangKeyPlacement.GRID, controller.uiState.value.derived.langKeyPlacement)
        assertEquals(SymbolTabId.PAIR, controller.uiState.value.derived.symbolTab)
        assertTrue(
            controller.uiState.value.activeLayout.rows.flattenKeys().any { it.action == KeyAction.ToggleLanguage }
        )

        // Explicit extension defaultSymbolTab=COMMON also wins over hostPolicy initialSymbolTab=CLI
        controller.updateEditorContext(
            EditorContext(
                packageName = "dev.example.host2",
                fieldKind = FieldKind.TEXT,
                extension = ExtensionConfig(defaultSymbolTab = SymbolTabId.COMMON)
            )
        )
        advanceUntilIdle()
        assertEquals(SymbolTabId.COMMON, controller.uiState.value.derived.symbolTab)
        assertEquals(LangKeyPlacement.HOST, controller.uiState.value.derived.langKeyPlacement)
        controller.close()
    }

    @Test
    fun interceptDuoTextInputSessionDelegatesWhenDisabledAndCleansUpOnCancelWhenEnabled() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = KeyboardController(
            scope = this,
            config = KeyboardControllerConfig(engineDispatcher = dispatcher, sinkDispatcher = dispatcher),
            engineFactory = { LiteInputEngine.createDefault() }
        )
        val imeState = DuoImeState(controller)
        advanceUntilIdle()

        // 1) enabled = false delegates directly to nextHandler without activating session
        var delegated = false
        var bindingCreated = false
        val disabledJob = launch {
            try {
                interceptDuoTextInputSession(
                    enabled = false,
                    state = imeState,
                    createBinding = {
                        bindingCreated = true
                        error("Must not create binding when disabled")
                    },
                    delegateToNext = {
                        delegated = true
                        throw CancellationException("delegated-done")
                    }
                )
            } catch (_: CancellationException) {
                // Expected termination from test delegate
            }
        }
        runCurrent()
        assertTrue(delegated)
        assertFalse(bindingCreated)
        assertFalse(imeState.isInputSessionActive.value)
        assertNull(imeState.activeSink)
        disabledJob.cancel()

        // 2) enabled = true binds sink & EditorContext, tracks active session, flushes & detaches on cancel
        val bridge = RecordingEmbedBridge()
        val sink = EmbedInputConnectionSink(connectionProvider = { bridge })
        val editorCtx = EmbedEditorInfoMapper.buildEditorContext(
            packageName = "dev.example.embed",
            inputType = EmbedEditorInfoMapper.TYPE_CLASS_TEXT,
            imeOptions = EditorContext.IME_ACTION_GO
        )
        val sessionJob = launch {
            interceptDuoTextInputSession(
                enabled = true,
                state = imeState,
                createBinding = { TextInputSessionBinding(editorCtx, sink) },
                delegateToNext = { error("Must not delegate when enabled") }
            )
        }
        runCurrent()
        advanceUntilIdle()

        assertTrue(imeState.isInputSessionActive.value)
        assertEquals(sink, imeState.activeSink)
        assertEquals(FieldKind.TEXT, imeState.uiState.value.derived.fieldKind)

        // Drive real Chinese composition through controller, then cancel intercepted session ->
        // bridge receives finishComposingText AND engine/UI composing state is cleanly reset!
        controller.dispatchKeyAction(KeyAction.Engine('n'.code))
        advanceUntilIdle()
        assertTrue(imeState.uiState.value.snapshot.isComposing)
        assertTrue(imeState.uiState.value.snapshot.preedit.isNotEmpty())

        sessionJob.cancel()
        advanceUntilIdle()

        assertFalse(imeState.isInputSessionActive.value)
        assertNull(imeState.activeSink)
        assertFalse(
            "Disconnect must reset engine composing state so no ghost candidates remain",
            imeState.uiState.value.snapshot.isComposing
        )
        assertEquals("", imeState.uiState.value.snapshot.preedit)
        assertTrue(imeState.uiState.value.snapshot.candidates.isEmpty())
        assertTrue("Expected finishComposingText on disconnect, got ${bridge.calls}", "finish" in bridge.calls)
        imeState.close()
    }

    private fun List<dev.duoime.core.layout.KeyRowSpec>.flattenKeys() = flatMap { it.keys }
}

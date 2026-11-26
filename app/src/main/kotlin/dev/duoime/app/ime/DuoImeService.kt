// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import dev.duoime.app.settings.DuoImePreferences
import dev.duoime.app.settings.DuoImeSettings
import dev.duoime.embed.DuoImeKeyboard
import dev.duoime.embed.KeyboardController
import dev.duoime.embed.KeyboardControllerConfig
import dev.duoime.embed.KeyboardHostCallbacks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Standalone Android [InputMethodService] for DuoIME (M1-5 Inline IME host; M1-6 adds dual-screen
 * OverlayHost). Hosts `:duoime-embed`'s [DuoImeKeyboard] directly so the system IME and embed AAR
 * share the exact same keyboard UI and single-actor [KeyboardController].
 */
class DuoImeService : InputMethodService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val composeOwner = ImeComposeOwner()
    private val settingsFlow = MutableStateFlow(DuoImeSettings())

    private lateinit var preferences: DuoImePreferences
    private lateinit var controller: KeyboardController

    private var activeEditorConnection: InputConnection? = null
    private var lastEditorInfo: EditorInfo? = null

    private val inputSink = InputConnectionSink(
        connectionProvider = { currentInputConnection?.let(::AndroidInputConnectionBridge) }
    )

    override fun onCreate() {
        super.onCreate()
        composeOwner.onCreate()
        preferences = DuoImePreferences(this)
        val initialSettings = preferences.load()
        settingsFlow.value = initialSettings

        controller = KeyboardController(
            scope = serviceScope,
            config = KeyboardControllerConfig(
                engineDispatcher = Dispatchers.Default,
                sinkDispatcher = Dispatchers.Main.immediate,
                initialHeightFraction = initialSettings.heightFraction,
                initialOverlayMode = false
            ),
            hostCallbacks = KeyboardHostCallbacks(
                onDismissRequested = { requestHideSelf(0) },
                onSwitchImeRequested = { showImePicker() },
                onHeightFractionChanged = { fraction ->
                    settingsFlow.value = preferences.updateHeightFraction(fraction)
                }
            )
        )
        controller.setIncludeNumberRow(initialSettings.includeNumberRow)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        attachComposeOwnerToDecor()
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            composeOwner.attachToView(this)
            setContent {
                val settings by settingsFlow.collectAsState()
                val keyboardState by controller.uiState.collectAsState()
                val density = LocalDensity.current
                val screenHeightPx = with(density) {
                    LocalConfiguration.current.screenHeightDp.toFloat() * density.density
                }.toInt()
                val heightPx = DuoImePreferences.computeInlineKeyboardHeightPx(
                    windowHeightPx = screenHeightPx,
                    heightFraction = keyboardState.heightFraction,
                    density = density.density
                )
                val heightDp = with(density) { heightPx.toDp() }
                val bgColor = if (settings.darkTheme) {
                    androidx.compose.ui.graphics.Color(0xFF0A0A0C)
                } else {
                    androidx.compose.ui.graphics.Color(0xFFE7EAF0)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor)
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout))
                ) {
                    DuoImeKeyboard(
                        controller = controller,
                        sink = inputSink,
                        darkTheme = settings.darkTheme,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(heightDp)
                    )
                }
            }
        }
        return composeView
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        val oldIc = activeEditorConnection
        if (oldIc != null && oldIc !== currentInputConnection) {
            inputSink.finishComposingOnEditorSwitch(AndroidInputConnectionBridge(oldIc))
        }
        super.onStartInput(attribute, restarting)
        activeEditorConnection = currentInputConnection
        lastEditorInfo = attribute
        applyEditorInfo(attribute)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        attachComposeOwnerToDecor()
        refreshSettings()
        lastEditorInfo = info
        activeEditorConnection = currentInputConnection
        applyEditorInfo(info)
        composeOwner.onResume()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (inputSink.consumeSelectionEcho()) return
        val shouldReset = EditorInfoMapper.shouldResetComposingOnSelectionUpdate(
            isComposing = controller.uiState.value.snapshot.isComposing,
            newSelStart = newSelStart,
            newSelEnd = newSelEnd,
            candidatesStart = candidatesStart,
            candidatesEnd = candidatesEnd
        )
        if (shouldReset) {
            inputSink.markComposingExternallyCleared()
            controller.reset()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        inputSink.finishComposingOnEditorSwitch(currentInputConnection?.let(::AndroidInputConnectionBridge))
        controller.reset()
        composeOwner.onPauseToCreated()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        composeOwner.onPauseToCreated()
        super.onWindowHidden()
    }

    override fun onFinishInput() {
        inputSink.finishComposingOnEditorSwitch(
            (activeEditorConnection ?: currentInputConnection)?.let(::AndroidInputConnectionBridge)
        )
        activeEditorConnection = null
        lastEditorInfo = null
        controller.reset()
        super.onFinishInput()
    }

    override fun onDestroy() {
        controller.close()
        serviceScope.cancel()
        composeOwner.onDestroy()
        super.onDestroy()
    }

    private fun attachComposeOwnerToDecor() {
        window?.window?.decorView?.let { decor ->
            composeOwner.attachToView(decor)
        }
    }

    private fun refreshSettings() {
        val latest = preferences.load()
        settingsFlow.value = latest
        controller.setIncludeNumberRow(latest.includeNumberRow)
    }

    private fun applyEditorInfo(info: EditorInfo?) {
        val settings = settingsFlow.value
        val capabilities = EditorInfoMapper.resolveSinkCapabilities(info)
        inputSink.updateCapabilities(capabilities)
        val context = EditorInfoMapper.fromEditorInfo(info, userIncognito = settings.alwaysIncognito)
        controller.updateEditorContext(context)
    }

    private fun showImePicker() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showInputMethodPicker()
    }
}

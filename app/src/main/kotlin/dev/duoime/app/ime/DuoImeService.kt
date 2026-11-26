// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.ime

import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.duoime.app.MainActivity
import dev.duoime.app.R
import dev.duoime.app.dualscreen.DisplayClassifier
import dev.duoime.app.dualscreen.OverlayHost
import dev.duoime.app.dualscreen.SystemImePinDetector
import dev.duoime.app.settings.DuoImePreferences
import dev.duoime.app.settings.DuoImeSettings
import dev.duoime.core.engine.InputSink
import dev.duoime.core.policy.DisplayPlacementInput
import dev.duoime.core.policy.DisplayPlacementPolicy
import dev.duoime.core.policy.PlacementDecision
import dev.duoime.core.policy.PlacementReason
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
 * Distinct identity wrapper so inline and overlay [DuoImeKeyboard] hosts never clobber each
 * other's `attachSink` / `detachSink` reference during swap transitions.
 */
private class SinkIdentityProxy(private val delegate: InputSink) : InputSink by delegate

/**
 * Standalone Android [InputMethodService] for DuoIME supporting adaptive opposite-screen
 * [OverlayHost] placement (API 30+) and full graceful Inline fallbacks.
 */
class DuoImeService : InputMethodService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val composeOwner = ImeComposeOwner()
    private val settingsFlow = MutableStateFlow(DuoImeSettings())
    private val decisionFlow = MutableStateFlow<PlacementDecision>(
        PlacementDecision.Inline(reason = PlacementReason.NO_ELIGIBLE_OPPOSITE_DISPLAY)
    )

    private lateinit var preferences: DuoImePreferences
    private lateinit var controller: KeyboardController

    private var displayManager: DisplayManager? = null
    private var pinObserverHandle: AutoCloseable? = null
    private var overlayHost: OverlayHost? = null

    private var activeEditorConnection: InputConnection? = null
    private var lastEditorInfo: EditorInfo? = null
    private var isInputViewActive: Boolean = false
    private var overlayHiddenByHost: Boolean = false
    private var placementApplied: Boolean = false
    private var lastSourceDisplayId: Int = Display.INVALID_DISPLAY
    private var lastDisplayActiveStates: Map<Int, Boolean> = emptyMap()

    private val inputSink = InputConnectionSink(
        connectionProvider = { currentInputConnection?.let(::AndroidInputConnectionBridge) }
    )
    private val inlineSinkProxy: InputSink = SinkIdentityProxy(inputSink)
    private val overlaySinkProxy: InputSink = SinkIdentityProxy(inputSink)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = handleDisplayAddedOrRemoved(displayId, removed = false)

        override fun onDisplayRemoved(displayId: Int) = handleDisplayAddedOrRemoved(displayId, removed = true)

        override fun onDisplayChanged(displayId: Int) = handleDisplayChanged(displayId)
    }

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
                onSwapScreenRequested = { handleSwapScreenRequested() },
                onDismissRequested = { hideAllKeyboardSurfaces() },
                onSwitchImeRequested = { showImePicker() },
                onHeightFractionChanged = { fraction -> handleHeightChanged(fraction) }
            )
        )
        controller.setIncludeNumberRow(initialSettings.includeNumberRow)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            overlayHost = OverlayHost(
                serviceContext = this,
                scope = serviceScope,
                controller = controller,
                inputSink = overlaySinkProxy,
                onOverlayHiddenByHost = {
                    overlayHiddenByHost = true
                    evaluateAndApplyPlacement()
                }
            )
        }

        displayManager = (getSystemService(DISPLAY_SERVICE) as? DisplayManager)?.also { dm ->
            dm.registerDisplayListener(displayListener, mainHandler)
        }
        pinObserverHandle = SystemImePinDetector.observe(contentResolver, mainHandler) {
            if (isInputViewActive) {
                evaluateAndApplyPlacement()
            }
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        attachComposeOwnerToDecor()
        return ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            composeOwner.attachToView(this)
            setContent {
                InlineImeContainer()
            }
        }
    }

    @Composable
    private fun InlineImeContainer() {
        val settings by settingsFlow.collectAsState()
        val decision by decisionFlow.collectAsState()
        val keyboardState by controller.uiState.collectAsState()

        if (decision is PlacementDecision.Overlay) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.dp)
            )
            return
        }

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
        val bgColor = if (settings.darkTheme) Color(0xFF0A0C10) else Color(0xFFECEFF4)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout))
        ) {
            if (decision.warnSystemImePin) {
                SystemImePinBanner(
                    darkTheme = settings.darkTheme,
                    onClick = { openSettingsActivity() }
                )
            }
            DuoImeKeyboard(
                controller = controller,
                sink = inlineSinkProxy,
                darkTheme = settings.darkTheme,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heightDp)
            )
        }
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val overlayActive = decisionFlow.value is PlacementDecision.Overlay && isOverlayCurrentlyShowing()
        if (overlayActive) {
            val decorHeight = window?.window?.decorView?.height ?: 0
            outInsets.contentTopInsets = decorHeight
            outInsets.visibleTopInsets = decorHeight
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.setEmpty()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && isOverlayCurrentlyShowing()) {
            hideAllKeyboardSurfaces()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        val oldIc = activeEditorConnection
        if (oldIc != null && oldIc !== currentInputConnection) {
            flushComposingOn(oldIc)
        }
        super.onStartInput(attribute, restarting)
        activeEditorConnection = currentInputConnection
        lastEditorInfo = attribute
        resetOverlayHostHideSuppression()
        inputSink.onStartInput(
            initialSelStart = attribute?.initialSelStart ?: SelectionState.NO_COMPOSING,
            initialSelEnd = attribute?.initialSelEnd ?: SelectionState.NO_COMPOSING
        )
        applyEditorInfo(attribute)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        attachComposeOwnerToDecor()
        refreshSettings()
        lastEditorInfo = info
        isInputViewActive = true
        placementApplied = false
        resetOverlayHostHideSuppression()
        applyEditorInfo(info)
        composeOwner.onResume()
        evaluateAndApplyPlacement()
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
        val shouldReset = inputSink.shouldResetOnSelectionUpdate(
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            overlayHost?.recomputeMetricsForConfigChange()
        }
        if (isInputViewActive) {
            evaluateAndApplyPlacement()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        isInputViewActive = false
        resetOverlayHostHideSuppression()
        hideOverlayWindow()
        flushComposingOn()
        controller.reset()
        composeOwner.onPauseToCreated()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        isInputViewActive = false
        resetOverlayHostHideSuppression()
        hideOverlayWindow()
        composeOwner.onPauseToCreated()
        super.onWindowHidden()
    }

    override fun onFinishInput() {
        isInputViewActive = false
        resetOverlayHostHideSuppression()
        hideOverlayWindow()
        flushComposingOn()
        activeEditorConnection = null
        lastEditorInfo = null
        controller.reset()
        super.onFinishInput()
    }

    override fun onDestroy() {
        isInputViewActive = false
        detachOverlayWindow()
        displayManager?.unregisterDisplayListener(displayListener)
        runCatching { pinObserverHandle?.close() }
        pinObserverHandle = null
        controller.close()
        serviceScope.cancel()
        composeOwner.onDestroy()
        super.onDestroy()
    }

    private fun flushComposingOn(connection: InputConnection? = activeEditorConnection ?: currentInputConnection) {
        inputSink.finishComposingOnEditorSwitch(connection?.let(::AndroidInputConnectionBridge))
    }

    private fun resetOverlayHostHideSuppression() {
        overlayHiddenByHost = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            overlayHost?.resetVisibilityTracking()
        }
    }

    private fun handleSwapScreenRequested() {
        val updated = preferences.toggleSwapToSameScreen()
        settingsFlow.value = updated
        if (!updated.swapToSameScreen) {
            resetOverlayHostHideSuppression()
        }
        // Preserve active composing state & engine snapshot across inline <-> overlay swap.
        evaluateAndApplyPlacement()
    }

    private fun handleHeightChanged(fraction: Float) {
        val updated = preferences.updateHeightFraction(fraction)
        settingsFlow.value = updated
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isOverlayCurrentlyShowing()) {
            overlayHost?.updatePresentation(updated.heightFraction, updated.darkTheme)
        }
    }

    private fun hideAllKeyboardSurfaces() {
        hideOverlayWindow()
        requestHideSelf(0)
    }

    private fun handleDisplayAddedOrRemoved(displayId: Int, removed: Boolean) {
        if (removed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            overlayHost?.attachedDisplayId == displayId
        ) {
            overlayHost?.detach()
            placementApplied = false
        }
        if (!isInputViewActive) return
        evaluateAndApplyPlacement()
    }

    private fun handleDisplayChanged(changedDisplayId: Int) {
        if (!isInputViewActive) return
        val currentActiveStates = snapshotDisplayActiveStates()
        val activeStateChanged = currentActiveStates != lastDisplayActiveStates
        val attachedId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            overlayHost?.attachedDisplayId ?: Display.INVALID_DISPLAY
        } else {
            Display.INVALID_DISPLAY
        }
        val shouldReevaluate = DisplayClassifier.shouldReevaluateOnDisplayChange(
            changedDisplayId = changedDisplayId,
            sourceDisplayId = lastSourceDisplayId,
            attachedOverlayDisplayId = attachedId,
            pairedPeerDisplayId = settingsFlow.value.pairedPeerDisplayId,
            activeStateChanged = activeStateChanged
        )
        if (!shouldReevaluate) return
        evaluateAndApplyPlacement()
    }

    private fun snapshotDisplayActiveStates(): Map<Int, Boolean> = displayManager?.displays
        ?.associate { it.displayId to DisplayClassifier.isDisplayActive(it.state) }
        .orEmpty()

    private fun evaluateAndApplyPlacement() {
        val settings = settingsFlow.value
        val dm = displayManager
        val rawDisplays = dm?.displays?.map(DisplayClassifier::fromAndroidDisplay).orEmpty()
        lastDisplayActiveStates = rawDisplays.associate {
            it.displayId to DisplayClassifier.isDisplayActive(it.state)
        }
        val candidates = DisplayClassifier.classifyDisplays(rawDisplays, settings.pairedPeerDisplayId)
        val sourceDisplayId = resolveSourceDisplayId()
        lastSourceDisplayId = sourceDisplayId
        val hasOverlayPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Settings.canDrawOverlays(this)
        val detectedPin = SystemImePinDetector.readSystemImePin(contentResolver)
        val effectivePin = SystemImePinDetector.resolveEffectivePin(detectedPin, settings.manualSystemImePin)
        val pkgName = lastEditorInfo?.packageName
        val isPassword = EditorInfoMapper.isPasswordInputType(lastEditorInfo?.inputType ?: 0)

        val decision = DisplayPlacementPolicy.resolve(
            DisplayPlacementInput(
                displays = candidates,
                sourceDisplayId = sourceDisplayId,
                hasOverlayPermission = hasOverlayPerm,
                systemImePin = effectivePin,
                isPasswordField = isPassword,
                perAppInlineOverride = pkgName != null && pkgName in settings.perAppInlinePackages,
                swapToSameScreen = settings.swapToSameScreen,
                overlayHiddenByHost = overlayHiddenByHost,
                apiLevel = Build.VERSION.SDK_INT,
                heightFraction = settings.heightFraction
            )
        )

        if (decision is PlacementDecision.Overlay && tryApplyOverlayDecision(decision, settings)) {
            return
        }
        val inlineDecision = if (decision is PlacementDecision.Inline) {
            decision
        } else {
            PlacementDecision.Inline(reason = PlacementReason.NO_ELIGIBLE_OPPOSITE_DISPLAY)
        }
        applyInlineDecision(inlineDecision)
    }

    private fun tryApplyOverlayDecision(decision: PlacementDecision.Overlay, settings: DuoImeSettings): Boolean {
        if (!isInputViewActive || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (placementApplied &&
            decision == decisionFlow.value &&
            overlayHost?.isShowingOn(decision.targetDisplayId, decision.heightFraction, settings.darkTheme) == true
        ) {
            return true
        }
        val targetDisplay = displayManager?.getDisplay(decision.targetDisplayId) ?: return false
        val shown = overlayHost?.show(targetDisplay, decision.heightFraction, settings.darkTheme) == true
        if (!shown) return false
        placementApplied = true
        decisionFlow.value = decision
        controller.updateOverlayMode(true)
        requestInsetsRefresh()
        return true
    }

    private fun applyInlineDecision(inlineDecision: PlacementDecision.Inline) {
        if (placementApplied && inlineDecision == decisionFlow.value && !isOverlayCurrentlyShowing()) {
            return
        }
        hideOverlayWindow()
        controller.attachSink(inlineSinkProxy)
        placementApplied = true
        decisionFlow.value = inlineDecision
        controller.updateOverlayMode(false)
        requestInsetsRefresh()
    }

    private fun resolveSourceDisplayId(): Int = window?.window?.decorView?.display?.displayId ?: Display.DEFAULT_DISPLAY

    private fun requestInsetsRefresh() {
        window?.window?.decorView?.requestLayout()
    }

    private fun isOverlayCurrentlyShowing(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && overlayHost?.isShowing == true

    private fun hideOverlayWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            overlayHost?.hide()
        }
    }

    private fun detachOverlayWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            overlayHost?.detach()
        }
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

    private fun openSettingsActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
    }
}

@Composable
private fun SystemImePinBanner(darkTheme: Boolean, onClick: () -> Unit) {
    val bg = if (darkTheme) Color(0xFF2B2315) else Color(0xFFFFF4D6)
    val fg = if (darkTheme) Color(0xFFFFD88A) else Color(0xFF664400)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        BasicText(
            text = stringResource(R.string.pin_banner_text),
            maxLines = 2,
            style = TextStyle(
                color = fg,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

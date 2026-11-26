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
import androidx.annotation.RequiresApi
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
import dev.duoime.app.dualscreen.DualScreenSessionController
import dev.duoime.app.dualscreen.OverlayHost
import dev.duoime.app.dualscreen.OverlaySurface
import dev.duoime.app.dualscreen.PlacementEvaluationSnapshot
import dev.duoime.app.dualscreen.SystemImePinDetector
import dev.duoime.app.settings.DuoImePreferences
import dev.duoime.app.settings.DuoImeSettings
import dev.duoime.core.engine.InputSink
import dev.duoime.core.policy.DisplayPlacementInput
import dev.duoime.core.policy.PlacementDecision
import dev.duoime.core.policy.PlacementReason
import dev.duoime.embed.DuoImeKeyboard
import dev.duoime.embed.KeyboardController
import dev.duoime.embed.KeyboardControllerConfig
import dev.duoime.embed.KeyboardHostCallbacks
import dev.duoime.ui.feedback.KeyFeedbackController
import dev.duoime.ui.feedback.KeyFeedbackOptions
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
    private lateinit var sessionController: DualScreenSessionController
    private lateinit var feedbackController: KeyFeedbackController

    private var displayManager: DisplayManager? = null
    private var pinObserverHandle: AutoCloseable? = null
    private var prefsObserverHandle: AutoCloseable? = null
    private var overlayHost: OverlayHost? = null
    private var inlineInputView: View? = null
    private var cachedFeedbackOptions: KeyFeedbackOptions = KeyFeedbackOptions()
    private var isServiceDestroyed: Boolean = false

    private var activeEditorConnection: InputConnection? = null
    private var lastEditorInfo: EditorInfo? = null
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
        cachedFeedbackOptions = initialSettings.toFeedbackOptions()
        feedbackController = KeyFeedbackController(this)

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
                onHeightFractionChanged = { fraction -> handleHeightChanged(fraction) },
                onFeedbackKey = { key -> feedbackController.onKeyPress(key, cachedFeedbackOptions) },
                onRepeatFeedbackKey = { key -> feedbackController.onKeyRepeat(key, cachedFeedbackOptions) }
            )
        )
        controller.setIncludeNumberRow(initialSettings.includeNumberRow)

        val overlaySurface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            OverlayHost(
                serviceContext = this,
                scope = serviceScope,
                controller = controller,
                inputSink = overlaySinkProxy,
                onOverlayHiddenByHost = { sessionController.onOverlayHiddenByHost() }
            ).also { overlayHost = it }.let(::createOverlaySurfaceAdapter)
        } else {
            null
        }
        sessionController = DualScreenSessionController(
            overlaySurface = overlaySurface,
            evaluatePlacementInput = { buildPlacementEvaluationSnapshot() },
            onOverlayApplied = { decision ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    feedbackController.attachFallbackView(overlayHost?.activeView)
                }
                decisionFlow.value = decision
                controller.updateOverlayMode(true)
                requestInsetsRefresh()
            },
            onInlineApplied = { inlineDecision ->
                feedbackController.attachFallbackView(inlineInputView)
                controller.attachSink(inlineSinkProxy)
                decisionFlow.value = inlineDecision
                controller.updateOverlayMode(false)
                requestInsetsRefresh()
            }
        )

        displayManager = (getSystemService(DISPLAY_SERVICE) as? DisplayManager)?.also { dm ->
            dm.registerDisplayListener(displayListener, mainHandler)
        }
        pinObserverHandle = SystemImePinDetector.observe(contentResolver, mainHandler) {
            sessionController.onRelevantEnvironmentChanged()
        }
        prefsObserverHandle = preferences.observe {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                applyLiveSettings(preferences.load())
            } else {
                mainHandler.post { applyLiveSettings(preferences.load()) }
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
        }.also { view ->
            inlineInputView = view
            feedbackController.attachFallbackView(view)
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
        inputSink.onStartInput(
            initialSelStart = attribute?.initialSelStart ?: SelectionState.NO_COMPOSING,
            initialSelEnd = attribute?.initialSelEnd ?: SelectionState.NO_COMPOSING
        )
        applyEditorInfo(attribute)
        sessionController.onStartInput()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        attachComposeOwnerToDecor()
        refreshSettings()
        lastEditorInfo = info
        applyEditorInfo(info)
        composeOwner.onResume()
        sessionController.onStartInputView()
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
        sessionController.onRelevantEnvironmentChanged()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        sessionController.onFinishInputView()
        feedbackController.onInputHidden()
        flushComposingOn()
        controller.reset()
        composeOwner.onPauseToCreated()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        sessionController.onWindowHidden()
        feedbackController.onInputHidden()
        composeOwner.onPauseToCreated()
        super.onWindowHidden()
    }

    override fun onFinishInput() {
        sessionController.onFinishInput()
        flushComposingOn()
        activeEditorConnection = null
        lastEditorInfo = null
        controller.reset()
        super.onFinishInput()
    }

    override fun onDestroy() {
        isServiceDestroyed = true
        sessionController.onDestroy()
        inlineInputView = null
        feedbackController.release()
        displayManager?.unregisterDisplayListener(displayListener)
        runCatching { pinObserverHandle?.close() }
        pinObserverHandle = null
        runCatching { prefsObserverHandle?.close() }
        prefsObserverHandle = null
        controller.close()
        serviceScope.cancel()
        composeOwner.onDestroy()
        super.onDestroy()
    }

    private fun flushComposingOn(connection: InputConnection? = activeEditorConnection ?: currentInputConnection) {
        inputSink.finishComposingOnEditorSwitch(connection?.let(::AndroidInputConnectionBridge))
    }

    private fun handleSwapScreenRequested() {
        val updated = preferences.toggleSwapToSameScreen()
        settingsFlow.value = updated
        cachedFeedbackOptions = updated.toFeedbackOptions()
        // Preserve active composing state & engine snapshot across inline <-> overlay swap.
        sessionController.onSwapScreenToggled(updated.swapToSameScreen)
    }

    private fun handleHeightChanged(fraction: Float) {
        val updated = preferences.updateHeightFraction(fraction)
        settingsFlow.value = updated
        cachedFeedbackOptions = updated.toFeedbackOptions()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isOverlayCurrentlyShowing()) {
            overlayHost?.updatePresentation(updated.heightFraction, updated.darkTheme)
        }
    }

    private fun hideAllKeyboardSurfaces() {
        hideOverlayWindow()
        requestHideSelf(0)
    }

    private fun handleDisplayAddedOrRemoved(displayId: Int, removed: Boolean) {
        if (removed) {
            sessionController.onDisplayRemoved(displayId)
        } else {
            sessionController.onRelevantEnvironmentChanged()
        }
    }

    private fun handleDisplayChanged(changedDisplayId: Int) {
        if (!sessionController.isInputViewActive) return
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
        sessionController.onRelevantEnvironmentChanged()
    }

    private fun snapshotDisplayActiveStates(): Map<Int, Boolean> = displayManager?.displays
        ?.associate { it.displayId to DisplayClassifier.isDisplayActive(it.state) }
        .orEmpty()

    private fun buildPlacementEvaluationSnapshot(): PlacementEvaluationSnapshot {
        val settings = settingsFlow.value
        val rawDisplays = displayManager?.displays?.map(DisplayClassifier::fromAndroidDisplay).orEmpty()
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
        return PlacementEvaluationSnapshot(
            input = DisplayPlacementInput(
                displays = candidates,
                sourceDisplayId = sourceDisplayId,
                hasOverlayPermission = hasOverlayPerm,
                systemImePin = effectivePin,
                isPasswordField = isPassword,
                perAppInlineOverride = pkgName != null && pkgName in settings.perAppInlinePackages,
                swapToSameScreen = settings.swapToSameScreen,
                overlayHiddenByHost = sessionController.overlayHiddenByHost,
                apiLevel = Build.VERSION.SDK_INT,
                heightFraction = settings.heightFraction
            ),
            darkTheme = settings.darkTheme
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createOverlaySurfaceAdapter(host: OverlayHost): OverlaySurface = object : OverlaySurface {
        override val isShowing: Boolean
            get() = host.isShowing

        override val attachedDisplayId: Int
            get() = host.attachedDisplayId

        override fun isShowingOn(displayId: Int, heightFraction: Float, darkTheme: Boolean): Boolean =
            host.isShowingOn(displayId, heightFraction, darkTheme)

        override fun show(targetDisplayId: Int, heightFraction: Float, darkTheme: Boolean): Boolean {
            val targetDisplay = displayManager?.getDisplay(targetDisplayId) ?: return false
            return host.show(targetDisplay, heightFraction, darkTheme)
        }

        override fun hide() {
            host.hide()
        }

        override fun detach() {
            host.detach()
        }

        override fun resetVisibilityTracking() {
            host.resetVisibilityTracking()
        }
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

    private fun attachComposeOwnerToDecor() {
        window?.window?.decorView?.let { decor ->
            composeOwner.attachToView(decor)
        }
    }

    private fun refreshSettings() {
        val latest = preferences.load()
        settingsFlow.value = latest
        cachedFeedbackOptions = latest.toFeedbackOptions()
        controller.setIncludeNumberRow(latest.includeNumberRow)
    }

    private fun applyLiveSettings(updated: DuoImeSettings) {
        if (isServiceDestroyed) return
        val previous = settingsFlow.value
        if (updated == previous) return
        settingsFlow.value = updated
        cachedFeedbackOptions = updated.toFeedbackOptions()
        controller.setIncludeNumberRow(updated.includeNumberRow)
        if (updated.alwaysIncognito != previous.alwaysIncognito) {
            applyEditorInfo(lastEditorInfo)
        }
        if (!sessionController.isInputViewActive) return

        val presentationChanged =
            updated.heightFraction != previous.heightFraction ||
                updated.darkTheme != previous.darkTheme
        if (presentationChanged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isOverlayCurrentlyShowing()) {
            overlayHost?.updatePresentation(updated.heightFraction, updated.darkTheme)
        }
        val placementChanged = presentationChanged ||
            updated.swapToSameScreen != previous.swapToSameScreen ||
            updated.pairedPeerDisplayId != previous.pairedPeerDisplayId ||
            updated.manualSystemImePin != previous.manualSystemImePin ||
            updated.perAppInlinePackages != previous.perAppInlinePackages
        if (placementChanged) {
            sessionController.onRelevantEnvironmentChanged()
        }
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

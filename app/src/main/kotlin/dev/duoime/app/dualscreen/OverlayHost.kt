// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.dualscreen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import dev.duoime.app.ime.ImeComposeOwner
import dev.duoime.core.engine.InputSink
import dev.duoime.embed.DuoImeKeyboard
import dev.duoime.embed.KeyboardController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

private data class OverlayRenderConfig(
    val darkTheme: Boolean = true,
    val bottomInsetPx: Int = 0
)

@SuppressLint("ViewConstructor")
private class OverlayRootView(
    context: Context,
    private val onWindowVisibilitySignal: (Boolean) -> Unit
) : FrameLayout(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        onWindowVisibilitySignal(visibility == View.VISIBLE)
    }
}

/**
 * Manages a non-focusable `TYPE_APPLICATION_OVERLAY` Compose window on an opposite built-in
 * display (API 30+).
 *
 * - Keeps the overlay attached across show/hide cycles (toggling visibility + `FLAG_NOT_TOUCHABLE`)
 *   to eliminate cold relayout jank; detaches and destroys its dedicated [ImeComposeOwner] on
 *   display changes, swap-detach, or service destroy (zero `WindowLeaked`).
 * - Bleeds keyboard background edge-to-edge (`setFitInsetsTypes(0)`) while padding Compose content
 *   above target-display `navigationBars | displayCutout` insets.
 * - Detects silent overlay hiding (`onWindowVisibilityChanged(!VISIBLE)` debounced by 150 ms) and
 *   reports back via [onOverlayHiddenByHost] so the service degrades to Inline in the same session.
 */
@RequiresApi(Build.VERSION_CODES.R)
class OverlayHost(
    private val serviceContext: Context,
    private val scope: CoroutineScope,
    private val controller: KeyboardController,
    private val inputSink: InputSink,
    private val onOverlayHiddenByHost: () -> Unit
) {
    private val renderConfigFlow = MutableStateFlow(OverlayRenderConfig())
    private val showTracker = OverlayShowTracker(
        scope = scope,
        onHiddenConfirmed = onOverlayHiddenByHost
    )

    private var windowManager: WindowManager? = null
    private var rootView: OverlayRootView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var composeOwner: ImeComposeOwner? = null

    val attachedDisplayId: Int
        get() = showTracker.attachedDisplayId

    val isShowing: Boolean
        get() = showTracker.isShowing

    fun isShowingOn(displayId: Int, heightFraction: Float, darkTheme: Boolean): Boolean {
        val root = rootView ?: return false
        return showTracker.shouldSkipShow(
            displayId = displayId,
            heightFraction = heightFraction,
            darkTheme = darkTheme,
            isWindowVisible = root.windowVisibility == View.VISIBLE
        )
    }

    fun show(targetDisplay: Display, heightFraction: Float, darkTheme: Boolean): Boolean {
        if (isShowingOn(targetDisplay.displayId, heightFraction, darkTheme)) {
            return true
        }
        if (attachedDisplayId != targetDisplay.displayId || rootView == null) {
            detach()
            if (!attachToDisplay(targetDisplay, heightFraction, darkTheme)) {
                return false
            }
        } else {
            updatePresentation(heightFraction, darkTheme)
        }

        val root = rootView ?: return false
        val wm = windowManager ?: return false
        val lp = layoutParams ?: return false

        showTracker.beginShow(targetDisplay.displayId, heightFraction, darkTheme)
        controller.attachSink(inputSink)
        lp.flags = ACTIVE_FLAGS
        root.visibility = View.VISIBLE
        composeOwner?.onResume()
        val updated = runCatching {
            wm.updateViewLayout(root, lp)
            true
        }.getOrElse {
            detach()
            false
        }
        if (updated) {
            showTracker.probeWindowVisibility(isWindowVisible = root.windowVisibility == View.VISIBLE)
        }
        return updated
    }

    fun resetVisibilityTracking() {
        showTracker.resetVisibilityTracking()
    }

    fun hide() {
        showTracker.markHidden()
        composeOwner?.onPauseToCreated()
        val root = rootView ?: return
        val wm = windowManager ?: return
        val lp = layoutParams ?: return
        lp.flags = HIDDEN_FLAGS
        root.visibility = View.GONE
        runCatching { wm.updateViewLayout(root, lp) }
    }

    fun updatePresentation(heightFraction: Float, darkTheme: Boolean) {
        showTracker.updatePresentation(heightFraction, darkTheme)
        val wm = windowManager ?: return
        val root = rootView ?: return
        val lp = layoutParams ?: return

        val metrics = computeMetricsForWindowManager(wm, heightFraction)
        renderConfigFlow.value = OverlayRenderConfig(
            darkTheme = darkTheme,
            bottomInsetPx = metrics.bottomInsetPx
        )
        if (lp.height != metrics.windowHeightPx) {
            lp.height = metrics.windowHeightPx
            runCatching { wm.updateViewLayout(root, lp) }
        }
    }

    fun recomputeMetricsForConfigChange() {
        val wm = windowManager ?: return
        val root = rootView ?: return
        val lp = layoutParams ?: return
        val metrics = computeMetricsForWindowManager(wm, showTracker.lastHeightFraction)
        renderConfigFlow.value = renderConfigFlow.value.copy(bottomInsetPx = metrics.bottomInsetPx)
        lp.height = metrics.windowHeightPx
        runCatching { wm.updateViewLayout(root, lp) }
    }

    fun detach() {
        showTracker.markDetached()
        val root = rootView
        val wm = windowManager
        val owner = composeOwner

        rootView = null
        windowManager = null
        layoutParams = null
        composeOwner = null

        if (root != null && wm != null) {
            runCatching { wm.removeViewImmediate(root) }
        }
        owner?.onDestroy()
    }

    private fun attachToDisplay(targetDisplay: Display, heightFraction: Float, darkTheme: Boolean): Boolean {
        val windowContext = runCatching {
            serviceContext
                .createDisplayContext(targetDisplay)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        }.getOrNull() ?: return false

        val wm = windowContext.getSystemService(WindowManager::class.java) ?: return false
        val metrics = computeMetricsForWindowManager(wm, heightFraction)
        renderConfigFlow.value = OverlayRenderConfig(
            darkTheme = darkTheme,
            bottomInsetPx = metrics.bottomInsetPx
        )

        val owner = ImeComposeOwner().apply { onCreate() }
        val root = OverlayRootView(windowContext) { visible ->
            showTracker.onWindowVisibilityChanged(isVisible = visible)
        }
        owner.attachToView(root)

        val composeView = ComposeView(windowContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            owner.attachToView(this)
            setContent {
                val cfg by renderConfigFlow.collectAsState()
                val density = LocalDensity.current
                val bottomPadDp = with(density) { cfg.bottomInsetPx.toDp() }
                val bgColor = if (cfg.darkTheme) Color(0xFF0A0C10) else Color(0xFFECEFF4)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bgColor)
                        .padding(bottom = bottomPadDp)
                ) {
                    DuoImeKeyboard(
                        controller = controller,
                        sink = inputSink,
                        darkTheme = cfg.darkTheme,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        root.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            metrics.windowHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            ACTIVE_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.FILL_HORIZONTAL
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        val added = runCatching {
            wm.addView(root, lp)
            true
        }.getOrDefault(false)

        if (!added) {
            owner.onDestroy()
            return false
        }

        windowManager = wm
        rootView = root
        layoutParams = lp
        composeOwner = owner
        return true
    }

    private fun computeMetricsForWindowManager(wm: WindowManager, heightFraction: Float): OverlayLayoutMetrics {
        val windowMetrics = wm.currentWindowMetrics
        val displayHeight = windowMetrics.bounds.height()
        val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        return OverlayLayoutCalculator.computeOverlayLayoutMetrics(
            displayHeightPx = displayHeight,
            topInsetPx = insets.top,
            bottomInsetPx = insets.bottom,
            heightFraction = heightFraction
        )
    }

    companion object {
        private const val ACTIVE_FLAGS: Int =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        private const val HIDDEN_FLAGS: Int =
            ACTIVE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }
}

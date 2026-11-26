// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.dualscreen

import dev.duoime.core.policy.DisplayPlacementPolicy
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Edge-to-edge overlay window metrics computed for a target display.
 *
 * - [windowHeightPx] covers the keyboard content plus [bottomInsetPx] so the keyboard background
 *   draws seamlessly under gesture/3-button navigation bars without ever intruding into [topInsetPx].
 * - [contentHeightPx] is the usable keyboard area padded above [bottomInsetPx].
 */
data class OverlayLayoutMetrics(
    val windowHeightPx: Int,
    val contentHeightPx: Int,
    val bottomInsetPx: Int
)

object OverlayLayoutCalculator {
    /**
     * Computes overlay window height from target display metrics:
     * `windowHeightPx = (clampHeightFraction(heightFraction) * (displayHeightPx - topInsetPx)) + bottomInsetPx`,
     * capped at `displayHeightPx - topInsetPx` so 90% height can never collide with the status bar
     * or display cutout even when a tall 3-button navigation bar is active.
     */
    fun computeOverlayLayoutMetrics(
        displayHeightPx: Int,
        topInsetPx: Int,
        bottomInsetPx: Int,
        heightFraction: Float
    ): OverlayLayoutMetrics {
        val safeDisplayHeight = displayHeightPx.coerceAtLeast(1)
        val safeTop = topInsetPx.coerceIn(0, (safeDisplayHeight - 1).coerceAtLeast(0))
        val safeBottom = bottomInsetPx.coerceIn(0, (safeDisplayHeight - safeTop - 1).coerceAtLeast(0))
        val maxWindowHeight = (safeDisplayHeight - safeTop).coerceAtLeast(1)
        val clampedFraction = DisplayPlacementPolicy.clampHeightFraction(heightFraction)

        val rawWindowHeight = (clampedFraction * maxWindowHeight).roundToInt() + safeBottom
        val windowHeight = rawWindowHeight.coerceIn(safeBottom + 1, maxWindowHeight)
        val contentHeight = (windowHeight - safeBottom).coerceAtLeast(1)
        return OverlayLayoutMetrics(
            windowHeightPx = windowHeight,
            contentHeightPx = contentHeight,
            bottomInsetPx = safeBottom
        )
    }
}

/**
 * Pure-JVM debouncer for detecting when the system or host app silently hides the overlay window
 * (`setHideOverlayWindows` / `FLAG_SECURE` banking apps) while the IME session is active.
 *
 * Debounces `!VISIBLE` signals by [debounceMs] (~150 ms) so transient show/hide transitions never
 * trigger a false inline fallback.
 */
class OverlayVisibilityDebouncer(
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val onHiddenConfirmed: () -> Unit
) {
    private var pendingHideJob: Job? = null
    private var confirmedHidden: Boolean = false

    fun onVisibilityChanged(isVisible: Boolean, isSessionActive: Boolean) {
        if (!isSessionActive || isVisible) {
            pendingHideJob?.cancel()
            pendingHideJob = null
            return
        }
        if (confirmedHidden || pendingHideJob != null) return
        pendingHideJob = scope.launch {
            delay(debounceMs)
            pendingHideJob = null
            confirmedHidden = true
            onHiddenConfirmed()
        }
    }

    fun reset() {
        pendingHideJob?.cancel()
        pendingHideJob = null
        confirmedHidden = false
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS: Long = 150L
    }
}

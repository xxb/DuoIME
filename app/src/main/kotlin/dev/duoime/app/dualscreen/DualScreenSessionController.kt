// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.dualscreen

import dev.duoime.core.policy.DisplayPlacementInput
import dev.duoime.core.policy.DisplayPlacementPolicy
import dev.duoime.core.policy.PlacementDecision
import dev.duoime.core.policy.PlacementReason
import dev.duoime.ui.theme.DuoThemePreset

/**
 * Platform-neutral abstraction over the opposite-screen overlay window host, enabling pure-JVM
 * lifecycle and placement verification without Robolectric.
 */
interface OverlaySurface {
    val isShowing: Boolean
    val attachedDisplayId: Int

    fun isShowingOn(displayId: Int, heightFraction: Float, themePreset: DuoThemePreset): Boolean

    fun show(targetDisplayId: Int, heightFraction: Float, themePreset: DuoThemePreset): Boolean

    fun hide()

    fun detach()

    fun resetVisibilityTracking()
}

/**
 * Snapshot of placement policy inputs and presentation flags captured for a single evaluation pass.
 */
data class PlacementEvaluationSnapshot(
    val input: DisplayPlacementInput,
    val themePreset: DuoThemePreset
)

/**
 * Pure-JVM session lifecycle coordinator for dual-screen overlay vs. inline IME placement.
 *
 * Scopes host-hide suppression strictly to the active visible input-view session so same-session
 * `onStartInput` editor transitions apply immediately (e.g., password field or per-app inline
 * overrides) without clearing suppression or restarting an actively ticking host-hide probe.
 */
class DualScreenSessionController(
    private val overlaySurface: OverlaySurface?,
    private val evaluatePlacementInput: () -> PlacementEvaluationSnapshot,
    private val onOverlayApplied: (PlacementDecision.Overlay) -> Unit,
    private val onInlineApplied: (PlacementDecision.Inline) -> Unit
) {
    var isInputViewActive: Boolean = false
        private set
    var placementApplied: Boolean = false
        private set
    var currentDecision: PlacementDecision? = null
        private set

    private val overlaySuppression = OverlaySessionSuppression(
        onResetHostTracking = { overlaySurface?.resetVisibilityTracking() },
        onFallback = { evaluateAndApplyPlacement() }
    )

    val overlayHiddenByHost: Boolean
        get() = overlaySuppression.overlayHiddenByHost

    fun onStartInput() {
        if (isInputViewActive) {
            evaluateAndApplyPlacement()
        }
    }

    fun onStartInputView() {
        isInputViewActive = true
        placementApplied = false
        overlaySuppression.resetForSessionBoundary()
        evaluateAndApplyPlacement()
    }

    fun onFinishInputView() {
        endActiveSession()
    }

    fun onWindowHidden() {
        endActiveSession()
    }

    fun onFinishInput() {
        endActiveSession()
    }

    fun onSwapScreenToggled(swapToSameScreen: Boolean) {
        if (!swapToSameScreen) {
            overlaySuppression.resetForSessionBoundary()
        }
        evaluateAndApplyPlacement()
    }

    fun onOverlayHiddenByHost() {
        overlaySuppression.onOverlayHiddenConfirmed()
    }

    fun onDisplayRemoved(displayId: Int) {
        if (overlaySurface?.attachedDisplayId == displayId) {
            overlaySurface.detach()
            placementApplied = false
        }
        if (isInputViewActive) {
            evaluateAndApplyPlacement()
        }
    }

    fun onRelevantEnvironmentChanged() {
        if (isInputViewActive) {
            evaluateAndApplyPlacement()
        }
    }

    fun onDestroy() {
        isInputViewActive = false
        overlaySurface?.detach()
    }

    fun evaluateAndApplyPlacement() {
        val snapshot = evaluatePlacementInput()
        val decision = DisplayPlacementPolicy.resolve(snapshot.input)
        if (decision is PlacementDecision.Overlay && tryApplyOverlayDecision(decision, snapshot.themePreset)) {
            return
        }
        val inlineDecision = if (decision is PlacementDecision.Inline) {
            decision
        } else {
            PlacementDecision.Inline(reason = PlacementReason.NO_ELIGIBLE_OPPOSITE_DISPLAY)
        }
        applyInlineDecision(inlineDecision)
    }

    private fun endActiveSession() {
        isInputViewActive = false
        overlaySuppression.resetForSessionBoundary()
        overlaySurface?.hide()
    }

    private fun tryApplyOverlayDecision(decision: PlacementDecision.Overlay, themePreset: DuoThemePreset): Boolean {
        val surface = overlaySurface ?: return false
        if (!isInputViewActive) return false
        if (placementApplied &&
            decision == currentDecision &&
            surface.isShowingOn(decision.targetDisplayId, decision.heightFraction, themePreset)
        ) {
            return true
        }
        val shown = surface.show(decision.targetDisplayId, decision.heightFraction, themePreset)
        if (!shown) return false
        placementApplied = true
        currentDecision = decision
        onOverlayApplied(decision)
        return true
    }

    private fun applyInlineDecision(inlineDecision: PlacementDecision.Inline) {
        val surfaceShowing = overlaySurface?.isShowing == true
        if (placementApplied && inlineDecision == currentDecision && !surfaceShowing) {
            return
        }
        overlaySurface?.hide()
        placementApplied = true
        currentDecision = inlineDecision
        onInlineApplied(inlineDecision)
    }
}

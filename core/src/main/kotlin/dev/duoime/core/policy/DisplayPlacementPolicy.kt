// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.policy

import dev.duoime.core.DuoImeCoreInfo

/**
 * Classification of a connected Android display.
 * Only [BUILT_IN] displays are eligible targets for opposite-screen IME overlays.
 */
enum class DisplayKind {
    BUILT_IN,
    EXTERNAL,
    VIRTUAL,
    PRESENTATION_ONLY
}

/**
 * Platform-neutral description of a display inspected by [DisplayPlacementPolicy].
 */
data class DisplayCandidate(
    val displayId: Int,
    val kind: DisplayKind,
    val isOn: Boolean,
    val widthPx: Int = 0,
    val heightPx: Int = 0
)

/**
 * Immutable input snapshot evaluated by [DisplayPlacementPolicy.resolve].
 */
data class DisplayPlacementInput(
    val displays: List<DisplayCandidate>,
    val sourceDisplayId: Int,
    val hasOverlayPermission: Boolean,
    val systemImePin: Boolean? = null,
    val isPasswordField: Boolean = false,
    val perAppInlineOverride: Boolean = false,
    val swapToSameScreen: Boolean = false,
    val overlayHiddenByHost: Boolean = false,
    val apiLevel: Int = DisplayPlacementPolicy.MIN_OVERLAY_API_LEVEL,
    val heightFraction: Float = DisplayPlacementPolicy.DEFAULT_HEIGHT_FRACTION
)

/**
 * Auditable reason explaining why a particular placement decision was chosen.
 */
enum class PlacementReason {
    OPPOSITE_DISPLAY,
    API_BELOW_30,
    NO_OVERLAY_PERMISSION,
    SYSTEM_IME_PIN_ACTIVE,
    PASSWORD_FIELD,
    PER_APP_INLINE_OVERRIDE,
    USER_SWAPPED_TO_SAME_SCREEN,
    OVERLAY_HIDDEN_BY_HOST,
    NO_ELIGIBLE_OPPOSITE_DISPLAY
}

/**
 * Output of [DisplayPlacementPolicy.resolve].
 */
sealed interface PlacementDecision {
    val reason: PlacementReason
    val warnSystemImePin: Boolean

    data class Inline(
        override val reason: PlacementReason,
        override val warnSystemImePin: Boolean = false
    ) : PlacementDecision

    data class Overlay(
        val targetDisplayId: Int,
        val heightFraction: Float,
        override val reason: PlacementReason = PlacementReason.OPPOSITE_DISPLAY,
        override val warnSystemImePin: Boolean = false
    ) : PlacementDecision
}

/**
 * Pure dual-screen keyboard placement policy.
 * Never hard-codes display IDs (such as 0 or 2) and degrades safely to [PlacementDecision.Inline]
 * whenever overlay rendering is unsupported, blocked, restricted, or lacks an active built-in peer display.
 */
object DisplayPlacementPolicy {
    const val MIN_OVERLAY_API_LEVEL: Int = 30
    const val MIN_HEIGHT_FRACTION: Float = DuoImeCoreInfo.MIN_OVERLAY_HEIGHT_FRACTION
    const val MAX_HEIGHT_FRACTION: Float = DuoImeCoreInfo.MAX_OVERLAY_HEIGHT_FRACTION
    const val DEFAULT_HEIGHT_FRACTION: Float = 0.75f

    fun clampHeightFraction(fraction: Float): Float {
        if (!fraction.isFinite()) return DEFAULT_HEIGHT_FRACTION
        return fraction.coerceIn(MIN_HEIGHT_FRACTION, MAX_HEIGHT_FRACTION)
    }

    fun resolve(input: DisplayPlacementInput): PlacementDecision {
        val inlineFallback = resolveInlineFallback(input)
        if (inlineFallback != null) return inlineFallback

        val target = input.displays.firstOrNull { candidate ->
            candidate.displayId != input.sourceDisplayId &&
                candidate.kind == DisplayKind.BUILT_IN &&
                candidate.isOn
        } ?: return PlacementDecision.Inline(reason = PlacementReason.NO_ELIGIBLE_OPPOSITE_DISPLAY)

        return PlacementDecision.Overlay(
            targetDisplayId = target.displayId,
            heightFraction = clampHeightFraction(input.heightFraction),
            reason = PlacementReason.OPPOSITE_DISPLAY
        )
    }

    private fun resolveInlineFallback(input: DisplayPlacementInput): PlacementDecision.Inline? = when {
        input.apiLevel < MIN_OVERLAY_API_LEVEL ->
            PlacementDecision.Inline(reason = PlacementReason.API_BELOW_30)
        !input.hasOverlayPermission ->
            PlacementDecision.Inline(reason = PlacementReason.NO_OVERLAY_PERMISSION)
        input.systemImePin == true ->
            PlacementDecision.Inline(
                reason = PlacementReason.SYSTEM_IME_PIN_ACTIVE,
                warnSystemImePin = true
            )
        input.isPasswordField ->
            PlacementDecision.Inline(reason = PlacementReason.PASSWORD_FIELD)
        input.perAppInlineOverride ->
            PlacementDecision.Inline(reason = PlacementReason.PER_APP_INLINE_OVERRIDE)
        input.swapToSameScreen ->
            PlacementDecision.Inline(reason = PlacementReason.USER_SWAPPED_TO_SAME_SCREEN)
        input.overlayHiddenByHost ->
            PlacementDecision.Inline(reason = PlacementReason.OVERLAY_HIDDEN_BY_HOST)
        else -> null
    }
}

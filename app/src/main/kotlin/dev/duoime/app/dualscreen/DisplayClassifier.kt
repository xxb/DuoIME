// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.dualscreen

import android.view.Display
import dev.duoime.core.policy.DisplayCandidate
import dev.duoime.core.policy.DisplayKind
import java.lang.reflect.Method

/**
 * Platform-neutral display metadata snapshot used by [DisplayClassifier].
 */
data class RawDisplayInfo(
    val displayId: Int,
    val flags: Int,
    val state: Int,
    val reflectedType: Int? = null,
    val description: String = "",
    val widthPx: Int = 0,
    val heightPx: Int = 0
)

/**
 * Classifies connected Android displays into `:core` [DisplayCandidate] values without hard-coding
 * OEM display IDs.
 *
 * Rules:
 * 1. Ignores `FLAG_PRESENTATION` alone because built-in secondary displays on dual-screen handhelds
 *    (e.g. `INTERNAL + FLAG_TRUSTED + FLAG_PRESENTATION`) legitimately advertise it.
 * 2. Excludes `FLAG_PRIVATE` (mapped to [DisplayKind.VIRTUAL]) and treats `STATE_UNKNOWN` / off /
 *    doze states as `isOn = false` (`isOn = state == STATE_ON || state == STATE_ON_SUSPEND`).
 * 3. Safely probes hidden `Display.getType()` via `runCatching` reflection (with `Display.toString()`
 *    fallback) strictly to identify and exclude `VIRTUAL`, `WIFI`, `OVERLAY`, and `EXTERNAL` displays.
 * 4. Honors persisted calibration [pairedPeerDisplayId]: prioritizes the paired peer display and
 *    demotes dynamically added third displays (casting/screen-recording) once calibrated.
 */
object DisplayClassifier {
    const val DEFAULT_DISPLAY_ID: Int = 0
    const val FLAG_PRIVATE: Int = 1 shl 2
    const val FLAG_PRESENTATION: Int = 1 shl 3
    const val FLAG_TRUSTED: Int = 1 shl 7
    const val FLAG_ALLOWED_TO_BE_DEFAULT: Int = 1 shl 11

    const val STATE_UNKNOWN: Int = 0
    const val STATE_OFF: Int = 1
    const val STATE_ON: Int = 2
    const val STATE_DOZE: Int = 3
    const val STATE_DOZE_SUSPEND: Int = 4
    const val STATE_VR: Int = 5
    const val STATE_ON_SUSPEND: Int = 6

    const val TYPE_INTERNAL: Int = 1
    const val TYPE_EXTERNAL: Int = 2
    const val TYPE_WIFI: Int = 3
    const val TYPE_OVERLAY: Int = 4
    const val TYPE_VIRTUAL: Int = 5

    private val getTypeMethod: Method? by lazy {
        runCatching { Display::class.java.getMethod("getType") }.getOrNull()
    }

    fun fromAndroidDisplay(display: Display): RawDisplayInfo {
        val reflected = runCatching { getTypeMethod?.invoke(display) as? Int }.getOrNull()
        val mode = runCatching { display.mode }.getOrNull()
        return RawDisplayInfo(
            displayId = display.displayId,
            flags = display.flags,
            state = display.state,
            reflectedType = reflected,
            description = runCatching { display.toString() }.getOrDefault(""),
            widthPx = mode?.physicalWidth ?: 0,
            heightPx = mode?.physicalHeight ?: 0
        )
    }

    fun classifyDisplays(displays: List<RawDisplayInfo>, pairedPeerDisplayId: Int? = null): List<DisplayCandidate> {
        val classified = displays.map { raw ->
            DisplayCandidate(
                displayId = raw.displayId,
                kind = classifyKind(raw, pairedPeerDisplayId),
                isOn = isDisplayActive(raw.state),
                widthPx = raw.widthPx,
                heightPx = raw.heightPx
            )
        }
        if (pairedPeerDisplayId == null) return classified
        return classified.sortedBy { candidate ->
            when (candidate.displayId) {
                pairedPeerDisplayId -> 0
                DEFAULT_DISPLAY_ID -> 1
                else -> 2
            }
        }
    }

    fun classifyKind(raw: RawDisplayInfo, pairedPeerDisplayId: Int? = null): DisplayKind {
        if ((raw.flags and FLAG_PRIVATE) != 0) {
            return DisplayKind.VIRTUAL
        }
        val detectedType = raw.reflectedType ?: parseTypeFromDescription(raw.description)
        val baseKind = when (detectedType) {
            TYPE_VIRTUAL, TYPE_WIFI, TYPE_OVERLAY -> DisplayKind.VIRTUAL
            TYPE_EXTERNAL -> DisplayKind.EXTERNAL
            TYPE_INTERNAL -> DisplayKind.BUILT_IN
            else -> if (raw.displayId == DEFAULT_DISPLAY_ID || raw.displayId == pairedPeerDisplayId) {
                DisplayKind.BUILT_IN
            } else {
                DisplayKind.PRESENTATION_ONLY
            }
        }
        if (baseKind != DisplayKind.BUILT_IN) {
            return baseKind
        }
        // Once calibrated, reject dynamically added displays other than default + paired peer.
        if (pairedPeerDisplayId != null &&
            raw.displayId != DEFAULT_DISPLAY_ID &&
            raw.displayId != pairedPeerDisplayId
        ) {
            return DisplayKind.EXTERNAL
        }
        return DisplayKind.BUILT_IN
    }

    fun isDisplayActive(state: Int): Boolean = state == STATE_ON || state == STATE_ON_SUSPEND

    /**
     * Filters high-frequency `DisplayListener.onDisplayChanged` callbacks (brightness, refresh
     * rate, power ticks) so placement is only re-evaluated when a relevant display changes or any
     * display flips active on/off state.
     */
    fun shouldReevaluateOnDisplayChange(
        changedDisplayId: Int,
        sourceDisplayId: Int,
        attachedOverlayDisplayId: Int,
        pairedPeerDisplayId: Int?,
        activeStateChanged: Boolean
    ): Boolean {
        if (activeStateChanged) return true
        return changedDisplayId == sourceDisplayId ||
            (attachedOverlayDisplayId >= 0 && changedDisplayId == attachedOverlayDisplayId) ||
            (pairedPeerDisplayId != null && changedDisplayId == pairedPeerDisplayId)
    }

    /**
     * Picks the best non-default built-in display candidate for onboarding calibration.
     */
    fun findCalibratablePeerDisplay(
        displays: List<RawDisplayInfo>,
        pairedPeerDisplayId: Int? = null
    ): DisplayCandidate? = classifyDisplays(displays, pairedPeerDisplayId).firstOrNull { candidate ->
        candidate.displayId != DEFAULT_DISPLAY_ID &&
            candidate.kind == DisplayKind.BUILT_IN &&
            candidate.isOn
    }

    /**
     * Returns `true` when at least two distinct active [DisplayKind.BUILT_IN] displays are
     * currently available, matching `DisplayPlacementPolicy.resolve` eligibility for the
     * dual-screen `[换屏]` capability in `HostChrome`.
     */
    fun hasEligiblePeerDisplay(displays: List<RawDisplayInfo>, pairedPeerDisplayId: Int? = null): Boolean =
        hasEligiblePeerDisplay(classifyDisplays(displays, pairedPeerDisplayId))

    fun hasEligiblePeerDisplay(candidates: List<DisplayCandidate>): Boolean =
        candidates.filter { it.isOn && it.kind == DisplayKind.BUILT_IN }
            .distinctBy { it.displayId }
            .size >= 2

    internal fun parseTypeFromDescription(description: String): Int? {
        if (description.isBlank()) return null
        val upper = description.uppercase()
        return when {
            "TYPE VIRTUAL" in upper || "TYPE=VIRTUAL" in upper -> TYPE_VIRTUAL
            "TYPE WIFI" in upper || "TYPE=WIFI" in upper -> TYPE_WIFI
            "TYPE OVERLAY" in upper || "TYPE=OVERLAY" in upper -> TYPE_OVERLAY
            "TYPE EXTERNAL" in upper || "TYPE=EXTERNAL" in upper -> TYPE_EXTERNAL
            "TYPE INTERNAL" in upper || "TYPE=INTERNAL" in upper -> TYPE_INTERNAL
            else -> null
        }
    }
}

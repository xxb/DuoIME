// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.grid

import dev.duoime.core.layout.KeySpec
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.KeyAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pure-JVM hold-gesture coordinator for [KeyGrid]:
 * - Drives auto-repeat for repeatable keys (e.g. Backspace) after [repeatInitialDelayMs] every
 *   [repeatIntervalMs].
 * - Drives long-press secondary symbol preview + commit after [longPressDelayMs] for keys that
 *   declare a secondary symbol/action.
 *
 * Designed to be owned by the single `pointerInput` key grid (no per-key gesture coroutines).
 */
class KeyHoldController(
    private val scope: CoroutineScope,
    private val onRepeatAction: (KeyAction) -> Unit,
    private val onVisualChanged: () -> Unit,
    private val timings: KeyHoldTimings = KeyHoldTimings(),
    private val onRepeatFeedback: (FeedbackKey) -> Unit = {}
) {
    private class ActiveHold(
        val keyIndex: Int,
        val key: KeySpec,
        var job: Job? = null,
        var repeated: Boolean = false,
        var longPressed: Boolean = false
    )

    private val holds = HashMap<Long, ActiveHold>(4)

    var activePopupKeyIndex: Int = -1
        private set

    var activePopupLabel: String? = null
        private set

    fun onKeyDown(pointerId: Long, keyIndex: Int, key: KeySpec) {
        cancelPointer(pointerId)
        val secondaryAction = if (key.repeatable) null else key.resolveSecondaryAction()
        if (!key.repeatable && secondaryAction == null) {
            return
        }
        val entry = ActiveHold(keyIndex = keyIndex, key = key)
        holds[pointerId] = entry
        entry.job = scope.launch {
            if (key.repeatable) {
                delay(timings.repeatInitialDelayMs)
                while (isActive && holds[pointerId] === entry) {
                    entry.repeated = true
                    onRepeatFeedback(FeedbackKey(key.action, key.label, key.zone))
                    onRepeatAction(key.action)
                    delay(timings.repeatIntervalMs)
                }
            } else {
                delay(timings.longPressDelayMs)
                if (isActive && holds[pointerId] === entry) {
                    entry.longPressed = true
                    activePopupKeyIndex = keyIndex
                    activePopupLabel = key.secondaryLabel
                    onVisualChanged()
                }
            }
        }
    }

    fun onKeyChanged(pointerId: Long, newKeyIndex: Int) {
        val current = holds[pointerId] ?: return
        if (current.keyIndex != newKeyIndex) {
            cancelPointer(pointerId)
        }
    }

    /**
     * Resolves the action to emit on pointer release for [pointerId], or `null` when a repeatable
     * hold already emitted repeat ticks (preventing an extra duplicate action on lift).
     */
    fun onKeyUp(pointerId: Long, releasedKey: KeySpec): KeyAction? {
        val entry = holds.remove(pointerId)
        entry?.job?.cancel()
        if (entry != null && activePopupKeyIndex == entry.keyIndex) {
            clearPopup()
        }
        return when {
            entry == null -> releasedKey.action
            entry.repeated -> null
            entry.longPressed -> releasedKey.resolveSecondaryAction() ?: releasedKey.action
            else -> releasedKey.action
        }
    }

    fun cancelPointer(pointerId: Long) {
        val entry = holds.remove(pointerId) ?: return
        entry.job?.cancel()
        if (activePopupKeyIndex == entry.keyIndex) {
            clearPopup()
        }
    }

    fun cancelAll() {
        for (entry in holds.values) {
            entry.job?.cancel()
        }
        holds.clear()
        if (activePopupKeyIndex >= 0) {
            clearPopup()
        }
    }

    private fun clearPopup() {
        activePopupKeyIndex = -1
        activePopupLabel = null
        onVisualChanged()
    }
}

data class KeyHoldTimings(
    val repeatInitialDelayMs: Long = 400L,
    val repeatIntervalMs: Long = 55L,
    val longPressDelayMs: Long = 380L
)

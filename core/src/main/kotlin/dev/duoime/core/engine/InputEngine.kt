// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.engine

import dev.duoime.core.layout.KeyCodes
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.core.model.KeyProcessResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Engine-facing key input event using platform-neutral keysym and modifier mask.
 */
data class EngineKeyEvent(
    val keysym: Int,
    val mask: Int = 0,
    val label: String? = null
) {
    companion object {
        fun fromChar(ch: Char, mask: Int = 0): EngineKeyEvent = EngineKeyEvent(
            keysym = KeyCodes.keysymForChar(ch),
            mask = mask,
            label = ch.toString()
        )
    }
}

/**
 * Pure-JVM input engine contract implemented by `:engine-lite` (M1) and `:engine-rime` (M2).
 *
 * A single [onKey] or [selectCandidate] call may both commit text and leave remaining composition
 * active (for example, composing `"nihao"` and selecting `"你"` leaves `"hao"` in [state]).
 * Controllers MUST apply [KeyProcessResult.CommitText] first and then apply
 * `InputSink.setComposing` from the updated [state] snapshot, strictly in that order.
 */
interface InputEngine {
    val state: StateFlow<EngineSnapshot>

    fun onKey(event: EngineKeyEvent): KeyProcessResult

    fun selectCandidate(indexOnPage: Int): KeyProcessResult

    /**
     * Selects a syllable option from [EngineSnapshot.syllables] (e.g. T9 syllable rail) by 0-based
     * [index], or clears active syllable locks/filters when [index] is `-1`.
     * Returns `true` if engine state changed.
     */
    fun selectSyllable(index: Int): Boolean = false

    fun page(forward: Boolean): Boolean

    fun setOption(name: String, on: Boolean)

    fun selectSchema(id: String): Boolean

    fun reset()
}

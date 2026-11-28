// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.engine

import dev.duoime.core.layout.KeyCodes
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyProcessResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Standard engine option keys shared across `:engine-lite` (M1) and `:engine-rime` (M2).
 */
object EngineOptions {
    const val TRADITIONALIZATION: String = "traditionalization"
    const val ASCII_MODE: String = "ascii_mode"
    const val INCOGNITO: String = "incognito"
    const val DISABLE_USER_DICT: String = "disable_user_dict"
}

/**
 * Default schema identifiers shared across engine backends.
 */
object EngineSchemas {
    const val PINYIN_26: String = "pinyin_26"
    const val T9_PINYIN: String = "t9_pinyin"
    const val DOUBLE_PINYIN_FLYPY: String = "double_pinyin_flypy"
    const val ENGLISH: String = "english"

    val DEFAULT_MODE_MAP: Map<InputMode, String> = mapOf(
        InputMode.PINYIN_26 to PINYIN_26,
        InputMode.PINYIN_T9 to T9_PINYIN,
        InputMode.ENGLISH to ENGLISH
    )
}

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
interface InputEngine : AutoCloseable {
    val state: StateFlow<EngineSnapshot>

    /** Returns `true` once this engine has closed and can no longer process input events. */
    val isClosed: Boolean
        get() = false

    fun onKey(event: EngineKeyEvent): KeyProcessResult

    fun selectCandidate(indexOnPage: Int): KeyProcessResult

    /**
     * Selects a syllable option from [EngineSnapshot.syllables] (e.g. T9 syllable rail) by 0-based
     * [index], or clears active syllable locks/filters when [index] is `-1`.
     * Returns `true` if engine state changed.
     */
    fun selectSyllable(index: Int): Boolean = false

    /**
     * Unlocks locked T9 syllables starting from 0-based [index] (`0` clears all locked syllables,
     * `1` keeps the first locked syllable and unlocks subsequent ones). Returns `true` if engine
     * state changed.
     */
    fun unlockSyllablesFrom(index: Int): Boolean = if (index <= 0) selectSyllable(-1) else false

    fun page(forward: Boolean): Boolean

    fun setOption(name: String, on: Boolean)

    fun selectSchema(id: String): Boolean

    fun reset()

    override fun close() = Unit
}

/**
 * Zero-allocation fallback [InputEngine] that declines every key (`PassThrough`) with an empty
 * snapshot. Keeps the keyboard functional for direct text, Backspace, Space, Enter, and host
 * actions even if both primary and fallback engines fail to initialize.
 */
class PassThroughEngine : InputEngine {
    override val state: StateFlow<EngineSnapshot> = EMPTY_STATE

    override fun onKey(event: EngineKeyEvent): KeyProcessResult = KeyProcessResult.PassThrough

    override fun selectCandidate(indexOnPage: Int): KeyProcessResult = KeyProcessResult.PassThrough

    override fun page(forward: Boolean): Boolean = false

    override fun setOption(name: String, on: Boolean) = Unit

    override fun selectSchema(id: String): Boolean = false

    override fun reset() = Unit

    companion object {
        private val EMPTY_STATE: StateFlow<EngineSnapshot> = MutableStateFlow(EngineSnapshot.EMPTY).asStateFlow()
    }
}

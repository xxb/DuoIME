// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.model

/**
 * Active logical keyboard input mode.
 */
enum class InputMode {
    PINYIN_26,
    PINYIN_T9,
    ENGLISH,
    SYMBOLS
}

/**
 * Chinese character output variant.
 */
enum class ChineseVariant {
    SIMPLIFIED,
    TRADITIONAL
}

/**
 * Single selectable candidate on the current engine page.
 */
data class CandidateItem(
    val text: String,
    val comment: String? = null,
    val indexOnPage: Int = 0
)

/**
 * Immutable engine UI snapshot observed by `:keyboard-ui` and `:duoime-embed`.
 *
 * @property preedit Raw segmented input displayed in the candidate bar (for example, `"ni'hao"`).
 * @property composingText Inline editor text passed to `InputSink.setComposing`; ignored by the
 * controller when `!sink.capabilities.composing` (such as commit-only terminal hosts).
 */
data class EngineSnapshot(
    val mode: InputMode = InputMode.PINYIN_26,
    val variant: ChineseVariant = ChineseVariant.SIMPLIFIED,
    val schemaId: String = "pinyin_26",
    val preedit: String = "",
    val composingText: String = "",
    val candidates: List<CandidateItem> = emptyList(),
    val highlightedIndex: Int = 0,
    val pageIndex: Int = 0,
    val hasPrevPage: Boolean = false,
    val hasNextPage: Boolean = false,
    val syllables: List<String> = emptyList(),
    val selectedSyllableIndex: Int = -1,
    val isComposing: Boolean = false
) {
    companion object {
        val EMPTY: EngineSnapshot = EngineSnapshot()
    }
}

/**
 * Outcome returned by [dev.duoime.core.engine.InputEngine] after handling a key or selection.
 */
sealed interface KeyProcessResult {
    data object Consumed : KeyProcessResult

    data class CommitText(val text: String) : KeyProcessResult

    data object PassThrough : KeyProcessResult
}

/**
 * Logical key zone used by audio/haptic feedback engines.
 */
enum class FeedbackZone {
    LETTER,
    DIGIT,
    SPACE,
    ACTION,
    BACKSPACE,
    ENTER,
    TOOLBAR,
    CANDIDATE,
    SYMBOL
}

/**
 * Decoupled key descriptor passed to feedback generators (sound DSP / haptics).
 */
data class FeedbackKey(
    val action: KeyAction,
    val label: String,
    val zone: FeedbackZone
)

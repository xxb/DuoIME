// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.ime

import dev.duoime.embed.EmbedSelectionTracker

typealias SelectionState = dev.duoime.embed.SelectionState

/**
 * `:app` facade delegating directly to `:duoime-embed`'s [EmbedSelectionTracker], preserving
 * source-compatible entry points across `:app` services and tests.
 */
class SelectionTracker(
    clockMs: () -> Long = { System.nanoTime() / NANOS_PER_MS }
) {
    private val delegate = EmbedSelectionTracker(clockMs)

    internal val pendingPredictionCount: Int
        get() = delegate.pendingPredictionCount

    internal val isBaselineKnown: Boolean
        get() = delegate.isBaselineKnown

    fun onStartInput(initialSelStart: Int, initialSelEnd: Int) = delegate.onStartInput(initialSelStart, initialSelEnd)

    fun recordSetComposing(text: CharSequence) = delegate.recordSetComposing(text)

    fun recordCommitText(text: CharSequence) = delegate.recordCommitText(text)

    fun recordClearComposing() = delegate.recordClearComposing()

    fun recordFinishComposing() = delegate.recordFinishComposing()

    fun recordUnpredictableEdit() = delegate.recordUnpredictableEdit()

    fun clearPredictions() = delegate.clearPredictions()

    fun onUpdateSelection(
        isComposing: Boolean,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ): Boolean = delegate.onUpdateSelection(
        isComposing = isComposing,
        newSelStart = newSelStart,
        newSelEnd = newSelEnd,
        candidatesStart = candidatesStart,
        candidatesEnd = candidatesEnd
    )

    companion object {
        const val PREDICTION_TTL_MS: Long = EmbedSelectionTracker.PREDICTION_TTL_MS
        const val MAX_PREDICTIONS: Int = EmbedSelectionTracker.MAX_PREDICTIONS
        private const val NANOS_PER_MS: Long = 1_000_000L
    }
}

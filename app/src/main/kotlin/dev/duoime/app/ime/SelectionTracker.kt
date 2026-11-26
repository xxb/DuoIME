// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.ime

/**
 * Snapshot of the editor's selection and composing span indices in UTF-16 code units.
 */
data class SelectionState(
    val selStart: Int,
    val selEnd: Int,
    val compStart: Int = NO_COMPOSING,
    val compEnd: Int = NO_COMPOSING
) {
    companion object {
        const val NO_COMPOSING: Int = -1
    }
}

private data class PredictedSelection(
    val state: SelectionState,
    val timestampMs: Long
)

/**
 * LatinIME-style deterministic expected-state queue correlating our own `InputConnection` writes
 * against asynchronous `InputMethodService.onUpdateSelection` callbacks.
 *
 * - Starts from `EditorInfo.initialSelStart`/`initialSelEnd` (or `UNKNOWN` if either is `-1`).
 * - Each composing-relevant write appends a predicted [SelectionState] computed in UTF-16 code
 *   units with its own timestamp; entries older than [PREDICTION_TTL_MS] are pruned individually
 *   so repeated writes never extend stale windows, and the queue is capped at [MAX_PREDICTIONS].
 * - Unpredictable edits (`KEYCODE_DEL`, `KEYCODE_ENTER`, editor actions) clear the queue and mark
 *   the baseline `UNKNOWN` until the next editor callback re-baselines.
 * - Coalesced editor updates matching a newer entry drop that entry and all older ones cleanly
 *   with zero counter drift.
 */
class SelectionTracker(
    private val clockMs: () -> Long = { System.nanoTime() / NANOS_PER_MS }
) {
    private var baseline: SelectionState? = null
    private val predictions = ArrayDeque<PredictedSelection>()

    internal val pendingPredictionCount: Int
        get() = predictions.size

    internal val isBaselineKnown: Boolean
        get() = baseline != null

    fun onStartInput(initialSelStart: Int, initialSelEnd: Int) {
        predictions.clear()
        baseline = if (initialSelStart >= 0 && initialSelEnd >= 0) {
            SelectionState(
                selStart = initialSelStart,
                selEnd = initialSelEnd,
                compStart = SelectionState.NO_COMPOSING,
                compEnd = SelectionState.NO_COMPOSING
            )
        } else {
            null
        }
    }

    fun recordSetComposing(text: CharSequence) {
        val nowMs = clockMs()
        pruneExpired(nowMs)
        val prev = latestKnownState() ?: return
        val compStart = resolveEditOrigin(prev)
        val compEnd = compStart + text.length
        enqueuePrediction(
            state = SelectionState(
                selStart = compEnd,
                selEnd = compEnd,
                compStart = compStart,
                compEnd = compEnd
            ),
            nowMs = nowMs
        )
    }

    fun recordCommitText(text: CharSequence) {
        val nowMs = clockMs()
        pruneExpired(nowMs)
        val prev = latestKnownState() ?: return
        val origin = resolveEditOrigin(prev)
        val nextSel = origin + text.length
        enqueuePrediction(
            state = SelectionState(
                selStart = nextSel,
                selEnd = nextSel,
                compStart = SelectionState.NO_COMPOSING,
                compEnd = SelectionState.NO_COMPOSING
            ),
            nowMs = nowMs
        )
    }

    fun recordClearComposing() {
        val nowMs = clockMs()
        pruneExpired(nowMs)
        val prev = latestKnownState() ?: return
        val origin = resolveEditOrigin(prev)
        enqueuePrediction(
            state = SelectionState(
                selStart = origin,
                selEnd = origin,
                compStart = SelectionState.NO_COMPOSING,
                compEnd = SelectionState.NO_COMPOSING
            ),
            nowMs = nowMs
        )
    }

    fun recordFinishComposing() {
        val nowMs = clockMs()
        pruneExpired(nowMs)
        val prev = latestKnownState() ?: return
        enqueuePrediction(
            state = SelectionState(
                selStart = prev.selStart,
                selEnd = prev.selEnd,
                compStart = SelectionState.NO_COMPOSING,
                compEnd = SelectionState.NO_COMPOSING
            ),
            nowMs = nowMs
        )
    }

    fun recordUnpredictableEdit() {
        predictions.clear()
        baseline = null
    }

    fun clearPredictions() {
        predictions.clear()
    }

    /**
     * Processes an incoming `InputMethodService.onUpdateSelection` event and returns `true` iff
     * the callback represents an external cursor/text change that should reset active composition.
     */
    fun onUpdateSelection(
        isComposing: Boolean,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ): Boolean {
        val nowMs = clockMs()
        pruneExpired(nowMs)
        val reported = SelectionState(
            selStart = newSelStart,
            selEnd = newSelEnd,
            compStart = candidatesStart,
            compEnd = candidatesEnd
        )
        val matchIdx = predictions.indexOfFirst { it.state == reported }
        if (matchIdx >= 0) {
            repeat(matchIdx + 1) { predictions.removeFirst() }
            baseline = reported
            return false
        }
        if (baseline == null) {
            baseline = reported
            return false
        }
        baseline = reported
        predictions.clear()
        return EditorInfoMapper.shouldResetComposingOnSelectionUpdate(
            isComposing = isComposing,
            newSelStart = newSelStart,
            newSelEnd = newSelEnd,
            candidatesStart = candidatesStart,
            candidatesEnd = candidatesEnd
        )
    }

    private fun latestKnownState(): SelectionState? = predictions.lastOrNull()?.state ?: baseline

    private fun resolveEditOrigin(prev: SelectionState): Int =
        if (prev.compStart >= 0) prev.compStart else minOf(prev.selStart, prev.selEnd)

    private fun pruneExpired(nowMs: Long) {
        while (predictions.isNotEmpty()) {
            val age = nowMs - predictions.first().timestampMs
            if (age >= PREDICTION_TTL_MS || age < 0L) {
                predictions.removeFirst()
            } else {
                break
            }
        }
    }

    private fun enqueuePrediction(state: SelectionState, nowMs: Long) {
        while (predictions.size >= MAX_PREDICTIONS) {
            predictions.removeFirst()
        }
        predictions.addLast(PredictedSelection(state = state, timestampMs = nowMs))
    }

    companion object {
        const val PREDICTION_TTL_MS: Long = 1_000L
        const val MAX_PREDICTIONS: Int = 16
        private const val NANOS_PER_MS: Long = 1_000_000L
    }
}

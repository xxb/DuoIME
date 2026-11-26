// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTrackerTest {

    @Test
    fun staleCommitEchoBeforeNextComposeDoesNotTriggerReset() {
        var nowMs = 1_000L
        val tracker = SelectionTracker(clockMs = { nowMs })
        tracker.onStartInput(initialSelStart = 0, initialSelEnd = 0)

        // (a) commit("你") -> setComposing("h") -> stale commit echo (1,1,-1,-1) arrives -> NO reset
        tracker.recordCommitText("你")
        nowMs += 20L
        tracker.recordSetComposing("h")
        assertEquals(2, tracker.pendingPredictionCount)

        nowMs += 15L
        assertFalse(
            "Stale commit echo matching queued prediction must not reset",
            tracker.onUpdateSelection(
                isComposing = true,
                newSelStart = 1,
                newSelEnd = 1,
                candidatesStart = -1,
                candidatesEnd = -1
            )
        )
        assertEquals(1, tracker.pendingPredictionCount)

        nowMs += 15L
        assertFalse(
            "Following compose echo must match and drain queue",
            tracker.onUpdateSelection(
                isComposing = true,
                newSelStart = 2,
                newSelEnd = 2,
                candidatesStart = 1,
                candidatesEnd = 2
            )
        )
        assertEquals(0, tracker.pendingPredictionCount)
    }

    @Test
    fun repeatedDeleteEventsNeverExtendWindowAndMidPreeditTapResetsImmediately() {
        var nowMs = 1_000L
        val tracker = SelectionTracker(clockMs = { nowMs })
        tracker.onStartInput(initialSelStart = 50, initialSelEnd = 50)

        // (b) 50x DEL repeat at 55ms cadence clears predictions & never builds stale backlog
        repeat(50) {
            nowMs += 55L
            tracker.recordUnpredictableEdit()
        }
        assertEquals(0, tracker.pendingPredictionCount)
        assertFalse(tracker.isBaselineKnown)

        // Editor reports selection after DEL burst while not composing -> re-baselines
        nowMs += 20L
        assertFalse(tracker.onUpdateSelection(false, 0, 0, -1, -1))
        assertTrue(tracker.isBaselineKnown)

        // User starts composing "ni" then immediately taps elsewhere -> resets cleanly
        nowMs += 20L
        tracker.recordSetComposing("ni")
        nowMs += 10L
        assertTrue(
            "External tap mid-preedit after DEL repeats must reset immediately",
            tracker.onUpdateSelection(
                isComposing = true,
                newSelStart = 0,
                newSelEnd = 0,
                candidatesStart = -1,
                candidatesEnd = -1
            )
        )
        assertEquals(0, tracker.pendingPredictionCount)
    }

    @Test
    fun coalescedEchoMatchingNewestPredictionDrainsEntireQueueWithoutReset() {
        var nowMs = 1_000L
        val tracker = SelectionTracker(clockMs = { nowMs })
        tracker.onStartInput(initialSelStart = 0, initialSelEnd = 0)

        // (c) 4 rapid writes coalesced by editor into a single callback matching only the newest state
        tracker.recordSetComposing("n")
        nowMs += 10L
        tracker.recordSetComposing("ni")
        nowMs += 10L
        tracker.recordCommitText("你")
        nowMs += 10L
        tracker.recordSetComposing("h")
        assertEquals(4, tracker.pendingPredictionCount)

        nowMs += 20L
        assertFalse(
            "Coalesced echo matching newest prediction must not reset",
            tracker.onUpdateSelection(
                isComposing = true,
                newSelStart = 2,
                newSelEnd = 2,
                candidatesStart = 1,
                candidatesEnd = 2
            )
        )
        assertEquals("Coalesced match must drain all older predictions", 0, tracker.pendingPredictionCount)
    }

    @Test
    fun externalEditWhileComposingResetsAndUnknownInitialSelRebaselinesFirst() {
        var nowMs = 1_000L
        val tracker = SelectionTracker(clockMs = { nowMs })

        // (d) External edit while composing -> reset
        tracker.onStartInput(initialSelStart = 4, initialSelEnd = 4)
        tracker.recordSetComposing("ni")
        nowMs += 30L
        assertTrue(
            "External selection move outside composing span must reset",
            tracker.onUpdateSelection(
                isComposing = true,
                newSelStart = 0,
                newSelEnd = 2,
                candidatesStart = 4,
                candidatesEnd = 6
            )
        )
        assertEquals(0, tracker.pendingPredictionCount)

        // (e) initialSel == -1 -> UNKNOWN baseline: first update re-baselines only, no reset
        tracker.onStartInput(initialSelStart = -1, initialSelEnd = -1)
        assertFalse(tracker.isBaselineKnown)
        tracker.recordSetComposing("n")
        assertFalse(
            "First update from UNKNOWN baseline must re-baseline without resetting",
            tracker.onUpdateSelection(
                isComposing = true,
                newSelStart = 1,
                newSelEnd = 1,
                candidatesStart = -1,
                candidatesEnd = -1
            )
        )
        assertTrue(tracker.isBaselineKnown)

        // Subsequent external tap now triggers reset
        assertTrue(
            tracker.onUpdateSelection(
                isComposing = true,
                newSelStart = 0,
                newSelEnd = 0,
                candidatesStart = -1,
                candidatesEnd = -1
            )
        )
    }

    @Test
    fun expiredPredictionsArePrunedAfterOneSecondAndQueueCapsAtSixteen() {
        var nowMs = 1_000L
        val tracker = SelectionTracker(clockMs = { nowMs })
        tracker.onStartInput(initialSelStart = 0, initialSelEnd = 0)

        // (f) Entry at t=1000 pruned at t=2000 -> later external tap resets
        tracker.recordCommitText("你")
        nowMs += 50L
        tracker.recordSetComposing("h")
        assertEquals(2, tracker.pendingPredictionCount)

        nowMs = 2_000L
        assertTrue(
            "1s-old commit prediction must be pruned so matching tap at offset 1 resets",
            tracker.onUpdateSelection(
                isComposing = true,
                newSelStart = 1,
                newSelEnd = 1,
                candidatesStart = -1,
                candidatesEnd = -1
            )
        )

        // Queue cap stays bounded at MAX_PREDICTIONS (16) and handles UTF-16 surrogate pairs
        tracker.onStartInput(initialSelStart = 0, initialSelEnd = 0)
        repeat(25) {
            nowMs += 5L
            tracker.recordCommitText("😀")
        }
        assertEquals(SelectionTracker.MAX_PREDICTIONS, tracker.pendingPredictionCount)
        // 25 surrogate pairs = 50 UTF-16 code units
        assertFalse(tracker.onUpdateSelection(false, 50, 50, -1, -1))
        assertEquals(0, tracker.pendingPredictionCount)
    }
}

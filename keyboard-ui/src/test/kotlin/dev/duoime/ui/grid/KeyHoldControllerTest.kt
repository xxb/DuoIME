// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.grid

import dev.duoime.core.layout.BuiltInLayouts
import dev.duoime.core.layout.KeyCodes
import dev.duoime.core.model.KeyAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeyHoldControllerTest {

    @Test
    fun backspaceShortTapEmitsOnceAndHoldAutoRepeatsCleanly() = runTest {
        val repeats = mutableListOf<KeyAction>()
        var visualTicks = 0
        val controller = KeyHoldController(
            scope = this,
            onRepeatAction = { repeats.add(it) },
            onVisualChanged = { visualTicks++ }
        )
        val backspace = BuiltInLayouts.qwerty().rows[2].keys.last()
        assertTrue(backspace.repeatable)

        // Short tap (< 400ms) does not auto-repeat; emits once on key up
        controller.onKeyDown(pointerId = 1L, keyIndex = 20, key = backspace)
        advanceTimeBy(200L)
        runCurrent()
        assertTrue(repeats.isEmpty())
        assertEquals(KeyAction.Backspace, controller.onKeyUp(pointerId = 1L, releasedKey = backspace))

        // Sustained hold past 400ms triggers repeat ticks every 55ms and suppresses trailing key-up action
        controller.onKeyDown(pointerId = 2L, keyIndex = 20, key = backspace)
        advanceTimeBy(400L + 55L * 3 + 10L)
        runCurrent()
        assertEquals(4, repeats.size)
        assertTrue(repeats.all { it == KeyAction.Backspace })
        assertNull(controller.onKeyUp(pointerId = 2L, releasedKey = backspace))

        // Advancing time further emits no leaked repeats
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(4, repeats.size)
    }

    @Test
    fun longPressOnQwertyKeyShowsSecondaryPopupAndCommitsSecondarySymbol() = runTest {
        var visualTicks = 0
        val controller = KeyHoldController(
            scope = this,
            onRepeatAction = {},
            onVisualChanged = { visualTicks++ }
        )
        val qKey = BuiltInLayouts.qwerty(includeNumberRow = false).rows[0].keys[0]
        assertEquals("q", qKey.label)
        assertEquals("1", qKey.secondaryLabel)

        // Short tap returns primary Engine action
        controller.onKeyDown(pointerId = 1L, keyIndex = 0, key = qKey)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(-1, controller.activePopupKeyIndex)
        assertEquals(KeyAction.Engine(KeyCodes.keysymForChar('q')), controller.onKeyUp(1L, qKey))

        // Long press (>= 380ms) activates secondary popup and commits "1"
        controller.onKeyDown(pointerId = 2L, keyIndex = 0, key = qKey)
        advanceTimeBy(390L)
        runCurrent()
        assertEquals(0, controller.activePopupKeyIndex)
        assertEquals("1", controller.activePopupLabel)
        assertTrue(visualTicks > 0)
        assertEquals(KeyAction.Text("1"), controller.onKeyUp(2L, qKey))
        assertEquals(-1, controller.activePopupKeyIndex)
        assertNull(controller.activePopupLabel)
    }

    @Test
    fun slidingOffKeyOrCancellingPointerAbortsRepeatAndLongPressPopup() = runTest {
        val repeats = mutableListOf<KeyAction>()
        val controller = KeyHoldController(
            scope = this,
            onRepeatAction = { repeats.add(it) },
            onVisualChanged = {}
        )
        val layout = BuiltInLayouts.qwerty()
        val qKey = layout.rows[0].keys[0]
        val backspace = layout.rows[2].keys.last()

        controller.onKeyDown(pointerId = 1L, keyIndex = 0, key = qKey)
        advanceTimeBy(200L)
        controller.onKeyChanged(pointerId = 1L, newKeyIndex = 1)
        advanceTimeBy(400L)
        runCurrent()
        assertEquals(-1, controller.activePopupKeyIndex)

        controller.onKeyDown(pointerId = 2L, keyIndex = 20, key = backspace)
        advanceTimeBy(250L)
        controller.cancelPointer(pointerId = 2L)
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(repeats.isEmpty())
    }
}

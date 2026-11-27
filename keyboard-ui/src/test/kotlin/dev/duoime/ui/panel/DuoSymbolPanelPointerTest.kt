// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.panel

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.FeedbackZone
import dev.duoime.core.model.KeyAction
import dev.duoime.ui.grid.KeyHoldController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DuoSymbolPanelPointerTest {

    private val backspaceKey = DuoSymbolPanelMeta.BACKSPACE_KEY_SPEC

    private fun pointerChange(
        id: Long,
        position: Offset,
        pressed: Boolean,
        previousPressed: Boolean,
        uptimeMillis: Long = 100L,
        previousUptimeMillis: Long = 90L,
        previousPosition: Offset = position,
        initiallyConsumed: Boolean = false
    ): PointerInputChange = PointerInputChange(
        id = PointerId(id),
        uptimeMillis = uptimeMillis,
        position = position,
        pressed = pressed,
        pressure = if (pressed) 1f else 0f,
        previousUptimeMillis = previousUptimeMillis,
        previousPosition = previousPosition,
        previousPressed = previousPressed,
        isInitiallyConsumed = initiallyConsumed
    )

    @Test
    fun shortTapPressesConsumesAndReleasesInsideToEmitBackspaceOnce() = runTest(StandardTestDispatcher()) {
        val actions = mutableListOf<KeyAction>()
        val pressDowns = mutableListOf<FeedbackKey>()
        val repeats = mutableListOf<FeedbackKey>()
        val pressedState = mutableStateOf(false)
        val dispatch = RepeatableButtonDispatch(
            onActionState = mutableStateOf<(KeyAction) -> Unit>({ actions.add(it) }),
            onKeyPressDownState = mutableStateOf<(FeedbackKey) -> Unit>({ pressDowns.add(it) })
        )
        val holdController = KeyHoldController(
            scope = this,
            onRepeatAction = { actions.add(it) },
            onVisualChanged = {},
            onRepeatFeedback = { repeats.add(it) }
        )

        val down = pointerChange(id = 1L, position = Offset(40f, 20f), pressed = true, previousPressed = false)
        var activeId = processRepeatablePointerChange(
            change = down,
            activePointerId = null,
            inside = isInsideBounds(down, 100f, 50f),
            keySpec = backspaceKey,
            holdController = holdController,
            pressedState = pressedState,
            dispatch = dispatch
        )
        assertEquals(1L, activeId)
        assertTrue(pressedState.value)
        assertTrue(down.isConsumed)
        assertEquals(listOf(FeedbackKey(KeyAction.Backspace, "⌫", FeedbackZone.BACKSPACE)), pressDowns)
        assertTrue(actions.isEmpty())

        advanceTimeBy(200L)
        runCurrent()
        assertTrue(actions.isEmpty())
        assertTrue(repeats.isEmpty())

        val up = pointerChange(id = 1L, position = Offset(40f, 20f), pressed = false, previousPressed = true)
        activeId = processRepeatablePointerChange(
            change = up,
            activePointerId = activeId,
            inside = isInsideBounds(up, 100f, 50f),
            keySpec = backspaceKey,
            holdController = holdController,
            pressedState = pressedState,
            dispatch = dispatch
        )
        assertNull(activeId)
        assertFalse(pressedState.value)
        assertTrue(up.isConsumed)
        assertEquals(listOf(KeyAction.Backspace), actions)
        assertTrue(repeats.isEmpty())
    }

    @Test
    fun sustainedHoldAutoRepeatsAndReleaseInsideSuppressesDuplicateAction() = runTest(StandardTestDispatcher()) {
        val actions = mutableListOf<KeyAction>()
        val repeats = mutableListOf<FeedbackKey>()
        val pressedState = mutableStateOf(false)
        val dispatch = RepeatableButtonDispatch(
            onActionState = mutableStateOf<(KeyAction) -> Unit>({ actions.add(it) }),
            onKeyPressDownState = mutableStateOf<(FeedbackKey) -> Unit>({})
        )
        val holdController = KeyHoldController(
            scope = this,
            onRepeatAction = { actions.add(it) },
            onVisualChanged = {},
            onRepeatFeedback = { repeats.add(it) }
        )

        val down = pointerChange(id = 1L, position = Offset(50f, 25f), pressed = true, previousPressed = false)
        var activeId = processRepeatablePointerChange(
            change = down,
            activePointerId = null,
            inside = true,
            keySpec = backspaceKey,
            holdController = holdController,
            pressedState = pressedState,
            dispatch = dispatch
        )
        assertEquals(1L, activeId)

        advanceTimeBy(400L + 55L * 3 + 10L)
        runCurrent()
        assertEquals(4, actions.size)
        assertEquals(4, repeats.size)
        assertTrue(actions.all { it == KeyAction.Backspace })
        assertTrue(repeats.all { it == FeedbackKey(KeyAction.Backspace, "⌫", FeedbackZone.BACKSPACE) })

        val up = pointerChange(id = 1L, position = Offset(50f, 25f), pressed = false, previousPressed = true)
        activeId = processRepeatablePointerChange(
            change = up,
            activePointerId = activeId,
            inside = true,
            keySpec = backspaceKey,
            holdController = holdController,
            pressedState = pressedState,
            dispatch = dispatch
        )
        assertNull(activeId)
        assertFalse(pressedState.value)
        assertEquals("Release after repeat must not emit a duplicate Backspace", 4, actions.size)

        advanceTimeBy(500L)
        runCurrent()
        assertEquals(4, actions.size)
    }

    @Test
    fun slideOffCancelsHoldClearsPressedStateAndStopsFurtherRepeats() = runTest(StandardTestDispatcher()) {
        val actions = mutableListOf<KeyAction>()
        val pressedState = mutableStateOf(false)
        val dispatch = RepeatableButtonDispatch(
            onActionState = mutableStateOf<(KeyAction) -> Unit>({ actions.add(it) }),
            onKeyPressDownState = mutableStateOf<(FeedbackKey) -> Unit>({})
        )
        val holdController = KeyHoldController(
            scope = this,
            onRepeatAction = { actions.add(it) },
            onVisualChanged = {}
        )

        val down = pointerChange(id = 1L, position = Offset(50f, 25f), pressed = true, previousPressed = false)
        var activeId = processRepeatablePointerChange(
            change = down,
            activePointerId = null,
            inside = isInsideBounds(down, 100f, 50f),
            keySpec = backspaceKey,
            holdController = holdController,
            pressedState = pressedState,
            dispatch = dispatch
        )
        advanceTimeBy(400L + 60L)
        runCurrent()
        val countBeforeSlideOff = actions.size
        assertTrue(countBeforeSlideOff >= 2)

        val moveOut = pointerChange(id = 1L, position = Offset(120f, 25f), pressed = true, previousPressed = true)
        assertFalse(isInsideBounds(moveOut, 100f, 50f))
        activeId = processRepeatablePointerChange(
            change = moveOut,
            activePointerId = activeId,
            inside = false,
            keySpec = backspaceKey,
            holdController = holdController,
            pressedState = pressedState,
            dispatch = dispatch
        )
        assertNull(activeId)
        assertFalse(pressedState.value)
        assertTrue(moveOut.isConsumed)

        advanceTimeBy(500L)
        runCurrent()
        assertEquals("Slide-off must halt further auto-repeat ticks", countBeforeSlideOff, actions.size)
    }

    @Test
    fun preConsumedEventCancelsActiveHoldImmediatelyAndResetsPressedState() = runTest(StandardTestDispatcher()) {
        val actions = mutableListOf<KeyAction>()
        val pressedState = mutableStateOf(false)
        val dispatch = RepeatableButtonDispatch(
            onActionState = mutableStateOf<(KeyAction) -> Unit>({ actions.add(it) }),
            onKeyPressDownState = mutableStateOf<(FeedbackKey) -> Unit>({})
        )
        val holdController = KeyHoldController(
            scope = this,
            onRepeatAction = { actions.add(it) },
            onVisualChanged = {}
        )

        val down = pointerChange(id = 1L, position = Offset(40f, 20f), pressed = true, previousPressed = false)
        var activeId = processRepeatablePointerChange(
            change = down,
            activePointerId = null,
            inside = true,
            keySpec = backspaceKey,
            holdController = holdController,
            pressedState = pressedState,
            dispatch = dispatch
        )
        advanceTimeBy(250L)
        runCurrent()

        val consumedMove = pointerChange(
            id = 1L,
            position = Offset(42f, 20f),
            pressed = true,
            previousPressed = true
        ).also { it.consume() }
        activeId = processRepeatablePointerChange(
            change = consumedMove,
            activePointerId = activeId,
            inside = true,
            keySpec = backspaceKey,
            holdController = holdController,
            pressedState = pressedState,
            dispatch = dispatch
        )
        assertNull(activeId)
        assertFalse(pressedState.value)

        advanceTimeBy(500L)
        runCurrent()
        assertTrue("Pre-consumed event must cancel hold before any repeat fires", actions.isEmpty())
    }

    @Test
    fun secondFingerDownWhileFirstIsActiveIsIgnoredWithoutHijackingOrResetting() = runTest(StandardTestDispatcher()) {
        val actions = mutableListOf<KeyAction>()
        val pressDowns = mutableListOf<FeedbackKey>()
        val pressedState = mutableStateOf(false)
        val dispatch = RepeatableButtonDispatch(
            onActionState = mutableStateOf<(KeyAction) -> Unit>({ actions.add(it) }),
            onKeyPressDownState = mutableStateOf<(FeedbackKey) -> Unit>({ pressDowns.add(it) })
        )
        val holdController = KeyHoldController(
            scope = this,
            onRepeatAction = { actions.add(it) },
            onVisualChanged = {}
        )

        val finger1Down = pointerChange(id = 1L, position = Offset(30f, 20f), pressed = true, previousPressed = false)
        var activeId = processRepeatablePointerChange(
            change = finger1Down,
            activePointerId = null,
            inside = true,
            keySpec = backspaceKey,
            holdController = holdController,
            pressedState = pressedState,
            dispatch = dispatch
        )
        assertEquals(1L, activeId)
        assertEquals(1, pressDowns.size)

        // Second finger down, move, consumed, and up must never reset or hijack activePointerId = 1L
        val finger2Down = pointerChange(id = 2L, position = Offset(60f, 20f), pressed = true, previousPressed = false)
        activeId = processRepeatablePointerChange(
            change = finger2Down,
            activePointerId = activeId,
            inside = true,
            keySpec = backspaceKey,
            holdController = holdController,
            pressedState = pressedState,
            dispatch = dispatch
        )
        assertEquals(1L, activeId)
        assertTrue(pressedState.value)
        assertFalse(finger2Down.isConsumed)
        assertEquals(1, pressDowns.size)

        val finger2Up = pointerChange(id = 2L, position = Offset(60f, 20f), pressed = false, previousPressed = true)
        activeId = processRepeatablePointerChange(
            change = finger2Up,
            activePointerId = activeId,
            inside = true,
            keySpec = backspaceKey,
            holdController = holdController,
            pressedState = pressedState,
            dispatch = dispatch
        )
        assertEquals(1L, activeId)
        assertTrue(pressedState.value)
        assertTrue(actions.isEmpty())

        // Original finger 1 continues repeating uninterrupted
        advanceTimeBy(400L + 55L + 10L)
        runCurrent()
        assertEquals(2, actions.size)
        holdController.cancelAll()
    }

    @Test
    fun isInsideBoundsValidatesEdgesAndOutOfBoundsCoordinates() {
        val w = 100f
        val h = 46f
        assertTrue(isInsideBounds(pointerChange(1L, Offset(0f, 0f), true, false), w, h))
        assertTrue(isInsideBounds(pointerChange(1L, Offset(w, h), true, false), w, h))
        assertTrue(isInsideBounds(pointerChange(1L, Offset(50f, 23f), true, false), w, h))
        assertFalse(isInsideBounds(pointerChange(1L, Offset(-0.01f, 23f), true, false), w, h))
        assertFalse(isInsideBounds(pointerChange(1L, Offset(w + 0.01f, 23f), true, false), w, h))
        assertFalse(isInsideBounds(pointerChange(1L, Offset(50f, -0.01f), true, false), w, h))
        assertFalse(isInsideBounds(pointerChange(1L, Offset(50f, h + 0.01f), true, false), w, h))
        assertFalse(isInsideBounds(pointerChange(1L, Offset(Float.NaN, 23f), true, false), w, h))
    }
}

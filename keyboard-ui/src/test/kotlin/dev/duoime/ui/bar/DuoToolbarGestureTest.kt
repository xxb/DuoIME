// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.bar

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import dev.duoime.core.model.ChineseScheme
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.FeedbackZone
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.Language
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
class DuoToolbarGestureTest {

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
    fun unifiedLangLabelsHintsQuickSchemeAndClassifierCoverAllStates() {
        assertEquals("中·26", formatUnifiedLangLabel(Language.CHINESE, ChineseScheme.PINYIN_26))
        assertEquals("中·9", formatUnifiedLangLabel(Language.CHINESE, ChineseScheme.PINYIN_T9))
        assertEquals("中·双", formatUnifiedLangLabel(Language.CHINESE, ChineseScheme.XIAOHE_SHUANGPIN))
        assertEquals("英·26", formatUnifiedLangLabel(Language.ENGLISH, ChineseScheme.PINYIN_26))
        assertEquals("英·9", formatUnifiedLangLabel(Language.ENGLISH, ChineseScheme.PINYIN_T9))
        assertEquals("英·双", formatUnifiedLangLabel(Language.ENGLISH, ChineseScheme.XIAOHE_SHUANGPIN))

        assertEquals("↑9", formatUnifiedLangCornerHint(ChineseScheme.PINYIN_26))
        assertEquals("↑26", formatUnifiedLangCornerHint(ChineseScheme.PINYIN_T9))
        assertEquals("↑9", formatUnifiedLangCornerHint(ChineseScheme.XIAOHE_SHUANGPIN))

        assertEquals(ChineseScheme.PINYIN_T9, nextQuickChineseScheme(ChineseScheme.PINYIN_26))
        assertEquals(ChineseScheme.PINYIN_26, nextQuickChineseScheme(ChineseScheme.PINYIN_T9))
        assertEquals(ChineseScheme.PINYIN_T9, nextQuickChineseScheme(ChineseScheme.XIAOHE_SHUANGPIN))

        // Classifier checks
        assertEquals(
            UnifiedLangGesture.TAP_TOGGLE_LANG,
            resolveUnifiedLangGesture(dxPx = 4f, dyPx = -6f, flickThresholdPx = 40f, insideBounds = true)
        )
        assertNull(resolveUnifiedLangGesture(dxPx = 4f, dyPx = -6f, flickThresholdPx = 40f, insideBounds = false))
        assertEquals(
            UnifiedLangGesture.FLICK_UP_OR_HOLD_SCHEME,
            resolveUnifiedLangGesture(dxPx = 5f, dyPx = -40f, flickThresholdPx = 40f, insideBounds = false)
        )
        assertEquals(
            UnifiedLangGesture.FLICK_DOWN_VARIANT,
            resolveUnifiedLangGesture(dxPx = -10f, dyPx = 45f, flickThresholdPx = 40f, insideBounds = false)
        )
        // Horizontal-dominant drag outside bounds cancels cleanly
        assertNull(resolveUnifiedLangGesture(dxPx = 60f, dyPx = -45f, flickThresholdPx = 40f, insideBounds = false))
        assertNull(resolveUnifiedLangGesture(dxPx = Float.NaN, dyPx = 0f, flickThresholdPx = 40f, insideBounds = true))
        assertNull(resolveUnifiedLangGesture(dxPx = 0f, dyPx = 0f, flickThresholdPx = 0f, insideBounds = true))
    }

    @Test
    fun tapEmitsToggleLanguageAndFeedback() = runTest(StandardTestDispatcher()) {
        val actions = mutableListOf<KeyAction>()
        val feedback = mutableListOf<FeedbackKey>()
        val toolbarState = mutableStateOf(DuoToolbarState(activeChineseScheme = ChineseScheme.PINYIN_26))
        val controller = UnifiedLangGestureController(
            scope = this,
            stateRef = toolbarState,
            onActionState = mutableStateOf<(KeyAction) -> Unit>({ actions += it }),
            onKeyPressDownState = mutableStateOf<(FeedbackKey) -> Unit>({ feedback += it }),
            pressedState = mutableStateOf(false)
        )

        val down = pointerChange(1L, Offset(88f, 44f), pressed = true, previousPressed = false)
        controller.processPointerChange(down, widthPx = 176f, heightPx = 88f, flickThresholdPx = 40f)
        assertTrue(controller.pressedState.value)
        assertTrue(down.isConsumed)
        assertEquals(listOf(FeedbackKey(KeyAction.ToggleLanguage, "中·26", FeedbackZone.TOOLBAR)), feedback)

        advanceTimeBy(150L)
        runCurrent()
        assertTrue(actions.isEmpty())

        val up = pointerChange(1L, Offset(90f, 42f), pressed = false, previousPressed = true)
        controller.processPointerChange(up, widthPx = 176f, heightPx = 88f, flickThresholdPx = 40f)
        assertFalse(controller.pressedState.value)
        assertNull(controller.activePointerId)
        assertTrue(up.isConsumed)
        assertEquals(listOf<KeyAction>(KeyAction.ToggleLanguage), actions)
    }

    @Test
    fun flickUpSwitchesScheme26And9AndFlickDownSwitchesVariant() = runTest(StandardTestDispatcher()) {
        val actions = mutableListOf<KeyAction>()
        val toolbarState = mutableStateOf(DuoToolbarState(activeChineseScheme = ChineseScheme.PINYIN_26))
        val controller = UnifiedLangGestureController(
            scope = this,
            stateRef = toolbarState,
            onActionState = mutableStateOf<(KeyAction) -> Unit>({ actions += it }),
            onKeyPressDownState = mutableStateOf<(FeedbackKey) -> Unit>({}),
            pressedState = mutableStateOf(false)
        )

        // Flick up from 26 -> switches to PINYIN_T9 even when thumb exits top edge
        controller.processPointerChange(
            pointerChange(1L, Offset(88f, 20f), pressed = true, previousPressed = false),
            widthPx = 176f,
            heightPx = 88f,
            flickThresholdPx = 40f
        )
        controller.processPointerChange(
            pointerChange(1L, Offset(90f, -25f), pressed = false, previousPressed = true),
            widthPx = 176f,
            heightPx = 88f,
            flickThresholdPx = 40f
        )
        assertEquals(listOf<KeyAction>(KeyAction.SelectChineseScheme(ChineseScheme.PINYIN_T9)), actions)

        // Flick up from T9 -> switches back to PINYIN_26
        actions.clear()
        toolbarState.value = toolbarState.value.copy(activeChineseScheme = ChineseScheme.PINYIN_T9)
        controller.processPointerChange(
            pointerChange(2L, Offset(88f, 50f), pressed = true, previousPressed = false),
            widthPx = 176f,
            heightPx = 88f,
            flickThresholdPx = 40f
        )
        controller.processPointerChange(
            pointerChange(2L, Offset(88f, 5f), pressed = false, previousPressed = true),
            widthPx = 176f,
            heightPx = 88f,
            flickThresholdPx = 40f
        )
        assertEquals(listOf<KeyAction>(KeyAction.SelectChineseScheme(ChineseScheme.PINYIN_26)), actions)

        // Flick down -> KeyAction.SwitchVariant (简 <-> 繁)
        actions.clear()
        controller.processPointerChange(
            pointerChange(3L, Offset(88f, 44f), pressed = true, previousPressed = false),
            widthPx = 176f,
            heightPx = 88f,
            flickThresholdPx = 40f
        )
        controller.processPointerChange(
            pointerChange(3L, Offset(92f, 96f), pressed = false, previousPressed = true),
            widthPx = 176f,
            heightPx = 88f,
            flickThresholdPx = 40f
        )
        assertEquals(listOf<KeyAction>(KeyAction.SwitchVariant), actions)
    }

    @Test
    fun sustained400msHoldSwitchesSchemeAndSuppressesTrailingReleaseTap() = runTest(StandardTestDispatcher()) {
        val actions = mutableListOf<KeyAction>()
        val toolbarState = mutableStateOf(DuoToolbarState(activeChineseScheme = ChineseScheme.PINYIN_26))
        val controller = UnifiedLangGestureController(
            scope = this,
            stateRef = toolbarState,
            onActionState = mutableStateOf<(KeyAction) -> Unit>({ actions += it }),
            onKeyPressDownState = mutableStateOf<(FeedbackKey) -> Unit>({}),
            pressedState = mutableStateOf(false)
        )

        controller.processPointerChange(
            pointerChange(1L, Offset(88f, 44f), pressed = true, previousPressed = false),
            widthPx = 176f,
            heightPx = 88f,
            flickThresholdPx = 40f
        )
        advanceTimeBy(399L)
        runCurrent()
        assertTrue("Hold must not fire before 400ms", actions.isEmpty())

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf<KeyAction>(KeyAction.SelectChineseScheme(ChineseScheme.PINYIN_T9)), actions)

        // Release inside chip after hold -> suppressed (no trailing ToggleLanguage!)
        controller.processPointerChange(
            pointerChange(1L, Offset(88f, 44f), pressed = false, previousPressed = true),
            widthPx = 176f,
            heightPx = 88f,
            flickThresholdPx = 40f
        )
        assertFalse(controller.pressedState.value)
        assertEquals(1, actions.size)
    }

    @Test
    fun slideOffPreConsumedCancelAndDisabledGuardSuppressActionsAndResetPressedState() =
        runTest(StandardTestDispatcher()) {
            val actions = mutableListOf<KeyAction>()
            val feedback = mutableListOf<FeedbackKey>()
            val toolbarState = mutableStateOf(DuoToolbarState(activeChineseScheme = ChineseScheme.PINYIN_26))
            val controller = UnifiedLangGestureController(
                scope = this,
                stateRef = toolbarState,
                onActionState = mutableStateOf<(KeyAction) -> Unit>({ actions += it }),
                onKeyPressDownState = mutableStateOf<(FeedbackKey) -> Unit>({ feedback += it }),
                pressedState = mutableStateOf(false)
            )

            // 1) Horizontal slide-off cancels hold & resets pressed visual
            controller.processPointerChange(
                pointerChange(1L, Offset(88f, 44f), pressed = true, previousPressed = false),
                widthPx = 176f,
                heightPx = 88f,
                flickThresholdPx = 40f
            )
            advanceTimeBy(200L)
            runCurrent()
            controller.processPointerChange(
                pointerChange(1L, Offset(200f, 44f), pressed = true, previousPressed = true),
                widthPx = 176f,
                heightPx = 88f,
                flickThresholdPx = 40f
            )
            assertFalse(controller.pressedState.value)
            assertNull(controller.activePointerId)
            advanceTimeBy(500L)
            runCurrent()
            assertTrue("Slide-off must cancel hold", actions.isEmpty())

            // 2) Pre-consumed event cancels active hold immediately
            controller.processPointerChange(
                pointerChange(2L, Offset(88f, 44f), pressed = true, previousPressed = false),
                widthPx = 176f,
                heightPx = 88f,
                flickThresholdPx = 40f
            )
            advanceTimeBy(200L)
            runCurrent()
            val consumedMove = pointerChange(2L, Offset(89f, 44f), pressed = true, previousPressed = true)
                .also { it.consume() }
            controller.processPointerChange(consumedMove, widthPx = 176f, heightPx = 88f, flickThresholdPx = 40f)
            assertFalse(controller.pressedState.value)
            advanceTimeBy(500L)
            runCurrent()
            assertTrue("Pre-consumed event must cancel hold", actions.isEmpty())

            // 3) Dispose/cancel clears active hold and pressed state
            controller.processPointerChange(
                pointerChange(3L, Offset(88f, 44f), pressed = true, previousPressed = false),
                widthPx = 176f,
                heightPx = 88f,
                flickThresholdPx = 40f
            )
            assertTrue(controller.pressedState.value)
            controller.cancel()
            assertFalse(controller.pressedState.value)
            advanceTimeBy(500L)
            runCurrent()
            assertTrue("Cancel on dispose must stop pending hold", actions.isEmpty())

            // 4) Disabled (!canToggleLanguage) ignores all pointer events cleanly
            feedback.clear()
            toolbarState.value = toolbarState.value.copy(canToggleLanguage = false)
            val disabledDown = pointerChange(4L, Offset(88f, 44f), pressed = true, previousPressed = false)
            controller.processPointerChange(disabledDown, widthPx = 176f, heightPx = 88f, flickThresholdPx = 40f)
            assertFalse(disabledDown.isConsumed)
            assertFalse(controller.pressedState.value)
            assertTrue(feedback.isEmpty())
            advanceTimeBy(500L)
            runCurrent()
            assertTrue(actions.isEmpty())
        }
}

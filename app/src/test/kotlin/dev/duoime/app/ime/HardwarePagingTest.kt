// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.ime

import android.view.KeyEvent
import dev.duoime.core.model.KeyboardPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardwarePagingTest {

    @Test
    fun hardwareShoulderAndPageKeysPageCandidatesOnlyWhileComposingOutsideSymbolPanel() {
        for (panel in listOf(KeyboardPanel.PRIMARY, KeyboardPanel.NUMBER)) {
            assertEquals(
                false,
                resolveHardwareCandidatePageDirection(
                    KeyEvent.KEYCODE_BUTTON_L1,
                    isComposing = true,
                    activePanel = panel
                )
            )
            assertEquals(
                false,
                resolveHardwareCandidatePageDirection(KeyEvent.KEYCODE_PAGE_UP, isComposing = true, activePanel = panel)
            )
            assertEquals(
                true,
                resolveHardwareCandidatePageDirection(
                    KeyEvent.KEYCODE_BUTTON_R1,
                    isComposing = true,
                    activePanel = panel
                )
            )
            assertEquals(
                true,
                resolveHardwareCandidatePageDirection(
                    KeyEvent.KEYCODE_PAGE_DOWN,
                    isComposing = true,
                    activePanel = panel
                )
            )
            assertNull(
                resolveHardwareCandidatePageDirection(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    isComposing = true,
                    activePanel = panel
                )
            )
        }

        // Idle (not composing) -> always pass through (null)
        for (key in listOf(
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN
        )) {
            assertNull(
                resolveHardwareCandidatePageDirection(key, isComposing = false, activePanel = KeyboardPanel.PRIMARY)
            )
            // Active SYMBOL panel -> pass through (null) even while composing
            assertNull(
                resolveHardwareCandidatePageDirection(key, isComposing = true, activePanel = KeyboardPanel.SYMBOL)
            )
        }
    }
}

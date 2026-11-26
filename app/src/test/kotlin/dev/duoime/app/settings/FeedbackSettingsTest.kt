// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.settings

import dev.duoime.ui.feedback.KeyFeedbackOptions
import dev.duoime.ui.feedback.KeySoundStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackSettingsTest {

    @Test
    fun defaultsKeepSoundOffAndHapticsOn() {
        val defaults = DuoImeSettings()
        assertFalse("Key sound must default to OFF", defaults.soundEnabled)
        assertTrue("Key haptics must default to ON", defaults.hapticEnabled)
        assertEquals(KeyFeedbackOptions.DEFAULT_HAPTIC_STRENGTH, defaults.hapticStrength)
        assertEquals(KeySoundStyle.MECHANICAL, defaults.soundStyle)
        assertEquals(KeyFeedbackOptions.DEFAULT_SOUND_VOLUME, defaults.soundVolume, 0.0001f)
    }

    @Test
    fun toFeedbackOptionsClampsOutOfRangeValuesSafely() {
        val raw = DuoImeSettings(
            hapticEnabled = true,
            hapticStrength = 99,
            soundEnabled = true,
            soundStyle = KeySoundStyle.GUZHENG,
            soundVolume = Float.NaN
        )
        val options = raw.toFeedbackOptions()
        assertEquals(KeyFeedbackOptions.MAX_HAPTIC_STRENGTH, options.hapticStrength)
        assertEquals(KeyFeedbackOptions.DEFAULT_SOUND_VOLUME, options.soundVolume, 0.0001f)
        assertEquals(KeySoundStyle.GUZHENG, options.soundStyle)

        val low = raw.copy(hapticStrength = -5, soundVolume = -1f).toFeedbackOptions()
        assertEquals(KeyFeedbackOptions.MIN_HAPTIC_STRENGTH, low.hapticStrength)
        assertEquals(KeyFeedbackOptions.MIN_SOUND_VOLUME, low.soundVolume, 0.0001f)
    }

    @Test
    fun allSoundStylesMapToDistinctLocalizedStringResources() {
        val resIds = KeySoundStyle.entries.map { soundStyleLabelRes(it) }
        assertEquals(KeySoundStyle.entries.size, resIds.toSet().size)
        assertTrue(resIds.all { it != 0 })
    }
}

// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.dualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemImePinDetectorTest {

    @Test
    fun parsesPinValuesAndAppliesManualOverrideCleanly() {
        assertEquals(true, SystemImePinDetector.parsePinValue(1))
        assertEquals(false, SystemImePinDetector.parsePinValue(0))
        assertNull(SystemImePinDetector.parsePinValue(null))
        assertNull(SystemImePinDetector.parsePinValue(2))

        assertEquals(true, SystemImePinDetector.resolveEffectivePin(detectedPin = null, manualOverride = true))
        assertEquals(true, SystemImePinDetector.resolveEffectivePin(detectedPin = false, manualOverride = true))
        assertEquals(false, SystemImePinDetector.resolveEffectivePin(detectedPin = false, manualOverride = false))
        assertNull(SystemImePinDetector.resolveEffectivePin(detectedPin = null, manualOverride = false))
    }
}

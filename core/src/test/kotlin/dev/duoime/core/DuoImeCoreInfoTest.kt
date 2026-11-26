/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
package dev.duoime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuoImeCoreInfoTest {
    @Test
    fun overlayHeightBoundsAreOrdered() {
        assertEquals("dev.duoime.ext.v1", DuoImeCoreInfo.EXTENSION_SCHEMA_KEY)
        assertTrue(DuoImeCoreInfo.MIN_OVERLAY_HEIGHT_FRACTION < DuoImeCoreInfo.MAX_OVERLAY_HEIGHT_FRACTION)
    }
}

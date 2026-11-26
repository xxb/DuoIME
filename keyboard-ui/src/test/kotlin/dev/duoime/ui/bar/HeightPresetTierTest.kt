// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.bar

import org.junit.Assert.assertEquals
import org.junit.Test

class HeightPresetTierTest {

    @Test
    fun resolvesSemanticHeightTiersAcrossPresetFractions() {
        assertEquals(HeightPresetTier.COMPACT, resolveHeightPresetTier(0.60f))
        assertEquals(HeightPresetTier.STANDARD, resolveHeightPresetTier(0.75f))
        assertEquals(HeightPresetTier.TALL, resolveHeightPresetTier(0.90f))
    }
}

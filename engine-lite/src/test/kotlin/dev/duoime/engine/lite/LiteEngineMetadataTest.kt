/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
package dev.duoime.engine.lite

import org.junit.Assert.assertEquals
import org.junit.Test

class LiteEngineMetadataTest {
    @Test
    fun metadataReferencesCoreProtocolKey() {
        assertEquals("lite", LiteEngineMetadata.ENGINE_ID)
        assertEquals("dev.duoime.ext.v1", LiteEngineMetadata.protocolKey)
    }
}

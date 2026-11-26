// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.ime

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Test

class ImeComposeOwnerTest {

    @Test
    fun lifecycleTransitionsFollowImeShowHideAndDestroyContract() {
        val owner = ImeComposeOwner(useUnsafeRegistryForTests = true)
        assertEquals(Lifecycle.State.INITIALIZED, owner.lifecycle.currentState)

        owner.onCreate()
        assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)

        // Show keyboard -> RESUMED (activates Compose frame clock)
        owner.onResume()
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)

        // Hide keyboard -> CREATED (pauses Compose frame clock)
        owner.onPauseToCreated()
        assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)

        // Show again across app switches -> RESUMED again
        owner.onResume()
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)

        // Service destroy -> DESTROYED; further lifecycle calls are safe no-ops
        owner.onDestroy()
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
        owner.onResume()
        owner.onPauseToCreated()
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }
}

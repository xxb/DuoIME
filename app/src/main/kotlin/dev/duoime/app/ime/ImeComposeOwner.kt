// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.ime

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Single service-scoped Compose ViewTree owner for [DuoImeService] (inline & overlay hosts).
 *
 * Lifecycle discipline (spec §3):
 * - [onCreate]: attaches/restores SavedStateRegistry and moves to [Lifecycle.State.CREATED]
 * - [onResume]: moves to [Lifecycle.State.RESUMED] on `onStartInputView` so Compose's
 *   lifecycle-aware frame clock is active (prevents frozen IME UI)
 * - [onPauseToCreated]: returns to [Lifecycle.State.CREATED] on `onFinishInputView`/`onWindowHidden`
 *   to pause the frame clock and save battery
 * - [onDestroy]: moves to [Lifecycle.State.DESTROYED] and clears [viewModelStore]
 */
class ImeComposeOwner internal constructor(useUnsafeRegistryForTests: Boolean) :
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    constructor() : this(useUnsafeRegistryForTests = false)

    private val lifecycleRegistry: LifecycleRegistry = if (useUnsafeRegistryForTests) {
        LifecycleRegistry.createUnsafe(this)
    } else {
        LifecycleRegistry(this)
    }
    private val savedStateController: SavedStateRegistryController = SavedStateRegistryController.create(this)
    private val store: ViewModelStore = ViewModelStore()

    private var created: Boolean = false
    private var destroyed: Boolean = false

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    fun onCreate() {
        if (created || destroyed) return
        created = true
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onResume() {
        if (destroyed) return
        if (!created) {
            onCreate()
        }
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onPauseToCreated() {
        if (!created || destroyed) return
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onDestroy() {
        if (destroyed) return
        destroyed = true
        if (created) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        store.clear()
    }

    /**
     * Attaches all three ViewTree owners onto [view]. For inline IME windows, callers MUST attach
     * to `window.window!!.decorView` (in addition to the `ComposeView` itself) because Compose
     * resolves owners starting from the window decor root.
     */
    fun attachToView(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
    }
}

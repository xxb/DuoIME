// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.embed

import androidx.compose.runtime.Immutable
import dev.duoime.core.engine.EngineSchemas
import dev.duoime.core.layout.BuiltInLayouts
import dev.duoime.core.layout.LayoutSpec
import dev.duoime.core.model.DerivedKeyboardState
import dev.duoime.core.model.EditorContext
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.InputConstraints
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyboardPrefs
import dev.duoime.core.model.SessionState
import dev.duoime.core.model.deriveKeyboardState
import dev.duoime.core.policy.DisplayPlacementPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Lifecycle classification for engine warnings surfaced in [KeyboardUiState.engineIssue].
 */
enum class EngineIssueKind {
    /** Per-event exception recovery; clears automatically on the next successful input event. */
    TRANSIENT,

    /** Init-time engine fallback (e.g. Lite or PassThroughEngine); stays until explicitly dismissed. */
    PERSISTENT
}

/**
 * Structured engine status issue surfaced to the toolbar warning chip.
 */
@Immutable
data class EngineIssue(
    val kind: EngineIssueKind,
    val tag: String
)

/**
 * Immutable UI state hoisted out of Compose so display swaps can rebuild `ComposeView` cheaply
 * without losing composition, layout mode, or height settings.
 */
@Immutable
data class KeyboardUiState(
    val isEngineReady: Boolean = false,
    val engineIssue: EngineIssue? = null,
    val snapshot: EngineSnapshot = EngineSnapshot.EMPTY,
    val activeLayout: LayoutSpec = BuiltInLayouts.qwerty(),
    val shifted: Boolean = false,
    val includeNumberRow: Boolean = false,
    val heightFraction: Float = DisplayPlacementPolicy.DEFAULT_HEIGHT_FRACTION,
    val isOverlayMode: Boolean = false,
    val editorContext: EditorContext = EditorContext(),
    val prefs: KeyboardPrefs = KeyboardPrefs(),
    val session: SessionState = SessionState(),
    val constraints: InputConstraints = InputConstraints(),
    val derived: DerivedKeyboardState = deriveKeyboardState(prefs, session, constraints)
)

/**
 * Configuration for [KeyboardController] dispatchers, backend schema mapping, initial user
 * preferences, per-host language memory key, and presentation state.
 *
 * **Threading:** [sinkDispatcher] defaults to [Dispatchers.Main.immediate] because Android
 * `InputConnection` and host sinks expect main-thread invocation. Pure-JVM callers and unit tests
 * must override [sinkDispatcher] explicitly (e.g. with a `TestDispatcher`).
 */
data class KeyboardControllerConfig(
    val engineDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val sinkDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    val initialHeightFraction: Float = DisplayPlacementPolicy.DEFAULT_HEIGHT_FRACTION,
    val initialOverlayMode: Boolean = false,
    val modeSchemaIds: Map<InputMode, String> = EngineSchemas.DEFAULT_MODE_MAP,
    val initialPrefs: KeyboardPrefs = KeyboardPrefs(),
    val languageMemoryKey: String? = null
)

/**
 * Host-level callbacks invoked by [KeyboardController] for system/window-level actions.
 */
data class KeyboardHostCallbacks(
    val onSwapScreenRequested: () -> Unit = {},
    val onSwitchImeRequested: () -> Unit = {},
    val onDismissRequested: () -> Unit = {},
    val onHeightFractionChanged: (Float) -> Unit = {},
    val onFeedbackKey: (FeedbackKey) -> Unit = {},
    val onRepeatFeedbackKey: (FeedbackKey) -> Unit = {}
)

// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.model

/**
 * Semantic actions emitted by keyboard views and toolbars to the input controller.
 */
sealed interface KeyAction {
    data class Text(val text: String) : KeyAction

    data class Code(val keyCode: Int, val metaState: Int = 0) : KeyAction

    data class Engine(val keysym: Int, val mask: Int = 0) : KeyAction

    data class Custom(val id: String) : KeyAction

    data object Backspace : KeyAction

    data object Enter : KeyAction

    data object Space : KeyAction

    data object Shift : KeyAction

    data class SwitchMode(val targetMode: InputMode) : KeyAction

    data object ToggleLanguage : KeyAction

    data class SelectChineseScheme(val scheme: ChineseScheme) : KeyAction

    data class OpenPanel(val panel: KeyboardPanel, val tab: SymbolTabId? = null) : KeyAction

    data object ClosePanel : KeyAction

    data object TogglePanelLock : KeyAction

    data object ClearPreedit : KeyAction

    data class SelectSyllable(val index: Int) : KeyAction

    data class UnlockSyllablesFrom(val fromIndex: Int) : KeyAction

    data object ToggleDrawer : KeyAction

    data object ToggleIncognito : KeyAction

    data object ToggleNumberRow : KeyAction

    data class SelectSymbolTab(val tab: SymbolTabId) : KeyAction

    data class CommitPanelSymbol(val text: String, val keepOpen: Boolean = false) : KeyAction

    data object SwitchVariant : KeyAction

    data object SwapScreen : KeyAction

    data object CycleHeight : KeyAction

    data object ToggleToolbar : KeyAction

    data object SwitchIme : KeyAction

    data object Dismiss : KeyAction
}

// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.embed

import androidx.compose.runtime.Immutable
import dev.duoime.core.model.CandidatePaging
import dev.duoime.core.model.LangKeyPlacement
import dev.duoime.core.model.QuickPin
import dev.duoime.core.model.SymbolTabId
import dev.duoime.core.model.ToolbarMode
import dev.duoime.core.protocol.ExtensionToolbarButton
import dev.duoime.ui.theme.DuoThemePreset

/**
 * Declarative visual and chrome policy supplied by an IME or embedded Compose host.
 */
@Immutable
data class ChromePolicy(
    val toolbarMode: ToolbarMode = ToolbarMode.FULL,
    val pins: List<QuickPin> = EMBED_DEFAULT_PINS,
    val langKeyPlacement: LangKeyPlacement = LangKeyPlacement.TOOLBAR,
    val candidatePaging: CandidatePaging? = null,
    val themePreset: DuoThemePreset = DuoThemePreset.DARK,
    val initialSymbolTab: SymbolTabId = SymbolTabId.COMMON
) {
    /**
     * Resolves effective candidate browsing style:
     * explicit [candidatePaging] > editor [derivedOverride] > `PAGED` on [DuoThemePreset.EINK]
     * else `SCROLL`.
     */
    fun resolvedCandidatePaging(derivedOverride: CandidatePaging?): CandidatePaging = candidatePaging
        ?: derivedOverride
        ?: if (themePreset == DuoThemePreset.EINK) CandidatePaging.PAGED else CandidatePaging.SCROLL

    companion object {
        val EMBED_DEFAULT_PINS: List<QuickPin> = listOf(QuickPin.SCHEME_SWITCH)
        val APP_DEFAULT_PINS: List<QuickPin> = listOf(QuickPin.SCHEME_SWITCH, QuickPin.SWAP_SCREEN)

        val EMBED_DEFAULT: ChromePolicy = ChromePolicy(pins = EMBED_DEFAULT_PINS)
        val APP_DEFAULT: ChromePolicy = ChromePolicy(pins = APP_DEFAULT_PINS)
    }
}

/**
 * Capability-driven host action callbacks wired into `DuoToolbar` and `DuoQuickDrawer`.
 *
 * A `null` callback strictly hides the corresponding button in BOTH `DuoToolbar` and
 * `DuoQuickDrawer` so embedded apps never display dead system-IME controls.
 */
@Immutable
data class HostChrome(
    val onSwapScreen: (() -> Unit)? = null,
    val onSwitchIme: (() -> Unit)? = null,
    val onDismiss: (() -> Unit)? = null,
    val onExtensionButton: ((ExtensionToolbarButton) -> Unit)? = null
) {
    val canSwapScreen: Boolean get() = onSwapScreen != null
    val canSwitchIme: Boolean get() = onSwitchIme != null
    val showDismiss: Boolean get() = onDismiss != null

    companion object {
        val NONE: HostChrome = HostChrome()
    }
}

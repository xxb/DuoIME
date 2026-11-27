// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui

import dev.duoime.core.layout.BuiltInLayouts
import dev.duoime.core.model.ChineseScheme
import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.EditorContext
import dev.duoime.core.model.FieldKind
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.KeyboardPanel
import dev.duoime.core.model.KeyboardPrefs
import dev.duoime.core.model.LangKeyPlacement
import dev.duoime.core.model.Language
import dev.duoime.core.model.SessionState
import dev.duoime.core.model.deriveKeyboardState
import dev.duoime.core.model.toInputConstraints
import dev.duoime.ui.bar.DuoToolbarMeta
import dev.duoime.ui.bar.DuoToolbarState
import dev.duoime.ui.panel.DuoQuickDrawerMeta
import dev.duoime.ui.panel.DuoQuickDrawerState
import dev.duoime.ui.panel.DuoSymbolPanelMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive pure-JVM verification of DuoIME's Zero-Duplication Invariant across all
 * `(Language × ChineseScheme × KeyboardPanel × FieldKind × LangKeyPlacement)` states:
 * no navigation action (`ToggleLanguage`, `OpenPanel(SYMBOL)`, `OpenPanel(NUMBER)`,
 * `SwitchVariant`, or scheme switch) may ever appear twice on the same visible screen.
 */
class ZeroDuplicationInvariantTest {

    private enum class NavBucket {
        TOGGLE_LANGUAGE,
        OPEN_SYMBOL,
        OPEN_NUMBER,
        SWITCH_VARIANT,
        SCHEME_SWITCH
    }

    @Test
    fun noNavigationActionIsDuplicatedAcrossAllOrthogonalStateCombinations() {
        var combinationsChecked = 0

        for (language in Language.entries) {
            for (scheme in ChineseScheme.entries) {
                for (panel in KeyboardPanel.entries) {
                    for (fieldKind in FieldKind.entries) {
                        for (placement in LangKeyPlacement.entries) {
                            for (isComposing in listOf(false, true)) {
                                for (drawerOpen in listOf(false, true)) {
                                    verifyScreenCombination(
                                        language = language,
                                        scheme = scheme,
                                        panel = panel,
                                        fieldKind = fieldKind,
                                        placement = placement,
                                        isComposing = isComposing,
                                        drawerOpen = drawerOpen
                                    )
                                    combinationsChecked++
                                }
                            }
                        }
                    }
                }
            }
        }

        assertEquals(
            Language.entries.size *
                ChineseScheme.entries.size *
                KeyboardPanel.entries.size *
                FieldKind.entries.size *
                LangKeyPlacement.entries.size *
                4,
            combinationsChecked
        )
    }

    @Suppress("LongParameterList")
    private fun verifyScreenCombination(
        language: Language,
        scheme: ChineseScheme,
        panel: KeyboardPanel,
        fieldKind: FieldKind,
        placement: LangKeyPlacement,
        isComposing: Boolean,
        drawerOpen: Boolean
    ) {
        val prefs = KeyboardPrefs(
            language = language,
            chineseScheme = scheme,
            variant = ChineseVariant.SIMPLIFIED
        )
        val session = SessionState(
            panel = panel,
            languageOverride = language,
            drawerOpen = drawerOpen
        )
        val constraints = EditorContext(
            isPassword = fieldKind == FieldKind.PASSWORD,
            fieldKind = fieldKind
        ).toInputConstraints().copy(langKeyPlacementOverride = placement)
        val derived = deriveKeyboardState(prefs, session, constraints)
        val label = "$language/$scheme/$panel/$fieldKind/$placement/comp=$isComposing/drawer=$drawerOpen"

        val counts = HashMap<NavBucket, Int>()
        fun record(bucket: NavBucket) {
            counts[bucket] = (counts[bucket] ?: 0) + 1
        }

        // Top bar: DuoToolbar (when idle or drawerOpen) vs CandidateBar (composing && !drawerOpen -> 0 nav actions)
        val showToolbar = drawerOpen || !isComposing
        if (showToolbar) {
            val toolbarState = DuoToolbarState(
                activeChineseScheme = derived.effectiveChineseScheme,
                effectiveLanguage = derived.effectiveLanguage,
                variant = prefs.variant,
                drawerOpen = drawerOpen,
                canToggleLanguage = derived.canToggleLanguage
            )
            recordActionsList(DuoToolbarMeta.visibleActions(toolbarState), ::record)
        }

        // Body: DuoQuickDrawer vs DuoSymbolPanel vs KeyGrid (QWERTY / T9 / Numpad)
        if (drawerOpen) {
            val drawerState = DuoQuickDrawerState(
                activeChineseScheme = derived.effectiveChineseScheme,
                variant = prefs.variant,
                includeNumberRow = derived.includeNumberRow,
                heightFraction = 0.75f,
                isOverlayMode = false,
                canToggleLanguage = derived.canToggleLanguage
            )
            recordActionsList(DuoQuickDrawerMeta.visibleActions(drawerState), ::record)
        } else if (derived.panel == KeyboardPanel.SYMBOL) {
            recordActionsList(DuoSymbolPanelMeta.bottomBarActions(), ::record)
        } else {
            val layout = when (derived.panel) {
                KeyboardPanel.NUMBER -> BuiltInLayouts.numberPad(
                    fieldKind = derived.fieldKind,
                    mode = derived.activeMode
                )
                KeyboardPanel.PRIMARY -> when (derived.activeMode) {
                    InputMode.PINYIN_T9 -> BuiltInLayouts.t9(
                        isComposing = isComposing,
                        variant = prefs.variant,
                        canToggleLanguage = derived.canToggleLanguage,
                        langKeyPlacement = placement,
                        language = derived.effectiveLanguage
                    )
                    InputMode.PINYIN_26, InputMode.ENGLISH -> BuiltInLayouts.qwerty(
                        includeNumberRow = derived.includeNumberRow,
                        shifted = derived.shifted,
                        englishMode = derived.activeMode == InputMode.ENGLISH,
                        variant = prefs.variant,
                        fieldKind = derived.fieldKind,
                        canToggleLanguage = derived.canToggleLanguage,
                        langKeyPlacement = placement
                    )
                }
                KeyboardPanel.SYMBOL -> error("Handled above")
            }

            val primaryActions = layout.rows.flatMap { it.keys }.filter { !it.isSlot }.map { it.action }
            val secondaryActions = layout.rows.flatMap { it.keys }
                .filter { !it.isSlot }
                .mapNotNull { it.resolveSecondaryAction() }
            recordActionsList(primaryActions + secondaryActions, ::record)

            // Verify HOST placement omits ToggleLanguage from primary grids
            if (derived.panel == KeyboardPanel.PRIMARY) {
                val hasToggleLang = primaryActions.any { it == KeyAction.ToggleLanguage }
                if (placement == LangKeyPlacement.HOST) {
                    assertFalse("HOST placement must omit ToggleLanguage in $label", hasToggleLang)
                } else {
                    assertTrue("GRID placement must include ToggleLanguage in $label", hasToggleLang)
                }
            }
        }

        for ((bucket, count) in counts) {
            assertTrue("Duplicate $bucket ($count occurrences) in state $label", count <= 1)
        }
    }

    private fun recordActionsList(actions: List<KeyAction>, record: (NavBucket) -> Unit) {
        var sawSchemeSwitchInThisComponent = false
        for (action in actions) {
            when (action) {
                KeyAction.ToggleLanguage -> record(NavBucket.TOGGLE_LANGUAGE)
                is KeyAction.OpenPanel -> when (action.panel) {
                    KeyboardPanel.SYMBOL -> record(NavBucket.OPEN_SYMBOL)
                    KeyboardPanel.NUMBER -> record(NavBucket.OPEN_NUMBER)
                    KeyboardPanel.PRIMARY -> Unit
                }
                KeyAction.SwitchVariant -> record(NavBucket.SWITCH_VARIANT)
                is KeyAction.SelectChineseScheme -> {
                    // A segmented [26|9] toggle counts as ONE scheme-switch control per component;
                    // if TWO separate components both expose SelectChineseScheme, count reaches 2 and fails!
                    if (!sawSchemeSwitchInThisComponent) {
                        sawSchemeSwitchInThisComponent = true
                        record(NavBucket.SCHEME_SWITCH)
                    }
                }
                else -> Unit
            }
        }
    }
}

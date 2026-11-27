// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui

import dev.duoime.core.layout.BuiltInLayouts
import dev.duoime.core.layout.LayoutSpec
import dev.duoime.core.model.ChineseScheme
import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.DerivedKeyboardState
import dev.duoime.core.model.EditorContext
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.core.model.FieldKind
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.KeyboardPanel
import dev.duoime.core.model.KeyboardPrefs
import dev.duoime.core.model.LangKeyPlacement
import dev.duoime.core.model.Language
import dev.duoime.core.model.QuickPin
import dev.duoime.core.model.SessionState
import dev.duoime.core.model.ToolbarMode
import dev.duoime.core.model.deriveKeyboardState
import dev.duoime.core.model.resolveEffectiveLangKeyPlacement
import dev.duoime.core.model.toInputConstraints
import dev.duoime.ui.bar.DEFAULT_TOOLBAR_PINS
import dev.duoime.ui.bar.DuoToolbarMeta
import dev.duoime.ui.panel.DuoQuickDrawerMeta
import dev.duoime.ui.panel.DuoSymbolPanelMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive pure-JVM verification of DuoIME's Zero-Duplication Invariant across all
 * `(Language × ChineseScheme × KeyboardPanel × FieldKind × LangKeyPlacement × isComposing ×
 * drawerOpen × toolbarPins × capability flags)` combinations using the shared
 * [DuoKeyboardSurfaceState.toToolbarState] and [DuoKeyboardSurfaceState.toQuickDrawerState]
 * production mappings.
 */
class ZeroDuplicationInvariantTest {

    private enum class NavBucket {
        TOGGLE_LANGUAGE,
        OPEN_SYMBOL,
        OPEN_NUMBER,
        SWITCH_VARIANT,
        SCHEME_SWITCH,
        TOGGLE_DRAWER,
        CYCLE_HEIGHT,
        TOGGLE_NUMBER_ROW,
        SWAP_SCREEN,
        SWITCH_IME,
        DISMISS
    }

    private data class HostCapabilities(
        val canSwapScreen: Boolean,
        val canSwitchIme: Boolean,
        val showDismiss: Boolean
    )

    @Test
    fun noNavigationActionIsDuplicatedAcrossAllOrthogonalStatePinAndCapabilityCombinations() {
        val pinConfigs: List<List<QuickPin>> = buildList {
            add(emptyList())
            add(DEFAULT_TOOLBAR_PINS)
            add(QuickPin.entries)
            for (pin in QuickPin.entries) {
                add(listOf(pin))
            }
        }
        val capabilityCombos: List<HostCapabilities> = buildList {
            for (canSwap in listOf(false, true)) {
                for (canSwitchIme in listOf(false, true)) {
                    for (showDismiss in listOf(false, true)) {
                        add(HostCapabilities(canSwap, canSwitchIme, showDismiss))
                    }
                }
            }
        }

        var combinationsChecked = 0
        for (language in Language.entries) {
            for (scheme in ChineseScheme.entries) {
                for (panel in KeyboardPanel.entries) {
                    for (fieldKind in FieldKind.entries) {
                        for (placement in LangKeyPlacement.entries) {
                            for (isComposing in listOf(false, true)) {
                                combinationsChecked += verifyBaseStateAcrossSurfaceConfigs(
                                    language = language,
                                    scheme = scheme,
                                    panel = panel,
                                    fieldKind = fieldKind,
                                    placement = placement,
                                    isComposing = isComposing,
                                    pinConfigs = pinConfigs,
                                    capabilityCombos = capabilityCombos
                                )
                            }
                        }
                    }
                }
            }
        }

        val expected = Language.entries.size *
            ChineseScheme.entries.size *
            KeyboardPanel.entries.size *
            FieldKind.entries.size *
            LangKeyPlacement.entries.size *
            2 * // isComposing
            2 * // drawerOpen
            ToolbarMode.entries.size *
            pinConfigs.size *
            capabilityCombos.size
        assertEquals(expected, combinationsChecked)
    }

    @Suppress("LongParameterList")
    private fun verifyBaseStateAcrossSurfaceConfigs(
        language: Language,
        scheme: ChineseScheme,
        panel: KeyboardPanel,
        fieldKind: FieldKind,
        placement: LangKeyPlacement,
        isComposing: Boolean,
        pinConfigs: List<List<QuickPin>>,
        capabilityCombos: List<HostCapabilities>
    ): Int {
        val prefs = KeyboardPrefs(
            language = language,
            chineseScheme = scheme,
            variant = ChineseVariant.SIMPLIFIED
        )
        val baseSession = SessionState(
            panel = panel,
            languageOverride = language,
            drawerOpen = false
        )
        val constraints = EditorContext(
            isPassword = fieldKind == FieldKind.PASSWORD,
            fieldKind = fieldKind
        ).toInputConstraints().copy(langKeyPlacementOverride = placement)
        val closedDerived = deriveKeyboardState(prefs, baseSession, constraints)
        val snapshot = EngineSnapshot(
            mode = closedDerived.activeMode,
            variant = prefs.variant,
            isComposing = isComposing
        )

        var checked = 0
        for (toolbarMode in ToolbarMode.entries) {
            val effectivePlacement = resolveEffectiveLangKeyPlacement(placement, toolbarMode)
            if (placement == LangKeyPlacement.TOOLBAR) {
                val expectedPlacement = if (toolbarMode == ToolbarMode.FULL) {
                    LangKeyPlacement.TOOLBAR
                } else {
                    LangKeyPlacement.GRID
                }
                assertEquals(
                    "TOOLBAR placement fallback mismatch for toolbarMode=$toolbarMode",
                    expectedPlacement,
                    effectivePlacement
                )
            }
            val activeLayout = buildLayoutFor(closedDerived, prefs, effectivePlacement, isComposing)
            val bodyGridActions = collectBodyActions(
                derived = closedDerived,
                layout = activeLayout,
                placement = effectivePlacement,
                label = "$language/$scheme/$panel/$fieldKind/$placement/$toolbarMode"
            )
            for (drawerOpen in listOf(false, true)) {
                val derived = if (drawerOpen) {
                    deriveKeyboardState(prefs, baseSession.copy(drawerOpen = true), constraints)
                } else {
                    closedDerived
                }
                for (pins in pinConfigs) {
                    for (caps in capabilityCombos) {
                        val surfaceState = DuoKeyboardSurfaceState(
                            snapshot = snapshot,
                            layout = activeLayout,
                            isReady = true,
                            heightFraction = 0.75f,
                            isOverlayMode = false,
                            canToggleLanguage = derived.canToggleLanguage,
                            activeChineseScheme = derived.effectiveChineseScheme,
                            effectiveLanguage = derived.effectiveLanguage,
                            activePanel = derived.panel,
                            panelLocked = derived.panelLocked,
                            symbolTab = derived.symbolTab,
                            fieldKind = derived.fieldKind,
                            drawerOpen = derived.drawerOpen,
                            includeNumberRow = derived.includeNumberRow,
                            canSwapScreen = caps.canSwapScreen,
                            canSwitchIme = caps.canSwitchIme,
                            showDismiss = caps.showDismiss,
                            toolbarPins = pins,
                            toolbarMode = toolbarMode,
                            langKeyPlacement = effectivePlacement
                        )
                        verifySingleSurfaceState(
                            surfaceState = surfaceState,
                            bodyGridActions = bodyGridActions,
                            label = "$language/$scheme/$panel/$fieldKind/$placement/eff=$effectivePlacement/" +
                                "comp=$isComposing/drawer=$drawerOpen/tb=$toolbarMode/pins=$pins/$caps"
                        )
                        checked++
                    }
                }
            }
        }
        return checked
    }

    private fun verifySingleSurfaceState(
        surfaceState: DuoKeyboardSurfaceState,
        bodyGridActions: List<KeyAction>,
        label: String
    ) {
        val counts = IntArray(NavBucket.entries.size)
        fun record(bucket: NavBucket) {
            counts[bucket.ordinal]++
        }

        // Top bar mirrors DuoKeyboardSurface.resolveHeaderSlotMode()
        val headerMode = surfaceState.resolveHeaderSlotMode()
        if (surfaceState.toolbarMode != ToolbarMode.FULL) {
            assertFalse(
                "Non-FULL ToolbarMode must never render DuoToolbar in $label",
                headerMode == HeaderSlotMode.TOOLBAR
            )
        }
        if (headerMode == HeaderSlotMode.TOOLBAR) {
            recordActionsList(DuoToolbarMeta.visibleActions(surfaceState.toToolbarState()), ::record)
        }

        // Body mirrors SurfaceBody: DuoQuickDrawer vs DuoSymbolPanel / KeyGrid
        if (surfaceState.drawerOpen) {
            recordActionsList(DuoQuickDrawerMeta.visibleActions(surfaceState.toQuickDrawerState()), ::record)
        } else {
            recordActionsList(bodyGridActions, ::record)
        }

        for (bucket in NavBucket.entries) {
            val count = counts[bucket.ordinal]
            assertTrue("Duplicate $bucket ($count occurrences) in $label", count <= 1)
        }
        if (!surfaceState.canSwapScreen) {
            assertEquals(
                "SwapScreen must be hidden when !canSwapScreen in $label",
                0,
                counts[NavBucket.SWAP_SCREEN.ordinal]
            )
        }
        if (!surfaceState.canSwitchIme) {
            assertEquals(
                "SwitchIme must be hidden when !canSwitchIme in $label",
                0,
                counts[NavBucket.SWITCH_IME.ordinal]
            )
        }
        if (!surfaceState.showDismiss) {
            assertEquals("Dismiss must be hidden when !showDismiss in $label", 0, counts[NavBucket.DISMISS.ordinal])
        }
    }

    private fun buildLayoutFor(
        derived: DerivedKeyboardState,
        prefs: KeyboardPrefs,
        placement: LangKeyPlacement,
        isComposing: Boolean
    ): LayoutSpec = when (derived.panel) {
        KeyboardPanel.NUMBER -> BuiltInLayouts.numberPad(
            fieldKind = derived.fieldKind,
            mode = derived.activeMode
        )
        KeyboardPanel.SYMBOL -> BuiltInLayouts.symbolsV0(derived.activeMode)
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
    }

    private fun collectBodyActions(
        derived: DerivedKeyboardState,
        layout: LayoutSpec,
        placement: LangKeyPlacement,
        label: String
    ): List<KeyAction> {
        if (derived.panel == KeyboardPanel.SYMBOL) {
            return DuoSymbolPanelMeta.bottomBarActions()
        }
        val primaryActions = layout.rows.flatMap { it.keys }.filter { !it.isSlot }.map { it.action }
        val secondaryActions = layout.rows.flatMap { it.keys }
            .filter { !it.isSlot }
            .mapNotNull { it.resolveSecondaryAction() }
        if (derived.panel == KeyboardPanel.PRIMARY) {
            val hasToggleLang = primaryActions.any { it == KeyAction.ToggleLanguage }
            if (placement == LangKeyPlacement.GRID) {
                assertTrue("GRID placement must include ToggleLanguage in $label", hasToggleLang)
            } else {
                assertFalse("$placement placement must omit ToggleLanguage in $label", hasToggleLang)
            }
        }
        return primaryActions + secondaryActions
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
                KeyAction.ToggleDrawer -> record(NavBucket.TOGGLE_DRAWER)
                KeyAction.CycleHeight -> record(NavBucket.CYCLE_HEIGHT)
                KeyAction.ToggleNumberRow -> record(NavBucket.TOGGLE_NUMBER_ROW)
                KeyAction.SwapScreen -> record(NavBucket.SWAP_SCREEN)
                KeyAction.SwitchIme -> record(NavBucket.SWITCH_IME)
                KeyAction.Dismiss -> record(NavBucket.DISMISS)
                else -> Unit
            }
        }
    }
}

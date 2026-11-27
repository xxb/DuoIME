/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
package dev.duoime.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.duoime.core.layout.BuiltInLayouts
import dev.duoime.core.layout.LayoutSpec
import dev.duoime.core.model.CandidateItem
import dev.duoime.core.model.EngineSnapshot
import dev.duoime.core.model.ToolbarMode
import dev.duoime.core.model.WindowCandidateItem
import dev.duoime.ui.bar.CandidateBarLayout
import dev.duoime.ui.bar.resolveScrollTargetIndex
import dev.duoime.ui.grid.KeyGridGeometry
import dev.duoime.ui.theme.DuoDarkColors
import dev.duoime.ui.theme.DuoDefaultDimensions
import dev.duoime.ui.theme.DuoDimensionTokens
import dev.duoime.ui.theme.DuoEinkColors
import dev.duoime.ui.theme.DuoLightColors
import dev.duoime.ui.theme.DuoThemePreset
import dev.duoime.ui.theme.resolveAdaptiveDimensions
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuoKeyboardUiSmokeTest {
    @Test
    fun placeholderLabelIsNonBlank() {
        assertTrue(DuoKeyboardUiInfo.PLACEHOLDER_LABEL.isNotBlank())
    }

    @Test
    fun defaultBarHeightsSatisfyErgonomicInvariantsAndKeepToolbarEqualToCandidateBar() {
        assertEquals(DuoDefaultDimensions.toolbarHeight, DuoDefaultDimensions.candidateBarHeight)
        assertTrue("Toolbar must be >= 48dp", DuoDefaultDimensions.toolbarHeight >= 48.dp)
        assertTrue("Candidate bar must be >= 48dp", DuoDefaultDimensions.candidateBarHeight >= 48.dp)
        assertTrue(
            "Candidate bar inner hit height must be >= 48dp",
            DuoDefaultDimensions.candidateBarHeight - (CandidateBarLayout.OUTER_VERTICAL_PADDING_DP * 2f).dp >= 48.dp
        )
    }

    @Test
    fun candidateBarWidthBudgetFitsComfortableCandidateCountsOnCompactAndStandardScreens() {
        assertTrue(CandidateBarLayout.CANDIDATE_MIN_WIDTH_DP >= 48f)
        val capacityPagedAt320 = CandidateBarLayout.maxVisibleMinWidthCandidates(
            barWidthDp = 320f,
            hasPagingButtons = true
        )
        val capacityScrollAt320 = CandidateBarLayout.maxVisibleMinWidthCandidates(
            barWidthDp = 320f,
            hasPagingButtons = false
        )
        val capacityPagedAt375 = CandidateBarLayout.maxVisibleMinWidthCandidates(
            barWidthDp = 375f,
            hasPagingButtons = true
        )
        assertTrue("Expected >= 3 paged chips at 320dp, got $capacityPagedAt320", capacityPagedAt320 >= 3)
        assertTrue("Expected scroll capacity > paged capacity", capacityScrollAt320 > capacityPagedAt320)
        assertTrue("Expected >= 4 paged chips at 375dp, got $capacityPagedAt375", capacityPagedAt375 >= 4)
    }

    @Test
    fun resolveAdaptiveDimensionsWithoutHorizontalSyllableRailMatchesRenderedKeyRows() {
        val qwerty4 = BuiltInLayouts.qwerty(includeNumberRow = false)
        val qwerty5 = BuiltInLayouts.qwerty(includeNumberRow = true)
        val t9 = BuiltInLayouts.t9()

        fun measureRenderedRowHeight(
            availableHeightDp: Float,
            layout: LayoutSpec,
            resolved: DuoDimensionTokens
        ): Float {
            assertEquals(resolved.toolbarHeight, resolved.candidateBarHeight)
            val gridHeight = availableHeightDp -
                resolved.candidateBarHeight.value -
                1f -
                resolved.gridVerticalPadding.value * 2f
            val hitMap = KeyGridGeometry.computeHitMap(
                layout = layout,
                widthPx = 360f,
                heightPx = gridHeight,
                horizontalGapPx = resolved.keyHorizontalGap.value,
                verticalGapPx = resolved.keyVerticalGap.value
            )
            return hitMap.bottom(0) - hitMap.top(0)
        }

        // 360dp 4-row QWERTY & 4-row T9 (no horizontal syllable rail -> reclaimed 34-64dp!)
        val r360Four = resolveAdaptiveDimensions(360f, qwerty4.rows.size, hasSyllableRail = false)
        val row360Four = measureRenderedRowHeight(360f, qwerty4, r360Four)
        assertTrue(abs(r360Four.candidateBarHeight.value - row360Four) < 0.05f)
        assertTrue(r360Four.candidateBarHeight.value - CandidateBarLayout.OUTER_VERTICAL_PADDING_DP * 2f >= 48f)

        val r360T9 = resolveAdaptiveDimensions(360f, t9.rows.size, hasSyllableRail = false)
        val row360T9 = measureRenderedRowHeight(360f, t9, r360T9)
        assertTrue(abs(r360T9.candidateBarHeight.value - row360T9) < 0.05f)
        assertEquals(row360Four, row360T9, 0.05f)
        assertTrue("Reclaimed T9 key row at 360dp must exceed 64dp, got $row360T9", row360T9 >= 64f)

        // 360dp 5-row QWERTY & 432dp 5-row QWERTY
        val r360Five = resolveAdaptiveDimensions(360f, qwerty5.rows.size, hasSyllableRail = false)
        val row360Five = measureRenderedRowHeight(360f, qwerty5, r360Five)
        assertTrue(abs(r360Five.candidateBarHeight.value - row360Five) < 0.05f)
        assertTrue(r360Five.candidateBarHeight.value - CandidateBarLayout.OUTER_VERTICAL_PADDING_DP * 2f >= 48f)
        assertTrue(r360Five.candidateBarHeight < r360Four.candidateBarHeight)

        val r432Five = resolveAdaptiveDimensions(432f, qwerty5.rows.size, hasSyllableRail = false)
        val row432Five = measureRenderedRowHeight(432f, qwerty5, r432Five)
        assertTrue(abs(r432Five.candidateBarHeight.value - row432Five) < 0.05f)

        // 260dp T9 without horizontal syllable rail now reaches ~45.8dp key rows (reclaiming space!)
        val r260T9 = resolveAdaptiveDimensions(260f, t9.rows.size, hasSyllableRail = false)
        val row260T9 = measureRenderedRowHeight(260f, t9, r260T9)
        assertTrue(abs(r260T9.candidateBarHeight.value - row260T9) < 0.05f)
        assertTrue("260dp T9 without horizontal rail stays >= 45dp, got $row260T9", row260T9 >= 45f)

        // 900dp giant screen strictly capped at 76dp
        val r900 = resolveAdaptiveDimensions(900f, t9.rows.size, hasSyllableRail = false)
        assertEquals(76.dp, r900.toolbarHeight)
        assertEquals(76.dp, r900.candidateBarHeight)
        assertTrue(r900.candidateFontSp in 16f..25f)
        assertTrue(r900.barButtonFontSp in 15f..22f)
        assertTrue(r900.preeditFontSp in 13f..19f)
        assertTrue(r900.toolbarFontSp in 12f..18f)

        // Header-less (ToolbarMode.NONE / idle CANDIDATE_ONLY) gives reclaimed height cleanly to key rows
        val withHeader = resolveAdaptiveDimensions(300f, qwerty4.rows.size, hasSyllableRail = false, hasHeader = true)
        val noHeader = resolveAdaptiveDimensions(300f, qwerty4.rows.size, hasSyllableRail = false, hasHeader = false)
        assertTrue(noHeader.toolbarHeight > withHeader.toolbarHeight)
    }

    @Test
    fun einkThemePresetHasHighContrastLegibleColorsAndToolbarModeResolvesHeaderCleanly() {
        assertEquals(DuoDarkColors, DuoThemePreset.DARK.toColorTokens())
        assertEquals(DuoLightColors, DuoThemePreset.LIGHT.toColorTokens())
        assertEquals(DuoEinkColors, DuoThemePreset.EINK.toColorTokens())
        assertEquals(DuoThemePreset.EINK, DuoThemePreset.fromNameOrDefault("eink"))
        assertEquals(DuoThemePreset.DARK, DuoThemePreset.fromNameOrDefault("unknown"))

        assertEquals(Color(0xFFFFFFFF), DuoEinkColors.keyboardBackground)
        assertEquals(Color(0xFFFFFFFF), DuoEinkColors.keyBackground)
        assertEquals(Color(0xFF000000), DuoEinkColors.keyText)
        assertEquals(Color(0xFF000000), DuoEinkColors.keyBorder)
        assertEquals(Color(0xFF000000), DuoEinkColors.divider)
        assertEquals(Color(0xFF000000), DuoEinkColors.accentKeyBackground)
        assertEquals(Color(0xFFFFFFFF), DuoEinkColors.accentKeyText)
        assertEquals(Color(0xFFFFFFFF), DuoEinkColors.chipSelectedText)
        assertEquals(Color(0xFFCCCCCC), DuoEinkColors.keyPressedBackground)
        assertEquals(Color(0xFFCCCCCC), DuoEinkColors.actionKeyPressedBackground)

        val idle = DuoKeyboardSurfaceState(
            snapshot = EngineSnapshot.EMPTY,
            layout = BuiltInLayouts.qwerty(),
            isReady = true,
            heightFraction = 0.75f,
            isOverlayMode = false
        )
        val composing = idle.copy(
            snapshot = EngineSnapshot(
                preedit = "ni",
                candidates = listOf(CandidateItem(text = "你", indexOnPage = 0)),
                isComposing = true
            )
        )
        assertEquals(HeaderSlotMode.TOOLBAR, idle.copy(toolbarMode = ToolbarMode.FULL).resolveHeaderSlotMode())
        assertEquals(HeaderSlotMode.CANDIDATES, composing.copy(toolbarMode = ToolbarMode.FULL).resolveHeaderSlotMode())
        assertEquals(
            HeaderSlotMode.TOOLBAR,
            composing.copy(toolbarMode = ToolbarMode.FULL, drawerOpen = true).resolveHeaderSlotMode()
        )
        assertEquals(HeaderSlotMode.NONE, idle.copy(toolbarMode = ToolbarMode.CANDIDATE_ONLY).resolveHeaderSlotMode())
        assertEquals(
            HeaderSlotMode.CANDIDATES,
            composing.copy(toolbarMode = ToolbarMode.CANDIDATE_ONLY).resolveHeaderSlotMode()
        )
        assertEquals(HeaderSlotMode.NONE, idle.copy(toolbarMode = ToolbarMode.NONE).resolveHeaderSlotMode())
        assertEquals(HeaderSlotMode.NONE, composing.copy(toolbarMode = ToolbarMode.NONE).resolveHeaderSlotMode())
    }

    @Test
    fun resolveScrollTargetIndexFollowsHighlightedCandidateOrPageStartCleanly() {
        assertEquals(-1, resolveScrollTargetIndex(emptyList(), pageIndex = 0))

        val items = listOf(
            WindowCandidateItem(globalIndex = 0, pageIndex = 0, indexOnPage = 0, text = "一", highlighted = false),
            WindowCandidateItem(globalIndex = 1, pageIndex = 0, indexOnPage = 1, text = "以", highlighted = false),
            WindowCandidateItem(globalIndex = 2, pageIndex = 1, indexOnPage = 0, text = "意", highlighted = true),
            WindowCandidateItem(globalIndex = 3, pageIndex = 1, indexOnPage = 1, text = "义", highlighted = false)
        )
        assertEquals(2, resolveScrollTargetIndex(items, pageIndex = 1))

        val unhighlighted = items.map { it.copy(highlighted = false) }
        assertEquals(2, resolveScrollTargetIndex(unhighlighted, pageIndex = 1))
        assertEquals(0, resolveScrollTargetIndex(unhighlighted, pageIndex = 0))
        assertEquals(0, resolveScrollTargetIndex(unhighlighted, pageIndex = 5))
    }
}

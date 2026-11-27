// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Visual color & interaction preset for DuoIME surfaces (decoupled from Material 3).
 */
enum class DuoThemePreset {
    DARK,
    LIGHT,
    EINK;

    fun toColorTokens(): DuoColorTokens = when (this) {
        DARK -> DuoDarkColors
        LIGHT -> DuoLightColors
        EINK -> DuoEinkColors
    }

    companion object {
        fun fromNameOrDefault(raw: String?, fallback: DuoThemePreset = DARK): DuoThemePreset {
            val normalized = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return fallback
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: fallback
        }
    }
}

/**
 * OLED-friendly minimalist color tokens for DuoIME (decoupled from Material 3).
 */
@Immutable
data class DuoColorTokens(
    val keyboardBackground: Color,
    val barBackground: Color,
    val keyBackground: Color,
    val keyPressedBackground: Color,
    val actionKeyBackground: Color,
    val actionKeyPressedBackground: Color,
    val accentKeyBackground: Color,
    val accentKeyPressedBackground: Color,
    val keyText: Color,
    val keySecondaryText: Color,
    val accentKeyText: Color,
    val keyBorder: Color,
    val chipBackground: Color,
    val chipSelectedBackground: Color,
    val chipText: Color,
    val chipSelectedText: Color,
    val preeditText: Color,
    val divider: Color,
    val disabledScrim: Color,
    val warningBackground: Color,
    val warningText: Color
)

@Immutable
data class DuoDimensionTokens(
    val keyCornerRadius: Dp = 8.dp,
    val keyHorizontalGap: Dp = 4.dp,
    val keyVerticalGap: Dp = 6.dp,
    val gridHorizontalPadding: Dp = 6.dp,
    val gridVerticalPadding: Dp = 6.dp,
    val toolbarHeight: Dp = 52.dp,
    val candidateBarHeight: Dp = 52.dp,
    val syllableBarHeight: Dp = 50.dp
) {
    val candidateFontSp: Float
        get() = (candidateBarHeight.value * 0.35f).coerceIn(16f, 25f)

    val barButtonFontSp: Float
        get() = (candidateBarHeight.value * 0.30f).coerceIn(15f, 22f)

    val preeditFontSp: Float
        get() = (candidateBarHeight.value * 0.25f).coerceIn(13f, 19f)

    val toolbarFontSp: Float
        get() = (toolbarHeight.value * 0.25f).coerceIn(12f, 18f)

    val syllableFontSp: Float
        get() = (syllableBarHeight.value * 0.32f).coerceIn(14f, 21f)
}

private const val MIN_ADAPTIVE_HEADER_HEIGHT_DP: Float = 40f
private const val MIN_ADAPTIVE_SYLLABLE_HEIGHT_DP: Float = 34f
private const val MAX_ADAPTIVE_HEADER_HEIGHT_DP: Float = 76f
private const val MAX_ADAPTIVE_SYLLABLE_HEIGHT_DP: Float = 64f
private const val ADAPTIVE_SYLLABLE_ROW_RATIO: Float = 0.85f

/**
 * Scales the top header ([DuoDimensionTokens.toolbarHeight] == [DuoDimensionTokens.candidateBarHeight])
 * and T9 [DuoDimensionTokens.syllableBarHeight] proportionally to the rendered key row height inside
 * [availableHeightDp] (accounting for dividers, grid padding, and inter-row `keyVerticalGap`),
 * clamped within `40..76.dp` (header) and `34..64.dp` (syllable rail).
 *
 * Compact-mode trade-off: unbounded [DuoDefaultDimensions] (`52/52/50.dp`) and standard `>= 360.dp`
 * layouts preserve `>= 48.dp` inner header touch targets (with T9's `0.85x` syllable rail staying
 * near `48.dp` and reaching `>= 48.dp` inner height on taller surfaces), whereas compact T9 heights
 * (`< 320.dp`) relax smoothly down to `40.dp` header (`36.dp` inner chip height) and `34.dp` syllable
 * rail (`32.dp` inner chip height) so `KeyGridGeometry` key rows degrade gracefully to `38.5.dp`
 * (at `260.dp`) rather than collapsing.
 */
fun resolveAdaptiveDimensions(
    availableHeightDp: Float,
    keyRowCount: Int,
    hasSyllableRail: Boolean,
    base: DuoDimensionTokens = DuoDefaultDimensions,
    hasHeader: Boolean = true
): DuoDimensionTokens {
    val safeHeight = if (availableHeightDp.isFinite()) availableHeightDp else 0f
    val safeRows = keyRowCount.coerceAtLeast(1)
    val overheadDp = (if (hasHeader) 1f else 0f) +
        (if (hasSyllableRail) 1f else 0f) +
        base.gridVerticalPadding.value * 2f +
        base.keyVerticalGap.value * (safeRows - 1)
    val rowUnits = safeRows.toFloat() +
        (if (hasHeader) 1f else 0f) +
        (if (hasSyllableRail) ADAPTIVE_SYLLABLE_ROW_RATIO else 0f)
    val targetRowDp = (safeHeight - overheadDp).coerceAtLeast(1f) / rowUnits
    val headerHeight = targetRowDp.coerceIn(MIN_ADAPTIVE_HEADER_HEIGHT_DP, MAX_ADAPTIVE_HEADER_HEIGHT_DP).dp
    val syllableHeight = (targetRowDp * ADAPTIVE_SYLLABLE_ROW_RATIO)
        .coerceIn(MIN_ADAPTIVE_SYLLABLE_HEIGHT_DP, MAX_ADAPTIVE_SYLLABLE_HEIGHT_DP)
        .dp
    return base.copy(
        toolbarHeight = headerHeight,
        candidateBarHeight = headerHeight,
        syllableBarHeight = syllableHeight
    )
}

val DuoDarkColors: DuoColorTokens = DuoColorTokens(
    keyboardBackground = Color(0xFF0A0C10),
    barBackground = Color(0xFF11141B),
    keyBackground = Color(0xFF1B202A),
    keyPressedBackground = Color(0xFF2D4B7A),
    actionKeyBackground = Color(0xFF232936),
    actionKeyPressedBackground = Color(0xFF36455E),
    accentKeyBackground = Color(0xFF2B6CB0),
    accentKeyPressedBackground = Color(0xFF3B82F6),
    keyText = Color(0xFFF3F6FB),
    keySecondaryText = Color(0xFF94A0B4),
    accentKeyText = Color(0xFFFFFFFF),
    keyBorder = Color(0xFF293140),
    chipBackground = Color(0xFF1C222E),
    chipSelectedBackground = Color(0xFF2B6CB0),
    chipText = Color(0xFFE2E8F0),
    chipSelectedText = Color(0xFFFFFFFF),
    preeditText = Color(0xFF7CC4FA),
    divider = Color(0xFF222836),
    disabledScrim = Color(0x66000000),
    warningBackground = Color(0xFF3B2508),
    warningText = Color(0xFFFFE0A3)
)

val DuoLightColors: DuoColorTokens = DuoColorTokens(
    keyboardBackground = Color(0xFFECEFF4),
    barBackground = Color(0xFFF7F9FC),
    keyBackground = Color(0xFFFFFFFF),
    keyPressedBackground = Color(0xFFCCE0FF),
    actionKeyBackground = Color(0xFFD8DEE9),
    actionKeyPressedBackground = Color(0xFFBAC7DD),
    accentKeyBackground = Color(0xFF2563EB),
    accentKeyPressedBackground = Color(0xFF1D4ED8),
    keyText = Color(0xFF111827),
    keySecondaryText = Color(0xFF4B5563),
    accentKeyText = Color(0xFFFFFFFF),
    keyBorder = Color(0xFFD1D5DB),
    chipBackground = Color(0xFFE5E9F0),
    chipSelectedBackground = Color(0xFF2563EB),
    chipText = Color(0xFF1F2937),
    chipSelectedText = Color(0xFFFFFFFF),
    preeditText = Color(0xFF1D4ED8),
    divider = Color(0xFFD1D5DB),
    disabledScrim = Color(0x44FFFFFF),
    warningBackground = Color(0xFFFFF4D6),
    warningText = Color(0xFF664400)
)

val DuoEinkColors: DuoColorTokens = DuoColorTokens(
    keyboardBackground = Color(0xFFFFFFFF),
    barBackground = Color(0xFFFFFFFF),
    keyBackground = Color(0xFFFFFFFF),
    keyPressedBackground = Color(0xFFCCCCCC),
    actionKeyBackground = Color(0xFFFFFFFF),
    actionKeyPressedBackground = Color(0xFFCCCCCC),
    accentKeyBackground = Color(0xFF000000),
    accentKeyPressedBackground = Color(0xFF333333),
    keyText = Color(0xFF000000),
    keySecondaryText = Color(0xFF444444),
    accentKeyText = Color(0xFFFFFFFF),
    keyBorder = Color(0xFF000000),
    chipBackground = Color(0xFFFFFFFF),
    chipSelectedBackground = Color(0xFF000000),
    chipText = Color(0xFF000000),
    chipSelectedText = Color(0xFFFFFFFF),
    preeditText = Color(0xFF000000),
    divider = Color(0xFF000000),
    disabledScrim = Color(0x33000000),
    warningBackground = Color(0xFFFFFFFF),
    warningText = Color(0xFF000000)
)

val DuoDefaultDimensions: DuoDimensionTokens = DuoDimensionTokens()

val LocalDuoThemePreset = staticCompositionLocalOf { DuoThemePreset.DARK }
val LocalDuoColors = staticCompositionLocalOf { DuoDarkColors }
val LocalDuoDimensions = staticCompositionLocalOf { DuoDefaultDimensions }

@Composable
fun DuoTheme(
    preset: DuoThemePreset = DuoThemePreset.DARK,
    colors: DuoColorTokens = preset.toColorTokens(),
    dimensions: DuoDimensionTokens = DuoDefaultDimensions,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalDuoThemePreset provides preset,
        LocalDuoColors provides colors,
        LocalDuoDimensions provides dimensions,
        content = content
    )
}

@Composable
fun DuoTheme(
    darkTheme: Boolean,
    colors: DuoColorTokens = if (darkTheme) DuoDarkColors else DuoLightColors,
    dimensions: DuoDimensionTokens = DuoDefaultDimensions,
    content: @Composable () -> Unit
) {
    DuoTheme(
        preset = if (darkTheme) DuoThemePreset.DARK else DuoThemePreset.LIGHT,
        colors = colors,
        dimensions = dimensions,
        content = content
    )
}

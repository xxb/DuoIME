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
    val toolbarHeight: Dp = 40.dp,
    val candidateBarHeight: Dp = 40.dp,
    val syllableBarHeight: Dp = 30.dp
)

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

val DuoDefaultDimensions: DuoDimensionTokens = DuoDimensionTokens()

val LocalDuoColors = staticCompositionLocalOf { DuoDarkColors }
val LocalDuoDimensions = staticCompositionLocalOf { DuoDefaultDimensions }

@Composable
fun DuoTheme(
    darkTheme: Boolean = true,
    colors: DuoColorTokens = if (darkTheme) DuoDarkColors else DuoLightColors,
    dimensions: DuoDimensionTokens = DuoDefaultDimensions,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalDuoColors provides colors,
        LocalDuoDimensions provides dimensions,
        content = content
    )
}

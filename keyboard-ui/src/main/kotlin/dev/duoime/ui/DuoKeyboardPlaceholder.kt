// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.duoime.ui.theme.LocalDuoColors

/**
 * Lightweight pure-Foundation status banner composable inside `:keyboard-ui` (zero Material 3).
 */
@Composable
fun DuoKeyboardPlaceholder(statusLabel: String, modifier: Modifier = Modifier) {
    val colors = LocalDuoColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.barBackground)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = statusLabel,
            style = TextStyle(color = colors.keyText, fontSize = 14.sp)
        )
    }
}

internal object DuoKeyboardUiInfo {
    const val PLACEHOLDER_LABEL: String = "DuoIME Compose UI Scaffold"
}

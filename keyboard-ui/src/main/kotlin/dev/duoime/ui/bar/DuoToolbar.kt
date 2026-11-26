// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.bar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyAction
import dev.duoime.core.protocol.ExtensionToolbarButton
import dev.duoime.ui.R
import dev.duoime.ui.theme.DuoColorTokens
import dev.duoime.ui.theme.LocalDuoColors
import dev.duoime.ui.theme.LocalDuoDimensions

/**
 * Semantic keyboard height preset tier rendered in [DuoToolbar] (`S`/`M`/`L` in English,
 * `矮`/`中`/`高` in Chinese) so users see a preset rather than a raw overlay-only percentage.
 */
enum class HeightPresetTier {
    COMPACT,
    STANDARD,
    TALL
}

fun resolveHeightPresetTier(heightFraction: Float): HeightPresetTier = when {
    heightFraction < 0.675f -> HeightPresetTier.COMPACT
    heightFraction <= 0.825f -> HeightPresetTier.STANDARD
    else -> HeightPresetTier.TALL
}

/**
 * Immutable state bundle for [DuoToolbar], ensuring Compose skippability even when
 * host-supplied [extensionButtons] or an active [engineIssueTag] are present.
 */
@Immutable
data class DuoToolbarState(
    val activeMode: InputMode,
    val variant: ChineseVariant,
    val heightFraction: Float,
    val isOverlayMode: Boolean,
    val extensionButtons: List<ExtensionToolbarButton> = emptyList(),
    val engineIssueTag: String? = null
)

/**
 * Top keyboard toolbar exposing an optional leading dismissible engine-warning chip, mode
 * switching (26-key/T9/EN/?123), S/T toggle, one-tap screen swap, height preset cycling
 * (60%/75%/90%), and host-declared [ExtensionToolbarButton] slots.
 */
@Composable
fun DuoToolbar(
    state: DuoToolbarState,
    onAction: (KeyAction) -> Unit,
    onExtensionButton: (ExtensionToolbarButton) -> Unit,
    modifier: Modifier = Modifier,
    onDismissEngineError: () -> Unit = {}
) {
    val colors = LocalDuoColors.current
    val dimensions = LocalDuoDimensions.current
    val heightLabel = when (resolveHeightPresetTier(state.heightFraction)) {
        HeightPresetTier.COMPACT -> stringResource(R.string.duo_height_preset_compact)
        HeightPresetTier.STANDARD -> stringResource(R.string.duo_height_preset_standard)
        HeightPresetTier.TALL -> stringResource(R.string.duo_height_preset_tall)
    }
    val issueTag = state.engineIssueTag

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(dimensions.toolbarHeight)
            .background(colors.barBackground)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!issueTag.isNullOrBlank()) {
                item(key = "engine_issue") {
                    EngineIssueChip(
                        tag = issueTag,
                        colors = colors,
                        onDismiss = onDismissEngineError
                    )
                }
            }
            item(key = "mode_26") {
                ToolbarChip(
                    label = "26键",
                    selected = state.activeMode == InputMode.PINYIN_26,
                    colors = colors,
                    onClick = { onAction(KeyAction.SwitchMode(InputMode.PINYIN_26)) }
                )
            }
            item(key = "mode_t9") {
                ToolbarChip(
                    label = "九键",
                    selected = state.activeMode == InputMode.PINYIN_T9,
                    colors = colors,
                    onClick = { onAction(KeyAction.SwitchMode(InputMode.PINYIN_T9)) }
                )
            }
            item(key = "mode_en") {
                ToolbarChip(
                    label = "EN",
                    selected = state.activeMode == InputMode.ENGLISH,
                    colors = colors,
                    onClick = { onAction(KeyAction.SwitchMode(InputMode.ENGLISH)) }
                )
            }
            item(key = "mode_sym") {
                ToolbarChip(
                    label = "?123",
                    selected = state.activeMode == InputMode.SYMBOLS,
                    colors = colors,
                    onClick = { onAction(KeyAction.SwitchMode(InputMode.SYMBOLS)) }
                )
            }
            item(key = "variant") {
                ToolbarChip(
                    label = if (state.variant == ChineseVariant.TRADITIONAL) "繁" else "简",
                    selected = state.variant == ChineseVariant.TRADITIONAL,
                    colors = colors,
                    onClick = { onAction(KeyAction.SwitchVariant) }
                )
            }
            item(key = "swap") {
                ToolbarChip(
                    label = if (state.isOverlayMode) "换屏●" else "换屏",
                    selected = state.isOverlayMode,
                    colors = colors,
                    onClick = { onAction(KeyAction.SwapScreen) }
                )
            }
            item(key = "height") {
                ToolbarChip(
                    label = heightLabel,
                    selected = false,
                    colors = colors,
                    onClick = { onAction(KeyAction.CycleHeight) }
                )
            }
            items(
                items = state.extensionButtons,
                key = { button -> "ext:${button.id}" }
            ) { button ->
                ToolbarChip(
                    label = button.label,
                    selected = false,
                    colors = colors,
                    onClick = { onExtensionButton(button) }
                )
            }
        }

        ToolbarChip(
            label = "收起",
            selected = false,
            colors = colors,
            onClick = { onAction(KeyAction.Dismiss) }
        )
    }
}

@Composable
private fun EngineIssueChip(tag: String, colors: DuoColorTokens, onDismiss: () -> Unit) {
    val warningStyle = TextStyle(
        color = colors.warningText,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.warningBackground)
            .widthIn(max = 140.dp)
            .semantics { contentDescription = "Dismiss engine warning: $tag" }
            .clickable(onClick = onDismiss)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BasicText(
            text = "⚠ $tag",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = warningStyle,
            modifier = Modifier.weight(1f, fill = false)
        )
        BasicText(
            text = "✕",
            maxLines = 1,
            style = warningStyle
        )
    }
}

@Composable
private fun ToolbarChip(label: String, selected: Boolean, colors: DuoColorTokens, onClick: () -> Unit) {
    val bg = if (selected) colors.chipSelectedBackground else colors.chipBackground
    val fg = if (selected) colors.chipSelectedText else colors.chipText
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            maxLines = 1,
            style = TextStyle(
                color = fg,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        )
    }
}

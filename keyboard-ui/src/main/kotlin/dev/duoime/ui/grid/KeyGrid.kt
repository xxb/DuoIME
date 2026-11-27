// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.grid

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import dev.duoime.core.layout.KeySpec
import dev.duoime.core.layout.LayoutSpec
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.FeedbackZone
import dev.duoime.core.model.KeyAction
import dev.duoime.ui.theme.DuoColorTokens
import dev.duoime.ui.theme.LocalDuoColors
import dev.duoime.ui.theme.LocalDuoDimensions

private class GridRenderState(val holdController: KeyHoldController) {
    var hitMap: KeyGridHitMap = KeyGridHitMap.EMPTY
    var lastSize: IntSize = IntSize.Zero
    var lastLayoutId: String = ""
    var cachedPrimaryLayouts: List<TextLayoutResult> = emptyList()
    var cachedSecondaryLayouts: List<TextLayoutResult?> = emptyList()
    var cachedPopupLayouts: List<TextLayoutResult?> = emptyList()
    val pointerTracker: MultiTouchPointerTracker = MultiTouchPointerTracker()
}

private data class GridLayoutMetrics(val hGapPx: Float, val vGapPx: Float, val density: Float)

private class GridPointerDispatch(
    val enabledState: State<Boolean>,
    val onKeyActionState: State<(KeyAction) -> Unit>,
    val onPressDownState: State<(FeedbackKey) -> Unit>
)

/**
 * High-frame-rate Compose key grid using:
 * - One `pointerInput` per grid with layout-phase `FloatArray` hit-testing
 * - Multi-touch tracking by `pointerId`, Backspace auto-repeat, and long-press secondary symbol popup
 * - Press and popup visual invalidation read strictly in the draw phase via [MutableIntState]
 * - Cached [TextMeasurer] layouts rendered via `drawText` (no per-key `Text()` composables)
 */
@Suppress("LongParameterList")
@Composable
fun KeyGrid(
    layout: LayoutSpec,
    enabled: Boolean,
    onKeyAction: (KeyAction) -> Unit,
    modifier: Modifier = Modifier,
    onKeyPressDown: (FeedbackKey) -> Unit = {},
    onKeyRepeat: (FeedbackKey) -> Unit = {}
) {
    val colors = LocalDuoColors.current
    val dimensions = LocalDuoDimensions.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val coroutineScope = rememberCoroutineScope()

    val metrics = GridLayoutMetrics(
        hGapPx = with(density) { dimensions.keyHorizontalGap.toPx() },
        vGapPx = with(density) { dimensions.keyVerticalGap.toPx() },
        density = density.density.coerceAtLeast(1f)
    )
    val cornerRadiusPx = with(density) { dimensions.keyCornerRadius.toPx() }

    val pressDrawTrigger = remember { mutableIntStateOf(0) }
    val currentLayout = rememberUpdatedState(layout)
    val currentOnKeyAction = rememberUpdatedState(onKeyAction)
    val currentOnKeyRepeat = rememberUpdatedState(onKeyRepeat)
    val pointerDispatch = GridPointerDispatch(
        enabledState = rememberUpdatedState(enabled),
        onKeyActionState = currentOnKeyAction,
        onPressDownState = rememberUpdatedState(onKeyPressDown)
    )
    val renderState = remember(coroutineScope) {
        GridRenderState(
            holdController = KeyHoldController(
                scope = coroutineScope,
                onRepeatAction = { action -> currentOnKeyAction.value(action) },
                onVisualChanged = { pressDrawTrigger.intValue++ },
                onRepeatFeedback = { key -> currentOnKeyRepeat.value(key) }
            )
        )
    }

    DisposableEffect(renderState) {
        onDispose { renderState.holdController.cancelAll() }
    }

    if (renderState.lastLayoutId != layout.id && renderState.lastSize != IntSize.Zero) {
        updateRenderCache(renderState, layout, renderState.lastSize, metrics, textMeasurer)
        pressDrawTrigger.intValue++
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { newSize ->
                if (newSize != renderState.lastSize || renderState.lastLayoutId != currentLayout.value.id) {
                    updateRenderCache(renderState, currentLayout.value, newSize, metrics, textMeasurer)
                    pressDrawTrigger.intValue++
                }
            }
            .pointerInput(layout.id) {
                handleKeyGridPointers(renderState, pressDrawTrigger, pointerDispatch)
            }
    ) {
        // Read pressDrawTrigger strictly inside the draw phase -> zero recomposition/relayout on key press.
        @Suppress("UNUSED_VARIABLE")
        val drawTick = pressDrawTrigger.intValue
        drawKeyGrid(
            renderState = renderState,
            colors = colors,
            cornerRadiusPx = cornerRadiusPx,
            enabled = enabled
        )
    }
}

private suspend fun PointerInputScope.handleKeyGridPointers(
    renderState: GridRenderState,
    pressDrawTrigger: MutableIntState,
    dispatch: GridPointerDispatch
) {
    val tracker = renderState.pointerTracker
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (!dispatch.enabledState.value) {
                if (tracker.activePointerCount > 0) {
                    tracker.cancelAll()
                    renderState.holdController.cancelAll()
                    pressDrawTrigger.intValue++
                }
                continue
            }
            var visualChanged = false
            for (change in event.changes) {
                if (processPointerChange(change, renderState, dispatch)) {
                    visualChanged = true
                }
            }
            if (visualChanged) {
                pressDrawTrigger.intValue++
            }
        }
    }
}

private fun processPointerChange(
    change: PointerInputChange,
    renderState: GridRenderState,
    dispatch: GridPointerDispatch
): Boolean {
    val hitMap = renderState.hitMap
    val tracker = renderState.pointerTracker
    val holds = renderState.holdController
    val pid = change.id.value
    return when {
        change.isConsumed -> {
            holds.cancelPointer(pid)
            tracker.onPointerCancel(pid)
        }
        change.pressed && !change.previousPressed -> {
            val idx = tracker.onPointerDown(pid, change.position.x, change.position.y, hitMap)
            change.consume()
            if (idx >= 0) {
                val key = hitMap.keys[idx]
                if (!key.enabled) return false
                holds.onKeyDown(pid, idx, key)
                dispatch.onPressDownState.value(FeedbackKey(key.action, key.label, key.zone))
                true
            } else {
                false
            }
        }
        !change.pressed && change.previousPressed -> {
            val releasedIdx = tracker.onPointerUp(pid)
            change.consume()
            if (releasedIdx in hitMap.keys.indices) {
                val releasedKey = hitMap.keys[releasedIdx]
                val action = if (releasedKey.enabled) holds.onKeyUp(pid, releasedKey) else null
                if (action != null) {
                    dispatch.onKeyActionState.value(action)
                }
                true
            } else {
                holds.cancelPointer(pid)
                false
            }
        }
        change.pressed -> {
            val prevIdx = tracker.keyForPointer(pid)
            val nextIdx = tracker.onPointerMove(pid, change.position.x, change.position.y, hitMap)
            holds.onKeyChanged(pid, nextIdx)
            change.consume()
            prevIdx != nextIdx
        }
        else -> false
    }
}

private fun updateRenderCache(
    state: GridRenderState,
    layout: LayoutSpec,
    size: IntSize,
    metrics: GridLayoutMetrics,
    textMeasurer: TextMeasurer
) {
    state.lastSize = size
    state.lastLayoutId = layout.id
    val hitMap = KeyGridGeometry.computeHitMap(
        layout = layout,
        widthPx = size.width.toFloat(),
        heightPx = size.height.toFloat(),
        horizontalGapPx = metrics.hGapPx,
        verticalGapPx = metrics.vGapPx
    )
    state.hitMap = hitMap
    state.pointerTracker.reset(hitMap.size)
    state.holdController.cancelAll()

    val rowCount = layout.rows.size.coerceAtLeast(1)
    val approxRowHeightDp = (size.height.toFloat() / rowCount) / metrics.density
    val primaryFontSp = (approxRowHeightDp * 0.32f).coerceIn(14f, 26f).sp
    val secondaryFontSp = (approxRowHeightDp * 0.16f).coerceIn(8f, 11f).sp
    val popupFontSp = (approxRowHeightDp * 0.40f).coerceIn(15f, 24f).sp

    val primaryStyle = TextStyle(fontSize = primaryFontSp, fontWeight = FontWeight.Medium)
    val secondaryStyle = TextStyle(fontSize = secondaryFontSp, fontWeight = FontWeight.Normal)
    val popupStyle = TextStyle(fontSize = popupFontSp, fontWeight = FontWeight.Bold)

    state.cachedPrimaryLayouts = hitMap.keys.map { key ->
        textMeasurer.measure(text = key.label, style = primaryStyle, maxLines = 1)
    }
    state.cachedSecondaryLayouts = hitMap.keys.map { key ->
        key.secondaryLabel?.let { sec ->
            textMeasurer.measure(text = sec, style = secondaryStyle, maxLines = 1)
        }
    }
    state.cachedPopupLayouts = hitMap.keys.map { key ->
        key.secondaryLabel?.let { sec ->
            textMeasurer.measure(text = sec, style = popupStyle, maxLines = 1)
        }
    }
}

private fun DrawScope.drawKeyGrid(
    renderState: GridRenderState,
    colors: DuoColorTokens,
    cornerRadiusPx: Float,
    enabled: Boolean
) {
    val hitMap = renderState.hitMap
    val tracker = renderState.pointerTracker
    val radius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
    val stroke = Stroke(width = 1f)

    for (i in 0 until hitMap.size) {
        val key = hitMap.keys[i]
        val left = hitMap.left(i)
        val top = hitMap.top(i)
        val width = hitMap.right(i) - left
        val height = hitMap.bottom(i) - top
        val pressed = enabled && key.enabled && tracker.isKeyPressed(i)

        val bg = keyFillColor(key, pressed, colors)
        val textColor = keyTextColor(key, colors)
        val secondaryColor = keySecondaryTextColor(key, colors)

        drawRoundRect(
            color = bg,
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = radius
        )
        drawRoundRect(
            color = colors.keyBorder,
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = radius,
            style = stroke
        )
        drawKeyLabels(renderState, i, textColor, secondaryColor)
    }
    val popupIndex = renderState.holdController.activePopupKeyIndex
    if (enabled && popupIndex in 0 until hitMap.size) {
        drawLongPressPopup(renderState, popupIndex, colors, radius)
    }
    if (!enabled) {
        drawRect(color = colors.disabledScrim)
    }
}

private fun DrawScope.drawLongPressPopup(
    renderState: GridRenderState,
    index: Int,
    colors: DuoColorTokens,
    radius: CornerRadius
) {
    val popupLayout = renderState.cachedPopupLayouts.getOrNull(index) ?: return
    val hitMap = renderState.hitMap
    val keyLeft = hitMap.left(index)
    val keyTop = hitMap.top(index)
    val keyWidth = hitMap.right(index) - keyLeft
    val keyHeight = hitMap.bottom(index) - keyTop

    val pillWidth = maxOf(keyWidth * 1.1f, popupLayout.size.width + 24f)
    val pillHeight = maxOf(keyHeight * 0.9f, popupLayout.size.height + 16f)
    val pillLeft = (keyLeft + (keyWidth - pillWidth) * 0.5f).coerceIn(0f, (size.width - pillWidth).coerceAtLeast(0f))
    val pillTop = (keyTop - pillHeight - 6f).coerceAtLeast(0f)

    drawRoundRect(
        color = colors.chipSelectedBackground,
        topLeft = Offset(pillLeft, pillTop),
        size = Size(pillWidth, pillHeight),
        cornerRadius = radius
    )
    drawRoundRect(
        color = colors.keyBorder,
        topLeft = Offset(pillLeft, pillTop),
        size = Size(pillWidth, pillHeight),
        cornerRadius = radius,
        style = Stroke(width = 1.5f)
    )
    val textX = pillLeft + (pillWidth - popupLayout.size.width) * 0.5f
    val textY = pillTop + (pillHeight - popupLayout.size.height) * 0.5f
    drawText(
        textLayoutResult = popupLayout,
        color = colors.chipSelectedText,
        topLeft = Offset(textX, textY)
    )
}

private fun DrawScope.drawKeyLabels(renderState: GridRenderState, index: Int, textColor: Color, secondaryColor: Color) {
    val primary = renderState.cachedPrimaryLayouts.getOrNull(index) ?: return
    val secondary = renderState.cachedSecondaryLayouts.getOrNull(index)
    val hitMap = renderState.hitMap
    val left = hitMap.left(index)
    val top = hitMap.top(index)
    val width = hitMap.right(index) - left
    val height = hitMap.bottom(index) - top

    val placement = KeyGridGeometry.computeLabelVerticalPlacement(
        keyTop = top,
        keyHeight = height,
        primaryHeight = primary.size.height.toFloat(),
        secondaryHeight = secondary?.size?.height?.toFloat(),
        density = density
    )
    if (secondary != null && placement.secondaryTop != null) {
        val secX = left + (width - secondary.size.width) * 0.5f
        drawText(
            textLayoutResult = secondary,
            color = secondaryColor,
            topLeft = Offset(secX, placement.secondaryTop)
        )
    }
    val priX = left + (width - primary.size.width) * 0.5f
    drawText(
        textLayoutResult = primary,
        color = textColor,
        topLeft = Offset(priX, placement.primaryTop)
    )
}

private fun keyFillColor(key: KeySpec, pressed: Boolean, colors: DuoColorTokens): Color = when {
    !key.enabled -> if (key.zone == FeedbackZone.ACTION) colors.actionKeyBackground else colors.keyBackground
    key.highlighted -> if (pressed) colors.accentKeyPressedBackground else colors.chipSelectedBackground
    key.zone == FeedbackZone.ENTER -> if (pressed) colors.accentKeyPressedBackground else colors.accentKeyBackground
    key.zone == FeedbackZone.ACTION || key.zone == FeedbackZone.BACKSPACE || key.zone == FeedbackZone.TOOLBAR ->
        if (pressed) colors.actionKeyPressedBackground else colors.actionKeyBackground
    else -> if (pressed) colors.keyPressedBackground else colors.keyBackground
}

private fun keyTextColor(key: KeySpec, colors: DuoColorTokens): Color = when {
    !key.enabled -> colors.keySecondaryText
    key.highlighted -> colors.chipSelectedText
    key.zone == FeedbackZone.ENTER -> colors.accentKeyText
    else -> colors.keyText
}

private fun keySecondaryTextColor(key: KeySpec, colors: DuoColorTokens): Color = when {
    !key.enabled -> colors.keySecondaryText
    key.highlighted -> colors.chipSelectedText.copy(alpha = 0.85f)
    else -> colors.keySecondaryText
}

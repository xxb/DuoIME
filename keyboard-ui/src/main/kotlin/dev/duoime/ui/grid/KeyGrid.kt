// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.grid

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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

private class GridRenderState {
    var hitMap: KeyGridHitMap = KeyGridHitMap.EMPTY
    var lastSize: IntSize = IntSize.Zero
    var lastLayoutId: String = ""
    var cachedPrimaryLayouts: List<TextLayoutResult> = emptyList()
    var cachedSecondaryLayouts: List<TextLayoutResult?> = emptyList()
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
 * - Multi-touch tracking by `pointerId` with clean cancel handling
 * - Press visual invalidation read strictly in the draw phase via [MutableIntState]
 * - Cached [TextMeasurer] layouts rendered via `drawText` (no per-key `Text()` composables)
 */
@Composable
fun KeyGrid(
    layout: LayoutSpec,
    enabled: Boolean,
    onKeyAction: (KeyAction) -> Unit,
    modifier: Modifier = Modifier,
    onKeyPressDown: (FeedbackKey) -> Unit = {}
) {
    val colors = LocalDuoColors.current
    val dimensions = LocalDuoDimensions.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val metrics = GridLayoutMetrics(
        hGapPx = with(density) { dimensions.keyHorizontalGap.toPx() },
        vGapPx = with(density) { dimensions.keyVerticalGap.toPx() },
        density = density.density.coerceAtLeast(1f)
    )
    val cornerRadiusPx = with(density) { dimensions.keyCornerRadius.toPx() }

    val renderState = remember { GridRenderState() }
    val pressDrawTrigger = remember { mutableIntStateOf(0) }

    val currentLayout = rememberUpdatedState(layout)
    val pointerDispatch = GridPointerDispatch(
        enabledState = rememberUpdatedState(enabled),
        onKeyActionState = rememberUpdatedState(onKeyAction),
        onPressDownState = rememberUpdatedState(onKeyPressDown)
    )

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
    val pid = change.id.value
    return when {
        change.isConsumed -> tracker.onPointerCancel(pid)
        change.pressed && !change.previousPressed -> {
            val idx = tracker.onPointerDown(pid, change.position.x, change.position.y, hitMap)
            change.consume()
            if (idx >= 0) {
                val key = hitMap.keys[idx]
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
                dispatch.onKeyActionState.value(hitMap.keys[releasedIdx].action)
                true
            } else {
                false
            }
        }
        change.pressed -> {
            val prevIdx = tracker.keyForPointer(pid)
            val nextIdx = tracker.onPointerMove(pid, change.position.x, change.position.y, hitMap)
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

    val rowCount = layout.rows.size.coerceAtLeast(1)
    val approxRowHeightDp = (size.height.toFloat() / rowCount) / metrics.density
    val primaryFontSp = (approxRowHeightDp * 0.36f).coerceIn(14f, 24f).sp
    val secondaryFontSp = (approxRowHeightDp * 0.19f).coerceIn(9f, 13f).sp

    val primaryStyle = TextStyle(fontSize = primaryFontSp, fontWeight = FontWeight.Medium)
    val secondaryStyle = TextStyle(fontSize = secondaryFontSp, fontWeight = FontWeight.Normal)

    state.cachedPrimaryLayouts = hitMap.keys.map { key ->
        textMeasurer.measure(text = key.label, style = primaryStyle, maxLines = 1)
    }
    state.cachedSecondaryLayouts = hitMap.keys.map { key ->
        key.secondaryLabel?.let { sec ->
            textMeasurer.measure(text = sec, style = secondaryStyle, maxLines = 1)
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
        val pressed = enabled && tracker.isKeyPressed(i)

        val bg = keyFillColor(key, pressed, colors)
        val textColor = keyTextColor(key, colors)

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
        drawKeyLabels(renderState, i, textColor, colors.keySecondaryText)
    }
    if (!enabled) {
        drawRect(color = colors.disabledScrim)
    }
}

private fun DrawScope.drawKeyLabels(renderState: GridRenderState, index: Int, textColor: Color, secondaryColor: Color) {
    val primary = renderState.cachedPrimaryLayouts.getOrNull(index) ?: return
    val secondary = renderState.cachedSecondaryLayouts.getOrNull(index)
    val hitMap = renderState.hitMap
    val left = hitMap.left(index)
    val top = hitMap.top(index)
    val width = hitMap.right(index) - left
    val height = hitMap.bottom(index) - top

    if (secondary != null) {
        val secX = left + (width - secondary.size.width) * 0.5f
        val secY = top + height * 0.14f
        drawText(textLayoutResult = secondary, color = secondaryColor, topLeft = Offset(secX, secY))

        val priX = left + (width - primary.size.width) * 0.5f
        val priY = top + height * 0.56f - primary.size.height * 0.35f
        drawText(textLayoutResult = primary, color = textColor, topLeft = Offset(priX, priY))
    } else {
        val priX = left + (width - primary.size.width) * 0.5f
        val priY = top + (height - primary.size.height) * 0.5f
        drawText(textLayoutResult = primary, color = textColor, topLeft = Offset(priX, priY))
    }
}

private fun keyFillColor(key: KeySpec, pressed: Boolean, colors: DuoColorTokens): Color = when (key.zone) {
    FeedbackZone.ENTER -> if (pressed) colors.accentKeyPressedBackground else colors.accentKeyBackground
    FeedbackZone.ACTION, FeedbackZone.BACKSPACE, FeedbackZone.TOOLBAR ->
        if (pressed) colors.actionKeyPressedBackground else colors.actionKeyBackground
    else -> if (pressed) colors.keyPressedBackground else colors.keyBackground
}

private fun keyTextColor(key: KeySpec, colors: DuoColorTokens): Color =
    if (key.zone == FeedbackZone.ENTER) colors.accentKeyText else colors.keyText

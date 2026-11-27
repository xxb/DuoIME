// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.ui.bar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.duoime.core.model.ChineseScheme
import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.FeedbackKey
import dev.duoime.core.model.FeedbackZone
import dev.duoime.core.model.KeyAction
import dev.duoime.core.model.Language
import dev.duoime.ui.R
import dev.duoime.ui.theme.DuoColorTokens
import dev.duoime.ui.theme.DuoThemePreset
import dev.duoime.ui.theme.LocalDuoDimensions
import dev.duoime.ui.theme.LocalDuoThemePreset
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val UNIFIED_LANG_CHIP_WIDTH_DP: Float = 88f
internal const val UNIFIED_LANG_FLICK_THRESHOLD_DP: Float = 20f
internal const val UNIFIED_LANG_HOLD_DELAY_MS: Long = 400L

/**
 * Resolved gesture outcome on the stationary [UnifiedLangModeChip].
 */
internal enum class UnifiedLangGesture {
    TAP_TOGGLE_LANG,
    FLICK_UP_OR_HOLD_SCHEME,
    FLICK_DOWN_VARIANT
}

/**
 * Toggles quickly between 9-key T9 and 26-key Pinyin (treating double-pinyin as a 26-key family).
 */
internal fun nextQuickChineseScheme(activeScheme: ChineseScheme): ChineseScheme =
    if (activeScheme == ChineseScheme.PINYIN_T9) ChineseScheme.PINYIN_26 else ChineseScheme.PINYIN_T9

/**
 * Pure-JVM gesture classifier for [UnifiedLangModeChip]:
 * - Vertical-dominant swipe past [flickThresholdPx] (`abs(dy) >= threshold && abs(dy) > abs(dx)`)
 *   resolves to [UnifiedLangGesture.FLICK_UP_OR_HOLD_SCHEME] (`dy < 0`) or
 *   [UnifiedLangGesture.FLICK_DOWN_VARIANT] (`dy > 0`), even if the thumb exits top/bottom bounds.
 * - Otherwise, release inside bounds resolves to [UnifiedLangGesture.TAP_TOGGLE_LANG]; release
 *   outside bounds cancels cleanly (`null`).
 */
internal fun resolveUnifiedLangGesture(
    dxPx: Float,
    dyPx: Float,
    flickThresholdPx: Float,
    insideBounds: Boolean
): UnifiedLangGesture? {
    if (!dxPx.isFinite() || !dyPx.isFinite() || !flickThresholdPx.isFinite() || flickThresholdPx <= 0f) {
        return null
    }
    val absDx = abs(dxPx)
    val absDy = abs(dyPx)
    if (absDy >= flickThresholdPx && absDy > absDx) {
        return if (dyPx < 0f) {
            UnifiedLangGesture.FLICK_UP_OR_HOLD_SCHEME
        } else {
            UnifiedLangGesture.FLICK_DOWN_VARIANT
        }
    }
    return if (insideBounds) UnifiedLangGesture.TAP_TOGGLE_LANG else null
}

internal fun formatUnifiedLangLabel(language: Language, scheme: ChineseScheme): String {
    val langPrefix = if (language == Language.CHINESE) "中" else "英"
    val schemeTag = when (scheme) {
        ChineseScheme.PINYIN_26 -> "26"
        ChineseScheme.PINYIN_T9 -> "9"
        ChineseScheme.XIAOHE_SHUANGPIN -> "双"
    }
    return "$langPrefix·$schemeTag"
}

internal fun formatUnifiedLangCornerHint(scheme: ChineseScheme): String =
    if (scheme == ChineseScheme.PINYIN_T9) "↑26" else "↑9"

internal fun isInsideUnifiedLangChip(position: Offset, widthPx: Float, heightPx: Float): Boolean =
    position.x in 0f..widthPx && position.y in 0f..heightPx

/**
 * Pure-JVM pointer & long-press state machine for [UnifiedLangModeChip].
 */
internal class UnifiedLangGestureController(
    private val scope: CoroutineScope,
    private val stateRef: State<DuoToolbarState>,
    private val onActionState: State<(KeyAction) -> Unit>,
    private val onKeyPressDownState: State<(FeedbackKey) -> Unit>,
    val pressedState: MutableState<Boolean>,
    private val holdDelayMs: Long = UNIFIED_LANG_HOLD_DELAY_MS
) {
    var activePointerId: Long? = null
        private set

    private var downPosition: Offset = Offset.Zero
    private var holdTriggered: Boolean = false
    private var holdJob: Job? = null

    fun processPointerChange(change: PointerInputChange, widthPx: Float, heightPx: Float, flickThresholdPx: Float) {
        val currentState = stateRef.value
        if (!currentState.canToggleLanguage) {
            cancel()
            return
        }
        val pid = change.id.value
        if (change.isConsumed) {
            if (activePointerId == pid) cancel()
            return
        }
        if (change.pressed && !change.previousPressed) {
            handleDown(change, pid, widthPx, heightPx)
            return
        }
        if (activePointerId != pid) return
        change.consume()
        if (!change.pressed) {
            handleUp(change, widthPx, heightPx, flickThresholdPx)
            return
        }
        handleMove(change, widthPx, heightPx, flickThresholdPx)
    }

    fun cancel() {
        holdJob?.cancel()
        holdJob = null
        activePointerId = null
        holdTriggered = false
        pressedState.value = false
    }

    private fun handleDown(change: PointerInputChange, pid: Long, widthPx: Float, heightPx: Float) {
        if (activePointerId != null || !isInsideUnifiedLangChip(change.position, widthPx, heightPx)) {
            return
        }
        activePointerId = pid
        downPosition = change.position
        holdTriggered = false
        pressedState.value = true
        change.consume()
        holdJob?.cancel()
        holdJob = scope.launch {
            delay(holdDelayMs)
            if (isActive && activePointerId == pid && !holdTriggered && stateRef.value.canToggleLanguage) {
                holdTriggered = true
                emitGesture(UnifiedLangGesture.FLICK_UP_OR_HOLD_SCHEME)
            }
        }
    }

    private fun handleMove(change: PointerInputChange, widthPx: Float, heightPx: Float, flickThresholdPx: Float) {
        if (holdTriggered) return
        val dx = change.position.x - downPosition.x
        val dy = change.position.y - downPosition.y
        val inside = isInsideUnifiedLangChip(change.position, widthPx, heightPx)
        val absDx = abs(dx)
        val absDy = abs(dy)
        if (!inside || absDx >= flickThresholdPx || absDy >= flickThresholdPx) {
            holdJob?.cancel()
            holdJob = null
        }
        val horizontalOff = change.position.x < 0f || change.position.x > widthPx
        val horizontalDominantPastThreshold = absDx >= flickThresholdPx && absDx >= absDy
        if (horizontalOff || horizontalDominantPastThreshold) {
            cancel()
        }
    }

    private fun handleUp(change: PointerInputChange, widthPx: Float, heightPx: Float, flickThresholdPx: Float) {
        val wasHold = holdTriggered
        val start = downPosition
        cancel()
        if (wasHold) return
        val dx = change.position.x - start.x
        val dy = change.position.y - start.y
        val inside = isInsideUnifiedLangChip(change.position, widthPx, heightPx)
        resolveUnifiedLangGesture(dx, dy, flickThresholdPx, inside)?.let(::emitGesture)
    }

    internal fun emitGesture(gesture: UnifiedLangGesture) {
        val state = stateRef.value
        if (!state.canToggleLanguage) return
        val action = when (gesture) {
            UnifiedLangGesture.TAP_TOGGLE_LANG -> KeyAction.ToggleLanguage
            UnifiedLangGesture.FLICK_UP_OR_HOLD_SCHEME ->
                KeyAction.SelectChineseScheme(nextQuickChineseScheme(state.activeChineseScheme))
            UnifiedLangGesture.FLICK_DOWN_VARIANT -> KeyAction.SwitchVariant
        }
        val label = formatUnifiedLangLabel(state.effectiveLanguage, state.activeChineseScheme)
        onKeyPressDownState.value(FeedbackKey(action, label, FeedbackZone.TOOLBAR))
        onActionState.value(action)
    }
}

@Immutable
internal data class UnifiedLangAccessibilitySpec(
    val contentDescription: String,
    val stateDescription: String,
    val toggleLanguageLabel: String,
    val switchSchemeLabel: String,
    val switchVariantLabel: String
)

internal fun SemanticsPropertyReceiver.applyUnifiedLangSemantics(
    state: DuoToolbarState,
    spec: UnifiedLangAccessibilitySpec,
    onGesture: (UnifiedLangGesture) -> Unit
) {
    role = Role.Button
    contentDescription = spec.contentDescription
    stateDescription = spec.stateDescription
    if (state.canToggleLanguage) {
        onClick(label = spec.toggleLanguageLabel) {
            onGesture(UnifiedLangGesture.TAP_TOGGLE_LANG)
            true
        }
        customActions = listOf(
            CustomAccessibilityAction(label = spec.switchSchemeLabel) {
                onGesture(UnifiedLangGesture.FLICK_UP_OR_HOLD_SCHEME)
                true
            },
            CustomAccessibilityAction(label = spec.switchVariantLabel) {
                onGesture(UnifiedLangGesture.FLICK_DOWN_VARIANT)
                true
            }
        )
    } else {
        disabled()
    }
}

@Composable
private fun rememberUnifiedLangAccessibilitySpec(state: DuoToolbarState): UnifiedLangAccessibilitySpec {
    val langLabel = stringResource(
        if (state.effectiveLanguage == Language.CHINESE) {
            R.string.duo_lang_chip_lang_zh
        } else {
            R.string.duo_lang_chip_lang_en
        }
    )
    val schemeLabel = stringResource(
        when (state.activeChineseScheme) {
            ChineseScheme.PINYIN_26 -> R.string.duo_lang_chip_scheme_26
            ChineseScheme.PINYIN_T9 -> R.string.duo_lang_chip_scheme_t9
            ChineseScheme.XIAOHE_SHUANGPIN -> R.string.duo_lang_chip_scheme_xiaohe
        }
    )
    val variantLabel = stringResource(
        if (state.variant == ChineseVariant.TRADITIONAL) {
            R.string.duo_lang_chip_variant_traditional
        } else {
            R.string.duo_lang_chip_variant_simplified
        }
    )
    val description = stringResource(R.string.duo_lang_chip_description, langLabel, schemeLabel, variantLabel)
    val toggleLabel = stringResource(R.string.duo_lang_chip_action_toggle_language)
    val switchSchemeLabel = stringResource(
        if (nextQuickChineseScheme(state.activeChineseScheme) == ChineseScheme.PINYIN_T9) {
            R.string.duo_lang_chip_action_switch_to_t9
        } else {
            R.string.duo_lang_chip_action_switch_to_26
        }
    )
    val switchVariantLabel = stringResource(R.string.duo_lang_chip_action_switch_variant)
    return remember(description, toggleLabel, switchSchemeLabel, switchVariantLabel) {
        UnifiedLangAccessibilitySpec(
            contentDescription = description,
            stateDescription = description,
            toggleLanguageLabel = toggleLabel,
            switchSchemeLabel = switchSchemeLabel,
            switchVariantLabel = switchVariantLabel
        )
    }
}

private data class UnifiedChipPalette(
    val background: Color,
    val foreground: Color,
    val hint: Color
)

private fun resolveUnifiedChipPalette(
    state: DuoToolbarState,
    pressed: Boolean,
    isEink: Boolean,
    colors: DuoColorTokens
): UnifiedChipPalette {
    val enabled = state.canToggleLanguage
    val isChinese = state.effectiveLanguage == Language.CHINESE
    val bg = when {
        !enabled -> colors.chipBackground
        pressed -> if (isChinese) colors.accentKeyPressedBackground else colors.actionKeyPressedBackground
        isChinese -> colors.chipSelectedBackground
        else -> colors.chipBackground
    }
    val fg = when {
        !enabled -> colors.keySecondaryText
        isChinese -> colors.chipSelectedText
        else -> colors.chipText
    }
    val hint = when {
        !enabled -> colors.keySecondaryText
        isChinese && isEink -> colors.chipSelectedText
        isChinese -> colors.chipSelectedText.copy(alpha = 0.80f)
        else -> colors.keySecondaryText
    }
    return UnifiedChipPalette(background = bg, foreground = fg, hint = hint)
}

/**
 * Stationary fixed-width (`88.dp`) ShellDeck-style unified language / scheme / variant button
 * mounted directly in [DuoToolbar]'s outer `Row` (outside `LazyRow`) so vertical flicks never
 * fight horizontal scrolling and neighboring pins such as `[换屏]` never shift pixels.
 */
@Composable
internal fun UnifiedLangModeChip(
    state: DuoToolbarState,
    colors: DuoColorTokens,
    onAction: (KeyAction) -> Unit,
    onKeyPressDown: (FeedbackKey) -> Unit
) {
    val dimensions = LocalDuoDimensions.current
    val density = LocalDensity.current
    val isEink = LocalDuoThemePreset.current == DuoThemePreset.EINK
    val flickThresholdPx = with(density) { UNIFIED_LANG_FLICK_THRESHOLD_DP.dp.toPx() }
    val coroutineScope = rememberCoroutineScope()
    val currentState = rememberUpdatedState(state)
    val currentOnAction = rememberUpdatedState(onAction)
    val currentOnKeyPressDown = rememberUpdatedState(onKeyPressDown)
    val pressedState = remember { mutableStateOf(false) }
    val gestureController = remember(coroutineScope) {
        UnifiedLangGestureController(
            scope = coroutineScope,
            stateRef = currentState,
            onActionState = currentOnAction,
            onKeyPressDownState = currentOnKeyPressDown,
            pressedState = pressedState
        )
    }

    LaunchedEffect(state.canToggleLanguage) {
        if (!state.canToggleLanguage) {
            gestureController.cancel()
        }
    }
    DisposableEffect(gestureController) {
        onDispose { gestureController.cancel() }
    }

    val palette = resolveUnifiedChipPalette(state, pressedState.value, isEink, colors)
    val accessibilitySpec = rememberUnifiedLangAccessibilitySpec(state)
    val shape = RoundedCornerShape(8.dp)
    val labelWeight = if (state.effectiveLanguage == Language.CHINESE && state.canToggleLanguage) {
        FontWeight.SemiBold
    } else {
        FontWeight.Medium
    }

    Box(
        modifier = Modifier
            .width(UNIFIED_LANG_CHIP_WIDTH_DP.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(palette.background)
            .border(width = 1.dp, color = colors.keyBorder, shape = shape)
            .focusable(enabled = state.canToggleLanguage)
            .semantics(mergeDescendants = true) {
                applyUnifiedLangSemantics(
                    state = state,
                    spec = accessibilitySpec,
                    onGesture = gestureController::emitGesture
                )
            }
            .pointerInput(gestureController, flickThresholdPx) {
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                gestureController.processPointerChange(
                                    change = change,
                                    widthPx = size.width.toFloat(),
                                    heightPx = size.height.toFloat(),
                                    flickThresholdPx = flickThresholdPx
                                )
                            }
                        }
                    }
                } finally {
                    gestureController.cancel()
                }
            }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        if (state.variant == ChineseVariant.TRADITIONAL) {
            BasicText(
                text = "繁",
                maxLines = 1,
                style = TextStyle(
                    color = palette.hint,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                modifier = Modifier.align(Alignment.TopStart)
            )
        }
        BasicText(
            text = formatUnifiedLangCornerHint(state.activeChineseScheme),
            maxLines = 1,
            style = TextStyle(
                color = palette.hint,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            ),
            modifier = Modifier.align(Alignment.TopEnd)
        )
        BasicText(
            text = formatUnifiedLangLabel(state.effectiveLanguage, state.activeChineseScheme),
            maxLines = 1,
            style = TextStyle(
                color = palette.foreground,
                fontSize = dimensions.toolbarFontSp.sp,
                fontWeight = labelWeight
            ),
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

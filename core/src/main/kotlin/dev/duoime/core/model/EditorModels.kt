// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.model

import dev.duoime.core.protocol.ExtensionConfig

/**
 * Platform-independent Enter key action resolved from [EditorContext.imeOptions].
 */
sealed interface EnterBehavior {
    data object Newline : EnterBehavior

    data class EditorAction(val actionId: Int) : EnterBehavior
}

/**
 * Platform-independent snapshot of the focused editor or embedded host context.
 */
data class EditorContext(
    val packageName: String? = null,
    val inputType: Int = 0,
    val imeOptions: Int = 0,
    val incognito: Boolean = false,
    val isPassword: Boolean = false,
    val extension: ExtensionConfig? = null,
    val fieldKind: FieldKind = FieldKind.TEXT
) {
    /**
     * Resolves whether pressing Enter with no active composition should insert a newline
     * (`KEYCODE_ENTER`) or invoke `performEditorAction(actionId)`.
     *
     * Mirrors Android `EditorInfo` semantics purely on JVM:
     * - If [IME_FLAG_NO_ENTER_ACTION] is set, always insert a newline.
     * - Otherwise mask with [IME_MASK_ACTION]; if [IME_ACTION_UNSPECIFIED] or [IME_ACTION_NONE],
     *   insert a newline; otherwise trigger the masked editor action.
     */
    fun resolveEnterBehavior(): EnterBehavior {
        if ((imeOptions and IME_FLAG_NO_ENTER_ACTION) != 0) {
            return EnterBehavior.Newline
        }
        val action = imeOptions and IME_MASK_ACTION
        return if (action == IME_ACTION_UNSPECIFIED || action == IME_ACTION_NONE) {
            EnterBehavior.Newline
        } else {
            EnterBehavior.EditorAction(action)
        }
    }

    companion object {
        const val IME_MASK_ACTION: Int = 0x000000ff
        const val IME_ACTION_UNSPECIFIED: Int = 0x00000000
        const val IME_ACTION_NONE: Int = 0x00000001
        const val IME_ACTION_GO: Int = 0x00000002
        const val IME_ACTION_SEARCH: Int = 0x00000003
        const val IME_ACTION_SEND: Int = 0x00000004
        const val IME_ACTION_NEXT: Int = 0x00000005
        const val IME_ACTION_DONE: Int = 0x00000006
        const val IME_ACTION_PREVIOUS: Int = 0x00000007
        const val IME_FLAG_NO_ENTER_ACTION: Int = 0x40000000
        const val IME_FLAG_NO_EXTRACT_UI: Int = 0x10000000
        const val IME_FLAG_FORCE_ASCII: Int = Int.MIN_VALUE

        const val TYPE_CLASS_TEXT: Int = 0x00000001
        const val TYPE_TEXT_FLAG_MULTI_LINE: Int = 0x00020000
        const val TYPE_TEXT_FLAG_IME_MULTI_LINE: Int = 0x00040000
    }
}

/**
 * Converts [EditorContext] into fail-closed [InputConstraints] for the orthogonal navigation state
 * machine. Security constraints (`PASSWORD`, `incognito`, `IME_FLAG_FORCE_ASCII`, `TERMINAL`
 * no-learning) can only tighten and can never be weakened by host [ExtensionConfig] declarations.
 */
fun EditorContext.toInputConstraints(): InputConstraints {
    val ext = extension
    val effectiveKind = resolveEffectiveFieldKind(ext)
    val forceAscii = (imeOptions and EditorContext.IME_FLAG_FORCE_ASCII) != 0
    val baseNoLearning = incognito || isPassword || ext?.noLearning == true
    val defaultSymbolTab = ext?.defaultSymbolTab
        ?: if (effectiveKind == FieldKind.TERMINAL) SymbolTabId.CLI else SymbolTabId.COMMON

    return InputConstraints(
        kind = effectiveKind,
        allowChinese = resolveAllowChinese(effectiveKind, forceAscii),
        forcedPanel = if (effectiveKind.isNumericFieldKind()) KeyboardPanel.NUMBER else null,
        forceNumberRow = effectiveKind == FieldKind.PASSWORD,
        noLearning = baseNoLearning || effectiveKind == FieldKind.PASSWORD || effectiveKind == FieldKind.TERMINAL,
        initialLanguage = resolveInitialLanguage(effectiveKind, forceAscii, ext?.initialLanguage),
        defaultSymbolTab = defaultSymbolTab,
        langKeyPlacementOverride = ext?.langKeyPlacement,
        candidatePagingOverride = ext?.candidatePaging
    )
}

private fun EditorContext.resolveEffectiveFieldKind(ext: ExtensionConfig?): FieldKind = when {
    isPassword || fieldKind == FieldKind.PASSWORD -> FieldKind.PASSWORD
    ext?.fieldKind != null -> ext.fieldKind
    else -> fieldKind
}

private fun FieldKind.isNumericFieldKind(): Boolean = when (this) {
    FieldKind.NUMBER,
    FieldKind.DECIMAL,
    FieldKind.SIGNED_NUMBER,
    FieldKind.PHONE,
    FieldKind.DATETIME -> true
    else -> false
}

private fun resolveAllowChinese(kind: FieldKind, forceAscii: Boolean): Boolean =
    !forceAscii && kind != FieldKind.PASSWORD && !kind.isNumericFieldKind()

private fun resolveInitialLanguage(kind: FieldKind, forceAscii: Boolean, extInitial: Language?): Language? = when {
    !resolveAllowChinese(kind, forceAscii) -> Language.ENGLISH
    kind == FieldKind.URI || kind == FieldKind.EMAIL || kind == FieldKind.TERMINAL -> extInitial ?: Language.ENGLISH
    else -> extInitial
}

/**
 * Capabilities supported by an [dev.duoime.core.engine.InputSink] implementation.
 * For example, terminal hosts disable inline composing and accept raw navigation/control keys.
 */
data class SinkCapabilities(
    val composing: Boolean = true,
    val imageMimeTypes: List<String> = emptyList(),
    val rawKeys: Boolean = false
) {
    companion object {
        val STANDARD_IME: SinkCapabilities = SinkCapabilities()
        val TERMINAL: SinkCapabilities = SinkCapabilities(composing = false, rawKeys = true)
    }
}

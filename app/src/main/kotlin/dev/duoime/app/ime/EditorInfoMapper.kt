// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.core.view.inputmethod.EditorInfoCompat
import dev.duoime.core.model.EditorContext
import dev.duoime.core.model.FieldKind
import dev.duoime.core.model.SinkCapabilities
import dev.duoime.core.model.mergeFieldKindConstraint
import dev.duoime.core.protocol.ExtensionConfigParser

/**
 * Maps Android [EditorInfo] metadata into pure-JVM `:core` [EditorContext] and [SinkCapabilities].
 */
object EditorInfoMapper {
    const val EXTENSION_EXTRA_KEY: String = "dev.duoime.ext.v1"
    const val IME_FLAG_NO_PERSONALIZED_LEARNING: Int = 0x01000000
    const val IME_FLAG_FORCE_ASCII: Int = EditorContext.IME_FLAG_FORCE_ASCII

    const val TYPE_NULL: Int = 0x00000000
    const val TYPE_MASK_CLASS: Int = 0x0000000f
    const val TYPE_MASK_VARIATION: Int = 0x00000ff0
    const val TYPE_MASK_FLAGS: Int = 0x00fff000

    const val TYPE_CLASS_TEXT: Int = 0x00000001
    const val TYPE_CLASS_NUMBER: Int = 0x00000002
    const val TYPE_CLASS_PHONE: Int = 0x00000003
    const val TYPE_CLASS_DATETIME: Int = 0x00000004

    const val TYPE_TEXT_VARIATION_URI: Int = 0x00000010
    const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS: Int = 0x00000020
    const val TYPE_TEXT_VARIATION_PASSWORD: Int = 0x00000080
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD: Int = 0x00000090
    const val TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS: Int = 0x000000d0
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD: Int = 0x000000e0

    const val TYPE_NUMBER_VARIATION_PASSWORD: Int = 0x00000010
    const val TYPE_NUMBER_FLAG_SIGNED: Int = 0x00001000
    const val TYPE_NUMBER_FLAG_DECIMAL: Int = 0x00002000

    fun fromEditorInfo(info: EditorInfo?, userIncognito: Boolean): EditorContext {
        if (info == null) {
            return EditorContext(incognito = userIncognito)
        }
        val rawExt = info.extras?.getString(EXTENSION_EXTRA_KEY)
        return buildEditorContext(
            packageName = info.packageName,
            inputType = info.inputType,
            imeOptions = info.imeOptions,
            rawExtensionJson = rawExt,
            userIncognito = userIncognito
        )
    }

    fun resolveSinkCapabilities(info: EditorInfo?): SinkCapabilities {
        if (info == null) return SinkCapabilities.STANDARD_IME
        val mimeTypes = EditorInfoCompat.getContentMimeTypes(info).toList()
        return resolveSinkCapabilities(inputType = info.inputType, imageMimeTypes = mimeTypes)
    }

    fun buildEditorContext(
        packageName: String?,
        inputType: Int,
        imeOptions: Int,
        rawExtensionJson: String? = null,
        userIncognito: Boolean = false
    ): EditorContext {
        val basePassword = isPasswordInputType(inputType)
        val baseKind = classifyFieldKind(inputType)
        val noLearningFlag = (imeOptions and IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        val extension = ExtensionConfigParser.parse(rawExtensionJson)

        // Monotonic fail-closed merge: authoritative platform constraints (PASSWORD, numeric,
        // phone, datetime, terminal) cannot be weakened by host extension hints.
        val mergedKind = mergeFieldKindConstraint(
            platformKind = baseKind,
            extensionKind = extension?.fieldKind,
            platformIsPassword = basePassword
        )
        val effectivePassword = basePassword || mergedKind == FieldKind.PASSWORD
        val extNoLearning = extension?.noLearning == true
        return EditorContext(
            packageName = packageName,
            inputType = inputType,
            imeOptions = imeOptions,
            incognito = userIncognito || effectivePassword || noLearningFlag || extNoLearning,
            isPassword = effectivePassword,
            extension = extension,
            fieldKind = mergedKind
        )
    }

    fun classifyFieldKind(inputType: Int): FieldKind {
        if (inputType == TYPE_NULL) return FieldKind.TERMINAL
        if (isPasswordInputType(inputType)) return FieldKind.PASSWORD
        val inputClass = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION
        val flags = inputType and TYPE_MASK_FLAGS
        return when (inputClass) {
            TYPE_CLASS_NUMBER -> when {
                (flags and TYPE_NUMBER_FLAG_DECIMAL) != 0 -> FieldKind.DECIMAL
                (flags and TYPE_NUMBER_FLAG_SIGNED) != 0 -> FieldKind.SIGNED_NUMBER
                else -> FieldKind.NUMBER
            }
            TYPE_CLASS_PHONE -> FieldKind.PHONE
            TYPE_CLASS_DATETIME -> FieldKind.DATETIME
            TYPE_CLASS_TEXT -> when (variation) {
                TYPE_TEXT_VARIATION_URI -> FieldKind.URI
                TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL
                else -> FieldKind.TEXT
            }
            else -> FieldKind.TEXT
        }
    }

    fun resolveSinkCapabilities(inputType: Int, imageMimeTypes: List<String> = emptyList()): SinkCapabilities {
        if (inputType == InputType.TYPE_NULL) {
            return SinkCapabilities(
                composing = false,
                imageMimeTypes = imageMimeTypes,
                rawKeys = true
            )
        }
        return SinkCapabilities(
            composing = true,
            imageMimeTypes = imageMimeTypes,
            rawKeys = false
        )
    }

    fun isPasswordInputType(inputType: Int): Boolean {
        val inputClass = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION
        return when (inputClass) {
            TYPE_CLASS_TEXT ->
                variation == TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == TYPE_TEXT_VARIATION_WEB_PASSWORD
            TYPE_CLASS_NUMBER -> variation == TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /**
     * Returns `true` when `InputMethodService.onUpdateSelection` indicates the editor dropped the
     * composing span (`candidatesStart == -1`) or the user moved the cursor/selection outside the
     * active `[candidatesStart, candidatesEnd]` range while the engine is still composing.
     */
    fun shouldResetComposingOnSelectionUpdate(
        isComposing: Boolean,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ): Boolean {
        if (!isComposing) return false
        if (candidatesStart < 0 || candidatesEnd < 0) return true
        val selOutsideStart = newSelStart < candidatesStart || newSelStart > candidatesEnd
        val selOutsideEnd = newSelEnd < candidatesStart || newSelEnd > candidatesEnd
        return selOutsideStart || selOutsideEnd
    }
}

// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.core.view.inputmethod.EditorInfoCompat
import dev.duoime.core.model.EditorContext
import dev.duoime.core.model.SinkCapabilities
import dev.duoime.core.protocol.ExtensionConfigParser

/**
 * Maps Android [EditorInfo] metadata into pure-JVM `:core` [EditorContext] and [SinkCapabilities].
 */
object EditorInfoMapper {
    const val EXTENSION_EXTRA_KEY: String = "dev.duoime.ext.v1"
    const val IME_FLAG_NO_PERSONALIZED_LEARNING: Int = 0x01000000
    const val TYPE_MASK_CLASS: Int = 0x0000000f
    const val TYPE_MASK_VARIATION: Int = 0x00000ff0
    const val TYPE_CLASS_TEXT: Int = 0x00000001
    const val TYPE_CLASS_NUMBER: Int = 0x00000002
    const val TYPE_TEXT_VARIATION_PASSWORD: Int = 0x00000080
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD: Int = 0x00000090
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD: Int = 0x000000e0
    const val TYPE_NUMBER_VARIATION_PASSWORD: Int = 0x00000010

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
        val password = isPasswordInputType(inputType)
        val noLearningFlag = (imeOptions and IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        val extension = ExtensionConfigParser.parse(rawExtensionJson)
        return EditorContext(
            packageName = packageName,
            inputType = inputType,
            imeOptions = imeOptions,
            incognito = userIncognito || password || noLearningFlag,
            isPassword = password,
            extension = extension
        )
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

// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.ime

import android.view.inputmethod.EditorInfo
import dev.duoime.core.model.EditorContext
import dev.duoime.core.model.FieldKind
import dev.duoime.core.model.SinkCapabilities
import dev.duoime.embed.EmbedEditorInfoMapper

/**
 * `:app` facade over [EmbedEditorInfoMapper], preserving source-compatible constants and mapping
 * entry points across `:app` services and tests.
 */
object EditorInfoMapper {
    const val EXTENSION_EXTRA_KEY: String = EmbedEditorInfoMapper.EXTENSION_EXTRA_KEY
    const val IME_FLAG_NO_PERSONALIZED_LEARNING: Int = EmbedEditorInfoMapper.IME_FLAG_NO_PERSONALIZED_LEARNING
    const val IME_FLAG_FORCE_ASCII: Int = EmbedEditorInfoMapper.IME_FLAG_FORCE_ASCII

    const val TYPE_NULL: Int = EmbedEditorInfoMapper.TYPE_NULL
    const val TYPE_MASK_CLASS: Int = EmbedEditorInfoMapper.TYPE_MASK_CLASS
    const val TYPE_MASK_VARIATION: Int = EmbedEditorInfoMapper.TYPE_MASK_VARIATION
    const val TYPE_MASK_FLAGS: Int = EmbedEditorInfoMapper.TYPE_MASK_FLAGS

    const val TYPE_CLASS_TEXT: Int = EmbedEditorInfoMapper.TYPE_CLASS_TEXT
    const val TYPE_CLASS_NUMBER: Int = EmbedEditorInfoMapper.TYPE_CLASS_NUMBER
    const val TYPE_CLASS_PHONE: Int = EmbedEditorInfoMapper.TYPE_CLASS_PHONE
    const val TYPE_CLASS_DATETIME: Int = EmbedEditorInfoMapper.TYPE_CLASS_DATETIME

    const val TYPE_TEXT_VARIATION_URI: Int = EmbedEditorInfoMapper.TYPE_TEXT_VARIATION_URI
    const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS: Int = EmbedEditorInfoMapper.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    const val TYPE_TEXT_VARIATION_PASSWORD: Int = EmbedEditorInfoMapper.TYPE_TEXT_VARIATION_PASSWORD
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD: Int = EmbedEditorInfoMapper.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    const val TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS: Int = EmbedEditorInfoMapper.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD: Int = EmbedEditorInfoMapper.TYPE_TEXT_VARIATION_WEB_PASSWORD

    const val TYPE_NUMBER_VARIATION_PASSWORD: Int = EmbedEditorInfoMapper.TYPE_NUMBER_VARIATION_PASSWORD
    const val TYPE_NUMBER_FLAG_SIGNED: Int = EmbedEditorInfoMapper.TYPE_NUMBER_FLAG_SIGNED
    const val TYPE_NUMBER_FLAG_DECIMAL: Int = EmbedEditorInfoMapper.TYPE_NUMBER_FLAG_DECIMAL

    fun fromEditorInfo(info: EditorInfo?, userIncognito: Boolean): EditorContext =
        EmbedEditorInfoMapper.fromEditorInfo(info, userIncognito)

    fun resolveSinkCapabilities(info: EditorInfo?): SinkCapabilities =
        EmbedEditorInfoMapper.resolveSinkCapabilities(info)

    fun buildEditorContext(
        packageName: String?,
        inputType: Int,
        imeOptions: Int,
        rawExtensionJson: String? = null,
        userIncognito: Boolean = false
    ): EditorContext = EmbedEditorInfoMapper.buildEditorContext(
        packageName = packageName,
        inputType = inputType,
        imeOptions = imeOptions,
        rawExtensionJson = rawExtensionJson,
        userIncognito = userIncognito
    )

    fun classifyFieldKind(inputType: Int): FieldKind = EmbedEditorInfoMapper.classifyFieldKind(inputType)

    fun resolveSinkCapabilities(inputType: Int, imageMimeTypes: List<String> = emptyList()): SinkCapabilities =
        EmbedEditorInfoMapper.resolveSinkCapabilities(inputType, imageMimeTypes)

    fun isPasswordInputType(inputType: Int): Boolean = EmbedEditorInfoMapper.isPasswordInputType(inputType)

    fun shouldResetComposingOnSelectionUpdate(
        isComposing: Boolean,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ): Boolean = EmbedEditorInfoMapper.shouldResetComposingOnSelectionUpdate(
        isComposing = isComposing,
        newSelStart = newSelStart,
        newSelEnd = newSelEnd,
        candidatesStart = candidatesStart,
        candidatesEnd = candidatesEnd
    )
}

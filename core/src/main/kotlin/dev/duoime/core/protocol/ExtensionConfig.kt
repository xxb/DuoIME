// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Explicit sandboxed action types permitted for host-declared extension toolbar keys.
 * Extension payloads can never invoke clipboard, filesystem, or network operations.
 */
enum class ExtensionActionType {
    SEND_TEXT,
    SEND_KEY,
    HOST_COMMAND
}

/**
 * Validated host-declared custom toolbar button.
 */
data class ExtensionToolbarButton(
    val id: String,
    val label: String,
    val actionType: ExtensionActionType,
    val value: String = ""
)

/**
 * Sandboxed configuration parsed from `EditorInfo.extras["dev.duoime.ext.v1"]`.
 */
data class ExtensionConfig(
    val schemaVersion: Int = SUPPORTED_SCHEMA_VERSION,
    val hostName: String? = null,
    val toolbarButtons: List<ExtensionToolbarButton> = emptyList(),
    val preferInline: Boolean = false,
    val acceptImageMimes: List<String> = emptyList()
) {
    companion object {
        const val SUPPORTED_SCHEMA_VERSION: Int = 1
    }
}

@Serializable
private data class RawExtensionConfig(
    @SerialName("version")
    val version: Int = ExtensionConfig.SUPPORTED_SCHEMA_VERSION,
    @SerialName("hostName")
    val hostName: String? = null,
    @SerialName("preferInline")
    val preferInline: Boolean = false,
    @SerialName("toolbarButtons")
    val toolbarButtons: List<RawToolbarButton?> = emptyList(),
    @SerialName("acceptImageMimes")
    val acceptImageMimes: List<String?> = emptyList()
)

@Serializable
private data class RawToolbarButton(
    @SerialName("id")
    val id: String? = null,
    @SerialName("label")
    val label: String? = null,
    @SerialName("action")
    val action: String? = null,
    @SerialName("value")
    val value: String? = null
)

/**
 * Boundary parser and sanitizer for untrusted host JSON in `dev.duoime.ext.v1`.
 */
object ExtensionConfigParser {
    const val MAX_RAW_JSON_BYTES: Int = 8_192
    const val MAX_TOOLBAR_BUTTONS: Int = 12
    const val MAX_ID_LENGTH: Int = 48
    const val MAX_LABEL_LENGTH: Int = 16
    const val MAX_VALUE_LENGTH: Int = 64
    const val MAX_HOST_NAME_LENGTH: Int = 48
    const val MAX_IMAGE_MIMES: Int = 8

    private val safeIdRegex = Regex("""^[A-Za-z0-9._-]{1,48}$""")
    private val imageMimeRegex = Regex("""^image/[a-z0-9.+-]{1,48}$""")
    private val controlCharRegex = Regex("""[\p{Cc}\p{Cf}]""")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        coerceInputValues = true
    }

    /**
     * Parses and sanitizes [rawJson]. Returns `null` for null, blank, oversized,
     * malformed, or unsupported-version payloads without throwing.
     */
    fun parse(rawJson: String?): ExtensionConfig? {
        if (rawJson.isNullOrBlank()) return null
        if (rawJson.toByteArray(Charsets.UTF_8).size > MAX_RAW_JSON_BYTES) return null

        val raw = runCatching { json.decodeFromString<RawExtensionConfig>(rawJson) }.getOrNull()
            ?: return null

        if (raw.version != ExtensionConfig.SUPPORTED_SCHEMA_VERSION) return null

        val sanitizedHost = raw.hostName
            ?.let { sanitizePlainText(it, MAX_HOST_NAME_LENGTH) }
            ?.takeIf { it.isNotEmpty() }

        val buttons = sanitizeButtons(raw.toolbarButtons)
        val mimes = sanitizeImageMimes(raw.acceptImageMimes)

        return ExtensionConfig(
            schemaVersion = raw.version,
            hostName = sanitizedHost,
            toolbarButtons = buttons,
            preferInline = raw.preferInline,
            acceptImageMimes = mimes
        )
    }

    private fun sanitizeButtons(rawButtons: List<RawToolbarButton?>): List<ExtensionToolbarButton> {
        val seenIds = LinkedHashSet<String>()
        val result = ArrayList<ExtensionToolbarButton>()
        for (rawButton in rawButtons) {
            if (result.size >= MAX_TOOLBAR_BUTTONS) break
            if (rawButton == null) continue
            val button = sanitizeButton(rawButton) ?: continue
            if (seenIds.add(button.id)) {
                result.add(button)
            }
        }
        return result
    }

    private fun sanitizeButton(rawButton: RawToolbarButton): ExtensionToolbarButton? {
        val id = rawButton.id?.trim().orEmpty()
        if (id.length > MAX_ID_LENGTH || !safeIdRegex.matches(id)) return null

        val label = sanitizePlainText(rawButton.label.orEmpty(), MAX_LABEL_LENGTH)
        if (label.isEmpty()) return null

        val actionType = parseActionType(rawButton.action.orEmpty()) ?: return null
        val value = sanitizePlainText(rawButton.value.orEmpty(), MAX_VALUE_LENGTH)
        if (actionType == ExtensionActionType.SEND_TEXT && value.isEmpty()) return null

        return ExtensionToolbarButton(
            id = id,
            label = label,
            actionType = actionType,
            value = value
        )
    }

    private fun parseActionType(rawAction: String): ExtensionActionType? = when (rawAction.trim().lowercase()) {
        "send_text" -> ExtensionActionType.SEND_TEXT
        "send_key" -> ExtensionActionType.SEND_KEY
        "host_command" -> ExtensionActionType.HOST_COMMAND
        else -> null
    }

    private fun sanitizeImageMimes(rawMimes: List<String?>): List<String> = rawMimes
        .asSequence()
        .filterNotNull()
        .map { it.trim().lowercase() }
        .filter { imageMimeRegex.matches(it) }
        .distinct()
        .take(MAX_IMAGE_MIMES)
        .toList()

    /**
     * Strips control and format characters (including `\p{Cf}` bidi-override characters to prevent
     * label spoofing) and truncates by Unicode code points so supplementary-plane characters
     * (e.g. emoji surrogate pairs) are never split into lone surrogates.
     */
    private fun sanitizePlainText(raw: String, maxCodePoints: Int): String {
        val cleaned = controlCharRegex.replace(raw, "").trim()
        if (cleaned.codePointCount(0, cleaned.length) <= maxCodePoints) {
            return cleaned
        }
        val endIndex = cleaned.offsetByCodePoints(0, maxCodePoints)
        return cleaned.substring(0, endIndex)
    }
}

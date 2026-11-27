// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.protocol

import dev.duoime.core.model.CandidatePaging
import dev.duoime.core.model.FieldKind
import dev.duoime.core.model.LangKeyPlacement
import dev.duoime.core.model.Language
import dev.duoime.core.model.SymbolTabId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

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
 *
 * Additive v1 fields (`fieldKind`, `initialLanguage`, `defaultSymbolTab`, `langKeyPlacement`,
 * `candidatePaging`, `noLearning`) fall back per-field to `null` when unrecognized or wrong-typed,
 * while unknown top-level keys are ignored and unsupported `"version"` values fail closed to `null`.
 */
data class ExtensionConfig(
    val schemaVersion: Int = SUPPORTED_SCHEMA_VERSION,
    val hostName: String? = null,
    val toolbarButtons: List<ExtensionToolbarButton> = emptyList(),
    val preferInline: Boolean = false,
    val acceptImageMimes: List<String> = emptyList(),
    val fieldKind: FieldKind? = null,
    val initialLanguage: Language? = null,
    val defaultSymbolTab: SymbolTabId? = null,
    val langKeyPlacement: LangKeyPlacement? = null,
    val candidatePaging: CandidatePaging? = null,
    val noLearning: Boolean? = null
) {
    companion object {
        const val SUPPORTED_SCHEMA_VERSION: Int = 1
    }
}

/**
 * Boundary parser and sanitizer for untrusted host JSON in `dev.duoime.ext.v1`.
 *
 * Uses strict JSON DOM type inspection on core protocol fields (`version`, `preferInline`,
 * `hostName`, `toolbarButtons`, `acceptImageMimes`) so unsupported versions or wrong-typed core
 * fields fail closed to `null`. Unknown keys are ignored, and invalid values on additive optional
 * v1 fields (`fieldKind`, `initialLanguage`, `defaultSymbolTab`, `langKeyPlacement`,
 * `candidatePaging`, `noLearning`) fall back per-field to `null` without discarding the rest of
 * the valid configuration.
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
        isLenient = false
    }

    /**
     * Parses and sanitizes [rawJson]. Returns `null` for null, blank, oversized,
     * malformed, wrong-typed core fields, or unsupported-version payloads without throwing.
     */
    fun parse(rawJson: String?): ExtensionConfig? {
        if (rawJson.isNullOrBlank()) return null
        if (rawJson.toByteArray(Charsets.UTF_8).size > MAX_RAW_JSON_BYTES) return null

        val root = runCatching { json.parseToJsonElement(rawJson) }.getOrNull() as? JsonObject
            ?: return null

        val version = parseOptionalStrictInt(root["version"], ExtensionConfig.SUPPORTED_SCHEMA_VERSION)
            ?: return null
        if (version != ExtensionConfig.SUPPORTED_SCHEMA_VERSION) return null

        val preferInline = parseOptionalStrictBoolean(root["preferInline"], default = false)
            ?: return null

        val rawHost = parseOptionalStrictString(root["hostName"]) ?: return null
        val sanitizedHost = rawHost.value
            ?.let { sanitizePlainText(it, MAX_HOST_NAME_LENGTH) }
            ?.takeIf { it.isNotEmpty() }

        val buttonsArray = parseOptionalJsonArray(root["toolbarButtons"]) ?: return null
        val mimesArray = parseOptionalJsonArray(root["acceptImageMimes"]) ?: return null

        return ExtensionConfig(
            schemaVersion = version,
            hostName = sanitizedHost,
            toolbarButtons = sanitizeButtons(buttonsArray),
            preferInline = preferInline,
            acceptImageMimes = sanitizeImageMimes(mimesArray),
            fieldKind = parseSoftFieldKind(root["fieldKind"]),
            initialLanguage = parseSoftLanguage(root["initialLanguage"]),
            defaultSymbolTab = parseSoftSymbolTab(root["defaultSymbolTab"]),
            langKeyPlacement = parseSoftLangKeyPlacement(root["langKeyPlacement"]),
            candidatePaging = parseSoftCandidatePaging(root["candidatePaging"]),
            noLearning = parseSoftBoolean(root["noLearning"])
        )
    }

    private data class OptionalString(val value: String?)

    private fun parseOptionalStrictInt(element: JsonElement?, default: Int): Int? = when (element) {
        null, JsonNull -> default
        is JsonPrimitive -> if (!element.isString) element.intOrNull else null
        else -> null
    }

    private fun parseOptionalStrictBoolean(element: JsonElement?, default: Boolean): Boolean? = when (element) {
        null, JsonNull -> default
        is JsonPrimitive -> if (!element.isString) element.booleanOrNull else null
        else -> null
    }

    private fun parseOptionalStrictString(element: JsonElement?): OptionalString? = when (element) {
        null, JsonNull -> OptionalString(null)
        is JsonPrimitive -> if (element.isString) OptionalString(element.content) else null
        else -> null
    }

    private fun parseOptionalJsonArray(element: JsonElement?): JsonArray? = when (element) {
        null, JsonNull -> JsonArray(emptyList())
        is JsonArray -> element
        else -> null
    }

    private fun parseSoftString(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase()

    private fun parseSoftBoolean(element: JsonElement?): Boolean? =
        (element as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun parseSoftFieldKind(element: JsonElement?): FieldKind? = when (parseSoftString(element)) {
        "text" -> FieldKind.TEXT
        "number" -> FieldKind.NUMBER
        "decimal" -> FieldKind.DECIMAL
        "signed_number" -> FieldKind.SIGNED_NUMBER
        "phone" -> FieldKind.PHONE
        "datetime" -> FieldKind.DATETIME
        "password" -> FieldKind.PASSWORD
        "uri", "url" -> FieldKind.URI
        "email" -> FieldKind.EMAIL
        "terminal" -> FieldKind.TERMINAL
        else -> null
    }

    private fun parseSoftLanguage(element: JsonElement?): Language? = when (parseSoftString(element)) {
        "chinese", "zh", "cn" -> Language.CHINESE
        "english", "en" -> Language.ENGLISH
        else -> null
    }

    private fun parseSoftSymbolTab(element: JsonElement?): SymbolTabId? = when (parseSoftString(element)) {
        "common" -> SymbolTabId.COMMON
        "chinese", "zh", "cn" -> SymbolTabId.CHINESE
        "cli" -> SymbolTabId.CLI
        "english", "en" -> SymbolTabId.ENGLISH
        "pair" -> SymbolTabId.PAIR
        "math_unit" -> SymbolTabId.MATH_UNIT
        "sequence" -> SymbolTabId.SEQUENCE
        "arrow_special" -> SymbolTabId.ARROW_SPECIAL
        "greek_phonetic" -> SymbolTabId.GREEK_PHONETIC
        else -> null
    }

    private fun parseSoftLangKeyPlacement(element: JsonElement?): LangKeyPlacement? = when (parseSoftString(element)) {
        "grid" -> LangKeyPlacement.GRID
        "host" -> LangKeyPlacement.HOST
        else -> null
    }

    private fun parseSoftCandidatePaging(element: JsonElement?): CandidatePaging? = when (parseSoftString(element)) {
        "scroll" -> CandidatePaging.SCROLL
        "paged" -> CandidatePaging.PAGED
        else -> null
    }

    private fun sanitizeButtons(rawButtons: JsonArray): List<ExtensionToolbarButton> {
        val seenIds = LinkedHashSet<String>()
        val result = ArrayList<ExtensionToolbarButton>()
        for (element in rawButtons) {
            if (result.size >= MAX_TOOLBAR_BUTTONS) break
            val obj = element as? JsonObject ?: continue
            val button = sanitizeButton(obj) ?: continue
            if (seenIds.add(button.id)) {
                result.add(button)
            }
        }
        return result
    }

    private fun sanitizeButton(obj: JsonObject): ExtensionToolbarButton? {
        val rawId = parseOptionalStrictString(obj["id"])?.value?.trim().orEmpty()
        if (rawId.length > MAX_ID_LENGTH || !safeIdRegex.matches(rawId)) return null

        val rawLabel = parseOptionalStrictString(obj["label"])?.value.orEmpty()
        val label = sanitizePlainText(rawLabel, MAX_LABEL_LENGTH)
        if (label.isEmpty()) return null

        val rawAction = parseOptionalStrictString(obj["action"])?.value.orEmpty()
        val actionType = parseActionType(rawAction) ?: return null

        val rawValue = parseOptionalStrictString(obj["value"]) ?: return null
        val value = sanitizePlainText(rawValue.value.orEmpty(), MAX_VALUE_LENGTH)
        if (actionType == ExtensionActionType.SEND_TEXT && value.isEmpty()) return null

        return ExtensionToolbarButton(
            id = rawId,
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

    private fun sanitizeImageMimes(rawMimes: JsonArray): List<String> = rawMimes
        .asSequence()
        .mapNotNull { elem -> (elem as? JsonPrimitive)?.takeIf { it.isString }?.content }
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

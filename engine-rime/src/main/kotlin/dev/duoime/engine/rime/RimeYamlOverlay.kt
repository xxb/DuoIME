// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.engine.rime

import java.io.File

internal data class ParsedSchemaYaml(
    val id: String,
    val name: String,
    val fileName: String
)

internal data class ManagedOverlayOutcome(
    val discoveredCustomSchemaIds: List<String>,
    val synthesizedDefaultCustom: Boolean,
    val hasUserDefaultCustom: Boolean
)

/**
 * Pure-JVM YAML validation, schema metadata parsing, `TrashDeprecatedUserCopy` version protection,
 * and managed `default.custom.yaml` overlay synthesis for [RimeWorkspaceManager].
 */
internal object RimeYamlOverlay {
    private const val MIN_SAFE_CONFIG_VERSION: String = "1.0"
    private const val DEFAULT_YAML_FILE: String = "default.yaml"
    private const val DEFAULT_CUSTOM_YAML_FILE: String = "default.custom.yaml"
    private const val SCHEMA_YAML_SUFFIX: String = ".schema.yaml"
    private const val CUSTOM_YAML_SUFFIX: String = ".custom.yaml"
    private const val CUSTOM_VERSION_MARKER: String = ".custom."

    private enum class MappingKeyPolicy {
        REQUIRED,
        REQUIRED_IF_NON_EMPTY
    }

    fun validateStagedYamlFiles(stageDir: File): String? {
        val defaultYaml = File(stageDir, DEFAULT_YAML_FILE)
        if (defaultYaml.isFile) {
            validateBasicYamlSyntax(defaultYaml, MappingKeyPolicy.REQUIRED)?.let { return it }
        }
        val customYamlFiles = stageDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(CUSTOM_YAML_SUFFIX, ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        for (customFile in customYamlFiles) {
            validateBasicYamlSyntax(customFile, MappingKeyPolicy.REQUIRED_IF_NON_EMPTY)?.let { return it }
        }
        val schemaFiles = listTopLevelSchemaFiles(stageDir)
        for (schemaFile in schemaFiles) {
            validateBasicYamlSyntax(schemaFile, MappingKeyPolicy.REQUIRED)?.let { return it }
            val parsed = parseSchemaYaml(schemaFile)
                ?: return "Invalid Rime schema YAML (missing schema.schema_id): ${schemaFile.name}"
            if (parsed.id.isBlank()) {
                return "Blank schema_id in ${schemaFile.name}"
            }
        }
        return null
    }

    /**
     * Guards staged `default.yaml` against `librime`'s `TrashDeprecatedUserCopy` (which moves
     * `userDir/default.yaml` into `userDir/trash/` when `config_version` is missing, `< "1.0"`,
     * or carries `.custom.`), then synthesizes `default.custom.yaml` for Case A & Case B while
     * preserving Case C verbatim.
     */
    fun applyManagedOverlay(stageDir: File): ManagedOverlayOutcome {
        val defaultYaml = File(stageDir, DEFAULT_YAML_FILE)
        val defaultCustom = File(stageDir, DEFAULT_CUSTOM_YAML_FILE)
        val hasDefaultYaml = defaultYaml.isFile
        val hasDefaultCustom = defaultCustom.isFile

        if (hasDefaultYaml) {
            guardDefaultYamlConfigVersion(defaultYaml)
        }

        val stagedSchemas = listTopLevelSchemaFiles(stageDir).mapNotNull(::parseSchemaYaml)
        val customSchemaIdsAll = stagedSchemas
            .map { it.id }
            .filter { it !in RimeEngineMetadata.ALL_BUILT_IN_SCHEMA_IDS }
            .distinct()
        val discoveredCustomNonIncognito = customSchemaIdsAll.filter {
            !it.endsWith(RimeEngineMetadata.INCOGNITO_SUFFIX)
        }

        if (hasDefaultCustom) {
            // Case C: user supplied default.custom.yaml -> keep verbatim.
            return ManagedOverlayOutcome(
                discoveredCustomSchemaIds = discoveredCustomNonIncognito,
                synthesizedDefaultCustom = false,
                hasUserDefaultCustom = true
            )
        }

        val schemasToAppend = if (!hasDefaultYaml) {
            // Case A: no default.yaml and no default.custom.yaml -> append custom schemas onto shared/default.yaml.
            customSchemaIdsAll
        } else {
            // Case B: user supplied default.yaml without default.custom.yaml -> append missing starter
            // schemas (deduped against user's default.yaml schema_list) plus unreferenced custom schemas.
            val existingInDefault = extractDefaultYamlSchemaList(readUtf8StrippingBom(defaultYaml).orEmpty())
            buildList {
                for (starter in RimeEngineMetadata.ALL_BUILT_IN_SCHEMA_IDS) {
                    if (starter !in existingInDefault) {
                        add(starter)
                    }
                }
                for (custom in customSchemaIdsAll) {
                    if (custom !in existingInDefault && custom !in this) {
                        add(custom)
                    }
                }
            }
        }

        if (schemasToAppend.isNotEmpty()) {
            defaultCustom.writeText(buildManagedDefaultCustomYaml(schemasToAppend), Charsets.UTF_8)
        }
        return ManagedOverlayOutcome(
            discoveredCustomSchemaIds = discoveredCustomNonIncognito,
            synthesizedDefaultCustom = schemasToAppend.isNotEmpty(),
            hasUserDefaultCustom = false
        )
    }

    fun guardDefaultYamlConfigVersion(defaultYaml: File) {
        val rawText = defaultYaml.readText(Charsets.UTF_8)
        val withoutBom = rawText.removePrefix(UTF8_BOM)
        val updated = ensureSafeConfigVersion(withoutBom)
        if (updated != rawText) {
            defaultYaml.writeText(updated, Charsets.UTF_8)
        }
    }

    internal fun ensureSafeConfigVersion(content: String): String {
        val clean = content.removePrefix(UTF8_BOM)
        val normalized = clean.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n').toMutableList()
        var versionLineIdx = -1
        var existingValue: String? = null
        for (idx in lines.indices) {
            val line = lines[idx]
            if (line.startsWith("config_version:")) {
                versionLineIdx = idx
                existingValue = stripYamlQuotesAndComment(line.substringAfter("config_version:"))
                break
            }
        }
        if (versionLineIdx >= 0) {
            if (!needsConfigVersionBump(existingValue)) {
                return clean
            }
            lines[versionLineIdx] = "config_version: \"$MIN_SAFE_CONFIG_VERSION\""
            return lines.joinToString("\n")
        }
        val insertIdx = lines.indexOfFirst { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() &&
                !trimmed.startsWith('#') &&
                trimmed != "---" &&
                !trimmed.startsWith("%")
        }.let { if (it < 0) lines.size else it }
        lines.add(insertIdx, "config_version: \"$MIN_SAFE_CONFIG_VERSION\"")
        return lines.joinToString("\n")
    }

    internal fun needsConfigVersionBump(rawVersion: String?): Boolean {
        val value = rawVersion?.trim().orEmpty()
        if (value.isEmpty()) return true
        if (value.contains(CUSTOM_VERSION_MARKER, ignoreCase = true)) return true
        return compareRimeVersion(MIN_SAFE_CONFIG_VERSION, value) > 0
    }

    internal fun compareRimeVersion(left: String, right: String): Int {
        val leftParts = left.trim().split('.')
        val rightParts = right.trim().split('.')
        val maxLen = maxOf(leftParts.size, rightParts.size)
        for (i in 0 until maxLen) {
            val lStr = leftParts.getOrNull(i).orEmpty()
            val rStr = rightParts.getOrNull(i).orEmpty()
            val lNum = lStr.toLongOrNull()
            val rNum = rStr.toLongOrNull()
            val cmp = if (lNum != null && rNum != null) {
                lNum.compareTo(rNum)
            } else {
                lStr.compareTo(rStr)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    internal fun extractDefaultYamlSchemaList(content: String): Set<String> {
        val result = LinkedHashSet<String>()
        var inSchemaList = false
        for (rawLine in content.removePrefix(UTF8_BOM).lineSequence()) {
            val line = rawLine.trimEnd('\r')
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            val isTopLevel = !line[0].isWhitespace()
            if (isTopLevel) {
                inSchemaList = trimmed.startsWith("schema_list:")
                continue
            }
            if (!inSchemaList) continue
            parseSchemaListItem(trimmed)?.let { result.add(it) }
        }
        return result
    }

    internal fun parseSchemaYaml(file: File): ParsedSchemaYaml? {
        if (!file.isFile) return null
        val content = readUtf8StrippingBom(file) ?: return null
        val (schemaId, schemaName) = extractSchemaHeaderFields(content)
        val resolvedId = schemaId?.takeIf { it.isNotEmpty() } ?: return null
        val resolvedName = schemaName?.takeIf { it.isNotEmpty() } ?: resolvedId
        return ParsedSchemaYaml(id = resolvedId, name = resolvedName, fileName = file.name)
    }

    private fun extractSchemaHeaderFields(content: String): Pair<String?, String?> {
        var inSchemaBlock = false
        var schemaId: String? = null
        var schemaName: String? = null
        for (rawLine in content.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            if (!line[0].isWhitespace()) {
                inSchemaBlock = isSchemaBlockHeader(trimmed)
                continue
            }
            if (!inSchemaBlock) continue
            if (trimmed.startsWith("schema_id:")) {
                schemaId = stripYamlQuotesAndComment(trimmed.substringAfter("schema_id:"))
            } else if (trimmed.startsWith("name:")) {
                schemaName = stripYamlQuotesAndComment(trimmed.substringAfter("name:"))
            }
        }
        return schemaId to schemaName
    }

    private fun isSchemaBlockHeader(trimmed: String): Boolean =
        trimmed == "schema:" || trimmed.startsWith("schema: ") || trimmed.startsWith("schema:#")

    fun listTopLevelSchemaFiles(dir: File): List<File> = dir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(SCHEMA_YAML_SUFFIX, ignoreCase = true) }
        ?.sortedBy { it.name }
        .orEmpty()

    internal fun buildManagedDefaultCustomYaml(schemaIds: List<String>): String = buildString {
        appendLine("# Managed by DuoIME Rime importer")
        appendLine("patch:")
        appendLine("  \"schema_list/+\":")
        for (id in schemaIds) {
            appendLine("    - schema: $id")
        }
    }

    private const val UTF8_BOM: String = "\uFEFF"

    private fun readUtf8StrippingBom(file: File): String? =
        runCatching { file.readText(Charsets.UTF_8).removePrefix(UTF8_BOM) }.getOrNull()

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private fun validateBasicYamlSyntax(file: File, mappingPolicy: MappingKeyPolicy): String? {
        val text = readUtf8StrippingBom(file)
            ?: return "Unable to read UTF-8 YAML: ${file.name}"
        if (text.indexOf('\u0000') >= 0) {
            return "Binary NUL byte found in YAML: ${file.name}"
        }
        var sawContentLine = false
        var sawMappingKey = false
        var blockScalarIndent = -1
        var squareDepth = 0
        var curlyDepth = 0
        var openQuote: Char? = null

        for ((idx, rawLine) in text.lineSequence().withIndex()) {
            val lineNum = idx + 1
            val line = rawLine.trimEnd('\r')
            if (openQuote != null) {
                val scan = scanYamlLineSyntax(line, squareDepth, curlyDepth, openQuote)
                if (scan.error != null) {
                    return "${scan.error} (${file.name}:$lineNum)"
                }
                squareDepth = scan.squareDepth
                curlyDepth = scan.curlyDepth
                openQuote = scan.openQuote
                if (scan.sawMappingKey) sawMappingKey = true
                continue
            }
            if (line.isEmpty()) continue
            val indent = countLeadingIndent(line)
            if (indent < 0) {
                return "Tab indentation is invalid in YAML (${file.name}:$lineNum)"
            }
            val trimmed = line.substring(indent).trimEnd()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            if (indent == 0 && (trimmed == "---" || trimmed.startsWith("%"))) continue
            if (indent == 0 && trimmed == "...") break

            sawContentLine = true
            if (blockScalarIndent >= 0) {
                if (indent > blockScalarIndent) continue
                blockScalarIndent = -1
            }

            val scan = scanYamlLineSyntax(trimmed, squareDepth, curlyDepth, startQuote = null)
            if (scan.error != null) {
                return "${scan.error} (${file.name}:$lineNum)"
            }
            squareDepth = scan.squareDepth
            curlyDepth = scan.curlyDepth
            openQuote = scan.openQuote
            if (scan.sawMappingKey) sawMappingKey = true
            if (scan.startsBlockScalar && openQuote == null) blockScalarIndent = indent
        }

        if (openQuote != null) {
            return "Unclosed $openQuote string in ${file.name}"
        }
        if (squareDepth != 0 || curlyDepth != 0) {
            return "Unbalanced YAML flow brackets in ${file.name}"
        }
        val missingRequiredMapping = when (mappingPolicy) {
            MappingKeyPolicy.REQUIRED -> !sawMappingKey
            MappingKeyPolicy.REQUIRED_IF_NON_EMPTY -> sawContentLine && !sawMappingKey
        }
        if (missingRequiredMapping) {
            return "No YAML mapping keys found in ${file.name}"
        }
        return null
    }

    private data class LineScanResult(
        val squareDepth: Int,
        val curlyDepth: Int,
        val openQuote: Char?,
        val sawMappingKey: Boolean,
        val startsBlockScalar: Boolean,
        val error: String? = null
    )

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private fun scanYamlLineSyntax(text: String, startSquare: Int, startCurly: Int, startQuote: Char?): LineScanResult {
        var square = startSquare
        var curly = startCurly
        var quote = startQuote
        var sawMappingKey = false
        var effectiveEnd = text.length

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val prev = if (i > 0) text[i - 1] else ' '
            if (quote != null) {
                if (quote == '"' && ch == '\\' && i + 1 < text.length) {
                    i += 2
                    continue
                }
                if (quote == '\'' && ch == '\'' && i + 1 < text.length && text[i + 1] == '\'') {
                    i += 2
                    continue
                }
                if (ch == quote) {
                    quote = null
                }
                i++
                continue
            }
            if (ch == '#' && (i == 0 || prev.isWhitespace())) {
                effectiveEnd = i
                break
            }
            if ((ch == '"' || ch == '\'') && isTokenStart(prev)) {
                quote = ch
                i++
                continue
            }
            if (ch == ':' && i > 0 && (i + 1 == text.length || text[i + 1].isWhitespace())) {
                sawMappingKey = true
            }
            val inFlow = square > 0 || curly > 0
            if (ch == '[' && (inFlow || isTokenStart(prev)) && prev != '\\') {
                square++
            } else if (ch == ']' && square > 0 && prev != '\\') {
                square--
            } else if (ch == '{' && (inFlow || isTokenStart(prev)) && prev != '\\') {
                curly++
            } else if (ch == '}' && curly > 0 && prev != '\\') {
                curly--
            } else if ((ch == ']' && square == 0 && prev != '\\' && inFlow) ||
                (ch == '}' && curly == 0 && prev != '\\' && inFlow)
            ) {
                return LineScanResult(square, curly, quote, sawMappingKey, false, "Unexpected closing '$ch'")
            }
            i++
        }

        val codePart = text.substring(0, effectiveEnd).trimEnd()
        val startsBlockScalar = quote == null && sawMappingKey &&
            (
                codePart.endsWith(": |") || codePart.endsWith(": |-") || codePart.endsWith(": |+") ||
                    codePart.endsWith(": >") || codePart.endsWith(": >-") || codePart.endsWith(": >+")
                )
        return LineScanResult(square, curly, quote, sawMappingKey, startsBlockScalar)
    }

    private fun countLeadingIndent(line: String): Int {
        var idx = 0
        while (idx < line.length) {
            when (line[idx]) {
                ' ' -> idx++
                '\t' -> return -1
                else -> return idx
            }
        }
        return idx
    }

    private fun isTokenStart(prev: Char): Boolean = prev.isWhitespace() || prev in ":,-[{"

    private fun parseSchemaListItem(trimmed: String): String? {
        if (!trimmed.startsWith('-')) return null
        val afterDash = trimmed.substring(1).trim()
        val unwrapped = if (afterDash.startsWith('{') && afterDash.endsWith('}')) {
            afterDash.substring(1, afterDash.length - 1).trim()
        } else {
            afterDash
        }
        if (!unwrapped.startsWith("schema:")) return null
        return stripYamlQuotesAndComment(unwrapped.substringAfter("schema:"))
            .takeIf { it.isNotEmpty() }
    }

    private fun stripYamlQuotesAndComment(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val first = trimmed[0]
        if (first == '"' || first == '\'') {
            val end = findClosingQuote(trimmed, first)
            if (end > 0) {
                return trimmed.substring(1, end).trim()
            }
        }
        val commentIdx = trimmed.indexOf(" #").takeIf { it >= 0 } ?: trimmed.indexOf("\t#")
        val noComment = if (commentIdx >= 0) trimmed.substring(0, commentIdx) else trimmed
        return noComment.trim().removeSurrounding("\"").removeSurrounding("'").trim()
    }

    private fun findClosingQuote(text: String, quote: Char): Int {
        var i = 1
        while (i < text.length) {
            val ch = text[i]
            if (quote == '"' && ch == '\\' && i + 1 < text.length) {
                i += 2
                continue
            }
            if (quote == '\'' && ch == '\'' && i + 1 < text.length && text[i + 1] == '\'') {
                i += 2
                continue
            }
            if (ch == quote) return i
            i++
        }
        return -1
    }
}

// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.embed

import dev.duoime.core.model.EditorContext
import dev.duoime.core.model.FieldKind
import dev.duoime.core.model.InputConstraints
import dev.duoime.core.model.Language

/**
 * Bounded in-memory LRU cache remembering the last [Language] chosen per unconstrained text host
 * (`languageMemoryKey ?: editorContext.packageName`).
 *
 * Privacy & constraint hygiene:
 * - Never persisted to disk.
 * - Never read or written for incognito, password, `noLearning`, or non-text/constrained fields
 *   (`NUMBER`, `DECIMAL`, `SIGNED_NUMBER`, `PHONE`, `DATETIME`, `URI`, `EMAIL`, `PASSWORD`,
 *   `TERMINAL`).
 * - Ignores null or blank keys.
 */
internal class LanguageMemoryStore(
    private val maxEntries: Int = MAX_ENTRIES
) {
    private val lru = object : LinkedHashMap<String, Language>(maxEntries + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Language>?): Boolean = size > maxEntries
    }

    val size: Int
        get() = lru.size

    fun get(rawKey: String?): Language? {
        val key = normalizeKey(rawKey) ?: return null
        return lru[key]
    }

    fun put(rawKey: String?, language: Language) {
        val key = normalizeKey(rawKey) ?: return
        lru[key] = language
    }

    fun clear() {
        lru.clear()
    }

    companion object {
        const val MAX_ENTRIES: Int = 32

        fun normalizeKey(rawKey: String?): String? = rawKey?.trim()?.takeIf { it.isNotEmpty() }

        fun isEligibleForMemory(constraints: InputConstraints, context: EditorContext): Boolean = !context.incognito &&
            !context.isPassword &&
            !constraints.noLearning &&
            constraints.kind == FieldKind.TEXT &&
            constraints.allowChinese &&
            constraints.forcedPanel == null
    }
}

// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.engine.rime

/**
 * Public identifiers and schema constants for the optional `:engine-rime` module.
 */
public object RimeEngineMetadata {
    public const val ENGINE_ID: String = "engine-rime"
    public const val ASSET_ROOT_PATH: String = "duoime/rime"
    public const val SCHEMA_PINYIN_26: String = "duoime_pinyin_26"
    public const val SCHEMA_T9_PINYIN: String = "duoime_t9"
    public const val SCHEMA_FLYPY: String = "duoime_flypy"
    public const val INCOGNITO_SUFFIX: String = "_incognito"

    public val BUILT_IN_SCHEMA_IDS: Set<String> = linkedSetOf(
        SCHEMA_PINYIN_26,
        SCHEMA_T9_PINYIN,
        SCHEMA_FLYPY
    )

    public val BUILT_IN_INCOGNITO_SCHEMA_IDS: Set<String> = linkedSetOf(
        "$SCHEMA_PINYIN_26$INCOGNITO_SUFFIX",
        "$SCHEMA_T9_PINYIN$INCOGNITO_SUFFIX",
        "$SCHEMA_FLYPY$INCOGNITO_SUFFIX"
    )

    internal val ALL_BUILT_IN_SCHEMA_IDS: Set<String> = BUILT_IN_SCHEMA_IDS + BUILT_IN_INCOGNITO_SCHEMA_IDS
}

/**
 * Metadata describing an installed Rime schema reported by `librime` or workspace disk discovery.
 */
public data class RimeSchemaInfo(
    public val id: String,
    public val name: String,
    public val isBuiltIn: Boolean
)

/**
 * Deployment callback invoked inside [RimeWorkspaceManager] after staging/swapping user files.
 * Returns the deployed non-incognito schema list on success, or `null` if native deploy failed.
 */
public fun interface RimeWorkspaceDeployer {
    public fun deployAndListSchemas(): List<RimeSchemaInfo>?
}

internal fun toRimeSchemaInfos(
    rawSchemas: List<Pair<String, String>>,
    includeIncognito: Boolean
): List<RimeSchemaInfo> {
    val seen = LinkedHashSet<String>()
    val result = ArrayList<RimeSchemaInfo>(rawSchemas.size)
    for ((rawId, rawName) in rawSchemas) {
        val id = rawId.trim()
        if (id.isEmpty() || !seen.add(id)) continue
        if (!includeIncognito && id.endsWith(RimeEngineMetadata.INCOGNITO_SUFFIX)) continue
        result.add(
            RimeSchemaInfo(
                id = id,
                name = rawName.trim().ifEmpty { id },
                isBuiltIn = id in RimeEngineMetadata.ALL_BUILT_IN_SCHEMA_IDS
            )
        )
    }
    return result
}

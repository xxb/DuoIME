// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.engine.lite

import java.io.InputStream

/**
 * Immutable read-only dictionaries bundled inside `:engine-lite` JVM classpath resources.
 */
data class LiteDictionaryBundle(
    val pinyinDictionary: PinyinDictionary,
    val chineseConverter: ChineseConverter,
    val englishLexicon: EnglishLexicon
)

/**
 * ClassLoader resource loader for bundled MIT/Apache-2.0 `:engine-lite` dictionaries.
 * Works identically across JVM tests, `:app`, and `:duoime-embed` without `AssetManager` coupling.
 */
object LiteBundledResources {
    const val CORE_DICT_RESOURCE: String = "duoime/dict/core_dict.tsv"
    const val ENGLISH_WORDS_RESOURCE: String = "duoime/dict/en_words.tsv"
    const val S2T_RESOURCE: String = "duoime/dict/s2t.tsv"

    private val sharedDefaultBundle: LiteDictionaryBundle by lazy { loadFromClasspath() }

    /**
     * Returns the lazily cached immutable dictionary bundle loaded from classpath resources.
     *
     * **Threading:** The first invocation performs blocking classpath I/O and parses ~0.9 MB of
     * dictionary TSVs (~100–300 ms on Android, including initial APK zip scanning). **Never call
     * on the Android main thread** — prewarm on a background dispatcher (such as `Dispatchers.Default`).
     */
    fun defaultBundle(): LiteDictionaryBundle = sharedDefaultBundle

    /**
     * Loads a fresh [LiteDictionaryBundle] from the bundled classpath resources.
     *
     * **Threading:** Performs blocking classpath I/O and dictionary parsing (~100–300 ms on Android).
     * **Never call on the Android main thread.**
     */
    fun loadFromClasspath(classLoader: ClassLoader = defaultClassLoader()): LiteDictionaryBundle {
        val dict = openResource(CORE_DICT_RESOURCE, classLoader).use { PinyinDictionary.fromStream(it) }
        val converter = openResource(S2T_RESOURCE, classLoader).use { ChineseConverter.fromStream(it) }
        val lexicon = openResource(ENGLISH_WORDS_RESOURCE, classLoader).use { EnglishLexicon.fromStream(it) }
        return LiteDictionaryBundle(
            pinyinDictionary = dict,
            chineseConverter = converter,
            englishLexicon = lexicon
        )
    }

    /**
     * Loads a [LiteDictionaryBundle] from caller-provided streams, guaranteeing all provided
     * streams are closed even if an earlier stream fails during parsing.
     */
    fun loadFromStreams(
        coreDictStream: InputStream,
        s2tStream: InputStream,
        englishWordsStream: InputStream? = null
    ): LiteDictionaryBundle = coreDictStream.use { core ->
        s2tStream.use { s2t ->
            loadWithOptionalEnglish(core, s2t, englishWordsStream)
        }
    }

    private fun loadWithOptionalEnglish(
        coreDictStream: InputStream,
        s2tStream: InputStream,
        englishWordsStream: InputStream?
    ): LiteDictionaryBundle = if (englishWordsStream != null) {
        englishWordsStream.use { en -> parseBundle(coreDictStream, s2tStream, en) }
    } else {
        parseBundle(coreDictStream, s2tStream, null)
    }

    private fun parseBundle(
        coreDictStream: InputStream,
        s2tStream: InputStream,
        englishWordsStream: InputStream?
    ): LiteDictionaryBundle = LiteDictionaryBundle(
        pinyinDictionary = PinyinDictionary.fromStream(coreDictStream),
        chineseConverter = ChineseConverter.fromStream(s2tStream),
        englishLexicon = englishWordsStream?.let { EnglishLexicon.fromStream(it) } ?: EnglishLexicon.empty()
    )

    /**
     * Opens a bundled resource from the classpath, throwing [IllegalStateException] if missing.
     */
    fun openResource(resourcePath: String, classLoader: ClassLoader = defaultClassLoader()): InputStream {
        val normalizedPath = resourcePath.removePrefix("/")
        return classLoader.getResourceAsStream(normalizedPath)
            ?: throw IllegalStateException("Missing bundled :engine-lite resource '$normalizedPath' on classpath")
    }

    private fun defaultClassLoader(): ClassLoader =
        LiteBundledResources::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
}

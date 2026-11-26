// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.engine.lite

import dev.duoime.core.engine.EngineKeyEvent
import dev.duoime.core.engine.InputEngine
import dev.duoime.core.layout.KeyCodes
import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.InputMode
import dev.duoime.core.model.KeyProcessResult
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteInputEngineContractTest {

    private fun typeChars(engine: InputEngine, text: String) {
        for (ch in text) {
            engine.onKey(EngineKeyEvent.fromChar(ch))
        }
    }

    @Test
    fun bundledResourcesLoadCleanlyViaClasspathAndStreams() {
        val bundle = LiteBundledResources.defaultBundle()
        assertTrue(bundle.pinyinDictionary.entryCount > 30_000)
        assertTrue(bundle.englishLexicon.entryCount > 3_000)
        assertEquals("終端", bundle.chineseConverter.toTraditional("终端"))

        val custom = LiteBundledResources.loadFromStreams(
            coreDictStream = "ni hao\t你好\t1000\n".byteInputStream(),
            s2tStream = "终\t終\n".byteInputStream(),
            englishWordsStream = "duo\t1000\n".byteInputStream()
        )
        assertEquals(1, custom.pinyinDictionary.entryCount)
        assertEquals(1, custom.englishLexicon.entryCount)
        assertEquals("終", custom.chineseConverter.toTraditional("终"))
    }

    @Test
    fun stateFlowTracks26KeyPinyinPartialCommitThenRemainingComposition() {
        val engine: InputEngine = LiteInputEngine.createDefault()
        assertEquals(InputMode.PINYIN_26, engine.state.value.mode)
        assertFalse(engine.state.value.isComposing)

        typeChars(engine, "nihaoshijie")
        val composingSnap = engine.state.value
        assertTrue(composingSnap.isComposing)
        assertTrue(composingSnap.preedit.isNotEmpty())
        assertEquals(composingSnap.preedit, composingSnap.composingText)
        assertTrue(composingSnap.candidates.isNotEmpty())

        // Pick partial candidate "你好" on page 0 -> returns CommitText AND keeps remaining composition active
        val partialIdx = composingSnap.candidates.indexOfFirst { it.text == "你好" }
        assertTrue("Expected partial candidate 你好 on page 0, got ${composingSnap.candidates}", partialIdx >= 0)
        val firstResult = engine.selectCandidate(partialIdx)
        assertEquals(KeyProcessResult.CommitText("你好"), firstResult)

        val remainingSnap = engine.state.value
        assertTrue("Remaining pinyin must still be composing after partial commit", remainingSnap.isComposing)
        assertEquals("shi'jie", remainingSnap.preedit)
        assertEquals("世界", remainingSnap.candidates.first().text)

        val secondResult = engine.onKey(EngineKeyEvent(KeyCodes.KEYSYM_SPACE))
        assertEquals(KeyProcessResult.CommitText("世界"), secondResult)
        assertFalse(engine.state.value.isComposing)
        assertEquals("", engine.state.value.preedit)
        assertEquals("", engine.state.value.composingText)
    }

    @Test
    fun supportsT9XiaoheEnglishSchemasAndUnknownSchemaRejection() {
        val engine = LiteInputEngine.createDefault()

        // T9 schema
        assertTrue(engine.selectSchema(LiteInputEngine.SCHEMA_T9_PINYIN))
        assertEquals(InputMode.PINYIN_T9, engine.state.value.mode)
        assertEquals(LiteInputEngine.SCHEMA_T9_PINYIN, engine.state.value.schemaId)
        typeChars(engine, "64426")
        assertEquals("ni'hao", engine.state.value.preedit)
        assertEquals("你好", engine.state.value.candidates.first().text)
        assertTrue(engine.state.value.syllables.isNotEmpty())
        assertEquals(KeyProcessResult.CommitText("你好"), engine.onKey(EngineKeyEvent(KeyCodes.KEYSYM_SPACE)))

        // Xiaohe double pinyin schema
        assertTrue(engine.selectSchema(LiteInputEngine.SCHEMA_XIAOHE))
        assertEquals(InputMode.PINYIN_26, engine.state.value.mode)
        assertEquals(LiteInputEngine.SCHEMA_XIAOHE, engine.state.value.schemaId)
        typeChars(engine, "ddma")
        assertEquals("代码", engine.state.value.candidates.first().text)
        engine.reset()
        assertFalse(engine.state.value.isComposing)

        // English schema passes through directly
        assertTrue(engine.selectSchema(LiteInputEngine.SCHEMA_ENGLISH))
        assertEquals(InputMode.ENGLISH, engine.state.value.mode)
        assertEquals(KeyProcessResult.PassThrough, engine.onKey(EngineKeyEvent.fromChar('a')))
        assertFalse(engine.state.value.isComposing)

        // Unknown schema returns false and keeps current schema intact
        assertFalse(engine.selectSchema("unknown_schema"))
        assertEquals(LiteInputEngine.SCHEMA_ENGLISH, engine.state.value.schemaId)
    }

    @Test
    fun supportsTraditionalizationAsciiModePagingSelectionAndReset() {
        val engine: InputEngine = LiteInputEngine.createDefault()

        // Traditional toggle via setOption("traditionalization")
        engine.setOption(LiteInputEngine.OPTION_TRADITIONALIZATION, true)
        assertEquals(ChineseVariant.TRADITIONAL, engine.state.value.variant)
        typeChars(engine, "zhongduan")
        assertEquals("終端", engine.state.value.candidates.first().text)

        engine.setOption(LiteInputEngine.OPTION_TRADITIONALIZATION, false)
        assertEquals(ChineseVariant.SIMPLIFIED, engine.state.value.variant)
        assertEquals("终端", engine.state.value.candidates.first().text)
        engine.reset()

        // Paging + page-relative selectCandidate(indexOnPage)
        typeChars(engine, "shi")
        val page0First = engine.state.value.candidates.first().text
        assertTrue(engine.state.value.hasNextPage)
        assertFalse(engine.state.value.hasPrevPage)

        assertTrue(engine.page(forward = true))
        assertEquals(1, engine.state.value.pageIndex)
        assertTrue(engine.state.value.hasPrevPage)
        val page1Second = engine.state.value.candidates[1].text
        assertNotEquals(page0First, page1Second)
        assertEquals(KeyProcessResult.CommitText(page1Second), engine.selectCandidate(1))
        assertFalse(engine.state.value.isComposing)

        // Out-of-bounds candidate index returns PassThrough safely
        assertEquals(KeyProcessResult.PassThrough, engine.selectCandidate(99))

        // ascii_mode option toggles English on/off while remembering prior Chinese schema
        engine.setOption(LiteInputEngine.OPTION_ASCII_MODE, true)
        assertEquals(InputMode.ENGLISH, engine.state.value.mode)
        engine.setOption(LiteInputEngine.OPTION_ASCII_MODE, false)
        assertEquals(InputMode.PINYIN_26, engine.state.value.mode)
    }

    @Test
    fun incognitoOptionSuppressesUserLearningPersistence() {
        val store = UserLearningStore()
        val engine = LiteInputEngine.createDefault(learningStore = store)

        engine.setOption(LiteInputEngine.OPTION_INCOGNITO, true)
        typeChars(engine, "nihao")
        assertEquals(KeyProcessResult.CommitText("你好"), engine.onKey(EngineKeyEvent(KeyCodes.KEYSYM_SPACE)))
        assertEquals(0L, store.getBoost("ni hao", "你好"))

        engine.setOption(LiteInputEngine.OPTION_INCOGNITO, false)
        typeChars(engine, "nihao")
        assertEquals(KeyProcessResult.CommitText("你好"), engine.onKey(EngineKeyEvent(KeyCodes.KEYSYM_SPACE)))
        assertTrue(store.getBoost("ni hao", "你好") > 0L)
    }

    @Test
    fun selectSyllableLocksAndClearsT9SyllablesViaCoreInterface() {
        val engine: InputEngine = LiteInputEngine.createDefault(initialSchemaId = LiteInputEngine.SCHEMA_T9_PINYIN)
        typeChars(engine, "326426")
        val initialOptions = engine.state.value.syllables
        val danIdx = initialOptions.indexOf("dan")
        assertTrue("Expected 'dan' in T9 syllables $initialOptions", danIdx >= 0)

        assertTrue(engine.selectSyllable(danIdx))
        assertTrue(engine.state.value.preedit.startsWith("dan'"))
        assertTrue("蛋糕" in engine.state.value.candidates.map { it.text })

        // Out-of-bounds syllable index returns false without altering state
        assertFalse(engine.selectSyllable(99))
        assertTrue(engine.state.value.preedit.startsWith("dan'"))

        // Passing -1 clears active syllable locks
        assertTrue(engine.selectSyllable(-1))
        assertTrue("fang" in engine.state.value.syllables)
        assertFalse(engine.selectSyllable(-1))
    }

    @Test
    fun userLearningStoreRejectsRelativeFileWithoutParentDirectory() {
        assertThrows(IllegalArgumentException::class.java) {
            UserLearningStore(File("orphan_learning.json"))
        }
    }

    @Test
    fun loadFromStreamsClosesAllStreamsWhenSubsequentStreamFails() {
        var firstClosed = false
        var secondClosed = false
        var thirdClosed = false

        val firstStream = object : ByteArrayInputStream("ni hao\t你好\t1000\n".toByteArray()) {
            override fun close() {
                firstClosed = true
                super.close()
            }
        }
        val failingSecond = object : InputStream() {
            override fun read(): Int = throw IOException("Simulated S2T stream failure")

            override fun close() {
                secondClosed = true
                super.close()
            }
        }
        val thirdStream = object : ByteArrayInputStream("duo\t1000\n".toByteArray()) {
            override fun close() {
                thirdClosed = true
                super.close()
            }
        }

        assertThrows(IOException::class.java) {
            LiteBundledResources.loadFromStreams(
                coreDictStream = firstStream,
                s2tStream = failingSecond,
                englishWordsStream = thirdStream
            )
        }
        assertTrue("First stream must be closed on failure", firstClosed)
        assertTrue("Failing second stream must be closed", secondClosed)
        assertTrue("Third stream must be closed on failure", thirdClosed)
    }

    @Test
    fun modifierMasksReleaseEventsNulAndIsoControlsAreHandledSafely() {
        val engine: InputEngine = LiteInputEngine.createDefault()

        // NUL, label-only (NO_SYMBOL), and ISO control keysyms pass through without committing or composing
        assertEquals(KeyProcessResult.PassThrough, engine.onKey(EngineKeyEvent.fromChar('\u0000')))
        assertEquals(
            KeyProcessResult.PassThrough,
            engine.onKey(EngineKeyEvent(keysym = KeyCodes.KEYSYM_NO_SYMBOL, label = "a"))
        )
        assertEquals(KeyProcessResult.PassThrough, engine.onKey(EngineKeyEvent(keysym = 0x0100_0001)))
        assertEquals(KeyProcessResult.PassThrough, engine.onKey(EngineKeyEvent(keysym = 0x0100_007f)))
        assertFalse(engine.state.value.isComposing)

        // SHIFT and LOCK masks are ignored (normal processing continues)
        assertEquals(
            KeyProcessResult.Consumed,
            engine.onKey(EngineKeyEvent(keysym = KeyCodes.keysymForChar('n'), mask = KeyCodes.MASK_SHIFT))
        )
        assertEquals(
            KeyProcessResult.Consumed,
            engine.onKey(EngineKeyEvent(keysym = KeyCodes.keysymForChar('i'), mask = KeyCodes.MASK_LOCK))
        )
        assertTrue(engine.state.value.isComposing)
        val preeditBeforeChords = engine.state.value.preedit

        // RELEASE events are no-op Consumed and never mutate state or double-commit
        assertEquals(
            KeyProcessResult.Consumed,
            engine.onKey(EngineKeyEvent(keysym = KeyCodes.KEYSYM_SPACE, mask = KeyCodes.MASK_RELEASE))
        )
        assertEquals(preeditBeforeChords, engine.state.value.preedit)

        // CONTROL / ALT / SUPER chords pass through for letters AND special keys without mutating preedit
        val chordMasks = listOf(KeyCodes.MASK_CONTROL, KeyCodes.MASK_ALT, KeyCodes.MASK_SUPER)
        val keys = listOf(
            KeyCodes.keysymForChar('c'),
            KeyCodes.KEYSYM_BACKSPACE,
            KeyCodes.KEYSYM_SPACE,
            KeyCodes.KEYSYM_RETURN,
            KeyCodes.KEYSYM_ESCAPE
        )
        for (mask in chordMasks) {
            for (keysym in keys) {
                assertEquals(
                    "Expected PassThrough for mask=$mask keysym=$keysym",
                    KeyProcessResult.PassThrough,
                    engine.onKey(EngineKeyEvent(keysym = keysym, mask = mask))
                )
            }
        }
        assertEquals(preeditBeforeChords, engine.state.value.preedit)
    }
}

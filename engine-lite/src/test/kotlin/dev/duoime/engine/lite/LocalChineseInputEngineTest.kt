/*
 * SPDX-License-Identifier: MIT
 * MIT License
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME by DuoIME Contributors (2026).
 */
package dev.duoime.engine.lite

import dev.duoime.core.model.ChineseVariant
import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class LocalChineseInputEngineTest {

    companion object {
        private lateinit var dictionary: PinyinDictionary
        private lateinit var converter: ChineseConverter
        private lateinit var englishLexicon: EnglishLexicon

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            val bundle = LiteBundledResources.defaultBundle()
            dictionary = bundle.pinyinDictionary
            converter = bundle.chineseConverter
            englishLexicon = bundle.englishLexicon
            assertTrue("Dictionary should contain >30,000 entries", dictionary.entryCount > 30000)
        }
    }

    private fun typeString(engine: LiteLocalEngine, input: String) {
        for (ch in input) {
            engine.onCharacter(ch)
        }
    }

    @Test
    fun `quanpin nihao and daima rank expected top candidates`() {
        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.QUANPIN
        )

        typeString(engine, "nihao")
        assertEquals("ni'hao", engine.snapshot.formattedPreEdit)
        assertTrue(engine.snapshot.candidates.isNotEmpty())
        assertEquals("你好", engine.snapshot.candidates.first().text)

        val commit = engine.onSpace()
        assertEquals(LiteKeyResult.CommitText("你好"), commit)
        assertEquals("", engine.snapshot.rawBuffer)

        typeString(engine, "daima")
        assertEquals("代码", engine.snapshot.candidates.first().text)
    }

    @Test
    fun `quanpin acronym shorthand nh matches nihao`() {
        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.QUANPIN
        )

        typeString(engine, "nh")
        val top5 = engine.snapshot.candidates.take(5).map { it.text }
        assertTrue("Expected 你好 in top 5 shorthand candidates, got $top5", "你好" in top5)
    }

    @Test
    fun `xiaohe shuangpin decodes nihc ulpb ddma and zero initials accurately`() {
        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.XIAOHE_SHUANGPIN
        )

        typeString(engine, "nihc")
        assertEquals("ni'hao", engine.snapshot.formattedPreEdit)
        assertEquals("你好", engine.snapshot.candidates.first().text)
        engine.clearPreEdit()

        typeString(engine, "ulpb")
        assertEquals("shuang'pin", engine.snapshot.formattedPreEdit)
        assertEquals("双拼", engine.snapshot.candidates.first().text)
        engine.clearPreEdit()

        typeString(engine, "ddma")
        assertEquals("dai'ma", engine.snapshot.formattedPreEdit)
        assertEquals("代码", engine.snapshot.candidates.first().text)
        engine.clearPreEdit()

        typeString(engine, "nv")
        assertTrue("女" in engine.snapshot.candidates.take(5).map { it.text })
        engine.clearPreEdit()

        typeString(engine, "xt")
        assertEquals("xue", engine.snapshot.formattedPreEdit)
        assertEquals("学", engine.snapshot.candidates.first().text)
    }

    @Test
    fun `viterbi sentence composer assembles full multi-word developer sentence`() {
        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.QUANPIN
        )

        typeString(engine, "qingbangwoxiufudaima")
        val first = engine.snapshot.candidates.firstOrNull()
        assertNotNull(first)
        assertEquals("请帮我修复代码", first!!.text)

        engine.setMode(LiteSchemaMode.XIAOHE_SHUANGPIN)
        typeString(engine, "qkbhwoxqfuddma")
        assertEquals("请帮我修复代码", engine.snapshot.candidates.first().text)
    }

    @Test
    fun `traditional chinese variant converts candidates seamlessly`() {
        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.QUANPIN,
            initialVariant = ChineseVariant.TRADITIONAL
        )

        typeString(engine, "zhongduan")
        assertEquals("終端", engine.snapshot.candidates.first().text)
        engine.clearPreEdit()

        typeString(engine, "qingbangwoxiufudaima")
        assertEquals("請幫我修復代碼", engine.snapshot.candidates.first().text)
    }

    @Test
    fun `partial prefix candidate selection retains remaining pre-edit and learns full phrase`() {
        val tempFile = File.createTempFile("duoime_learn_", ".json").apply { delete() }
        try {
            val store = UserLearningStore(tempFile)
            val engine = LocalChineseInputEngine(
                dictionary = dictionary,
                converter = converter,
                learningStore = store,
                initialMode = LiteSchemaMode.QUANPIN
            )

            typeString(engine, "nihaoshijie")
            val prefixIndex = engine.snapshot.candidates.indexOfFirst { it.simplifiedText == "你好" }
            assertTrue("Expected prefix candidate 你好", prefixIndex >= 0)

            val firstCommit = engine.selectCandidate(prefixIndex)
            assertEquals(LiteKeyResult.CommitText("你好"), firstCommit)
            assertEquals("shijie", engine.snapshot.rawBuffer)

            val secondCommit = engine.onSpace()
            assertEquals(LiteKeyResult.CommitText("世界"), secondCommit)
            assertEquals("", engine.snapshot.rawBuffer)

            val reloaded = UserLearningStore(tempFile)
            assertTrue(reloaded.getBoost("ni hao shi jie", "你好世界") > 0L)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `default 1-row 5-slot pagination supports digits 1 to 5 and minus-equals page turning`() {
        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.QUANPIN
        )

        typeString(engine, "shi")
        assertTrue(engine.snapshot.candidates.size >= 20)
        assertEquals(1, engine.snapshot.windowRows)
        assertEquals(5, engine.snapshot.pageSize)
        assertEquals(0, engine.snapshot.candidatePage)
        assertEquals(5, engine.snapshot.pageCandidates.size)
        assertTrue(engine.snapshot.hasNextPage)

        for (ignoredDigit in listOf('6', '7', '8', '9', '0')) {
            assertEquals(LiteKeyResult.Consumed, engine.onCharacter(ignoredDigit))
            assertEquals("shi", engine.snapshot.rawBuffer)
        }

        val expectedFifth = engine.snapshot.candidates[4].text
        assertEquals(LiteKeyResult.CommitText(expectedFifth), engine.onCharacter('5'))
        assertEquals("", engine.snapshot.rawBuffer)

        typeString(engine, "shi")
        val pageOneFirst = engine.snapshot.candidates[5].text
        val pageOneFifth = engine.snapshot.candidates[9].text

        assertEquals(LiteKeyResult.Consumed, engine.onCharacter('='))
        assertEquals(1, engine.snapshot.candidatePage)
        assertEquals(pageOneFirst, engine.snapshot.pageCandidates.first().text)

        assertEquals(LiteKeyResult.Consumed, engine.onCharacter('-'))
        assertEquals(0, engine.snapshot.candidatePage)
        assertEquals(LiteKeyResult.Consumed, engine.onCharacter('='))
        assertEquals(1, engine.snapshot.candidatePage)
        assertEquals(LiteKeyResult.CommitText(pageOneFifth), engine.onCharacter('5'))

        typeString(engine, "shi")
        engine.nextCandidatePage()
        assertEquals(1, engine.snapshot.candidatePage)
        assertEquals(LiteKeyResult.CommitText(pageOneFirst), engine.onSpace())
    }

    @Test
    fun `2D arrow candidate highlight moves horizontally and vertically by 5 columns across pages`() {
        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.QUANPIN
        )

        typeString(engine, "shi")
        assertEquals(0, engine.snapshot.highlightedIndex)

        assertTrue(engine.moveCandidateHighlightHorizontal(forward = true))
        assertTrue(engine.moveCandidateHighlightHorizontal(forward = true))
        assertEquals(2, engine.snapshot.highlightedIndex)
        assertEquals(0, engine.snapshot.candidatePage)

        assertTrue(engine.moveCandidateHighlightVertical(down = true))
        assertEquals(7, engine.snapshot.highlightedIndex)
        assertEquals(1, engine.snapshot.candidatePage)

        assertTrue(engine.moveCandidateHighlightVertical(down = true))
        assertEquals(12, engine.snapshot.highlightedIndex)
        assertEquals(2, engine.snapshot.candidatePage)

        assertTrue(engine.moveCandidateHighlightVertical(down = false))
        assertEquals(7, engine.snapshot.highlightedIndex)
        assertEquals(1, engine.snapshot.candidatePage)

        val expectedSeventh = engine.snapshot.candidates[7].text
        assertEquals(LiteKeyResult.CommitText(expectedSeventh), engine.onCharacter('3'))

        typeString(engine, "shi")
        assertTrue(engine.moveCandidateHighlightHorizontal(forward = true))
        assertTrue(engine.moveCandidateHighlightHorizontal(forward = true))
        assertTrue(engine.moveCandidateHighlightVertical(down = true))
        assertEquals(LiteKeyResult.CommitText(expectedSeventh), engine.onSpace())
    }

    @Test
    fun `one-shot 4-row expansion pages by 20 candidates and auto-collapses on commit clear mode or typing`() {
        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.QUANPIN
        )

        typeString(engine, "shi")
        assertTrue(engine.setCandidateWindowRows(4))
        assertEquals(4, engine.snapshot.windowRows)
        assertEquals(20, engine.snapshot.pageSize)
        assertTrue(engine.snapshot.isExpandedWindow)
        assertEquals(20, engine.snapshot.pageCandidates.size)

        assertTrue(engine.nextCandidatePage())
        assertEquals(1, engine.snapshot.candidatePage)
        assertEquals(20, engine.snapshot.highlightedIndex)

        assertNotNull(engine.selectCandidate(21))
        assertEquals(1, engine.snapshot.windowRows)
        assertEquals(5, engine.snapshot.pageSize)

        typeString(engine, "ni")
        assertTrue(engine.setCandidateWindowRows(4))
        assertEquals(LiteKeyResult.Consumed, engine.onCharacter('h'))
        assertEquals(1, engine.snapshot.windowRows)

        assertTrue(engine.setCandidateWindowRows(4))
        engine.clearPreEdit()
        assertEquals(1, engine.snapshot.windowRows)

        assertEquals(LiteKeyResult.Consumed, engine.onCharacter('a'))
        assertTrue(engine.setCandidateWindowRows(4))
        assertTrue(engine.onBackspace())
        assertEquals("", engine.snapshot.rawBuffer)
        assertEquals(1, engine.snapshot.windowRows)

        typeString(engine, "hao")
        assertTrue(engine.setCandidateWindowRows(4))
        engine.setMode(LiteSchemaMode.T9_PINYIN)
        assertEquals(1, engine.snapshot.windowRows)

        typeString(engine, "64")
        assertTrue(engine.setCandidateWindowRows(4))
        assertEquals(LiteKeyResult.Consumed, engine.onCharacter('4'))
        assertEquals(1, engine.snapshot.windowRows)

        val quanpinMulti = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.QUANPIN
        )
        typeString(quanpinMulti, "nihao")
        assertTrue(quanpinMulti.setCandidateWindowRows(4))
        val singleCharIdx = quanpinMulti.snapshot.candidates.indexOfFirst { it.consumedRawLength < "nihao".length }
        assertTrue(singleCharIdx >= 0)
        assertNotNull(quanpinMulti.selectCandidate(singleCharIdx))
        assertTrue(quanpinMulti.snapshot.hasPreEdit)
        assertEquals(4, quanpinMulti.snapshot.windowRows)
        assertNotNull(quanpinMulti.selectCandidate(0))
        assertFalse(quanpinMulti.snapshot.hasPreEdit)
        assertEquals(1, quanpinMulti.snapshot.windowRows)
    }

    @Test
    fun `compact single-row paging steps losslessly by 5-candidate sub-pages across equals and minus`() {
        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.QUANPIN,
            initialCompactSingleRowPaging = true
        )

        typeString(engine, "shi")
        assertTrue(engine.snapshot.candidates.size >= 20)
        assertEquals(LiteKeyResult.Consumed, engine.onCharacter('='))
        assertEquals(1, engine.snapshot.candidatePage)
        assertEquals(5, engine.snapshot.highlightedIndex)
        assertEquals(LiteKeyResult.Consumed, engine.onCharacter('='))
        assertEquals(2, engine.snapshot.candidatePage)
        assertEquals(10, engine.snapshot.highlightedIndex)

        assertEquals(LiteKeyResult.Consumed, engine.onCharacter('-'))
        assertEquals(1, engine.snapshot.candidatePage)
        val expectedSixth = engine.snapshot.candidates[5].text
        assertEquals(LiteKeyResult.CommitText(expectedSixth), engine.onSpace())

        typeString(engine, "shi")
        engine.setCandidateWindowRows(4)
        assertTrue(engine.nextCandidatePage())
        assertEquals(1, engine.snapshot.candidatePage)
        assertEquals(20, engine.snapshot.highlightedIndex)
    }

    @Test
    fun `t9 pinyin decodes words ambiguous splits developer sentences and traditional variant`() {
        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.T9_PINYIN
        )

        typeString(engine, "64426")
        assertEquals("ni'hao", engine.snapshot.formattedPreEdit)
        assertEquals("你好", engine.snapshot.candidates.first().text)
        assertEquals(LiteKeyResult.CommitText("你好"), engine.onCharacter('0'))
        assertEquals("", engine.snapshot.rawBuffer)

        typeString(engine, "32462")
        assertEquals("代码", engine.snapshot.candidates.first().text)
        assertEquals(LiteKeyResult.CommitText("代码"), engine.onEnter())
        assertEquals("", engine.snapshot.rawBuffer)

        typeString(engine, "326426")
        val cand326426 = engine.snapshot.candidates.map { it.text }
        assertTrue("方案" in cand326426)
        assertTrue("蛋糕" in cand326426)
        engine.clearPreEdit()

        typeString(engine, "94126")
        assertEquals("94'26", engine.snapshot.rawBuffer)
        assertTrue("西安" in engine.snapshot.candidates.take(3).map { it.text })
        engine.clearPreEdit()

        typeString(engine, "74642264969483832462")
        assertEquals("请帮我修复代码", engine.snapshot.candidates.first().text)
        assertEquals("qing'bang'wo'xiu'fu'dai'ma", engine.snapshot.formattedPreEdit)

        engine.setChineseVariant(ChineseVariant.TRADITIONAL)
        assertEquals("請幫我修復代碼", engine.snapshot.candidates.first().text)
    }

    @Test
    fun `t9 syllable filter chips narrow ambiguous digits and user learning stores canonical pinyin`() {
        val tempFile = File.createTempFile("duoime_t9_learn_", ".json").apply { delete() }
        try {
            val store = UserLearningStore(tempFile)
            val engine = LocalChineseInputEngine(
                dictionary = dictionary,
                converter = converter,
                learningStore = store,
                initialMode = LiteSchemaMode.T9_PINYIN
            )

            typeString(engine, "4664")
            val chips = engine.snapshot.t9SyllableOptions
            assertTrue("gong" in chips && "hong" in chips)

            assertTrue(engine.selectSyllableFilter("hong"))
            assertEquals("hong", engine.snapshot.activeSyllableFilter)
            assertTrue(engine.snapshot.candidates.all { it.pinyin.startsWith("hong") })
            assertTrue("红" in engine.snapshot.candidates.take(5).map { it.text })

            assertTrue(engine.selectSyllableFilter("hong"))
            assertEquals(null, engine.snapshot.activeSyllableFilter)
            engine.clearPreEdit()

            typeString(engine, "326426")
            assertTrue("dan" in engine.snapshot.t9SyllableOptions)
            assertTrue("fang" in engine.snapshot.t9SyllableOptions)
            assertTrue(engine.selectSyllableFilter("dan"))
            assertTrue(engine.snapshot.formattedPreEdit.startsWith("dan'"))
            assertTrue("蛋糕" in engine.snapshot.candidates.take(3).map { it.text })
            engine.clearPreEdit()

            typeString(engine, "64426744543")
            val prefixIdx = engine.snapshot.candidates.indexOfFirst { it.simplifiedText == "你好" }
            assertTrue(prefixIdx >= 0)
            assertEquals(LiteKeyResult.CommitText("你好"), engine.selectCandidate(prefixIdx))
            assertEquals("744543", engine.snapshot.rawBuffer)
            assertEquals(LiteKeyResult.CommitText("世界"), engine.onSpace())

            val reloaded = UserLearningStore(tempFile)
            assertTrue(reloaded.getBoost("ni hao shi jie", "你好世界") > 0L)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `quanpin english math and greek symbol aliases support exact priority, prefix restraint, highlight, backspace, and traditional invariance`() {
        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.QUANPIN
        )

        typeString(engine, "alpha")
        assertEquals("α", engine.snapshot.candidates.first().text)
        assertEquals(LiteKeyResult.CommitText("α"), engine.onSpace())

        typeString(engine, "lambda")
        assertEquals("λ", engine.snapshot.candidates.first().text)
        assertEquals(LiteKeyResult.CommitText("λ"), engine.onSpace())

        typeString(engine, "inf")
        assertEquals("∞", engine.snapshot.candidates.first().text)
        assertEquals(LiteKeyResult.CommitText("∞"), engine.onSpace())

        typeString(engine, "delta")
        assertEquals("δ", engine.snapshot.candidates[0].text)
        assertEquals("Δ", engine.snapshot.candidates[1].text)
        assertTrue(engine.moveCandidateHighlightHorizontal(forward = true))
        assertEquals(LiteKeyResult.CommitText("Δ"), engine.onSpace())

        engine.onCharacter('a')
        val aTexts = engine.snapshot.candidates.map { it.text }
        assertTrue("啊" == aTexts.first() || "阿" == aTexts.first())
        assertTrue("α" in aTexts)
        typeString(engine, "lph")
        assertTrue("α" in engine.snapshot.candidates.take(3).map { it.text })
        assertTrue(engine.onBackspace())
        assertTrue(engine.onBackspace())
        assertTrue(engine.onBackspace())
        assertEquals("a", engine.snapshot.rawBuffer)
        assertTrue("啊" == engine.snapshot.candidates.first().text || "阿" == engine.snapshot.candidates.first().text)
        engine.clearPreEdit()

        engine.setChineseVariant(ChineseVariant.TRADITIONAL)
        typeString(engine, "alpha")
        assertEquals("α", engine.snapshot.candidates.first().text)
        assertEquals(LiteKeyResult.CommitText("α"), engine.onSpace())
    }

    @Test
    fun `core dict math and greek entries validate syllables and resolve across quanpin, xiaohe, t9, and traditional`() {
        val dictLines = LiteBundledResources.openResource(LiteBundledResources.CORE_DICT_RESOURCE).use { stream ->
            stream.bufferedReader().readLines()
        }
        val symbolEntries = dictLines.dropWhile { !it.startsWith("a er fa\tα") }
        assertTrue(symbolEntries.size >= 45)
        for (line in symbolEntries) {
            val parts = line.split('\t')
            assertEquals(3, parts.size)
            for (syl in parts[0].split(' ')) {
                assertTrue("Invalid syllable '$syl' in entry: $line", syl in PinyinTokenizer.VALID_SYLLABLES)
            }
        }

        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.QUANPIN
        )

        typeString(engine, "aerfa")
        assertEquals("α", engine.snapshot.candidates.first().text)
        assertEquals(LiteKeyResult.CommitText("α"), engine.onSpace())

        typeString(engine, "budengyu")
        assertEquals("≠", engine.snapshot.candidates.first().text)
        assertEquals(LiteKeyResult.CommitText("≠"), engine.onSpace())

        engine.setMode(LiteSchemaMode.XIAOHE_SHUANGPIN)
        typeString(engine, "aaerfa")
        assertEquals("α", engine.snapshot.candidates.first().text)
        assertEquals(LiteKeyResult.CommitText("α"), engine.onSpace())

        typeString(engine, "qqhe")
        val sumIdx = engine.snapshot.candidates.indexOfFirst { it.text == "∑" }
        assertTrue(sumIdx in 0..4)
        assertEquals(LiteKeyResult.CommitText("∑"), engine.selectCandidate(sumIdx))

        engine.setMode(LiteSchemaMode.T9_PINYIN)
        typeString(engine, "23732")
        val alphaIdx = engine.snapshot.candidates.indexOfFirst { it.text == "α" }
        assertTrue(alphaIdx in 0..4)
        assertEquals(LiteKeyResult.CommitText("α"), engine.selectCandidate(alphaIdx))

        engine.setChineseVariant(ChineseVariant.TRADITIONAL)
        engine.setMode(LiteSchemaMode.QUANPIN)
        typeString(engine, "xigema")
        assertEquals("σ", engine.snapshot.candidates.first().text)
        assertEquals(LiteKeyResult.CommitText("σ"), engine.onSpace())
    }

    @Test
    fun symbolAliasSelectionAfterPartialChineseCommitDoesNotPolluteUserLearning() {
        val learningStore = UserLearningStore()
        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.QUANPIN,
            learningStore = learningStore
        )

        typeString(engine, "ni'alpha")
        val niIdx = engine.snapshot.candidates.indexOfFirst { it.text == "你" }
        assertTrue(niIdx >= 0)
        assertEquals(LiteKeyResult.CommitText("你"), engine.selectCandidate(niIdx))
        assertEquals("alpha", engine.snapshot.rawBuffer)

        val exactAlias = engine.snapshot.candidates.first()
        assertEquals("α", exactAlias.text)
        assertTrue(exactAlias.skipUserLearning)
        assertEquals(LiteKeyResult.CommitText("α"), engine.selectCandidate(0))
        assertTrue(learningStore.entriesForPinyin("ni").none { it.word.contains("α") })
        assertTrue(learningStore.entriesForPinyin("").isEmpty())
    }

    @Test
    fun `t9 progressive locking narrows multi-syllable dan gao and unlocks cleanly by index`() {
        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.T9_PINYIN
        )

        typeString(engine, "326426")
        assertTrue(engine.selectSyllableFilter("dan"))
        assertEquals(listOf("dan"), engine.snapshot.lockedT9Syllables)
        assertTrue("gao" in engine.snapshot.t9SyllableOptions)

        assertTrue(engine.selectSyllableFilter("gao"))
        assertEquals(listOf("dan", "gao"), engine.snapshot.lockedT9Syllables)
        assertEquals("dan'gao", engine.snapshot.formattedPreEdit)
        assertEquals("蛋糕", engine.snapshot.candidates.first().text)

        assertFalse(engine.unlockT9SyllablesFrom(-1))
        assertFalse(engine.unlockT9SyllablesFrom(2))
        assertTrue(engine.unlockT9SyllablesFrom(1))
        assertEquals(listOf("dan"), engine.snapshot.lockedT9Syllables)
        assertTrue(engine.unlockT9SyllablesFrom(0))
        assertEquals(emptyList<String>(), engine.snapshot.lockedT9Syllables)
    }

    @Test
    fun `t9 progressive locking supports 3-plus syllable words and reduplicated syllables`() {
        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.T9_PINYIN
        )

        typeString(engine, "7464226496")
        assertTrue(engine.selectSyllableFilter("qing"))
        assertTrue(engine.selectSyllableFilter("bang"))
        assertTrue(engine.selectSyllableFilter("wo"))
        assertEquals("qing'bang'wo", engine.snapshot.formattedPreEdit)
        assertEquals("请帮我", engine.snapshot.candidates.first().text)
        assertEquals(LiteKeyResult.CommitText("请帮我"), engine.onSpace())

        typeString(engine, "6262")
        assertTrue(engine.selectSyllableFilter("ma"))
        assertTrue(engine.selectSyllableFilter("ma"))
        assertEquals(listOf("ma", "ma"), engine.snapshot.lockedT9Syllables)
        assertEquals("妈妈", engine.snapshot.candidates.first().text)
    }

    @Test
    fun `t9 locks persist while typing additional digits and backspace pops only invalidated tail locks`() {
        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.T9_PINYIN
        )

        typeString(engine, "326")
        assertTrue(engine.selectSyllableFilter("dan"))
        typeString(engine, "426")
        assertTrue(engine.selectSyllableFilter("gao"))
        assertEquals(listOf("dan", "gao"), engine.snapshot.lockedT9Syllables)

        assertTrue(engine.onBackspace())
        assertEquals("32642", engine.snapshot.rawBuffer)
        assertEquals(listOf("dan"), engine.snapshot.lockedT9Syllables)

        assertTrue(engine.onBackspace())
        assertTrue(engine.onBackspace())
        assertEquals(listOf("dan"), engine.snapshot.lockedT9Syllables)

        assertTrue(engine.onBackspace())
        assertEquals("32", engine.snapshot.rawBuffer)
        assertEquals(emptyList<String>(), engine.snapshot.lockedT9Syllables)
    }

    @Test
    fun `t9 incomplete prefix completion tap appends missing digits cleanly`() {
        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.T9_PINYIN
        )

        typeString(engine, "3264")
        assertTrue(engine.selectSyllableFilter("dan"))
        assertTrue("gao" in engine.snapshot.t9SyllableOptions)
        assertTrue(engine.selectSyllableFilter("gao"))
        assertEquals("326426", engine.snapshot.rawBuffer)
        assertEquals("蛋糕", engine.snapshot.candidates.first().text)
    }

    @Test
    fun `t9 apostrophe separator boundary prevents cross-separator locking and decodes after separator`() {
        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.T9_PINYIN
        )

        typeString(engine, "94126")
        assertEquals("94'26", engine.snapshot.rawBuffer)
        assertFalse("xian" in engine.snapshot.t9SyllableOptions)
        assertTrue(engine.selectSyllableFilter("xi"))
        assertTrue(engine.selectSyllableFilter("an"))
        assertEquals("xi'an", engine.snapshot.formattedPreEdit)
        assertTrue("西安" in engine.snapshot.candidates.take(2).map { it.text })
        engine.clearPreEdit()

        typeString(engine, "32642174")
        assertTrue(engine.selectSyllableFilter("dan"))
        assertFalse("gao" in engine.snapshot.t9SyllableOptions)
        assertFalse(engine.selectSyllableFilter("gao"))
    }

    @Test
    fun `t9 partial candidate selection trims consumed prefix locks and retains unconsumed locks`() {
        val tempFile = File.createTempFile("duoime_t9_partial_lock_", ".json").apply { delete() }
        try {
            val store = UserLearningStore(tempFile)
            val engine = LocalChineseInputEngine(
                dictionary = dictionary,
                converter = converter,
                learningStore = store,
                initialMode = LiteSchemaMode.T9_PINYIN
            )

            typeString(engine, "64426744543")
            assertTrue(engine.selectSyllableFilter("ni"))
            assertTrue(engine.selectSyllableFilter("hao"))
            assertTrue(engine.selectSyllableFilter("shi"))

            val prefixIdx = engine.snapshot.candidates.indexOfFirst { it.simplifiedText == "你好" }
            assertTrue(prefixIdx >= 0)
            assertEquals(LiteKeyResult.CommitText("你好"), engine.selectCandidate(prefixIdx))
            assertEquals("744543", engine.snapshot.rawBuffer)
            assertEquals(listOf("shi"), engine.snapshot.lockedT9Syllables)

            val secondIdx = engine.snapshot.candidates.indexOfFirst { it.simplifiedText == "世界" }
            assertEquals(LiteKeyResult.CommitText("世界"), engine.selectCandidate(secondIdx))
            assertTrue(UserLearningStore(tempFile).getBoost("ni hao shi jie", "你好世界") > 0L)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `openResource throws descriptive error when resource is missing`() {
        val error = assertThrows(IllegalStateException::class.java) {
            LiteBundledResources.openResource("duoime/dict/missing_fixture.tsv").close()
        }
        assertTrue(error.message.orEmpty().contains("duoime/dict/missing_fixture.tsv"))
    }

    @Test
    fun `userLearningStore asyncSaveExecutor updates boost immediately and coalesces persistence`() {
        val tempFile = File.createTempFile("duoime_async_learn_", ".json").apply { delete() }
        val pendingTasks = ArrayDeque<Runnable>()
        val deferredExecutor = Executor { runnable -> pendingTasks.addLast(runnable) }
        try {
            val store = UserLearningStore(tempFile, deferredExecutor)
            store.recordCommit("ceshi", "测试", nowMs = 1_000L)
            store.recordCommit("nihao", "你好", nowMs = 2_000L)

            assertTrue(store.getBoost("ceshi", "测试") > 0L)
            assertFalse(tempFile.exists())
            assertEquals(1, pendingTasks.size)

            while (pendingTasks.isNotEmpty()) {
                pendingTasks.removeFirst().run()
            }

            assertTrue(tempFile.exists())
            val reloaded = UserLearningStore(tempFile)
            assertTrue(reloaded.getBoost("ceshi", "测试") > 0L)
            assertTrue(reloaded.getBoost("nihao", "你好") > 0L)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `userLearningStore enforces single active drain owner under concurrent commits`() {
        val tempFile = File.createTempFile("duoime_concurrent_learn_", ".json").apply { delete() }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val phaser = java.util.concurrent.Phaser(1)
        val activeWorkers = java.util.concurrent.atomic.AtomicInteger(0)
        val concurrencyViolations = java.util.concurrent.atomic.AtomicInteger(0)
        val trackedExecutor = Executor { task ->
            phaser.register()
            pool.execute {
                if (activeWorkers.incrementAndGet() > 1) {
                    concurrencyViolations.incrementAndGet()
                }
                try {
                    task.run()
                } finally {
                    activeWorkers.decrementAndGet()
                    phaser.arriveAndDeregister()
                }
            }
        }
        try {
            val store = UserLearningStore(tempFile, trackedExecutor)
            val threads = (0 until 4).map { workerIdx ->
                Thread {
                    for (step in 0 until 40) {
                        store.recordCommit(
                            pinyinKey = "key$workerIdx",
                            simplifiedWord = "w_${workerIdx}_$step",
                            nowMs = 10_000L + workerIdx * 100L + step
                        )
                    }
                }.apply { start() }
            }
            threads.forEach { it.join() }
            phaser.arriveAndAwaitAdvance()

            assertEquals(0, concurrencyViolations.get())
            val reloaded = UserLearningStore(tempFile)
            for (workerIdx in 0 until 4) {
                for (step in 0 until 40) {
                    assertTrue(reloaded.getBoost("key$workerIdx", "w_${workerIdx}_$step") > 0L)
                }
            }
        } finally {
            pool.shutdownNow()
            tempFile.delete()
        }
    }

    @Test
    fun `CandidateRowLayout reduces items per row for long candidates without gaps or skips`() {
        val sample = listOf(
            LiteCandidate("今天天气真不错我们一起去公园散步", "今天天气真不错我们一起去公园散步", 12, 12, 5000L, true),
            LiteCandidate("你好", "你好", 5, 2, 4000L, false),
            LiteCandidate("世界", "世界", 5, 2, 3000L, false),
            LiteCandidate("好", "好", 2, 1, 2000L, false),
            LiteCandidate("是", "是", 2, 1, 1000L, false),
            LiteCandidate("时", "时", 2, 1, 900L, false),
            LiteCandidate("事", "事", 2, 1, 800L, false),
            LiteCandidate("市", "市", 2, 1, 700L, false)
        )
        val rows = CandidateRowLayout.packRows(sample, maxRowUnits = 22f, showBadges = true)
        assertTrue(rows[0].count() <= 2)
        assertEquals(sample.indices.toList(), rows.flatten())
        for (i in 0 until rows.lastIndex) {
            assertEquals(rows[i].last + 1, rows[i + 1].first)
        }
    }

    @Test
    fun `LocalChineseInputEngine respects dynamic row width budgets across paging vertical move and number keys`() {
        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.QUANPIN
        )
        typeString(engine, "shi")
        engine.setCandidateRowWidthBudget(collapsedMaxUnits = 10.5f, expandedMaxUnits = 16f)
        val snap = engine.snapshot
        assertTrue(snap.rowRanges.isNotEmpty())
        val firstRow = snap.rowRanges[0]
        assertTrue(firstRow.count() in 1..4)
        assertEquals(firstRow.count(), snap.pageCandidates.size)

        val expectedSecondRowFirstIdx = snap.rowRanges[1].first
        assertTrue(engine.nextCandidatePage())
        assertEquals(1, engine.snapshot.candidatePage)
        assertEquals(expectedSecondRowFirstIdx, engine.snapshot.highlightedIndex)
    }

    @Test
    fun `quanpin multi-path segmentation resolves keneng and fangan accurately with dynamic preEdit display`() {
        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.QUANPIN
        )

        typeString(engine, "keneng")
        assertEquals("可能", engine.snapshot.candidates.first().text)
        assertEquals("ke'neng", engine.snapshot.formattedPreEdit)
        assertEquals(LiteKeyResult.CommitText("可能"), engine.onSpace())

        typeString(engine, "fangan")
        val fanganTexts = engine.snapshot.candidates.take(4).map { it.text }
        assertTrue("方案" in fanganTexts && "反感" in fanganTexts)
        assertEquals("fang'an", engine.snapshot.formattedPreEdit)
        engine.clearPreEdit()

        typeString(engine, "xi'an")
        assertEquals("西安", engine.snapshot.candidates.first().text)
        assertEquals("xi'an", engine.snapshot.formattedPreEdit)
    }

    @Test
    fun `chinese engine injects mixed english exact and prefix words without burying primary chinese or math symbols`() {
        val engine = LocalChineseInputEngine(
            dictionary = dictionary,
            converter = converter,
            initialMode = LiteSchemaMode.QUANPIN,
            englishLexicon = englishLexicon
        )

        typeString(engine, "git")
        assertEquals("git", engine.snapshot.candidates.first().text)
        assertEquals(LiteKeyResult.CommitText("git"), engine.onSpace())

        typeString(engine, "he")
        val heTop3 = engine.snapshot.candidates.take(3).map { it.text }
        assertEquals("he", heTop3[2])
        assertFalse(heTop3[0] == "he")
        engine.clearPreEdit()

        typeString(engine, "alpha")
        assertEquals(listOf("α", "alpha"), engine.snapshot.candidates.take(2).map { it.text })
        engine.clearPreEdit()

        typeString(engine, "nihao")
        assertEquals("你好", engine.snapshot.candidates.first().text)
        assertTrue(engine.snapshot.candidates.take(5).none { it.text.all { ch -> ch in 'a'..'z' } })
    }
}

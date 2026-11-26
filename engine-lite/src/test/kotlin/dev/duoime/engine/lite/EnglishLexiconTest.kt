/*
 * SPDX-License-Identifier: MIT
 * MIT License
 * Copyright (c) 2026 ShellDeck Contributors
 * Adapted for DuoIME by DuoIME Contributors (2026).
 */
package dev.duoime.engine.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class EnglishLexiconTest {

    companion object {
        private lateinit var lexicon: EnglishLexicon

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            lexicon = LiteBundledResources
                .openResource(LiteBundledResources.ENGLISH_WORDS_RESOURCE)
                .use { EnglishLexicon.fromStream(it) }
            assertTrue(
                "Expected compact lexicon between 3000 and 7000 entries, got ${lexicon.entryCount}",
                lexicon.entryCount in 3000..7000
            )
        }
    }

    private fun typeIntoTracker(tracker: EnglishShadowTracker, text: String) {
        for (ch in text) {
            tracker.onChar(ch = ch, isHiddenPrompt = false, hasModifier = false)
        }
    }

    @Test
    fun `lexicon contains core english and developer cli vocabulary without junk`() {
        val required = listOf(
            "the", "and", "that", "with", "this", "from", "what",
            "git", "commit", "status", "branch", "python", "docker",
            "kubectl", "systemd", "chmod", "grep", "sudo", "ssh",
            "shell", "terminal", "config", "server", "client"
        )
        for (word in required) {
            assertTrue("Expected lexicon to contain '$word'", lexicon.contains(word))
        }
        assertFalse(lexicon.contains("teh"))
        assertFalse(lexicon.contains("adn"))
        assertFalse(lexicon.contains("waht"))
    }

    @Test
    fun `prefix suggestions rank frequency matches and preserve exact word first`() {
        val pyth = lexicon.suggest("pyth", maxResults = 3)
        assertFalse(pyth.isExactWord)
        assertNull(pyth.autoCorrection)
        assertTrue("Expected python in ${pyth.suggestions}", "python" in pyth.suggestions)

        val syst = lexicon.suggest("syst", maxResults = 3)
        assertFalse(syst.isExactWord)
        assertTrue(syst.suggestions.any { it.startsWith("syst") })
        assertTrue(
            "Expected system or systemd in ${syst.suggestions}",
            "system" in syst.suggestions || "systemd" in syst.suggestions
        )

        val theResult = lexicon.suggest("the", maxResults = 3)
        assertTrue(theResult.isExactWord)
        assertNull(theResult.autoCorrection)
        assertEquals("the", theResult.suggestions.first())
        assertTrue(theResult.suggestions.size >= 2)
        assertTrue(theResult.suggestions.drop(1).all { it.startsWith("the") })

        val singleChar = lexicon.suggest("t", maxResults = 3)
        assertTrue(singleChar.suggestions.isEmpty())
        assertNull(singleChar.autoCorrection)
    }

    @Test
    fun `damerau levenshtein distance 1 and qwerty adjacency resolve high confidence typos`() {
        assertEquals("the", lexicon.resolveAutoCorrection("teh"))
        assertEquals("and", lexicon.resolveAutoCorrection("adn"))
        assertEquals("the", lexicon.resolveAutoCorrection("hte"))
        assertEquals("that", lexicon.resolveAutoCorrection("taht"))
        assertEquals("with", lexicon.resolveAutoCorrection("wiht"))
        assertEquals("what", lexicon.resolveAutoCorrection("waht"))
        assertEquals("this", lexicon.resolveAutoCorrection("htis"))
        assertEquals("git", lexicon.resolveAutoCorrection("gti"))
        assertEquals("commit", lexicon.resolveAutoCorrection("comit"))
        assertEquals("commit", lexicon.resolveAutoCorrection("committ"))
        assertEquals("python", lexicon.resolveAutoCorrection("pythn"))
        assertEquals("python", lexicon.resolveAutoCorrection("pythom"))
        assertEquals("docker", lexicon.resolveAutoCorrection("dockre"))
        assertEquals("systemd", lexicon.resolveAutoCorrection("systmed"))
        assertEquals("receive", lexicon.resolveAutoCorrection("recieve"))

        assertEquals("the", lexicon.resolveAutoCorrection("yhe"))
        assertEquals("the", lexicon.resolveAutoCorrection("tge"))
        assertEquals("with", lexicon.resolveAutoCorrection("wuth"))

        val fom = lexicon.suggest("fom", maxResults = 3)
        assertTrue("from" in fom.suggestions)
        assertNull(lexicon.resolveAutoCorrection("fom"))
    }

    @Test
    fun `valid short cli commands and exact words are never autocorrected`() {
        val protectedCommands = listOf(
            "ls", "cd", "cp", "mv", "rm", "vi", "go", "ps", "id", "ip",
            "df", "du", "tr", "wc", "jq", "rg", "fd", "gh", "cat", "git",
            "ssh", "vim", "hx", "grep", "awk", "sed", "sudo", "nix", "fzf"
        )
        val minimalLexicon = EnglishLexicon.fromStream(
            "is\t10000\nas\t8000\nid\t5000\nam\t4000\nhe\t6000\nget\t7000\n".byteInputStream()
        )
        for (cmd in protectedCommands) {
            assertNull("Full lexicon must not autocorrect '$cmd'", lexicon.resolveAutoCorrection(cmd))
            assertNull("Minimal lexicon must not autocorrect '$cmd'", minimalLexicon.resolveAutoCorrection(cmd))
        }
        assertNull(lexicon.resolveAutoCorrection("SSH"))
        assertNull(lexicon.resolveAutoCorrection("-rf"))
        assertNull(lexicon.resolveAutoCorrection("foo_bar"))
    }

    @Test
    fun `shadow tracker exempts cli flags paths snake case digits and camel case until boundary`() {
        val tracker = EnglishShadowTracker(lexicon)
        val exemptTokens = listOf(
            "-rf", "--version", "./app/src", "./run.sh", "foo.kt", "~/notes",
            "C:\\temp", "foo_bar", "host:port", "user@host", "\$PATH", "a=1",
            "sha256", "v2", "DuoIME", "SSH"
        )

        for (token in exemptTokens) {
            typeIntoTracker(tracker, token)
            assertTrue("Token '$token' should be marked exempt", tracker.isTokenExempt)
            assertTrue("Exempt token '$token' must have no suggestions", tracker.suggestions.isEmpty())
            assertEquals(0, tracker.onSuggestionApplied("anything"))
            val action = tracker.onCommitBoundary(
                delimiter = ' ',
                autocorrectEnabled = true,
                isTuiActive = false,
                isHiddenPrompt = false
            )
            assertNull("Exempt token '$token' must never autocorrect", action)
            assertEquals("", tracker.shadowWord)
            assertFalse(tracker.isTokenExempt)
        }

        typeIntoTracker(tracker, "teh")
        assertFalse(tracker.isTokenExempt)
        assertEquals("the", tracker.suggestions.first())
    }

    @Test
    fun `shadow tracker blocks hidden prompts modifiers tui mode and enter boundary`() {
        val tracker = EnglishShadowTracker(lexicon)

        typeIntoTracker(tracker, "teh")
        assertEquals("teh", tracker.shadowWord)
        tracker.onChar('s', isHiddenPrompt = true, hasModifier = false)
        assertEquals("", tracker.shadowWord)
        assertTrue(tracker.suggestions.isEmpty())

        typeIntoTracker(tracker, "teh")
        tracker.onChar('c', isHiddenPrompt = false, hasModifier = true)
        assertEquals("", tracker.shadowWord)

        typeIntoTracker(tracker, "teh")
        assertEquals(0, tracker.onSuggestionApplied("the", isTuiActive = true))
        typeIntoTracker(tracker, "teh")
        assertNull(
            tracker.onCommitBoundary(
                delimiter = ' ',
                autocorrectEnabled = true,
                isTuiActive = true,
                isHiddenPrompt = false
            )
        )

        typeIntoTracker(tracker, "teh")
        assertNull(
            tracker.onCommitBoundary(
                delimiter = '\n',
                autocorrectEnabled = true,
                isTuiActive = false,
                isHiddenPrompt = false
            )
        )

        typeIntoTracker(tracker, "teh")
        assertTrue("the" in tracker.suggestions)
        assertNull(
            tracker.onCommitBoundary(
                delimiter = ' ',
                autocorrectEnabled = false,
                isTuiActive = false,
                isHiddenPrompt = false
            )
        )
    }

    @Test
    fun `shadow tracker autocorrect backspace undo and swipe up adjustment stay synchronized`() {
        val tracker = EnglishShadowTracker(lexicon)

        typeIntoTracker(tracker, "teh")
        val action = tracker.onCommitBoundary(
            delimiter = ' ',
            autocorrectEnabled = true,
            isTuiActive = false,
            isHiddenPrompt = false
        )
        assertNotNull(action)
        assertEquals(3, action!!.deleteCodePoints)
        assertEquals("teh", action.originalWord)
        assertEquals("the", action.correctedWord)
        assertEquals(' ', action.delimiter)

        val undo = tracker.onBackspace(isHiddenPrompt = false)
        assertNotNull(undo)
        assertEquals(4, undo!!.deleteCodePoints)
        assertEquals("teh", undo.restoredWord)
        assertEquals("the", undo.correctedWord)
        assertEquals(' ', undo.trailingChar)
        assertEquals("teh", tracker.shadowWord)

        assertNull(
            tracker.onCommitBoundary(
                delimiter = ' ',
                autocorrectEnabled = true,
                isTuiActive = false,
                isHiddenPrompt = false
            )
        )

        typeIntoTracker(tracker, "pythn")
        assertEquals("python", tracker.suggestions.first())
        assertEquals(5, tracker.onSuggestionApplied("python"))
        assertEquals("", tracker.shadowWord)

        typeIntoTracker(tracker, "tehg")
        tracker.onSwipeUpAdjusted(previousCodePoints = 1, replacementText = "")
        assertEquals("teh", tracker.shadowWord)
        assertFalse(tracker.isTokenExempt)
        assertEquals("the", tracker.suggestions.first())

        tracker.onSwipeUpAdjusted(previousCodePoints = 1, replacementText = "=")
        assertEquals("te=", tracker.shadowWord)
        assertTrue(tracker.isTokenExempt)
        assertTrue(tracker.suggestions.isEmpty())

        tracker.reset()
        assertEquals("", tracker.shadowWord)
        assertFalse(tracker.isTokenExempt)

        typeIntoTracker(tracker, "teh.txt")
        assertTrue(tracker.isTokenExempt)
        assertTrue(tracker.suggestions.isEmpty())
        assertNull(
            tracker.onCommitBoundary(
                delimiter = ' ',
                autocorrectEnabled = true,
                isTuiActive = false,
                isHiddenPrompt = false
            )
        )
        for (excluded in listOf("gay", "lesbian", "bondage", "voyeur", "babes", "babe")) {
            assertFalse("Excluded word '$excluded' must not appear in lexicon", lexicon.contains(excluded))
        }
    }
}

// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionConfigTest {
    @Test
    fun parsesValidExtensionPayloadCleanly() {
        val payload = """
            {
              "version": 1,
              "hostName": "TerminalHost",
              "preferInline": true,
              "acceptImageMimes": ["image/png", "IMAGE/WEBP", "image/png"],
              "toolbarButtons": [
                {"id": "esc", "label": "ESC", "action": "send_key", "value": "111"},
                {"id": "tab_text", "label": "Tab", "action": "send_text", "value": "  "},
                {"id": "sketch", "label": "Sketch", "action": "host_command", "value": "open_board"}
              ]
            }
        """.trimIndent()

        val parsed = ExtensionConfigParser.parse(payload)
        assertNotNull(parsed)
        requireNotNull(parsed)
        assertEquals(1, parsed.schemaVersion)
        assertEquals("TerminalHost", parsed.hostName)
        assertTrue(parsed.preferInline)
        assertEquals(listOf("image/png", "image/webp"), parsed.acceptImageMimes)
        // Blank send_text value is dropped, valid send_key and host_command remain.
        assertEquals(2, parsed.toolbarButtons.size)
        assertEquals(
            ExtensionToolbarButton(
                id = "esc",
                label = "ESC",
                actionType = ExtensionActionType.SEND_KEY,
                value = "111"
            ),
            parsed.toolbarButtons[0]
        )
        assertEquals(
            ExtensionToolbarButton(
                id = "sketch",
                label = "Sketch",
                actionType = ExtensionActionType.HOST_COMMAND,
                value = "open_board"
            ),
            parsed.toolbarButtons[1]
        )
    }

    @Test
    fun enforcesBoundsSanitizationAndDropsUnsafeEntries() {
        val manyButtons = (1..20).joinToString(",") { idx ->
            """{"id":"btn_$idx","label":"Label\nWithControlChars_$idx","action":"host_command","value":"v"}"""
        }
        val payload = """
            {
              "version": 1,
              "hostName": "Host\u0000NameWithVeryLongSuffixThatMustBeClampedAtFortyEightCharacters",
              "acceptImageMimes": ["text/plain", "application/pdf", "image/jpeg", "image/svg+xml"],
              "toolbarButtons": [
                {"id": "bad id with spaces", "label": "Bad", "action": "host_command"},
                {"id": "unsafe_action", "label": "Clip", "action": "read_clipboard"},
                {"id": "btn_1", "label": "First\u0007ButtonExceedingLimit", "action": "host_command"},
                {"id": "btn_1", "label": "Duplicate", "action": "host_command"},
                $manyButtons
              ]
            }
        """.trimIndent()

        val parsed = ExtensionConfigParser.parse(payload)
        requireNotNull(parsed)
        assertEquals(ExtensionConfigParser.MAX_HOST_NAME_LENGTH, parsed.hostName!!.length)
        assertTrue(!parsed.hostName!!.contains('\u0000'))
        assertEquals(listOf("image/jpeg", "image/svg+xml"), parsed.acceptImageMimes)
        assertEquals(ExtensionConfigParser.MAX_TOOLBAR_BUTTONS, parsed.toolbarButtons.size)
        assertEquals("btn_1", parsed.toolbarButtons.first().id)
        assertEquals("FirstButtonExcee", parsed.toolbarButtons.first().label)
        assertTrue(parsed.toolbarButtons.all { it.label.length <= ExtensionConfigParser.MAX_LABEL_LENGTH })
    }

    @Test
    fun truncatesEmojiAtBoundaryWithoutLoneSurrogatesAndStripsBidiOverrides() {
        val exactBoundary = "a".repeat(15) + "\uD83D\uDE00" // 15 ASCII + 1 emoji = 16 code points (17 UTF-16 chars)
        val pastBoundary = "a".repeat(15) + "\uD83D\uDE00\uD83C\uDF89" // 17 code points -> must keep first emoji intact
        val bidiSpoof = "\u202EOK\u202C"
        val payload = """
            {
              "version": 1,
              "toolbarButtons": [
                {"id": "exact_emoji", "label": "$exactBoundary", "action": "host_command"},
                {"id": "past_emoji", "label": "$pastBoundary", "action": "host_command"},
                {"id": "bidi_btn", "label": "$bidiSpoof", "action": "host_command"}
              ]
            }
        """.trimIndent()

        val parsed = ExtensionConfigParser.parse(payload)
        requireNotNull(parsed)
        assertEquals(3, parsed.toolbarButtons.size)

        val exactLabel = parsed.toolbarButtons[0].label
        val pastLabel = parsed.toolbarButtons[1].label
        val bidiLabel = parsed.toolbarButtons[2].label

        assertEquals(exactBoundary, exactLabel)
        assertEquals(exactBoundary, pastLabel)
        assertEquals(ExtensionConfigParser.MAX_LABEL_LENGTH, exactLabel.codePointCount(0, exactLabel.length))
        assertEquals(ExtensionConfigParser.MAX_LABEL_LENGTH, pastLabel.codePointCount(0, pastLabel.length))
        assertNoLoneSurrogates(exactLabel)
        assertNoLoneSurrogates(pastLabel)
        assertEquals("OK", bidiLabel)
    }

    @Test
    fun dropsOnlyEntriesContainingExplicitJsonNullsInsteadOfFailingWholeConfig() {
        val payload = """
            {
              "version": 1,
              "hostName": "NullTolerantHost",
              "preferInline": null,
              "acceptImageMimes": [null, "image/png"],
              "toolbarButtons": [
                null,
                {"id": "null_label", "label": null, "action": "host_command", "value": "x"},
                {"id": "ok_btn", "label": "Run", "action": "host_command", "value": null}
              ]
            }
        """.trimIndent()

        val parsed = ExtensionConfigParser.parse(payload)
        assertNotNull(parsed)
        requireNotNull(parsed)
        assertEquals("NullTolerantHost", parsed.hostName)
        assertEquals(listOf("image/png"), parsed.acceptImageMimes)
        assertEquals(1, parsed.toolbarButtons.size)
        assertEquals(
            ExtensionToolbarButton(
                id = "ok_btn",
                label = "Run",
                actionType = ExtensionActionType.HOST_COMMAND,
                value = ""
            ),
            parsed.toolbarButtons.single()
        )
    }

    @Test
    fun rejectsMalformedOversizedOrUnsupportedPayloadsSafely() {
        assertNull(ExtensionConfigParser.parse(null))
        assertNull(ExtensionConfigParser.parse("   "))
        assertNull(ExtensionConfigParser.parse("{not valid json"))
        assertNull(ExtensionConfigParser.parse("""{"version": 2}"""))
        val oversized = """{"version":1,"hostName":"${"a".repeat(ExtensionConfigParser.MAX_RAW_JSON_BYTES + 64)}"}"""
        assertNull(ExtensionConfigParser.parse(oversized))
    }

    private fun assertNoLoneSurrogates(text: String) {
        var idx = 0
        while (idx < text.length) {
            val ch = text[idx]
            if (Character.isHighSurrogate(ch)) {
                assertTrue("Expected low surrogate after high surrogate at $idx", idx + 1 < text.length)
                assertTrue("Expected valid low surrogate at ${idx + 1}", Character.isLowSurrogate(text[idx + 1]))
                idx += 2
            } else {
                assertTrue("Unexpected lone low surrogate at $idx", !Character.isLowSurrogate(ch))
                idx += 1
            }
        }
    }
}

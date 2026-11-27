// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.ime

import dev.duoime.app.settings.DuoImePreferences
import dev.duoime.core.model.EditorContext
import dev.duoime.core.model.EnterBehavior
import dev.duoime.core.model.FieldKind
import dev.duoime.core.model.KeyboardPanel
import dev.duoime.core.model.Language
import dev.duoime.core.model.SymbolTabId
import dev.duoime.core.model.toInputConstraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorInfoMapperTest {

    @Test
    fun passwordVariationsAndIncognitoFlagsAreDetectedAccurately() {
        val passwordTypes = listOf(
            EditorInfoMapper.TYPE_CLASS_TEXT or EditorInfoMapper.TYPE_TEXT_VARIATION_PASSWORD,
            EditorInfoMapper.TYPE_CLASS_TEXT or EditorInfoMapper.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            EditorInfoMapper.TYPE_CLASS_TEXT or EditorInfoMapper.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            EditorInfoMapper.TYPE_CLASS_NUMBER or EditorInfoMapper.TYPE_NUMBER_VARIATION_PASSWORD
        )
        passwordTypes.forEach { inputType ->
            val ctx = EditorInfoMapper.buildEditorContext(
                packageName = "dev.example.auth",
                inputType = inputType,
                imeOptions = EditorContext.IME_ACTION_DONE
            )
            assertTrue("Expected password for 0x${inputType.toString(16)}", ctx.isPassword)
            assertTrue("Password must also set incognito", ctx.incognito)
        }

        val incognitoOnly = EditorInfoMapper.buildEditorContext(
            packageName = "dev.example.browser",
            inputType = EditorInfoMapper.TYPE_CLASS_TEXT,
            imeOptions = EditorContext.IME_ACTION_GO or EditorInfoMapper.IME_FLAG_NO_PERSONALIZED_LEARNING
        )
        assertFalse(incognitoOnly.isPassword)
        assertTrue(incognitoOnly.incognito)
        assertEquals(EnterBehavior.EditorAction(EditorContext.IME_ACTION_GO), incognitoOnly.resolveEnterBehavior())
    }

    @Test
    fun terminalTypeNullDisablesComposingAndParsesSandboxedExtensionJson() {
        val terminalCaps = EditorInfoMapper.resolveSinkCapabilities(inputType = 0)
        assertFalse(terminalCaps.composing)
        assertTrue(terminalCaps.rawKeys)

        val standardCaps = EditorInfoMapper.resolveSinkCapabilities(
            inputType = EditorInfoMapper.TYPE_CLASS_TEXT,
            imageMimeTypes = listOf("image/png")
        )
        assertTrue(standardCaps.composing)
        assertFalse(standardCaps.rawKeys)
        assertEquals(listOf("image/png"), standardCaps.imageMimeTypes)

        val extJson = """{"version":1,"toolbarButtons":[{"id":"esc","label":"ESC","action":"send_key","value":"111"}]}"""
        val ctx = EditorInfoMapper.buildEditorContext(
            packageName = "dev.example.term",
            inputType = 0,
            imeOptions = 0,
            rawExtensionJson = extJson,
            userIncognito = false
        )
        assertNotNull(ctx.extension)
        assertEquals("esc", ctx.extension?.toolbarButtons?.single()?.id)
    }

    @Test
    fun inlineKeyboardHeightScalesAcrossPortraitLandscapeAndNearSquareDisplays() {
        val portrait60 = DuoImePreferences.computeInlineKeyboardHeightPx(1920, 0.60f, 3.0f)
        val portrait75 = DuoImePreferences.computeInlineKeyboardHeightPx(1920, 0.75f, 3.0f)
        val portrait90 = DuoImePreferences.computeInlineKeyboardHeightPx(1920, 0.90f, 3.0f)
        assertTrue(portrait60 in 550..900)
        assertTrue(portrait75 >= portrait60)
        assertTrue(portrait90 >= portrait75)
        assertTrue(portrait90 <= (1920 * 0.52f).toInt())

        // Short landscape display scales across presets without starving the editor above
        val landscape60 = DuoImePreferences.computeInlineKeyboardHeightPx(1080, 0.60f, 3.0f)
        val landscape90 = DuoImePreferences.computeInlineKeyboardHeightPx(1080, 0.90f, 3.0f)
        assertTrue(landscape60 in 350..420)
        assertTrue(landscape90 > landscape60)
        assertTrue(landscape90 <= 562)

        // Near-square secondary screen (1240x1080)
        val nearSquare75 = DuoImePreferences.computeInlineKeyboardHeightPx(1080, 0.75f, 2.5f)
        assertTrue(nearSquare75 in 380..562)

        // Derived inline & overlay ranges match INLINE_HEIGHT_SCALE + HEIGHT_PRESETS
        assertEquals(0.52f, DuoImePreferences.INLINE_HEIGHT_SCALE, 0.0001f)
        assertEquals(31..47, DuoImePreferences.inlineHeightPercentRange(listOf(0.60f, 0.75f, 0.90f)))
        assertEquals(60..90, DuoImePreferences.overlayHeightPercentRange(listOf(0.60f, 0.75f, 0.90f)))
    }

    @Test
    fun selectionUpdateResetsComposingOnlyWhenSpanDroppedOrCursorLeavesComposingRange() {
        // Not composing -> never reset
        assertFalse(
            EditorInfoMapper.shouldResetComposingOnSelectionUpdate(
                isComposing = false,
                newSelStart = 0,
                newSelEnd = 0,
                candidatesStart = -1,
                candidatesEnd = -1
            )
        )
        // Normal cursor movement inside active composing span [5, 11] -> keep composing
        assertFalse(
            EditorInfoMapper.shouldResetComposingOnSelectionUpdate(
                isComposing = true,
                newSelStart = 11,
                newSelEnd = 11,
                candidatesStart = 5,
                candidatesEnd = 11
            )
        )
        // Editor dropped composing span (candidatesStart == -1) -> must reset
        assertTrue(
            EditorInfoMapper.shouldResetComposingOnSelectionUpdate(
                isComposing = true,
                newSelStart = 2,
                newSelEnd = 2,
                candidatesStart = -1,
                candidatesEnd = -1
            )
        )
        // Cursor tapped before composing span -> must reset
        assertTrue(
            EditorInfoMapper.shouldResetComposingOnSelectionUpdate(
                isComposing = true,
                newSelStart = 1,
                newSelEnd = 1,
                candidatesStart = 5,
                candidatesEnd = 11
            )
        )
        // Selection extends past composing span -> must reset
        assertTrue(
            EditorInfoMapper.shouldResetComposingOnSelectionUpdate(
                isComposing = true,
                newSelStart = 5,
                newSelEnd = 14,
                candidatesStart = 5,
                candidatesEnd = 11
            )
        )
    }

    @Test
    fun classifiesAllFieldKindsAndEnforcesSecurityFailClosedWithExtensionConfig() {
        assertEquals(FieldKind.TERMINAL, EditorInfoMapper.classifyFieldKind(EditorInfoMapper.TYPE_NULL))
        assertEquals(FieldKind.TEXT, EditorInfoMapper.classifyFieldKind(EditorInfoMapper.TYPE_CLASS_TEXT))
        assertEquals(FieldKind.NUMBER, EditorInfoMapper.classifyFieldKind(EditorInfoMapper.TYPE_CLASS_NUMBER))
        assertEquals(
            FieldKind.DECIMAL,
            EditorInfoMapper.classifyFieldKind(
                EditorInfoMapper.TYPE_CLASS_NUMBER or EditorInfoMapper.TYPE_NUMBER_FLAG_DECIMAL
            )
        )
        assertEquals(
            FieldKind.SIGNED_NUMBER,
            EditorInfoMapper.classifyFieldKind(
                EditorInfoMapper.TYPE_CLASS_NUMBER or EditorInfoMapper.TYPE_NUMBER_FLAG_SIGNED
            )
        )
        assertEquals(FieldKind.PHONE, EditorInfoMapper.classifyFieldKind(EditorInfoMapper.TYPE_CLASS_PHONE))
        assertEquals(FieldKind.DATETIME, EditorInfoMapper.classifyFieldKind(EditorInfoMapper.TYPE_CLASS_DATETIME))
        assertEquals(
            FieldKind.URI,
            EditorInfoMapper.classifyFieldKind(
                EditorInfoMapper.TYPE_CLASS_TEXT or EditorInfoMapper.TYPE_TEXT_VARIATION_URI
            )
        )
        assertEquals(
            FieldKind.EMAIL,
            EditorInfoMapper.classifyFieldKind(
                EditorInfoMapper.TYPE_CLASS_TEXT or EditorInfoMapper.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            )
        )
        assertEquals(
            FieldKind.EMAIL,
            EditorInfoMapper.classifyFieldKind(
                EditorInfoMapper.TYPE_CLASS_TEXT or EditorInfoMapper.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            )
        )
        assertEquals(
            FieldKind.PASSWORD,
            EditorInfoMapper.classifyFieldKind(
                EditorInfoMapper.TYPE_CLASS_TEXT or EditorInfoMapper.TYPE_TEXT_VARIATION_PASSWORD
            )
        )

        // Password CANNOT be weakened by extension JSON
        val pwdCtx = EditorInfoMapper.buildEditorContext(
            packageName = "dev.example.auth",
            inputType = EditorInfoMapper.TYPE_CLASS_TEXT or EditorInfoMapper.TYPE_TEXT_VARIATION_PASSWORD,
            imeOptions = 0,
            rawExtensionJson = """{"version":1,"fieldKind":"text","initialLanguage":"chinese","noLearning":false}""",
            userIncognito = false
        )
        assertEquals(FieldKind.PASSWORD, pwdCtx.fieldKind)
        assertTrue(pwdCtx.isPassword)
        assertTrue(pwdCtx.incognito)
        val pwdConstraints = pwdCtx.toInputConstraints()
        assertFalse(pwdConstraints.allowChinese)
        assertTrue(pwdConstraints.forceNumberRow)
        assertTrue(pwdConstraints.noLearning)

        // Extension can specialize TEXT into TERMINAL and tighten noLearning
        val termCtx = EditorInfoMapper.buildEditorContext(
            packageName = "dev.example.shell",
            inputType = EditorInfoMapper.TYPE_CLASS_TEXT,
            imeOptions = 0,
            rawExtensionJson = """{"version":1,"fieldKind":"terminal","noLearning":true}""",
            userIncognito = false
        )
        assertEquals(FieldKind.TERMINAL, termCtx.fieldKind)
        assertTrue(termCtx.incognito)
        val termConstraints = termCtx.toInputConstraints()
        assertEquals(SymbolTabId.CLI, termConstraints.defaultSymbolTab)
        assertEquals(Language.ENGLISH, termConstraints.initialLanguage)
        assertTrue(termConstraints.allowChinese)

        // IME_FLAG_FORCE_ASCII disallows Chinese on TEXT fields
        val asciiCtx = EditorInfoMapper.buildEditorContext(
            packageName = "dev.example.ascii",
            inputType = EditorInfoMapper.TYPE_CLASS_TEXT,
            imeOptions = EditorInfoMapper.IME_FLAG_FORCE_ASCII
        )
        val asciiConstraints = asciiCtx.toInputConstraints()
        assertFalse(asciiConstraints.allowChinese)
        assertEquals(Language.ENGLISH, asciiConstraints.initialLanguage)

        // NUMBER class forces NUMBER panel
        val numConstraints = EditorInfoMapper.buildEditorContext(
            packageName = "dev.example.calc",
            inputType = EditorInfoMapper.TYPE_CLASS_NUMBER or EditorInfoMapper.TYPE_NUMBER_FLAG_DECIMAL,
            imeOptions = 0
        ).toInputConstraints()
        assertEquals(FieldKind.DECIMAL, numConstraints.kind)
        assertEquals(KeyboardPanel.NUMBER, numConstraints.forcedPanel)
        assertFalse(numConstraints.allowChinese)
    }

    @Test
    fun extensionTextHintCannotRelaxPlatformNumericPhoneDatetimeOrTerminalFields() {
        val relaxJson = """{"version":1,"fieldKind":"text","initialLanguage":"chinese","noLearning":false}"""
        val numericPlatformInputs = listOf(
            EditorInfoMapper.TYPE_CLASS_NUMBER to FieldKind.NUMBER,
            (EditorInfoMapper.TYPE_CLASS_NUMBER or EditorInfoMapper.TYPE_NUMBER_FLAG_DECIMAL) to FieldKind.DECIMAL,
            (EditorInfoMapper.TYPE_CLASS_NUMBER or EditorInfoMapper.TYPE_NUMBER_FLAG_SIGNED) to
                FieldKind.SIGNED_NUMBER,
            EditorInfoMapper.TYPE_CLASS_PHONE to FieldKind.PHONE,
            EditorInfoMapper.TYPE_CLASS_DATETIME to FieldKind.DATETIME
        )
        numericPlatformInputs.forEach { (inputType, expectedKind) ->
            val ctx = EditorInfoMapper.buildEditorContext(
                packageName = "dev.example.form",
                inputType = inputType,
                imeOptions = 0,
                rawExtensionJson = relaxJson
            )
            assertEquals("Platform $expectedKind must not relax to TEXT", expectedKind, ctx.fieldKind)
            val constraints = ctx.toInputConstraints()
            assertEquals(expectedKind, constraints.kind)
            assertFalse(constraints.allowChinese)
            assertEquals(KeyboardPanel.NUMBER, constraints.forcedPanel)
        }

        val terminalCtx = EditorInfoMapper.buildEditorContext(
            packageName = "dev.example.term",
            inputType = EditorInfoMapper.TYPE_NULL,
            imeOptions = 0,
            rawExtensionJson = relaxJson
        )
        assertEquals(FieldKind.TERMINAL, terminalCtx.fieldKind)
        val termConstraints = terminalCtx.toInputConstraints()
        assertEquals(FieldKind.TERMINAL, termConstraints.kind)
        assertTrue(termConstraints.noLearning)
        assertEquals(SymbolTabId.CLI, termConstraints.defaultSymbolTab)
    }
}

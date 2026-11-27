// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.core.model

import dev.duoime.core.engine.EngineSchemas
import dev.duoime.core.protocol.ExtensionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardNavStateTest {

    @Test
    fun legacyInputModeMigrationHandlesSymbolsT9EnglishAndUnknownCleanly() {
        assertEquals(Language.CHINESE to ChineseScheme.PINYIN_T9, migrateLegacyInputMode("PINYIN_T9"))
        assertEquals(Language.CHINESE to ChineseScheme.PINYIN_T9, migrateLegacyInputMode(" pinyin_t9 "))
        assertEquals(Language.ENGLISH to ChineseScheme.PINYIN_26, migrateLegacyInputMode("ENGLISH"))
        assertEquals(Language.CHINESE to ChineseScheme.PINYIN_26, migrateLegacyInputMode("SYMBOLS"))
        assertEquals(Language.CHINESE to ChineseScheme.PINYIN_26, migrateLegacyInputMode("PINYIN_26"))
        assertEquals(Language.CHINESE to ChineseScheme.PINYIN_26, migrateLegacyInputMode(null))
        assertEquals(Language.CHINESE to ChineseScheme.PINYIN_26, migrateLegacyInputMode("UNKNOWN_V0"))
    }

    @Test
    fun t9ToEnglishToChineseRoundTripPreservesChineseSchemeAndVariant() {
        val startPrefs = KeyboardPrefs(
            language = Language.CHINESE,
            chineseScheme = ChineseScheme.PINYIN_T9,
            variant = ChineseVariant.TRADITIONAL
        )
        val textConstraints = InputConstraints(kind = FieldKind.TEXT)

        val toEn = KeyboardNavReducer.toggleLanguage(startPrefs, SessionState(drawerOpen = true), textConstraints)
        assertEquals(Language.ENGLISH, toEn.prefs.language)
        assertEquals(ChineseScheme.PINYIN_T9, toEn.prefs.chineseScheme)
        assertEquals(ChineseVariant.TRADITIONAL, toEn.prefs.variant)
        assertFalse(toEn.session.drawerOpen)
        assertEquals(KeyboardPanel.PRIMARY, toEn.session.panel)
        val derivedEn = deriveKeyboardState(toEn.prefs, toEn.session, textConstraints)
        assertEquals(Language.ENGLISH, derivedEn.effectiveLanguage)
        assertEquals(InputMode.ENGLISH, derivedEn.activeMode)
        assertEquals(ChineseScheme.PINYIN_T9, derivedEn.effectiveChineseScheme)

        val backToCn = KeyboardNavReducer.toggleLanguage(toEn.prefs, toEn.session, textConstraints)
        assertEquals(Language.CHINESE, backToCn.prefs.language)
        assertEquals(ChineseScheme.PINYIN_T9, backToCn.prefs.chineseScheme)
        assertEquals(ChineseVariant.TRADITIONAL, backToCn.prefs.variant)
        val derivedCn = deriveKeyboardState(backToCn.prefs, backToCn.session, textConstraints)
        assertEquals(Language.CHINESE, derivedCn.effectiveLanguage)
        assertEquals(InputMode.PINYIN_T9, derivedCn.activeMode)
        assertEquals(EngineSchemas.T9_PINYIN, derivedCn.activeSchemaId)
    }

    @Test
    fun xiaoheShuangpinMapsToFlypySchemaAndFallsBackToPinyin26WhenAbsent() {
        val prefs = KeyboardPrefs(language = Language.CHINESE, chineseScheme = ChineseScheme.XIAOHE_SHUANGPIN)
        val defaultDerived = deriveKeyboardState(prefs, SessionState())
        assertEquals(InputMode.PINYIN_26, defaultDerived.activeMode)
        assertEquals(EngineSchemas.DOUBLE_PINYIN_FLYPY, defaultDerived.activeSchemaId)

        val fallbackDerived = deriveKeyboardState(
            prefs = prefs,
            session = SessionState(),
            modeSchemaIds = mapOf(InputMode.PINYIN_26 to "custom_pinyin_26"),
            chineseSchemeSchemaIds = emptyMap()
        )
        assertEquals("custom_pinyin_26", fallbackDerived.activeSchemaId)
    }

    @Test
    fun doubleTapReturnAndClosePanelRespectForcedPanelInvariants() {
        val prefs = KeyboardPrefs()
        val text = InputConstraints(kind = FieldKind.TEXT, defaultSymbolTab = SymbolTabId.CLI)

        // Open SYMBOL -> unlocked, selects defaultSymbolTab
        val openSym = KeyboardNavReducer.openPanel(prefs, SessionState(), text, KeyboardPanel.SYMBOL)
        assertEquals(KeyboardPanel.SYMBOL, openSym.session.panel)
        assertFalse(openSym.session.panelLocked)
        assertEquals(SymbolTabId.CLI, openSym.session.symbolTab)

        // Double-tap SYMBOL -> returns to PRIMARY
        val closeSym = KeyboardNavReducer.openPanel(prefs, openSym.session, text, KeyboardPanel.SYMBOL)
        assertEquals(KeyboardPanel.PRIMARY, closeSym.session.panel)
        assertFalse(closeSym.session.panelLocked)

        // Open NUMBER -> locked by default; double-tap NUMBER -> returns to PRIMARY unlocked
        val openNum = KeyboardNavReducer.openPanel(prefs, SessionState(), text, KeyboardPanel.NUMBER)
        assertEquals(KeyboardPanel.NUMBER, openNum.session.panel)
        assertTrue(openNum.session.panelLocked)
        val closeNum = KeyboardNavReducer.openPanel(prefs, openNum.session, text, KeyboardPanel.NUMBER)
        assertEquals(KeyboardPanel.PRIMARY, closeNum.session.panel)
        assertFalse(closeNum.session.panelLocked)

        // Forced NUMBER field: Open SYMBOL switches to SYMBOL; double-tap or ClosePanel returns to NUMBER
        val numConstraints = InputConstraints(
            kind = FieldKind.NUMBER,
            allowChinese = false,
            forcedPanel = KeyboardPanel.NUMBER
        )
        val numToSym = KeyboardNavReducer.openPanel(prefs, SessionState(), numConstraints, KeyboardPanel.SYMBOL)
        assertEquals(KeyboardPanel.SYMBOL, deriveKeyboardState(prefs, numToSym.session, numConstraints).panel)
        val symBack = KeyboardNavReducer.openPanel(prefs, numToSym.session, numConstraints, KeyboardPanel.SYMBOL)
        assertEquals(KeyboardPanel.NUMBER, deriveKeyboardState(prefs, symBack.session, numConstraints).panel)
        val forcedClose = KeyboardNavReducer.closePanel(prefs, numToSym.session, numConstraints)
        assertEquals(KeyboardPanel.NUMBER, deriveKeyboardState(prefs, forcedClose.session, numConstraints).panel)
        assertFalse(forcedClose.session.panelLocked)
    }

    @Test
    fun allFieldKindsConvertToExpectedConstraintsAndEnforceSecurityFailClosed() {
        val pwd = EditorContext(isPassword = true, fieldKind = FieldKind.PASSWORD).toInputConstraints()
        assertEquals(FieldKind.PASSWORD, pwd.kind)
        assertFalse(pwd.allowChinese)
        assertTrue(pwd.forceNumberRow)
        assertTrue(pwd.noLearning)
        assertEquals(Language.ENGLISH, pwd.initialLanguage)
        assertNull(pwd.forcedPanel)

        // Extension CANNOT weaken PASSWORD into TEXT/TERMINAL or disable noLearning
        val weakenedPwd = EditorContext(
            isPassword = true,
            fieldKind = FieldKind.PASSWORD,
            extension = ExtensionConfig(
                fieldKind = FieldKind.TERMINAL,
                initialLanguage = Language.CHINESE,
                noLearning = false
            )
        ).toInputConstraints()
        assertEquals(FieldKind.PASSWORD, weakenedPwd.kind)
        assertFalse(weakenedPwd.allowChinese)
        assertTrue(weakenedPwd.forceNumberRow)
        assertTrue(weakenedPwd.noLearning)
        assertEquals(Language.ENGLISH, weakenedPwd.initialLanguage)

        // Numeric field kinds force NUMBER panel and disable Chinese
        val numericKinds = listOf(
            FieldKind.NUMBER,
            FieldKind.DECIMAL,
            FieldKind.SIGNED_NUMBER,
            FieldKind.PHONE,
            FieldKind.DATETIME
        )
        numericKinds.forEach { kind ->
            val c = EditorContext(fieldKind = kind).toInputConstraints()
            assertEquals(kind, c.kind)
            assertFalse("$kind must disable Chinese", c.allowChinese)
            assertEquals(KeyboardPanel.NUMBER, c.forcedPanel)
        }

        // URI & EMAIL default to ENGLISH while still allowing Chinese toggle
        listOf(FieldKind.URI, FieldKind.EMAIL).forEach { kind ->
            val c = EditorContext(fieldKind = kind).toInputConstraints()
            assertTrue("$kind must allow Chinese toggle", c.allowChinese)
            assertEquals(Language.ENGLISH, c.initialLanguage)
            assertNull(c.forcedPanel)
        }

        // TERMINAL sets ENGLISH + noLearning + CLI symbol tab + allowChinese
        val term = EditorContext(
            fieldKind = FieldKind.TERMINAL,
            extension = ExtensionConfig(noLearning = false)
        ).toInputConstraints()
        assertEquals(FieldKind.TERMINAL, term.kind)
        assertTrue(term.allowChinese)
        assertTrue("TERMINAL noLearning cannot be weakened", term.noLearning)
        assertEquals(Language.ENGLISH, term.initialLanguage)
        assertEquals(SymbolTabId.CLI, term.defaultSymbolTab)

        // Incognito cannot be weakened by extension noLearning = false, while extension noLearning = true tightens TEXT
        val incog = EditorContext(
            incognito = true,
            extension = ExtensionConfig(noLearning = false)
        ).toInputConstraints()
        assertTrue(incog.noLearning)

        val tightened = EditorContext(
            incognito = false,
            extension = ExtensionConfig(
                noLearning = true,
                langKeyPlacement = LangKeyPlacement.HOST,
                candidatePaging = CandidatePaging.PAGED
            )
        ).toInputConstraints()
        assertTrue(tightened.noLearning)
        assertEquals(LangKeyPlacement.HOST, tightened.langKeyPlacementOverride)
        assertEquals(CandidatePaging.PAGED, tightened.candidatePagingOverride)

        // IME_FLAG_FORCE_ASCII disallows Chinese even on TEXT
        val forceAscii = EditorContext(
            fieldKind = FieldKind.TEXT,
            imeOptions = EditorContext.IME_FLAG_FORCE_ASCII,
            extension = ExtensionConfig(initialLanguage = Language.CHINESE)
        ).toInputConstraints()
        assertFalse(forceAscii.allowChinese)
        assertEquals(Language.ENGLISH, forceAscii.initialLanguage)
    }

    @Test
    fun enterEditorRestartingPreservesSessionClampedWhileFreshEnterResetsSession() {
        val prefs = KeyboardPrefs(language = Language.CHINESE, chineseScheme = ChineseScheme.PINYIN_T9)
        val activeSession = SessionState(
            panel = KeyboardPanel.SYMBOL,
            panelLocked = true,
            symbolTab = SymbolTabId.MATH_UNIT,
            shiftState = ShiftState.ONCE,
            languageOverride = Language.CHINESE,
            drawerOpen = true
        )
        val pwdConstraints = EditorContext(isPassword = true).toInputConstraints()

        val restarted = KeyboardNavReducer.enterEditor(
            prefs = prefs,
            session = activeSession,
            constraints = pwdConstraints,
            rememberedLanguage = null,
            restarting = true
        )
        assertEquals(KeyboardPanel.SYMBOL, restarted.session.panel)
        assertTrue(restarted.session.panelLocked)
        assertEquals(SymbolTabId.MATH_UNIT, restarted.session.symbolTab)
        assertEquals(Language.ENGLISH, restarted.session.languageOverride)

        val fresh = KeyboardNavReducer.enterEditor(
            prefs = prefs,
            session = activeSession,
            constraints = InputConstraints(kind = FieldKind.TEXT, defaultSymbolTab = SymbolTabId.COMMON),
            rememberedLanguage = Language.ENGLISH,
            restarting = false
        )
        assertEquals(KeyboardPanel.PRIMARY, fresh.session.panel)
        assertFalse(fresh.session.panelLocked)
        assertFalse(fresh.session.drawerOpen)
        assertEquals(ShiftState.OFF, fresh.session.shiftState)
        assertEquals(SymbolTabId.COMMON, fresh.session.symbolTab)
        assertEquals(Language.ENGLISH, fresh.session.languageOverride)
    }

    @Test
    fun monotonicFieldKindMergePreventsRelaxingAuthoritativePlatformConstraints() {
        val textHint = ExtensionConfig(fieldKind = FieldKind.TEXT)

        // Numeric / phone / datetime fields cannot be relaxed to TEXT
        val numericKinds = listOf(
            FieldKind.NUMBER,
            FieldKind.DECIMAL,
            FieldKind.SIGNED_NUMBER,
            FieldKind.PHONE,
            FieldKind.DATETIME
        )
        numericKinds.forEach { kind ->
            assertEquals(kind, mergeFieldKindConstraint(kind, FieldKind.TEXT))
            val c = EditorContext(fieldKind = kind, extension = textHint).toInputConstraints()
            assertEquals(kind, c.kind)
            assertFalse("$kind must keep allowChinese=false", c.allowChinese)
            assertEquals(KeyboardPanel.NUMBER, c.forcedPanel)
        }

        // TERMINAL cannot be relaxed to TEXT, CHINESE initialLanguage, or COMMON defaultSymbolTab
        assertEquals(FieldKind.TERMINAL, mergeFieldKindConstraint(FieldKind.TERMINAL, FieldKind.TEXT))
        val termRelaxHint = ExtensionConfig(
            fieldKind = FieldKind.TEXT,
            initialLanguage = Language.CHINESE,
            defaultSymbolTab = SymbolTabId.COMMON
        )
        val termConstraints = EditorContext(
            fieldKind = FieldKind.TERMINAL,
            extension = termRelaxHint
        ).toInputConstraints()
        assertEquals(FieldKind.TERMINAL, termConstraints.kind)
        assertTrue(termConstraints.noLearning)
        assertEquals(SymbolTabId.CLI, termConstraints.defaultSymbolTab)
        assertEquals(Language.ENGLISH, termConstraints.initialLanguage)

        // URI & EMAIL cannot relax to TEXT or CHINESE initialLanguage, but may tighten to TERMINAL / numeric / PASSWORD
        listOf(FieldKind.URI, FieldKind.EMAIL).forEach { kind ->
            assertEquals(kind, mergeFieldKindConstraint(kind, FieldKind.TEXT))
            val c = EditorContext(fieldKind = kind, extension = termRelaxHint).toInputConstraints()
            assertEquals(kind, c.kind)
            assertEquals(Language.ENGLISH, c.initialLanguage)
            assertTrue(c.allowChinese)
            assertEquals(FieldKind.TERMINAL, mergeFieldKindConstraint(kind, FieldKind.TERMINAL))
            assertEquals(FieldKind.NUMBER, mergeFieldKindConstraint(kind, FieldKind.NUMBER))
            assertEquals(FieldKind.PASSWORD, mergeFieldKindConstraint(kind, FieldKind.PASSWORD))
        }

        // Platform TEXT may specialize to any kind; any kind may tighten to PASSWORD
        assertEquals(FieldKind.TERMINAL, mergeFieldKindConstraint(FieldKind.TEXT, FieldKind.TERMINAL))
        assertEquals(FieldKind.NUMBER, mergeFieldKindConstraint(FieldKind.TEXT, FieldKind.NUMBER))
        assertEquals(FieldKind.PASSWORD, mergeFieldKindConstraint(FieldKind.NUMBER, FieldKind.PASSWORD))
        assertEquals(
            FieldKind.PASSWORD,
            mergeFieldKindConstraint(FieldKind.TEXT, FieldKind.TEXT, platformIsPassword = true)
        )
    }
}

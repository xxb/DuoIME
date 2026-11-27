// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.settings

import android.content.SharedPreferences
import dev.duoime.core.model.ChineseScheme
import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.KeyboardPrefs
import dev.duoime.core.model.Language
import dev.duoime.ui.theme.DuoThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuoImePreferencesTest {

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = LinkedHashMap<String, Any?>()
        private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

        fun putRawString(key: String, value: String) {
            data[key] = value
        }

        fun putRawBoolean(key: String, value: Boolean) {
            data[key] = value
        }

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)

        override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = InMemoryEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) {
            listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) {
            listeners.remove(listener)
        }

        private inner class InMemoryEditor : SharedPreferences.Editor {
            private val updates = LinkedHashMap<String, Any?>()
            private val removals = LinkedHashSet<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                updates[key] = value
            }

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                updates[key] = values?.toSet()
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
                updates[key] = value
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
                updates[key] = value
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
                updates[key] = value
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
                updates[key] = value
            }

            override fun remove(key: String): SharedPreferences.Editor = apply {
                removals.add(key)
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearAll = true
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                val changedKeys = LinkedHashSet<String>()
                if (clearAll) {
                    changedKeys.addAll(data.keys)
                    data.clear()
                }
                for (key in removals) {
                    if (data.containsKey(key)) {
                        data.remove(key)
                        changedKeys.add(key)
                    }
                }
                for ((key, value) in updates) {
                    if (value == null) {
                        if (data.containsKey(key)) {
                            data.remove(key)
                            changedKeys.add(key)
                        }
                    } else if (data[key] != value) {
                        data[key] = value
                        changedKeys.add(key)
                    }
                }
                for (listener in listeners.toList()) {
                    for (key in changedKeys) {
                        listener.onSharedPreferenceChanged(this@InMemorySharedPreferences, key)
                    }
                }
            }
        }
    }

    @Test
    fun keyboardPrefsPersistAcrossSimulatedServiceRestartsWithoutSpuriousEchoes() {
        val backing = InMemorySharedPreferences()
        val firstSession = DuoImePreferences(backing)
        assertEquals(KeyboardPrefs(), firstSession.load().toKeyboardPrefs())

        val target = KeyboardPrefs(
            language = Language.ENGLISH,
            chineseScheme = ChineseScheme.PINYIN_T9,
            variant = ChineseVariant.TRADITIONAL,
            includeNumberRow = true
        )
        firstSession.updateKeyboardPrefs(target)

        // Fresh DuoImePreferences instance simulates cold restart
        val restartedSession = DuoImePreferences(backing)
        val loaded = restartedSession.load()
        assertEquals(target, loaded.toKeyboardPrefs())
        assertEquals(Language.ENGLISH, loaded.preferredLanguage)
        assertEquals(ChineseScheme.PINYIN_T9, loaded.chineseScheme)
        assertEquals(ChineseVariant.TRADITIONAL, loaded.chineseVariant)
        assertTrue(loaded.includeNumberRow)

        // Updating with identical prefs does not fire observer notifications
        var notifications = 0
        val handle = restartedSession.observe { notifications++ }
        restartedSession.updateKeyboardPrefs(target)
        assertEquals(0, notifications)
        handle.close()
    }

    @Test
    fun legacyInputModeMigratesT9SymbolsAndEnglishCleanlyAndClearsLegacyKeyOnSave() {
        val t9Backing = InMemorySharedPreferences().apply {
            putRawString(DuoImePreferences.KEY_LEGACY_INPUT_MODE, "PINYIN_T9")
        }
        val t9Prefs = DuoImePreferences(t9Backing)
        assertEquals(
            KeyboardPrefs(language = Language.CHINESE, chineseScheme = ChineseScheme.PINYIN_T9),
            t9Prefs.load().toKeyboardPrefs()
        )
        t9Prefs.updateKeyboardPrefs(t9Prefs.load().toKeyboardPrefs())
        assertFalse(
            "Saving migrated prefs must clear legacy key",
            t9Backing.contains(DuoImePreferences.KEY_LEGACY_INPUT_MODE)
        )
        assertEquals(
            KeyboardPrefs(language = Language.CHINESE, chineseScheme = ChineseScheme.PINYIN_T9),
            DuoImePreferences(t9Backing).load().toKeyboardPrefs()
        )

        val symbolsBacking = InMemorySharedPreferences().apply {
            putRawString(DuoImePreferences.KEY_LEGACY_INPUT_MODE, "SYMBOLS")
        }
        assertEquals(
            KeyboardPrefs(language = Language.CHINESE, chineseScheme = ChineseScheme.PINYIN_26),
            DuoImePreferences(symbolsBacking).load().toKeyboardPrefs()
        )

        val enBacking = InMemorySharedPreferences().apply {
            putRawString(DuoImePreferences.KEY_LEGACY_INPUT_MODE, "ENGLISH")
        }
        assertEquals(
            KeyboardPrefs(language = Language.ENGLISH, chineseScheme = ChineseScheme.PINYIN_26),
            DuoImePreferences(enBacking).load().toKeyboardPrefs()
        )
    }

    @Test
    fun preSyncingSettingsBeforeUpdateKeyboardPrefsSuppressesSelfEchoUnderSynchronousObserve() {
        val backing = InMemorySharedPreferences()
        val prefs = DuoImePreferences(backing)
        var settingsState = prefs.load()
        val echoedPrefs = mutableListOf<KeyboardPrefs>()

        val handle = prefs.observe { observed ->
            val previous = settingsState
            if (observed == previous) return@observe
            settingsState = observed
            if (observed.toKeyboardPrefs() != previous.toKeyboardPrefs()) {
                echoedPrefs.add(observed.toKeyboardPrefs())
            }
        }

        // Controller-originated update: pre-sync settingsState before writing SharedPreferences -> zero echo
        val controllerTarget = KeyboardPrefs(language = Language.ENGLISH, chineseScheme = ChineseScheme.PINYIN_T9)
        settingsState = settingsState.withKeyboardPrefs(controllerTarget)
        prefs.updateKeyboardPrefs(controllerTarget)
        assertTrue("Self-originated prefs save must not echo back into controller", echoedPrefs.isEmpty())

        // External Settings UI update (e.g. MainActivity toggling number row) still propagates cleanly
        val externalUpdated = prefs.load().copy(includeNumberRow = true)
        prefs.save(externalUpdated)
        assertEquals(listOf(externalUpdated.toKeyboardPrefs()), echoedPrefs)
        handle.close()
    }

    @Test
    fun saveGeneralSettingsPreservingKeyboardPrefsKeepsLiveLanguageSchemeAndVariant() {
        val backing = InMemorySharedPreferences()
        val prefs = DuoImePreferences(backing)
        val staleMainActivitySnapshot = prefs.load()
        assertEquals(Language.CHINESE, staleMainActivitySnapshot.preferredLanguage)
        assertEquals(ChineseScheme.PINYIN_26, staleMainActivitySnapshot.chineseScheme)
        assertEquals(ChineseVariant.SIMPLIFIED, staleMainActivitySnapshot.chineseVariant)
        assertFalse(staleMainActivitySnapshot.includeNumberRow)

        // IME service updates language/scheme/variant while MainActivity still holds stale snapshot
        prefs.updateKeyboardPrefs(
            KeyboardPrefs(
                language = Language.ENGLISH,
                chineseScheme = ChineseScheme.PINYIN_T9,
                variant = ChineseVariant.TRADITIONAL,
                includeNumberRow = false
            )
        )

        // MainActivity toggles themePreset & includeNumberRow on the stale snapshot and saves
        val merged = prefs.saveGeneralSettingsPreservingKeyboardPrefs(
            staleMainActivitySnapshot.copy(themePreset = DuoThemePreset.LIGHT, includeNumberRow = true)
        )
        assertEquals(DuoThemePreset.LIGHT, merged.themePreset)
        assertFalse(merged.darkTheme)
        assertTrue(merged.includeNumberRow)
        assertEquals(Language.ENGLISH, merged.preferredLanguage)
        assertEquals(ChineseScheme.PINYIN_T9, merged.chineseScheme)
        assertEquals(ChineseVariant.TRADITIONAL, merged.chineseVariant)

        // Persisted state matches merged snapshot on reload
        val reloaded = DuoImePreferences(backing).load()
        assertEquals(merged, reloaded)
    }

    @Test
    fun themePresetMigratesFromLegacyDarkThemePersistsEinkAndTriggersLivePlacementUpdate() {
        // Legacy user with KEY_DARK_THEME = false migrates to LIGHT
        val legacyLightBacking = InMemorySharedPreferences().apply {
            putRawBoolean(DuoImePreferences.KEY_DARK_THEME, false)
        }
        val migratedLight = DuoImePreferences(legacyLightBacking).load()
        assertEquals(DuoThemePreset.LIGHT, migratedLight.themePreset)
        assertFalse(migratedLight.darkTheme)

        // Legacy user with KEY_DARK_THEME = true migrates to DARK
        val legacyDarkBacking = InMemorySharedPreferences().apply {
            putRawBoolean(DuoImePreferences.KEY_DARK_THEME, true)
        }
        val migratedDark = DuoImePreferences(legacyDarkBacking).load()
        assertEquals(DuoThemePreset.DARK, migratedDark.themePreset)
        assertTrue(migratedDark.darkTheme)

        // Saving EINK persists KEY_THEME_PRESET and derives darkTheme = false
        val prefs = DuoImePreferences(legacyDarkBacking)
        prefs.save(migratedDark.copy(themePreset = DuoThemePreset.EINK))
        val reloadedEink = DuoImePreferences(legacyDarkBacking).load()
        assertEquals(DuoThemePreset.EINK, reloadedEink.themePreset)
        assertFalse(reloadedEink.darkTheme)

        // Switching LIGHT <-> EINK triggers RELEVANT_ENVIRONMENT_CHANGED even though darkTheme stays false
        assertEquals(
            LiveSettingsPlacementAction.RELEVANT_ENVIRONMENT_CHANGED,
            resolveLiveSettingsAction(
                previous = DuoImeSettings(themePreset = DuoThemePreset.LIGHT),
                updated = DuoImeSettings(themePreset = DuoThemePreset.EINK)
            )
        )
    }

    @Test
    fun engineBackendDefaultsToRimePersistsAndSurvivesGeneralAndKeyboardPrefUpdates() {
        val backing = InMemorySharedPreferences()
        val prefs = DuoImePreferences(backing)
        assertEquals(EngineBackend.RIME, prefs.load().engineBackend)

        // Unknown raw value degrades safely to default RIME
        backing.putRawString(DuoImePreferences.KEY_ENGINE_BACKEND, "UNKNOWN_ENGINE")
        assertEquals(EngineBackend.RIME, prefs.load().engineBackend)

        // Switching to LITE persists across reload and survives IME keyboard pref updates
        val merged = prefs.saveGeneralSettingsPreservingKeyboardPrefs(
            prefs.load().copy(engineBackend = EngineBackend.LITE)
        )
        assertEquals(EngineBackend.LITE, merged.engineBackend)
        assertEquals(EngineBackend.LITE, DuoImePreferences(backing).load().engineBackend)

        prefs.updateKeyboardPrefs(
            KeyboardPrefs(
                language = Language.CHINESE,
                chineseScheme = ChineseScheme.XIAOHE_SHUANGPIN,
                variant = ChineseVariant.TRADITIONAL,
                includeNumberRow = false
            )
        )
        val afterKeyboardUpdate = DuoImePreferences(backing).load()
        assertEquals(EngineBackend.LITE, afterKeyboardUpdate.engineBackend)
        assertEquals(ChineseScheme.XIAOHE_SHUANGPIN, afterKeyboardUpdate.chineseScheme)

        // Switching back to RIME persists cleanly
        prefs.save(afterKeyboardUpdate.copy(engineBackend = EngineBackend.RIME))
        assertEquals(EngineBackend.RIME, DuoImePreferences(backing).load().engineBackend)
    }
}

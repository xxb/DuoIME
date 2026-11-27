// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.settings

import android.content.SharedPreferences
import dev.duoime.core.model.ChineseScheme
import dev.duoime.core.model.ChineseVariant
import dev.duoime.core.model.KeyboardPrefs
import dev.duoime.core.model.Language
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
}

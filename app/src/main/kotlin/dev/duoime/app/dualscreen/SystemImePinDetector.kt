// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.dualscreen

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.provider.Settings

/**
 * Non-crashing reader and optional observer for OEM system "Pin IME to second screen" preferences
 * (such as `Settings.System` `"ime_fixed"` / `"ime_show_on_second"`). Never requests `WRITE_SETTINGS`.
 */
object SystemImePinDetector {
    const val KEY_IME_FIXED: String = "ime_fixed"
    const val KEY_IME_SHOW_ON_SECOND: String = "ime_show_on_second"

    fun parsePinValue(rawInt: Int?): Boolean? = when (rawInt) {
        1 -> true
        0 -> false
        else -> null
    }

    fun resolveEffectivePin(detectedPin: Boolean?, manualOverride: Boolean): Boolean? =
        detectedPin ?: if (manualOverride) true else null

    fun readSystemImePin(contentResolver: ContentResolver): Boolean? {
        val primary = readSystemIntOrNull(contentResolver, KEY_IME_FIXED)
        parsePinValue(primary)?.let { return it }
        val secondary = readSystemIntOrNull(contentResolver, KEY_IME_SHOW_ON_SECOND)
        return parsePinValue(secondary)
    }

    fun observe(contentResolver: ContentResolver, handler: Handler, onChanged: () -> Unit): AutoCloseable {
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                onChanged()
            }
        }
        val registered = runCatching {
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(KEY_IME_FIXED),
                false,
                observer
            )
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(KEY_IME_SHOW_ON_SECOND),
                false,
                observer
            )
            true
        }.getOrDefault(false)

        return AutoCloseable {
            if (registered) {
                runCatching { contentResolver.unregisterContentObserver(observer) }
            }
        }
    }

    private fun readSystemIntOrNull(contentResolver: ContentResolver, key: String): Int? =
        runCatching { Settings.System.getInt(contentResolver, key) }.getOrNull()
}

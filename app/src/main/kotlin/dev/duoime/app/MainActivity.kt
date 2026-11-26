/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
package dev.duoime.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.duoime.app.settings.DuoImePreferences
import dev.duoime.app.settings.DuoImeSettings
import dev.duoime.embed.DuoImeEmbed
import dev.duoime.embed.KeyboardController
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Onboarding, live typing testbed, and preferences Activity for DuoIME (`:app`).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = DuoImePreferences(this)
        setContent {
            var settings by remember { mutableStateOf(prefs.load()) }
            val scheme = if (settings.darkTheme) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DuoImeSettingsScreen(
                        settings = settings,
                        onSettingsChanged = { updated ->
                            settings = updated
                            prefs.save(updated)
                        }
                    )
                }
            }
        }
    }
}

internal object MainAppInfo {
    const val APP_TITLE: String = "DuoIME"
}

private data class ImeActivationStatus(val enabled: Boolean, val selected: Boolean)

private fun queryImeActivationStatus(context: Context): ImeActivationStatus {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    val pkg = context.packageName
    val enabled = imm?.enabledInputMethodList?.any { it.packageName == pkg } == true
    val defaultIme = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    }.getOrNull().orEmpty()
    val selected = defaultIme.startsWith("$pkg/")
    return ImeActivationStatus(enabled = enabled, selected = selected)
}

@Composable
private fun DuoImeSettingsScreen(settings: DuoImeSettings, onSettingsChanged: (DuoImeSettings) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var imeStatus by remember { mutableStateOf(queryImeActivationStatus(context)) }
    var scratchText by remember { mutableStateOf("") }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                imeStatus = queryImeActivationStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = MainAppInfo.APP_TITLE, style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Offline dual-screen Compose IME • Engine: ${DuoImeEmbed.defaultEngineId}",
            style = MaterialTheme.typography.bodyMedium
        )

        SetupStatusCard(
            status = imeStatus,
            onOpenImeSettings = {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
            onOpenImePicker = {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showInputMethodPicker()
            }
        )

        TypingTestCard(
            text = scratchText,
            onTextChanged = { scratchText = it }
        )

        PreferencesCard(
            settings = settings,
            onSettingsChanged = onSettingsChanged
        )

        PrivacyLicenseCard()
    }
}

@Composable
private fun SetupStatusCard(status: ImeActivationStatus, onOpenImeSettings: () -> Unit, onOpenImePicker: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "1. Setup DuoIME", style = MaterialTheme.typography.titleMedium)
            val enabledText = if (status.enabled) "Enabled" else "Not enabled yet"
            val selectedText = if (status.selected) "Active default IME" else "Not selected as active IME"
            Text(text = "System IME: $enabledText • $selectedText", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onOpenImeSettings) {
                    Text(text = "Enable IME")
                }
                OutlinedButton(onClick = onOpenImePicker) {
                    Text(text = "Select IME")
                }
            }
        }
    }
}

@Composable
private fun TypingTestCard(text: String, onTextChanged: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "2. Typing Scratchpad", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = onTextChanged,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 5,
                label = { Text("Tap here to test 26-key, T9, EN, S/T, long-press") }
            )
        }
    }
}

@Composable
private fun PreferencesCard(settings: DuoImeSettings, onSettingsChanged: (DuoImeSettings) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "3. Keyboard Preferences", style = MaterialTheme.typography.titleMedium)

            SettingSwitchRow(
                title = "OLED Dark Theme",
                checked = settings.darkTheme,
                onCheckedChange = { onSettingsChanged(settings.copy(darkTheme = it)) }
            )
            SettingSwitchRow(
                title = "Show Number Row (QWERTY)",
                checked = settings.includeNumberRow,
                onCheckedChange = { onSettingsChanged(settings.copy(includeNumberRow = it)) }
            )
            SettingSwitchRow(
                title = "Always Incognito (No Learning)",
                checked = settings.alwaysIncognito,
                onCheckedChange = { onSettingsChanged(settings.copy(alwaysIncognito = it)) }
            )

            Text(text = "Keyboard Height Preset", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyboardController.HEIGHT_PRESETS.forEach { preset ->
                    val pct = (preset * 100f).roundToInt()
                    FilterChip(
                        selected = abs(settings.heightFraction - preset) < 0.02f,
                        onClick = { onSettingsChanged(settings.copy(heightFraction = preset)) },
                        label = { Text("$pct%") }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PrivacyLicenseCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = "Privacy & Open Source", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Zero INTERNET permission, allowBackup=false, and password fields automatically " +
                    "force English mode with user learning disabled.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Licensed under BSD-3-Clause with MIT and Apache-2.0 notices preserved for bundled " +
                    ":engine-lite dictionaries and ported components.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

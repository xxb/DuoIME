/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
package dev.duoime.app

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.duoime.app.dualscreen.DisplayClassifier
import dev.duoime.app.dualscreen.RawDisplayInfo
import dev.duoime.app.dualscreen.SystemImePinDetector
import dev.duoime.app.ime.DuoImeEngineStatusRegistry
import dev.duoime.app.settings.DisplayCalibrationCard
import dev.duoime.app.settings.DuoImePreferences
import dev.duoime.app.settings.DuoImeSettings
import dev.duoime.app.settings.EngineSettingsCard
import dev.duoime.app.settings.FeedbackSettingsCard
import dev.duoime.app.settings.HeightControlSection
import dev.duoime.app.settings.OverlayPermissionCard
import dev.duoime.app.settings.PerAppInlineOverridesCard
import dev.duoime.app.settings.RimeWorkspaceCoordinator
import dev.duoime.app.settings.SettingSwitchRow
import dev.duoime.app.settings.SystemPinImeCard
import dev.duoime.app.settings.formatEngineStatusLabel
import dev.duoime.ui.theme.DuoThemePreset

/**
 * Onboarding, dual-screen calibration, live typing testbed, and preferences Activity for DuoIME (`:app`).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = DuoImePreferences(this)
        setContent {
            var settings by remember { mutableStateOf(prefs.load()) }
            DisposableEffect(prefs) {
                val handle = prefs.observe { latest ->
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        settings = latest
                    } else {
                        runOnUiThread { settings = latest }
                    }
                }
                onDispose { handle.close() }
            }
            val scheme = if (settings.darkTheme) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DuoImeSettingsScreen(
                        settings = settings,
                        onSettingsChanged = { updated ->
                            settings = prefs.saveGeneralSettingsPreservingKeyboardPrefs(updated)
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

private data class DeviceOnboardingSnapshot(
    val imeEnabled: Boolean,
    val imeSelected: Boolean,
    val apiSupportsOverlay: Boolean,
    val hasOverlayPermission: Boolean,
    val detectedSystemImePin: Boolean?,
    val rawDisplays: List<RawDisplayInfo>
)

private fun queryOnboardingSnapshot(context: Context): DeviceOnboardingSnapshot {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    val pkg = context.packageName
    val enabled = imm?.enabledInputMethodList?.any { it.packageName == pkg } == true
    val defaultIme = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    }.getOrNull().orEmpty()
    val selected = defaultIme.startsWith("$pkg/")
    val supportsOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val overlayPerm = supportsOverlay && Settings.canDrawOverlays(context)
    val pin = SystemImePinDetector.readSystemImePin(context.contentResolver)
    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    val displays = dm?.displays?.map(DisplayClassifier::fromAndroidDisplay).orEmpty()
    return DeviceOnboardingSnapshot(
        imeEnabled = enabled,
        imeSelected = selected,
        apiSupportsOverlay = supportsOverlay,
        hasOverlayPermission = overlayPerm,
        detectedSystemImePin = pin,
        rawDisplays = displays
    )
}

@Composable
private fun DuoImeSettingsScreen(settings: DuoImeSettings, onSettingsChanged: (DuoImeSettings) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val engineStatus by DuoImeEngineStatusRegistry.status.collectAsState()
    var snapshot by remember { mutableStateOf(queryOnboardingSnapshot(context)) }
    var scratchText by remember { mutableStateOf("") }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                snapshot = queryOnboardingSnapshot(context)
                RimeWorkspaceCoordinator.refreshSchemas(context)
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
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(
            text = stringResource(
                R.string.app_subtitle,
                formatEngineStatusLabel(settings.engineBackend, engineStatus)
            ),
            style = MaterialTheme.typography.bodyMedium
        )

        SetupStatusCard(
            enabled = snapshot.imeEnabled,
            selected = snapshot.imeSelected,
            onOpenImeSettings = {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
            onOpenImePicker = {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showInputMethodPicker()
            }
        )

        OverlayPermissionCard(
            apiSupportsOverlay = snapshot.apiSupportsOverlay,
            hasOverlayPermission = snapshot.hasOverlayPermission,
            onRequestOverlayPermission = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }
        )

        SystemPinImeCard(
            detectedSystemPin = snapshot.detectedSystemImePin,
            manualPinEnabled = settings.manualSystemImePin,
            onManualPinChanged = { onSettingsChanged(settings.copy(manualSystemImePin = it)) }
        )

        DisplayCalibrationCard(
            rawDisplays = snapshot.rawDisplays,
            pairedPeerDisplayId = settings.pairedPeerDisplayId,
            onPairPeerDisplay = { peerId -> onSettingsChanged(settings.copy(pairedPeerDisplayId = peerId)) }
        )

        TypingTestCard(
            text = scratchText,
            onTextChanged = { scratchText = it }
        )

        EngineSettingsCard(
            settings = settings,
            engineStatus = engineStatus,
            onBackendSelected = { backend ->
                onSettingsChanged(settings.copy(engineBackend = backend))
            }
        )

        PreferencesCard(
            settings = settings,
            onSettingsChanged = onSettingsChanged
        )

        FeedbackSettingsCard(
            settings = settings,
            onSettingsChanged = onSettingsChanged
        )

        PerAppInlineOverridesCard(
            packages = settings.perAppInlinePackages,
            onAddPackage = { pkg ->
                onSettingsChanged(settings.copy(perAppInlinePackages = settings.perAppInlinePackages + pkg))
            },
            onRemovePackage = { pkg ->
                onSettingsChanged(settings.copy(perAppInlinePackages = settings.perAppInlinePackages - pkg))
            }
        )

        PrivacyLicenseCard()
    }
}

@Composable
private fun SetupStatusCard(
    enabled: Boolean,
    selected: Boolean,
    onOpenImeSettings: () -> Unit,
    onOpenImePicker: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = stringResource(R.string.card_setup_title), style = MaterialTheme.typography.titleMedium)
            val enabledText = if (enabled) {
                stringResource(R.string.status_ime_enabled)
            } else {
                stringResource(R.string.status_ime_disabled)
            }
            val selectedText = if (selected) {
                stringResource(R.string.status_ime_selected)
            } else {
                stringResource(R.string.status_ime_unselected)
            }
            Text(
                text = stringResource(R.string.status_ime_summary, enabledText, selectedText),
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onOpenImeSettings) {
                    Text(text = stringResource(R.string.btn_enable_ime))
                }
                OutlinedButton(onClick = onOpenImePicker) {
                    Text(text = stringResource(R.string.btn_select_ime))
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
            Text(text = stringResource(R.string.card_scratchpad_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = onTextChanged,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 5,
                label = { Text(stringResource(R.string.scratchpad_label)) }
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
            Text(text = stringResource(R.string.card_prefs_title), style = MaterialTheme.typography.titleMedium)

            ThemePresetSelector(
                selected = settings.themePreset,
                onSelected = { preset -> onSettingsChanged(settings.copy(themePreset = preset)) }
            )
            SettingSwitchRow(
                title = stringResource(R.string.pref_number_row),
                checked = settings.includeNumberRow,
                onCheckedChange = { onSettingsChanged(settings.copy(includeNumberRow = it)) }
            )
            SettingSwitchRow(
                title = stringResource(R.string.pref_always_incognito),
                checked = settings.alwaysIncognito,
                onCheckedChange = { onSettingsChanged(settings.copy(alwaysIncognito = it)) }
            )
            SettingSwitchRow(
                title = stringResource(R.string.pref_same_screen),
                checked = settings.swapToSameScreen,
                onCheckedChange = { onSettingsChanged(settings.copy(swapToSameScreen = it)) }
            )

            HeightControlSection(
                heightFraction = settings.heightFraction,
                onHeightChanged = { onSettingsChanged(settings.copy(heightFraction = it)) }
            )
        }
    }
}

@Composable
private fun ThemePresetSelector(selected: DuoThemePreset, onSelected: (DuoThemePreset) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.pref_theme_label),
            style = MaterialTheme.typography.bodyMedium
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val options = listOf(
                DuoThemePreset.DARK to stringResource(R.string.theme_preset_dark),
                DuoThemePreset.LIGHT to stringResource(R.string.theme_preset_light),
                DuoThemePreset.EINK to stringResource(R.string.theme_preset_eink)
            )
            for ((preset, label) in options) {
                if (selected == preset) {
                    Button(
                        onClick = { onSelected(preset) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = label)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onSelected(preset) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = label)
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyLicenseCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = stringResource(R.string.card_privacy_title), style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(R.string.privacy_summary), style = MaterialTheme.typography.bodySmall)
            Text(text = stringResource(R.string.license_summary), style = MaterialTheme.typography.bodySmall)
        }
    }
}

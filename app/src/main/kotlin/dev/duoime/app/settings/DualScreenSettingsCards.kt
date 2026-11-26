/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
package dev.duoime.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.duoime.app.R
import dev.duoime.app.dualscreen.DisplayClassifier
import dev.duoime.app.dualscreen.RawDisplayInfo
import dev.duoime.core.policy.DisplayPlacementPolicy
import dev.duoime.embed.KeyboardController
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun OverlayPermissionCard(
    apiSupportsOverlay: Boolean,
    hasOverlayPermission: Boolean,
    onRequestOverlayPermission: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = stringResource(R.string.card_overlay_title), style = MaterialTheme.typography.titleMedium)
            val statusText = when {
                !apiSupportsOverlay -> stringResource(R.string.status_overlay_api_unsupported)
                hasOverlayPermission -> stringResource(R.string.status_overlay_granted)
                else -> stringResource(R.string.status_overlay_denied)
            }
            Text(
                text = stringResource(R.string.status_overlay_summary, statusText),
                style = MaterialTheme.typography.bodySmall
            )
            if (apiSupportsOverlay && !hasOverlayPermission) {
                Button(onClick = onRequestOverlayPermission) {
                    Text(text = stringResource(R.string.btn_grant_overlay))
                }
            }
        }
    }
}

@Composable
internal fun SystemPinImeCard(
    detectedSystemPin: Boolean?,
    manualPinEnabled: Boolean,
    onManualPinChanged: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = stringResource(R.string.card_pin_title), style = MaterialTheme.typography.titleMedium)
            val stateText = when (detectedSystemPin) {
                true -> stringResource(R.string.status_pin_active)
                false -> stringResource(R.string.status_pin_off)
                null -> stringResource(R.string.status_pin_unknown)
            }
            Text(
                text = stringResource(R.string.status_pin_summary, stateText),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = stringResource(R.string.pin_instructions),
                style = MaterialTheme.typography.bodySmall
            )
            if (detectedSystemPin == null || manualPinEnabled) {
                SettingSwitchRow(
                    title = stringResource(R.string.toggle_manual_pin),
                    checked = manualPinEnabled,
                    onCheckedChange = onManualPinChanged
                )
            }
        }
    }
}

@Composable
internal fun DisplayCalibrationCard(
    rawDisplays: List<RawDisplayInfo>,
    pairedPeerDisplayId: Int?,
    onPairPeerDisplay: (Int?) -> Unit
) {
    val classified = DisplayClassifier.classifyDisplays(rawDisplays, pairedPeerDisplayId)
    val calibratablePeer = DisplayClassifier.findCalibratablePeerDisplay(rawDisplays)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = stringResource(R.string.card_calibration_title), style = MaterialTheme.typography.titleMedium)
            val pairLabel = pairedPeerDisplayId?.let { stringResource(R.string.status_paired_peer, it) }
                ?: stringResource(R.string.status_unpaired_peer)
            Text(text = pairLabel, style = MaterialTheme.typography.bodySmall)

            classified.forEach { display ->
                val stateStr = if (display.isOn) {
                    stringResource(R.string.display_state_on)
                } else {
                    stringResource(R.string.display_state_off)
                }
                Text(
                    text = stringResource(
                        R.string.display_list_item,
                        display.displayId,
                        display.widthPx,
                        display.heightPx,
                        display.kind.name,
                        stateStr
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = calibratablePeer != null,
                    onClick = { calibratablePeer?.let { onPairPeerDisplay(it.displayId) } }
                ) {
                    Text(
                        text = calibratablePeer?.let { stringResource(R.string.btn_pair_display, it.displayId) }
                            ?: stringResource(R.string.btn_no_peer_found)
                    )
                }
                if (pairedPeerDisplayId != null) {
                    OutlinedButton(onClick = { onPairPeerDisplay(null) }) {
                        Text(text = stringResource(R.string.btn_clear_pairing))
                    }
                }
            }
        }
    }
}

@Composable
internal fun HeightControlSection(heightFraction: Float, onHeightChanged: (Float) -> Unit) {
    val pct = (DisplayPlacementPolicy.clampHeightFraction(heightFraction) * 100f).roundToInt()
    val inlineRange = DuoImePreferences.inlineHeightPercentRange(KeyboardController.HEIGHT_PRESETS)
    val overlayRange = DuoImePreferences.overlayHeightPercentRange(KeyboardController.HEIGHT_PRESETS)
    Text(text = stringResource(R.string.pref_height_label, pct), style = MaterialTheme.typography.bodyMedium)
    Text(
        text = stringResource(
            R.string.pref_height_range_subtitle,
            inlineRange.first,
            inlineRange.last,
            overlayRange.first,
            overlayRange.last
        ),
        style = MaterialTheme.typography.bodySmall
    )
    Slider(
        value = DisplayPlacementPolicy.clampHeightFraction(heightFraction),
        onValueChange = { onHeightChanged(it) },
        valueRange = DisplayPlacementPolicy.MIN_HEIGHT_FRACTION..DisplayPlacementPolicy.MAX_HEIGHT_FRACTION
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val presetLabels = listOf(
            stringResource(R.string.height_preset_compact),
            stringResource(R.string.height_preset_standard),
            stringResource(R.string.height_preset_tall)
        )
        KeyboardController.HEIGHT_PRESETS.forEachIndexed { idx, preset ->
            FilterChip(
                selected = abs(heightFraction - preset) < 0.02f,
                onClick = { onHeightChanged(preset) },
                label = { Text(presetLabels.getOrElse(idx) { presetLabels.last() }) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PerAppInlineOverridesCard(
    packages: Set<String>,
    onAddPackage: (String) -> Unit,
    onRemovePackage: (String) -> Unit
) {
    var draftPackage by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = stringResource(R.string.card_overrides_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.overrides_description),
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draftPackage,
                    onValueChange = { draftPackage = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.overrides_package_hint)) }
                )
                Button(
                    enabled = draftPackage.trim().isNotEmpty(),
                    onClick = {
                        val trimmed = draftPackage.trim()
                        if (trimmed.isNotEmpty()) {
                            onAddPackage(trimmed)
                            draftPackage = ""
                        }
                    }
                ) {
                    Text(text = stringResource(R.string.btn_add_package))
                }
            }
            if (packages.isEmpty()) {
                Text(text = stringResource(R.string.overrides_empty), style = MaterialTheme.typography.bodySmall)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    packages.sorted().forEach { pkg ->
                        FilterChip(
                            selected = true,
                            onClick = { onRemovePackage(pkg) },
                            label = { Text("$pkg ×") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

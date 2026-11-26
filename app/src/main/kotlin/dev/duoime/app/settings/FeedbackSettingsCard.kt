/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
package dev.duoime.app.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.duoime.app.R
import dev.duoime.ui.feedback.KeyFeedbackOptions
import dev.duoime.ui.feedback.KeySoundStyle
import dev.duoime.ui.feedback.rememberKeyFeedbackController
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FeedbackSettingsCard(settings: DuoImeSettings, onSettingsChanged: (DuoImeSettings) -> Unit) {
    val feedbackController = rememberKeyFeedbackController()
    var previewStep by remember { mutableIntStateOf(0) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = stringResource(R.string.card_feedback_title), style = MaterialTheme.typography.titleMedium)

            SettingSwitchRow(
                title = stringResource(R.string.pref_haptic_enabled),
                checked = settings.hapticEnabled,
                onCheckedChange = { enabled ->
                    onSettingsChanged(settings.copy(hapticEnabled = enabled))
                    if (enabled) {
                        feedbackController.previewHapticStrength(settings.hapticStrength)
                    }
                }
            )

            if (settings.hapticEnabled) {
                HapticStrengthSection(
                    strength = settings.hapticStrength,
                    onStrengthChanged = { level ->
                        onSettingsChanged(settings.copy(hapticStrength = level))
                        feedbackController.previewHapticStrength(level)
                    }
                )
            }

            SettingSwitchRow(
                title = stringResource(R.string.pref_sound_enabled),
                checked = settings.soundEnabled,
                onCheckedChange = { enabled ->
                    onSettingsChanged(settings.copy(soundEnabled = enabled))
                    if (enabled) {
                        feedbackController.playPreview(
                            style = settings.soundStyle,
                            volume = settings.soundVolume,
                            previewStep = previewStep++
                        )
                    }
                }
            )

            Text(
                text = stringResource(R.string.pref_sound_ringer_hint),
                style = MaterialTheme.typography.bodySmall
            )

            if (settings.soundEnabled) {
                SoundSettingsSection(
                    settings = settings,
                    onVolumeChanged = { vol -> onSettingsChanged(settings.copy(soundVolume = vol)) },
                    onStyleSelected = { style ->
                        onSettingsChanged(settings.copy(soundStyle = style))
                        feedbackController.playPreview(
                            style = style,
                            volume = settings.soundVolume,
                            previewStep = previewStep++
                        )
                    },
                    onPreviewSound = {
                        feedbackController.playPreview(
                            style = settings.soundStyle,
                            volume = settings.soundVolume,
                            previewStep = previewStep++
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun HapticStrengthSection(strength: Int, onStrengthChanged: (Int) -> Unit) {
    val activeStrength = strength.coerceIn(
        KeyFeedbackOptions.MIN_ACTIVE_HAPTIC_STRENGTH,
        KeyFeedbackOptions.MAX_HAPTIC_STRENGTH
    )
    Text(
        text = stringResource(R.string.pref_haptic_strength_label, activeStrength),
        style = MaterialTheme.typography.bodyMedium
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (level in KeyFeedbackOptions.MIN_ACTIVE_HAPTIC_STRENGTH..KeyFeedbackOptions.MAX_HAPTIC_STRENGTH) {
            FilterChip(
                selected = activeStrength == level,
                onClick = { onStrengthChanged(level) },
                label = { Text("$level") }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SoundSettingsSection(
    settings: DuoImeSettings,
    onVolumeChanged: (Float) -> Unit,
    onStyleSelected: (KeySoundStyle) -> Unit,
    onPreviewSound: () -> Unit
) {
    val volumePct = (KeyFeedbackOptions.clampSoundVolume(settings.soundVolume) * 100f).roundToInt()
    Text(
        text = stringResource(R.string.pref_sound_volume_label, volumePct),
        style = MaterialTheme.typography.bodyMedium
    )
    Slider(
        value = KeyFeedbackOptions.clampSoundVolume(settings.soundVolume),
        onValueChange = onVolumeChanged,
        onValueChangeFinished = onPreviewSound,
        valueRange = KeyFeedbackOptions.MIN_SOUND_VOLUME..KeyFeedbackOptions.MAX_SOUND_VOLUME
    )

    Text(
        text = stringResource(R.string.pref_sound_style_label),
        style = MaterialTheme.typography.bodyMedium
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        KeySoundStyle.entries.forEach { style ->
            FilterChip(
                selected = settings.soundStyle == style,
                onClick = { onStyleSelected(style) },
                label = { Text(stringResource(soundStyleLabelRes(style))) }
            )
        }
    }

    OutlinedButton(onClick = onPreviewSound) {
        Text(text = stringResource(R.string.btn_preview_sound))
    }
}

@StringRes
internal fun soundStyleLabelRes(style: KeySoundStyle): Int = when (style) {
    KeySoundStyle.MECHANICAL -> R.string.sound_style_mechanical
    KeySoundStyle.ARCADE_COMBO -> R.string.sound_style_arcade_combo
    KeySoundStyle.RETRO_8BIT -> R.string.sound_style_retro_8bit
    KeySoundStyle.SCIFI_SYNTH -> R.string.sound_style_scifi_synth
    KeySoundStyle.GRAND_PIANO -> R.string.sound_style_grand_piano
    KeySoundStyle.GUZHENG -> R.string.sound_style_guzheng
    KeySoundStyle.KALIMBA_MUSIC_BOX -> R.string.sound_style_kalimba
    KeySoundStyle.MARIMBA_HANDPAN -> R.string.sound_style_marimba
    KeySoundStyle.JAZZ_RHODES -> R.string.sound_style_jazz_rhodes
    KeySoundStyle.HARP_NYLON -> R.string.sound_style_harp_nylon
    KeySoundStyle.BRONZE_CHIME_JADE -> R.string.sound_style_bronze_chime
    KeySoundStyle.ACOUSTIC_DRUM_KIT -> R.string.sound_style_drum_kit
    KeySoundStyle.BAND_ENSEMBLE -> R.string.sound_style_band_ensemble
}

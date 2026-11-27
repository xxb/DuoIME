// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.duoime.app.R
import dev.duoime.app.ime.ActiveEngineRuntime
import dev.duoime.app.ime.ActiveEngineStatus

@Composable
internal fun formatEngineStatusLabel(configuredBackend: EngineBackend, status: ActiveEngineStatus): String =
    when (status.activeRuntime) {
        ActiveEngineRuntime.RIME -> {
            val ver = status.rimeVersion?.takeIf { it.isNotBlank() }
            if (ver != null) {
                stringResource(R.string.engine_status_rime_versioned, ver)
            } else {
                stringResource(R.string.engine_backend_rime)
            }
        }
        ActiveEngineRuntime.LITE -> {
            if (status.requestedBackend == EngineBackend.RIME) {
                stringResource(R.string.engine_status_lite_fallback)
            } else {
                stringResource(R.string.engine_backend_lite)
            }
        }
        ActiveEngineRuntime.PASS_THROUGH -> stringResource(R.string.engine_status_passthrough)
        ActiveEngineRuntime.STARTING -> stringResource(R.string.engine_status_starting)
        ActiveEngineRuntime.NOT_RUNNING -> when (configuredBackend) {
            EngineBackend.RIME -> stringResource(R.string.engine_status_configured_rime)
            EngineBackend.LITE -> stringResource(R.string.engine_status_configured_lite)
        }
    }

@Composable
internal fun EngineSettingsCard(
    settings: DuoImeSettings,
    engineStatus: ActiveEngineStatus,
    onBackendSelected: (EngineBackend) -> Unit
) {
    val activeLabel = formatEngineStatusLabel(settings.engineBackend, engineStatus)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = stringResource(R.string.card_engine_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.engine_active_summary, activeLabel),
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val options = listOf(
                    EngineBackend.RIME to stringResource(R.string.engine_backend_rime),
                    EngineBackend.LITE to stringResource(R.string.engine_backend_lite)
                )
                for ((backend, label) in options) {
                    if (settings.engineBackend == backend) {
                        Button(
                            onClick = { onBackendSelected(backend) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = label)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onBackendSelected(backend) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = label)
                        }
                    }
                }
            }
            if (engineStatus.isFallback) {
                Text(
                    text = stringResource(R.string.engine_fallback_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                text = stringResource(R.string.engine_description),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

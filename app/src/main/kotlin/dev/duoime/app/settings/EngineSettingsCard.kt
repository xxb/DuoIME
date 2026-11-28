// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.duoime.app.R
import dev.duoime.app.ime.ActiveEngineRuntime
import dev.duoime.app.ime.ActiveEngineStatus
import dev.duoime.app.ime.resolveSelectedRimeSchemaId
import dev.duoime.engine.rime.RimeSchemaInfo

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
    val context = LocalContext.current
    val workspaceState by RimeWorkspaceCoordinator.uiState.collectAsState()
    var pendingPicker by remember { mutableStateOf<RimeImportPickerTarget?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            RimeWorkspaceCoordinator.importZip(context, uri)
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            RimeWorkspaceCoordinator.importTree(context, uri)
        }
    }

    LaunchedEffect(settings.engineBackend) {
        if (settings.engineBackend == EngineBackend.RIME) {
            RimeWorkspaceCoordinator.refreshSchemas(context)
        }
    }

    pendingPicker?.let { target ->
        RimeImportLicenseDialog(
            target = target,
            onConfirm = { confirmed ->
                pendingPicker = null
                when (confirmed) {
                    RimeImportPickerTarget.ZIP -> zipLauncher.launch(RimeSafImporter.ZIP_MIME_TYPES)
                    RimeImportPickerTarget.FOLDER -> folderLauncher.launch(null)
                }
            },
            onDismiss = { pendingPicker = null }
        )
    }

    if (showResetConfirm) {
        RimeResetConfirmDialog(
            onConfirm = {
                showResetConfirm = false
                RimeWorkspaceCoordinator.resetWorkspace(context)
            },
            onDismiss = { showResetConfirm = false }
        )
    }

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
            EngineBackendSelectorRow(
                selected = settings.engineBackend,
                onSelected = onBackendSelected
            )
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
            if (settings.engineBackend == EngineBackend.RIME) {
                HorizontalDivider()
                RimeWorkspaceSection(
                    selectedSchemaId = resolveSelectedRimeSchemaId(settings),
                    workspaceState = workspaceState,
                    onSelectSchema = { schemaId -> RimeWorkspaceCoordinator.selectSchema(context, schemaId) },
                    onRequestImport = { target -> pendingPicker = target },
                    onRedeploy = { RimeWorkspaceCoordinator.redeployWorkspace(context) },
                    onRequestReset = { showResetConfirm = true }
                )
            }
        }
    }
}

@Composable
private fun EngineBackendSelectorRow(selected: EngineBackend, onSelected: (EngineBackend) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val options = listOf(
            EngineBackend.RIME to stringResource(R.string.engine_backend_rime),
            EngineBackend.LITE to stringResource(R.string.engine_backend_lite)
        )
        for ((backend, label) in options) {
            if (selected == backend) {
                Button(onClick = { onSelected(backend) }, modifier = Modifier.weight(1f)) {
                    Text(text = label)
                }
            } else {
                OutlinedButton(onClick = { onSelected(backend) }, modifier = Modifier.weight(1f)) {
                    Text(text = label)
                }
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun RimeWorkspaceSection(
    selectedSchemaId: String,
    workspaceState: RimeWorkspaceUiState,
    onSelectSchema: (String) -> Unit,
    onRequestImport: (RimeImportPickerTarget) -> Unit,
    onRedeploy: () -> Unit,
    onRequestReset: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.rime_schemas_title), style = MaterialTheme.typography.titleSmall)
        if (workspaceState.schemas.isEmpty()) {
            Text(
                text = stringResource(R.string.rime_schemas_empty),
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            RimeSchemaList(
                schemas = workspaceState.schemas,
                selectedSchemaId = selectedSchemaId,
                enabled = !workspaceState.isBusy,
                onSelectSchema = onSelectSchema
            )
        }
        RimeWorkspaceActionsRow(
            isBusy = workspaceState.isBusy,
            hasCustomFiles = workspaceState.hasCustomFiles,
            onImportZip = { onRequestImport(RimeImportPickerTarget.ZIP) },
            onImportFolder = { onRequestImport(RimeImportPickerTarget.FOLDER) },
            onRedeploy = onRedeploy,
            onReset = onRequestReset
        )
        workspaceState.busyOp?.let { busyOp ->
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            val busyLabel = when (busyOp) {
                RimeWorkspaceBusyOp.IMPORTING -> stringResource(R.string.rime_busy_importing)
                RimeWorkspaceBusyOp.DEPLOYING -> stringResource(R.string.rime_busy_deploying)
                RimeWorkspaceBusyOp.RESETTING -> stringResource(R.string.rime_busy_resetting)
            }
            Text(text = busyLabel, style = MaterialTheme.typography.bodySmall)
        }
        workspaceState.feedback?.let { feedback ->
            val color = if (feedback is RimeWorkspaceFeedback.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
            Text(
                text = formatRimeWorkspaceFeedback(feedback),
                style = MaterialTheme.typography.bodySmall,
                color = color
            )
        }
    }
}

@Composable
private fun RimeSchemaList(
    schemas: List<RimeSchemaInfo>,
    selectedSchemaId: String,
    enabled: Boolean,
    onSelectSchema: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (schema in schemas) {
            val isSelected = schema.id == selectedSchemaId
            val tag = if (schema.isBuiltIn) {
                stringResource(R.string.rime_schema_tag_builtin)
            } else {
                stringResource(R.string.rime_schema_tag_custom)
            }
            val label = stringResource(R.string.rime_schema_item_label, schema.name, tag)
            if (isSelected) {
                Button(
                    onClick = { onSelectSchema(schema.id) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                OutlinedButton(
                    onClick = { onSelectSchema(schema.id) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun RimeWorkspaceActionsRow(
    isBusy: Boolean,
    hasCustomFiles: Boolean,
    onImportZip: () -> Unit,
    onImportFolder: () -> Unit,
    onRedeploy: () -> Unit,
    onReset: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onImportZip,
                enabled = !isBusy,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = stringResource(R.string.rime_btn_import_zip))
            }
            OutlinedButton(
                onClick = onImportFolder,
                enabled = !isBusy,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = stringResource(R.string.rime_btn_import_folder))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onRedeploy,
                enabled = !isBusy,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = stringResource(R.string.rime_btn_deploy))
            }
            OutlinedButton(
                onClick = onReset,
                enabled = !isBusy && hasCustomFiles,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = stringResource(R.string.rime_btn_reset))
            }
        }
    }
}

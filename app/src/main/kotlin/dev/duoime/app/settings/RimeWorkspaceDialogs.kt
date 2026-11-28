// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
package dev.duoime.app.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.duoime.app.R
import dev.duoime.engine.rime.RimeImportFailureReason

internal enum class RimeImportPickerTarget {
    ZIP,
    FOLDER
}

/**
 * Upfront license & privacy notice shown strictly BEFORE launching Android's SAF document picker,
 * clarifying that imported third-party Rime schemas/dictionaries remain user-supplied external data.
 */
@Composable
internal fun RimeImportLicenseDialog(
    target: RimeImportPickerTarget,
    onConfirm: (RimeImportPickerTarget) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.rime_license_dialog_title)) },
        text = { Text(text = stringResource(R.string.rime_license_dialog_body)) },
        confirmButton = {
            Button(onClick = { onConfirm(target) }) {
                Text(text = stringResource(R.string.rime_license_dialog_confirm))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.btn_cancel))
            }
        }
    )
}

/**
 * Confirmation dialog shown before resetting the user's Rime workspace back to DuoIME starter schemas.
 */
@Composable
internal fun RimeResetConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.rime_reset_dialog_title)) },
        text = { Text(text = stringResource(R.string.rime_reset_dialog_body)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(text = stringResource(R.string.rime_btn_reset))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.btn_cancel))
            }
        }
    )
}

@Composable
internal fun formatRimeWorkspaceFeedback(feedback: RimeWorkspaceFeedback): String = when (feedback) {
    is RimeWorkspaceFeedback.Imported -> stringResource(
        R.string.rime_feedback_imported,
        feedback.customSchemaCount,
        feedback.fileCount
    )
    RimeWorkspaceFeedback.Deployed -> stringResource(R.string.rime_feedback_deployed)
    RimeWorkspaceFeedback.ResetComplete -> stringResource(R.string.rime_feedback_reset)
    is RimeWorkspaceFeedback.Failed -> {
        val reasonText = formatImportFailureReason(feedback.reason)
        if (feedback.rolledBack) {
            stringResource(R.string.rime_feedback_failed_rolled_back, reasonText)
        } else {
            stringResource(R.string.rime_feedback_failed, reasonText)
        }
    }
}

@Composable
private fun formatImportFailureReason(reason: RimeImportFailureReason): String = when (reason) {
    RimeImportFailureReason.EMPTY_ARCHIVE -> stringResource(R.string.rime_err_empty_archive)
    RimeImportFailureReason.NO_YAML_FILES -> stringResource(R.string.rime_err_no_yaml)
    RimeImportFailureReason.TOO_MANY_FILES -> stringResource(R.string.rime_err_too_many_files)
    RimeImportFailureReason.FILE_TOO_LARGE -> stringResource(R.string.rime_err_file_too_large)
    RimeImportFailureReason.TOTAL_SIZE_TOO_LARGE -> stringResource(R.string.rime_err_total_too_large)
    RimeImportFailureReason.UNSAFE_PATH -> stringResource(R.string.rime_err_unsafe_path)
    RimeImportFailureReason.FORBIDDEN_FILE_TYPE -> stringResource(R.string.rime_err_forbidden_type)
    RimeImportFailureReason.INVALID_YAML -> stringResource(R.string.rime_err_invalid_yaml)
    RimeImportFailureReason.IO_ERROR -> stringResource(R.string.rime_err_io)
    RimeImportFailureReason.DEPLOY_FAILED -> stringResource(R.string.rime_err_deploy_failed)
    RimeImportFailureReason.MISSING_IMPORTED_SCHEMA -> stringResource(R.string.rime_err_missing_schema)
}

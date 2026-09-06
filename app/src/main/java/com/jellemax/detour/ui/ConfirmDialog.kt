package com.jellemax.detour.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * The confirmation an irreversible action goes through, in the shape the
 * delete-trip dialog already established: [title] asks, [text] names what is
 * lost, and [confirmLabel] carries the verb in the error colour so the
 * destructive button never reads as the safe default. Cancel is always the
 * dismiss button, so the safe answer is in the same place every time.
 *
 * [onDismiss] is what closes the dialog and runs on both paths — it fires
 * before [onConfirm], so a caller's "which one is pending" state is already
 * cleared by the time the action itself runs.
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

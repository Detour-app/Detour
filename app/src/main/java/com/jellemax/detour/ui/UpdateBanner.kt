package com.jellemax.detour.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jellemax.detour.update.UpdateStatus

/**
 * The standing "you are out of date" state, not an announcement — so no dismiss
 * button. It goes away when the update is installed, or when a newer one
 * replaces it.
 */
@Composable
fun UpdateBanner(
    status: UpdateStatus,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (status is UpdateStatus.None) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (status) {
                is UpdateStatus.Available -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Detour ${status.update.version} is available",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onDownload) { Text("Download") }
                }

                is UpdateStatus.Downloading -> {
                    Text(
                        "Downloading ${status.update.version}…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // -1f means the server sent no length; an indeterminate bar
                    // is honest where a fake percentage is not.
                    if (status.fraction >= 0f) {
                        LinearProgressIndicator(
                            progress = { status.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }

                is UpdateStatus.Downloaded -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Detour ${status.update.version} is ready",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onInstall) { Text("Install") }
                }

                is UpdateStatus.Failed -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Download of ${status.update.version} failed",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onDownload) { Text("Retry") }
                }

                UpdateStatus.None -> Unit
            }
        }
    }
}

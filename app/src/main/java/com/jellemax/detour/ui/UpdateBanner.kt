package com.jellemax.detour.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jellemax.detour.update.UpdateStatus

/**
 * The standing "you are out of date" state, not an announcement — so no dismiss
 * button. It goes away when the update is installed, or when a newer one
 * replaces it.
 *
 * A pointer, since #277: it says which phase the update is in and hands the
 * rider to Settings, where the one control lives. It used to carry its own
 * Download button, Install button and progress bar, which made two live copies
 * of one control on two screens that never referenced each other — the split
 * #277 exists to remove. Two Download buttons is also two ways to open a second
 * socket onto the same partial file, and the phase shown here is read from the
 * same process-scoped `UpdateState` the Settings row reads, so a pointer cannot
 * disagree with what it points at.
 */
@Composable
fun UpdateBanner(
    status: UpdateStatus,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = when (status) {
        is UpdateStatus.Available -> "Detour ${status.update.version} is available"
        is UpdateStatus.Downloading -> "Downloading ${status.update.version}…"
        is UpdateStatus.Downloaded -> "Detour ${status.update.version} is ready to install"
        is UpdateStatus.Failed -> "Download of ${status.update.version} failed"
        UpdateStatus.None -> return
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Settings",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

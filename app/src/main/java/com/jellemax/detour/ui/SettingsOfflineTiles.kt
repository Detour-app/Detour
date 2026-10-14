package com.jellemax.detour.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.jellemax.detour.R
import com.jellemax.detour.map.RouteTileCache

/** The clear action #439 asks of the route-corridor tile cache. */
@Composable
internal fun OfflineTilesRow() {
    val context = LocalContext.current
    var cleared by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_offline_tiles), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(
                    if (cleared) R.string.settings_offline_tiles_cleared else R.string.settings_offline_tiles_hint,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { RouteTileCache.clear(context) { cleared = true } }, enabled = !cleared) {
            Text(stringResource(R.string.settings_offline_tiles_clear))
        }
    }
}

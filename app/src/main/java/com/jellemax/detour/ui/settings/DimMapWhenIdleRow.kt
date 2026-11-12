package com.jellemax.detour.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.R
import com.jellemax.detour.data.Settings

/** The Settings → Map row for [Settings.dimMapWhenIdle]. */
@Composable
internal fun DimMapWhenIdleRow() {
    val on by Settings.dimMapWhenIdle.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_dim_map_idle), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.settings_dim_map_idle_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = { Settings.setDimMapWhenIdle(it) })
    }
}

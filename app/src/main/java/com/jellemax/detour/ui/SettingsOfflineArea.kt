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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.R
import com.jellemax.detour.data.LatLonBox
import com.jellemax.detour.data.OfflineArea
import com.jellemax.detour.data.Settings
import com.jellemax.detour.map.MapViewport
import com.jellemax.detour.map.RouteTileCache

private const val BYTES_PER_MB = 1024L * 1024

/**
 * "Offline map of this view" (#510): keeps the area the map last showed, with
 * its size before and after and a delete. One area at a time; a new download
 * replaces it. The area is whatever [MapViewport] last saw, so it is chosen by
 * panning the map and then coming here.
 */
@Composable
internal fun OfflineAreaRow() {
    val context = LocalContext.current
    val darkTheme = isAppDarkTheme(Settings.theme.collectAsStateWithLifecycle().value)
    val box by MapViewport.box.collectAsStateWithLifecycle()
    var savedBytes by remember { mutableStateOf<Long?>(null) }
    var fraction by remember { mutableStateOf<Float?>(null) }
    remember { RouteTileCache.areaBytes(context) { savedBytes = it } }

    val area = box
    val fits = area != null && OfflineArea.fits(area)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_offline_area), style = MaterialTheme.typography.bodyLarge)
            OfflineAreaNote(offlineAreaStatus(area, fraction))
            savedBytes?.let { OfflineAreaNote(stringResource(R.string.settings_offline_area_saved, it / BYTES_PER_MB)) }
        }
        Column(horizontalAlignment = Alignment.End) {
            TextButton(
                onClick = {
                    val chosen = area ?: return@TextButton
                    fraction = 0f
                    RouteTileCache.downloadArea(context, chosen, darkTheme) { p ->
                        savedBytes = p.bytes
                        fraction = p.fraction.takeUnless { p.complete }
                    }
                },
                enabled = fits && fraction == null,
            ) { Text(stringResource(R.string.settings_offline_area_download)) }
            if (savedBytes != null) {
                TextButton(onClick = { RouteTileCache.clearArea(context) { savedBytes = null; fraction = null } }) {
                    Text(stringResource(R.string.settings_offline_area_delete))
                }
            }
        }
    }
}

/** The line under the title: progress while downloading, otherwise what the current view would cost. */
@Composable
private fun offlineAreaStatus(area: LatLonBox?, fraction: Float?): String {
    val limitMb = OfflineArea.MAX_BYTES / BYTES_PER_MB
    val estimateMb = area?.let { OfflineArea.estimateBytes(it) / BYTES_PER_MB } ?: 0
    return when {
        fraction != null -> stringResource(R.string.settings_offline_area_progress, (fraction * 100).toInt())
        area == null -> stringResource(R.string.settings_offline_area_no_map)
        !OfflineArea.fits(area) -> stringResource(R.string.settings_offline_area_too_big, estimateMb, limitMb)
        else -> stringResource(R.string.settings_offline_area_estimate, estimateMb)
    }
}

@Composable
private fun OfflineAreaNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

package com.jellemax.detour.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * "Download this area" (#510), opened from the map's top rail: keeps the view
 * the map is showing, with its size before and after and a delete. One area at
 * a time; a new download replaces it. The area is whatever [MapViewport] last
 * saw, which is the view the rider just chose by panning and zooming.
 */
@Composable
internal fun OfflineAreaDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.map_offline_area)) },
        text = { OfflineAreaBody() },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.map_close)) } },
    )
}

@Composable
private fun OfflineAreaBody() {
    val context = LocalContext.current
    val darkTheme = isAppDarkTheme(Settings.theme.collectAsStateWithLifecycle().value)
    val box by MapViewport.box.collectAsStateWithLifecycle()
    var savedBytes by remember { mutableStateOf<Long?>(null) }
    var fraction by remember { mutableStateOf<Float?>(null) }
    var capped by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { RouteTileCache.areaBytes(context) { savedBytes = it } }

    val area = box
    val fits = area != null && OfflineArea.fits(area)
    Column(Modifier.fillMaxWidth()) {
        Column {
            OfflineAreaNote(offlineAreaStatus(area, fraction))
            savedBytes?.let { OfflineAreaNote(stringResource(R.string.map_offline_area_saved, it / BYTES_PER_MB)) }
            if (capped) OfflineAreaNote(stringResource(R.string.map_offline_area_capped))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = {
                    val chosen = area ?: return@TextButton
                    fraction = 0f
                    capped = false
                    RouteTileCache.downloadArea(context, chosen, darkTheme) { p ->
                        if (!p.failed) savedBytes = p.bytes
                        capped = p.capped
                        fraction = p.fraction.takeUnless { p.complete }
                    }
                },
                enabled = fits && fraction == null,
            ) { Text(stringResource(R.string.map_offline_area_download)) }
            if (savedBytes != null) {
                TextButton(
                    onClick = {
                        RouteTileCache.clearArea(context) {
                            savedBytes = null
                            fraction = null
                            capped = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.map_offline_area_delete))
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
        fraction != null -> stringResource(R.string.map_offline_area_progress, (fraction * 100).toInt())
        area == null -> stringResource(R.string.map_offline_area_no_map)
        !OfflineArea.fits(area) -> stringResource(R.string.map_offline_area_too_big, estimateMb, limitMb)
        else -> stringResource(R.string.map_offline_area_estimate, estimateMb)
    }
}

@Composable
private fun OfflineAreaNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

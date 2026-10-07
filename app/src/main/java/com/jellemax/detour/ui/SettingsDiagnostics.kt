package com.jellemax.detour.ui

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.R
import com.jellemax.detour.data.AddressSource
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.Settings
import com.jellemax.detour.perf.PerfSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The #84 timing series: turn it on, and get it off the device.
 *
 * Its own file rather than another section in `SettingsScreen.kt`, which is
 * already past the 1000-line hard limit.
 *
 * The export is not a convenience. On a release install this file cannot be
 * read over adb at all — `run-as` refuses a non-debuggable package, `adbd`
 * refuses `adb root` on a production build, and since Android 12 `adb backup`
 * carries no app data for a non-debuggable app — so without a share sheet the
 * whole seam would be write-only in exactly the builds whose history makes it
 * worth having.
 */
@Composable
fun DiagnosticsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tracing by Settings.perfTracing.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<String?>(null) }

    SettingsSection(stringResource(R.string.settings_diagnostics)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_record_timings), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.settings_record_timings_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = tracing,
                onCheckedChange = {
                    PerfSink.setEnabled(context, it)
                    status = null
                },
            )
        }
        TextButton(onClick = {
            scope.launch {
                val uri = withContext(Dispatchers.IO) { PerfSink.writeForShare(context) }
                status = if (uri == null) context.getString(R.string.settings_nothing_recorded) else null
                if (uri != null) context.startActivity(shareTimingsIntent(uri))
            }
        }) { Text(stringResource(R.string.settings_export_timings)) }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        // Announcements the #273 gate dropped. Empty is the expected steady state:
        // a row here is either a duplicate the gate correctly swallowed or the
        // first evidence of a real transition going missing, and there is nowhere
        // else that distinction can be seen after the logcat buffer rolls.
        val suppressed = remember { Settings.suppressedPlaceEvents() }
        Text(
            stringResource(R.string.settings_suppressed_events),
            style = MaterialTheme.typography.titleSmall,
        )
        if (suppressed.isEmpty()) {
            Text(stringResource(R.string.settings_none), style = MaterialTheme.typography.bodySmall)
        } else {
            for (row in suppressed) {
                Text(
                    stringResource(
                        R.string.settings_suppressed_row,
                        row.kind.name.lowercase(),
                        row.placeId,
                        row.reason.name.lowercase().replace('_', ' '),
                        row.tsMs,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        ServerConfigurationReadout()
    }
}

/**
 * The addresses this install actually resolved and which slot supplied each
 * (#352) — the only place a self-hoster can see whether an announcement
 * reached the phone and whether it is winning. Read-only on purpose: an
 * announced value is never promoted into the rider's own typed config (#177).
 */
@Composable
private fun ServerConfigurationReadout() {
    // Keyed on a manual refresh: the Servers spoke above this on the same page
    // accepts, declines and saves through plain prefs, which Compose cannot
    // observe, so an unkeyed read would keep showing the state before the tap.
    var refreshes by remember { mutableIntStateOf(0) }
    val services = remember(refreshes) { RoutingServer.resolvedServices() }
    val features = remember(refreshes) { RoutingServer.knownServerFeatures() }
    Column {
        Text(stringResource(R.string.settings_server_config_resolved), style = MaterialTheme.typography.titleSmall)
        for (s in services) {
            val line = buildString {
                append("${s.name} · ${s.address.value.ifBlank { "—" }} · ")
                append(stringResource(sourceLabel(s.address.source)))
                if (s.pending.isNotBlank()) {
                    append(" · ")
                    append(stringResource(R.string.settings_service_announced, s.pending))
                }
                if (s.declined.isNotBlank()) {
                    append(" · ")
                    append(stringResource(R.string.settings_service_declined, s.declined))
                }
            }
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
        // null and empty are different answers here: "never asked" vs "asked and
        // the server advertises nothing" — the distinction #352 exists to show.
        Text(
            stringResource(
                R.string.settings_features,
                when {
                    features == null -> stringResource(R.string.settings_features_never)
                    features.isEmpty() -> stringResource(R.string.settings_features_none)
                    else -> features.joinToString(", ")
                },
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { refreshes++ }) { Text(stringResource(R.string.settings_refresh)) }
    }
}

@StringRes
private fun sourceLabel(source: AddressSource): Int = when (source) {
    AddressSource.TYPED -> R.string.settings_source_typed
    AddressSource.ANNOUNCED -> R.string.settings_source_announced
    AddressSource.GENERAL -> R.string.settings_source_general
    AddressSource.BAKED -> R.string.settings_source_baked
    AddressSource.NONE -> R.string.settings_source_none
}

/** The read grant is what makes the content:// Uri usable on the other side —
 *  the provider is not exported, so without it the receiver sees nothing. Same
 *  shape as `shareGpxIntent` in TripDetailScreen.kt. */
private fun shareTimingsIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "application/json"
    putExtra(Intent.EXTRA_STREAM, uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

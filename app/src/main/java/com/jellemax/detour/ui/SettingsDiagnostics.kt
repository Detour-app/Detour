package com.jellemax.detour.ui

import android.content.Intent
import android.net.Uri
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    SettingsSection("Diagnostics") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Record function timings", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Times the slow parts of the app against how much data they " +
                        "ran over, so it is possible to see whether they are getting " +
                        "slower as your history grows. Stays on this device — never " +
                        "synced, never backed up.",
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
                status = if (uri == null) "Nothing recorded yet" else null
                if (uri != null) context.startActivity(shareTimingsIntent(uri))
            }
        }) { Text("Export timings") }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        // Announcements the #273 gate dropped. Empty is the expected steady state:
        // a row here is either a duplicate the gate correctly swallowed or the
        // first evidence of a real transition going missing, and there is nowhere
        // else that distinction can be seen after the logcat buffer rolls.
        val suppressed = remember { Settings.suppressedPlaceEvents() }
        Text(
            "Suppressed circle events",
            style = MaterialTheme.typography.titleSmall,
        )
        if (suppressed.isEmpty()) {
            Text("None", style = MaterialTheme.typography.bodySmall)
        } else {
            for (row in suppressed) {
                Text(
                    "${row.kind.name.lowercase()} · place ${row.placeId} · " +
                        "${row.reason.name.lowercase().replace('_', ' ')} · ${row.tsMs}",
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
        Text("Server configuration (resolved)", style = MaterialTheme.typography.titleSmall)
        for (s in services) {
            val line = buildString {
                append("${s.name} · ${s.address.value.ifBlank { "—" }} · ${sourceLabel(s.address.source)}")
                if (s.pending.isNotBlank()) append(" · announced ${s.pending}, awaiting consent")
                if (s.declined.isNotBlank()) append(" · declined ${s.declined}")
            }
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
        // null and empty are different answers here: "never asked" vs "asked and
        // the server advertises nothing" — the distinction #352 exists to show.
        Text(
            "Features · " + when {
                features == null -> "never probed, or no probe has answered"
                features.isEmpty() -> "none advertised"
                else -> features.joinToString(", ")
            },
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { refreshes++ }) { Text("Refresh") }
    }
}

private fun sourceLabel(source: AddressSource): String = when (source) {
    AddressSource.TYPED -> "typed for this service"
    AddressSource.ANNOUNCED -> "announced by server"
    AddressSource.GENERAL -> "typed (one address)"
    AddressSource.BAKED -> "built-in default"
    AddressSource.NONE -> "not configured"
}

/** The read grant is what makes the content:// Uri usable on the other side —
 *  the provider is not exported, so without it the receiver sees nothing. Same
 *  shape as `shareGpxIntent` in TripDetailScreen.kt. */
private fun shareTimingsIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "application/json"
    putExtra(Intent.EXTRA_STREAM, uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

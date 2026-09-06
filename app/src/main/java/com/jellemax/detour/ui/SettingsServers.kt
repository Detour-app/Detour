package com.jellemax.detour.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.ConfigFile
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.ServerConfig
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings for a custom GraphHopper server. Built-in defaults are never
 * displayed: empty fields mean the built-in server is used.
 */
@Composable
internal fun ServerSection() {
    val context = LocalContext.current
    val custom = remember { RoutingServer.loadCustom() }
    val builtInAvailable = remember { RoutingServer.bakedDefaults().usable }
    var url by remember { mutableStateOf(custom?.url ?: "") }
    var apiUrl by remember { mutableStateOf(custom?.apiUrl ?: "") }
    var routingUrl by remember { mutableStateOf(custom?.routingUrl ?: "") }
    var geocoderUrl by remember { mutableStateOf(custom?.geocoderUrl ?: "") }
    var idpIssuer by remember { mutableStateOf(custom?.idpIssuer ?: "") }
    // Only the general address is shown by default: a rider on a one-hostname
    // deployment never needs the rest, and four more URL boxes read as four more
    // things that must be filled in. Opens already expanded when any of them is
    // set, so a split deployment does not look unconfigured on the way back in.
    var showPerService by remember {
        mutableStateOf(
            listOf(apiUrl, routingUrl, geocoderUrl).any { it.isNotBlank() },
        )
    }
    val geocoderPublicFallback by Settings.geocoderPublicFallback.collectAsStateWithLifecycle()
    var saved by remember { mutableStateOf(false) }

    SettingsSection("Server") {
        Text(
            when {
                custom != null -> "Custom server: ${custom.url}"
                builtInAvailable -> "Using built-in server"
                else -> "Public servers only"
            },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Optional: one self-hosted address for routing, search, sync " +
                "and the convoy live relay (see the one-hostname layout in " +
                "one-hostname layout). Leave empty to use the built-in " +
                "routing/search servers, with sync and live off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CredentialTextField(
            value = url, onValueChange = { url = it; saved = false },
            label = "Server URL",
            keyboardType = KeyboardType.Uri,
            placeholder = "https://…",
            modifier = Modifier.fillMaxWidth(),
        )
        CredentialTextField(
            value = idpIssuer, onValueChange = { idpIssuer = it; saved = false },
            label = "Sign-in realm URL (deprecated)",
            keyboardType = KeyboardType.Uri,
            placeholder = "https://idp.example.com/realms/detour",
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Deprecated — newer servers tell the app which realm to use, so " +
                "leave this empty unless your server has not been updated. " +
                "Anything typed here still wins over what the server says. " +
                "Changing it signs this device out: tokens from one realm mean " +
                "nothing to another.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { showPerService = !showPerService }) {
            Text(if (showPerService) "Hide per-service addresses" else "Different address per service")
        }
        if (showPerService) {
            Text(
                "For a deployment split across hostnames. Anything left empty " +
                    "uses the server address above. Routing and search cannot " +
                    "share one host with sync, because the API answers /api/trips " +
                    "and the search server answers /api/ — so one address cannot " +
                    "serve both.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CredentialTextField(
                value = apiUrl, onValueChange = { apiUrl = it; saved = false },
                label = "Sync & social API (optional)",
                keyboardType = KeyboardType.Uri,
                placeholder = "https://api.example.com",
                modifier = Modifier.fillMaxWidth(),
            )
            CredentialTextField(
                value = routingUrl, onValueChange = { routingUrl = it; saved = false },
                label = "Routing server (optional)",
                keyboardType = KeyboardType.Uri,
                placeholder = "https://route.example.com",
                modifier = Modifier.fillMaxWidth(),
            )
            CredentialTextField(
                value = geocoderUrl, onValueChange = { geocoderUrl = it; saved = false },
                label = "Search server (optional)",
                keyboardType = KeyboardType.Uri,
                placeholder = "https://search.example.com",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Fall back to public search", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "If your search server is unreachable, retry via the public " +
                        "Photon instance (komoot.io) — sends the query and your " +
                        "approximate location off your own hardware.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = geocoderPublicFallback,
                onCheckedChange = { Settings.setGeocoderPublicFallback(it) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                // Named arguments, not positional: ServerConfig's address fields
                // are all String and sit next to each other, so a positional call
                // that drifts out of order still compiles and quietly saves the
                // client id as an API address.
                val addresses = listOf(url, apiUrl, routingUrl, geocoderUrl, idpIssuer)
                if (addresses.all { it.isBlank() }) {
                    RoutingServer.clearCustom()
                } else {
                    RoutingServer.save(
                        ServerConfig(
                            url = url,
                            apiUrl = apiUrl,
                            routingUrl = routingUrl,
                            geocoderUrl = geocoderUrl,
                            idpIssuer = idpIssuer,
                            enabled = true,
                        ),
                    )
                }
                saved = true
            }) { Text(if (saved) "Saved ✓" else "Save server") }
            if (custom != null) {
                RemoveCustomServerButton(
                    builtInAvailable = builtInAvailable,
                    onRemove = {
                        RoutingServer.clearCustom()
                        url = ""; apiUrl = ""; routingUrl = ""; geocoderUrl = ""
                        idpIssuer = ""
                        saved = true
                    },
                )
            }
        }
    }
}

/** Clearing all five addresses in one tap, so it asks first. Its own
 *  composable because the confirmation is state nothing else on the Server
 *  card reads. */
@Composable
private fun RemoveCustomServerButton(builtInAvailable: Boolean, onRemove: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    TextButton(onClick = { confirming = true }) { Text("Remove custom server") }
    if (confirming) {
        ConfirmDialog(
            title = "Remove the custom server?",
            text = "All five addresses are cleared and the app falls back to " +
                if (builtInAvailable) "the built-in server." else "the public servers.",
            confirmLabel = "Remove",
            onConfirm = onRemove,
            onDismiss = { confirming = false },
        )
    }
}

/** Backup sync with the owner's server (see backend/README.md). */
@Composable
internal fun SyncSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }

    val signedInAs by Settings.authUsername.collectAsStateWithLifecycle()

    SettingsSection("Backup sync") {
        Text(
            "Trips, explored area and badges are merged with your server after " +
                "every trip and on app start, so a reinstall restores everything. " +
                "Uses the Server URL under Server settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (signedInAs.isBlank()) "Not signed in — open Friends to create an account."
            else "Signed in as $signedInAs",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                enabled = !syncing && SyncClient.configured() && signedInAs.isNotBlank(),
                onClick = {
                    syncing = true
                    status = "Syncing…"
                    scope.launch {
                        status = withContext(Dispatchers.IO) {
                            try {
                                val r = SyncClient.sync()
                                "Synced: ${r.trips} trips, ${r.traces} trace segments, " +
                                    "${r.badges} badges"
                            } catch (e: Exception) {
                                "Sync failed: ${e.message}"
                            }
                        }
                        syncing = false
                    }
                },
            ) { Text("Sync now") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * Server settings to and from a file the user keeps outside the app.
 * Preferences die with an uninstall and the baked-in defaults only exist in
 * APKs built from a local.properties; this is what makes a reinstall a two-tap
 * restore instead of retyping a URL.
 */
@Composable
internal fun ConfigFileSection() {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ConfigFile.MIME_TYPE)
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = try {
            ConfigFile.export(context, uri)
            "Config exported"
        } catch (e: Exception) {
            "Export failed: ${e.message}"
        }
    }
    // The picked file is held rather than applied: importing overwrites the
    // server URL and the sign-in token with no preview and no undo, so the
    // confirmation goes here, after the file is known, not in front of the
    // picker where it would only be asking about opening one.
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingImport = uri
    }

    fun runImport(uri: Uri) {
        status = try {
            ConfigFile.import(context, uri)
            "Config imported — restart the app to use the new servers"
        } catch (e: Exception) {
            "Import failed: ${e.message}"
        }
    }

    SettingsSection("Server config file") {
        Text(
            "Save the server URL and your sign-in to a file. After a " +
                "reinstall, import it instead of typing everything again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "The file contains your sign-in token. Keep it somewhere private — " +
                "anyone holding it is signed in as you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                status = null
                exportLauncher.launch(ConfigFile.SUGGESTED_NAME)
            }) { Text("Export config") }
            TextButton(onClick = {
                status = null
                // Some file pickers hide application/json; */* keeps the file reachable.
                importLauncher.launch(arrayOf(ConfigFile.MIME_TYPE, "*/*"))
            }) { Text("Import config") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    pendingImport?.let { uri ->
        ConfirmDialog(
            title = "Import this config?",
            text = "It replaces the server addresses and the sign-in token on this " +
                "device with the file's. What is there now is not recoverable.",
            confirmLabel = "Import",
            onConfirm = { runImport(uri) },
            onDismiss = { pendingImport = null },
        )
    }
}

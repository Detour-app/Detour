package com.jellemax.detour.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import com.jellemax.detour.presentation.ServersSyncStatus
import com.jellemax.detour.presentation.failureText
import com.jellemax.detour.presentation.serversSyncStatusFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Servers & sync spoke: state first, then what to tap, then the detail.
 *
 * It used to open on three cards of prose with the controls between the
 * paragraphs — a rider setting up their own server read about twenty-five lines
 * of `bodySmall` before reaching the one box to fill in, and nothing on screen
 * said whether the address already saved actually worked. The order is now
 * status rows, the three actions, then configuration, so the first screenful
 * answers "what is set up" and "what can I do" and the reading is optional.
 *
 * One composable rather than three siblings because the three cards share
 * state: the status rows read the saved server and the sign-in, and the action
 * row's result line is written by the sync and by the file import alike.
 */
@Composable
internal fun ServersSyncSpoke(scrollState: ScrollState) {
    val scope = rememberCoroutineScope()
    val builtInAvailable = remember { RoutingServer.bakedDefaults().usable }
    var custom by remember { mutableStateOf(RoutingServer.loadCustom()) }
    val signedInAs by Settings.authUsername.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<String?>(null) }

    // Re-derived when an action finishes, which is also when the sync stamp can
    // have moved. Not a StateFlow: `last_sync_ms` is a plain preference with no
    // observable behind it, and giving it one is a storage change.
    val rows = remember(custom, signedInAs, status) {
        serversSyncStatusFrom(
            custom, builtInAvailable, signedInAs,
            Settings.lastSyncMs(), System.currentTimeMillis(),
        )
    }

    // A section's offset inside the scrolling column is its top in root space
    // minus the first card's: both move by the same amount when the list
    // scrolls, so the difference is the scroll value that parks the section at
    // the top of the viewport, whatever the scroll position is when it is read.
    var contentTop by remember { mutableFloatStateOf(0f) }
    var serverTop by remember { mutableFloatStateOf(0f) }
    var syncTop by remember { mutableFloatStateOf(0f) }
    var backupTop by remember { mutableFloatStateOf(0f) }
    fun jumpTo(top: Float) {
        scope.launch { scrollState.animateScrollTo((top - contentTop).toInt().coerceAtLeast(0)) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ServersStatusCard(
            rows,
            onServer = { jumpTo(serverTop) },
            onSync = { jumpTo(syncTop) },
            onBackup = { jumpTo(backupTop) },
            modifier = Modifier.onGloballyPositioned { contentTop = it.positionInRoot().y },
        )
        ServersActionsCard(
            canSync = SyncClient.configured() && signedInAs.isNotBlank(),
            status = status,
            onStatus = { status = it },
        )
        ServerSection(
            custom = custom,
            builtInAvailable = builtInAvailable,
            onCustomChange = { custom = it },
            modifier = Modifier.onGloballyPositioned { serverTop = it.positionInRoot().y },
        )
        SyncSection(signedInAs, Modifier.onGloballyPositioned { syncTop = it.positionInRoot().y })
        ConfigFileSection(Modifier.onGloballyPositioned { backupTop = it.positionInRoot().y })
    }
}

/**
 * The dashboard. Each row says what is set, not why it matters, and tapping it
 * scrolls to the section that changes it — the same row shape the Settings root
 * uses, so a spoke that summarises itself reads like the hub that led here.
 */
@Composable
private fun ServersStatusCard(
    status: ServersSyncStatus,
    onServer: () -> Unit,
    onSync: () -> Unit,
    onBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("STATUS")
        ListCard {
            HubRow(
                Icons.Outlined.Dns, "Server", onServer,
                subtitle = status.server, paintCard = false,
            )
            CardDivider()
            HubRow(
                Icons.Outlined.CloudSync, "Backup sync", onSync,
                subtitle = status.sync, paintCard = false,
            )
            CardDivider()
            HubRow(
                Icons.Outlined.Description, "Server config file", onBackup,
                subtitle = status.backup, paintCard = false,
            )
        }
    }
}

/**
 * Sync now, Export and Import above the configuration instead of one per card
 * below it. Riders reach for these far more often than they retype an address,
 * and "Sync now" used to sit under the fold on a 640dp screen.
 */
@Composable
private fun ServersActionsCard(
    canSync: Boolean,
    status: String?,
    onStatus: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSection("Actions", modifier) {
        SyncNowButton(canSync, onStatus)
        ConfigFileButtons(onStatus)
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** "Sync now" and the round trip behind it. Its own composable because
 *  `syncing` is state nothing else on the card reads — the same reason
 *  [RemoveCustomServerButton] holds its own confirmation. */
@Composable
private fun SyncNowButton(
    enabled: Boolean,
    onStatus: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }
    TextButton(
        modifier = modifier,
        enabled = enabled && !syncing,
        onClick = {
            syncing = true
            onStatus("Syncing…")
            scope.launch {
                onStatus(
                    withContext(Dispatchers.IO) {
                        try {
                            val r = SyncClient.sync()
                            "Synced: ${r.trips} trips, ${r.traces} trace segments, " +
                                "${r.badges} badges"
                        } catch (e: Exception) {
                            failureText("Sync", e)
                        }
                    },
                )
                syncing = false
            }
        },
    ) { Text("Sync now") }
}

/** Export and import, with the file pickers and the import confirmation that
 *  only these two buttons use. */
@Composable
private fun ConfigFileButtons(onStatus: (String?) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ConfigFile.MIME_TYPE)
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onStatus(
            try {
                ConfigFile.export(context, uri)
                "Config exported"
            } catch (e: Exception) {
                failureText("Export", e)
            },
        )
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
        onStatus(
            try {
                ConfigFile.import(context, uri)
                "Config imported — restart the app to use the new servers"
            } catch (e: Exception) {
                failureText("Import", e)
            },
        )
    }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            onStatus(null)
            exportLauncher.launch(ConfigFile.SUGGESTED_NAME)
        }) { Text("Export config") }
        TextButton(onClick = {
            onStatus(null)
            // Some file pickers hide application/json; */* keeps the file reachable.
            importLauncher.launch(arrayOf(ConfigFile.MIME_TYPE, "*/*"))
        }) { Text("Import config") }
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

/**
 * Settings for a custom GraphHopper server. Built-in defaults are never
 * displayed: empty fields mean the built-in server is used.
 *
 * One field and two buttons by default. The per-service addresses, the
 * deprecated realm and the public-search fallback are all things a
 * one-hostname install never touches, and they moved behind [ServerAdvanced].
 */
@Composable
private fun ServerSection(
    custom: ServerConfig?,
    builtInAvailable: Boolean,
    onCustomChange: (ServerConfig?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One draft, not five loose `var`s. Every edit is a `copy(name = …)`, so an
    // address still cannot land in the wrong slot — which is what the old
    // save call's named arguments were guarding against, now guaranteed by
    // construction instead of by a comment.
    var draft by remember { mutableStateOf(custom ?: ServerConfig()) }
    var saved by remember { mutableStateOf(false) }
    // Opens already expanded when anything inside it is set, so a split
    // deployment — or a server still on the deprecated realm field — does not
    // look unconfigured on the way back in.
    var showAdvanced by remember {
        mutableStateOf(
            listOf(draft.apiUrl, draft.routingUrl, draft.geocoderUrl, draft.idpIssuer)
                .any { it.isNotBlank() },
        )
    }

    SettingsSection("Server", modifier) {
        LearnMore(
            "Optional: your own address for routing, search, sync and live.",
            "Optional: one self-hosted address for routing, search, sync " +
                "and the convoy live relay (see the one-hostname layout in " +
                "one-hostname layout). Leave empty to use the built-in " +
                "routing/search servers, with sync and live off.",
        )
        CredentialTextField(
            value = draft.url,
            onValueChange = { draft = draft.copy(url = it); saved = false },
            label = "Server URL",
            keyboardType = KeyboardType.Uri,
            placeholder = "https://…",
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                val addresses = listOf(
                    draft.url, draft.apiUrl, draft.routingUrl, draft.geocoderUrl, draft.idpIssuer,
                )
                val next = if (addresses.all { it.isBlank() }) null else draft.copy(enabled = true)
                if (next == null) RoutingServer.clearCustom() else RoutingServer.save(next)
                onCustomChange(next)
                saved = true
            }) { Text(if (saved) "Saved ✓" else "Save server") }
            if (custom != null) {
                RemoveCustomServerButton(
                    builtInAvailable = builtInAvailable,
                    onRemove = {
                        RoutingServer.clearCustom()
                        draft = ServerConfig()
                        onCustomChange(null)
                        saved = true
                    },
                )
            }
        }
        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(if (showAdvanced) "Hide advanced" else "Advanced")
        }
        if (showAdvanced) {
            ServerAdvanced(draft = draft, onDraftChange = { draft = it; saved = false })
        }
    }
}

/**
 * The three things only a split or an out-of-date deployment needs: an address
 * per service, the deprecated sign-in realm, and the public-search fallback.
 *
 * Takes the whole draft rather than four values and four setters, which would
 * be eight parameters for one card — the gate in `boundaries.md` §8.4.
 */
@Composable
private fun ServerAdvanced(
    draft: ServerConfig,
    onDraftChange: (ServerConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val geocoderPublicFallback by Settings.geocoderPublicFallback.collectAsStateWithLifecycle()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            value = draft.apiUrl, onValueChange = { onDraftChange(draft.copy(apiUrl = it)) },
            label = "Sync & social API (optional)",
            keyboardType = KeyboardType.Uri,
            placeholder = "https://api.example.com",
            modifier = Modifier.fillMaxWidth(),
        )
        CredentialTextField(
            value = draft.routingUrl, onValueChange = { onDraftChange(draft.copy(routingUrl = it)) },
            label = "Routing server (optional)",
            keyboardType = KeyboardType.Uri,
            placeholder = "https://route.example.com",
            modifier = Modifier.fillMaxWidth(),
        )
        CredentialTextField(
            value = draft.geocoderUrl,
            onValueChange = { onDraftChange(draft.copy(geocoderUrl = it)) },
            label = "Search server (optional)",
            keyboardType = KeyboardType.Uri,
            placeholder = "https://search.example.com",
            modifier = Modifier.fillMaxWidth(),
        )
        CredentialTextField(
            value = draft.idpIssuer, onValueChange = { onDraftChange(draft.copy(idpIssuer = it)) },
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
private fun SyncSection(signedInAs: String, modifier: Modifier = Modifier) {
    SettingsSection("Backup sync", modifier) {
        LearnMore(
            "Your trips, explored area and badges are kept on your own server.",
            "Trips, explored area and badges are merged with your server after " +
                "every trip and on app start, so a reinstall restores everything. " +
                "Uses the Server URL under Server settings.",
        )
        Text(
            if (signedInAs.isBlank()) "Not signed in — open Friends to create an account."
            else "Signed in as $signedInAs",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Server settings to and from a file the user keeps outside the app.
 * Preferences die with an uninstall and the baked-in defaults only exist in
 * APKs built from a local.properties; this is what makes a reinstall a two-tap
 * restore instead of retyping a URL.
 */
@Composable
private fun ConfigFileSection(modifier: Modifier = Modifier) {
    SettingsSection("Server config file", modifier) {
        LearnMore(
            "Keep your server setup in a file, and import it after a reinstall.",
            "Save the server URL and your sign-in to a file. After a " +
                "reinstall, import it instead of typing everything again.",
            warning = "The file contains your sign-in token. Keep it somewhere private — " +
                "anyone holding it is signed in as you.",
        )
    }
}

/**
 * One line of copy, with the rest of it behind a tap.
 *
 * This spoke carried six `bodySmall` paragraphs for one hostname, which is what
 * pushed every control below the fold. None of the wording is gone — [detail]
 * and [warning] are the original paragraphs — it is only no longer the first
 * thing between a rider and the field they came to fill in.
 */
@Composable
private fun LearnMore(summary: String, detail: String, warning: String? = null) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (open) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            warning?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        TextButton(onClick = { open = !open }) {
            Text(if (open) "Show less" else "Learn more")
        }
    }
}

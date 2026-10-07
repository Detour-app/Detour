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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.R
import com.jellemax.detour.data.ConfigFile
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.ServerConfig
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SyncClient
import com.jellemax.detour.presentation.ServersSyncState
import com.jellemax.detour.presentation.failureText
import com.jellemax.detour.presentation.fileFailureText
import com.jellemax.detour.presentation.serversSyncStateFrom
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
        serversSyncStateFrom(
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
            onServerChange = { custom = RoutingServer.loadCustom() },
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
    state: ServersSyncState,
    onServer: () -> Unit,
    onSync: () -> Unit,
    onBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.settings_status))
        ListCard {
            HubRow(
                Icons.Outlined.Dns, stringResource(R.string.settings_server), onServer,
                subtitle = state.server, paintCard = false,
            )
            CardDivider()
            HubRow(
                Icons.Outlined.CloudSync, stringResource(R.string.settings_backup_sync), onSync,
                subtitle = state.sync, paintCard = false,
            )
            CardDivider()
            HubRow(
                Icons.Outlined.Description, stringResource(R.string.settings_server_config_file), onBackup,
                subtitle = state.backup, paintCard = false,
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
    onServerChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSection(stringResource(R.string.settings_actions), modifier) {
        SyncNowButton(canSync, onStatus)
        ConfigFileButtons(onStatus, onServerChange)
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }
    TextButton(
        modifier = modifier,
        enabled = enabled && !syncing,
        onClick = {
            syncing = true
            onStatus(context.getString(R.string.settings_syncing))
            scope.launch {
                onStatus(
                    withContext(Dispatchers.IO) {
                        try {
                            val r = SyncClient.sync()
                            context.getString(R.string.settings_synced, r.trips, r.traces, r.badges)
                        } catch (e: Exception) {
                            failureText("Sync", e)
                        }
                    },
                )
                syncing = false
            }
        },
    ) { Text(stringResource(R.string.settings_sync_now)) }
}

/** Export and import, with the file pickers and the import confirmation that
 *  only these two buttons use. */
@Composable
private fun ConfigFileButtons(
    onStatus: (String?) -> Unit,
    onServerChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ConfigFile.MIME_TYPE)
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onStatus(
            try {
                ConfigFile.export(context, uri)
                context.getString(R.string.settings_config_exported)
            } catch (e: Exception) {
                fileFailureText("Export", e)
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
                // An import writes RoutingServer behind the Server card's back,
                // so the card is told to re-read. Without this the status row,
                // the address fields and the Remove button all keep showing the
                // server that was there before the file was applied.
                onServerChange()
                context.getString(R.string.settings_config_imported)
            } catch (e: Exception) {
                fileFailureText("Import", e)
            },
        )
    }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            onStatus(null)
            exportLauncher.launch(ConfigFile.SUGGESTED_NAME)
        }) { Text(stringResource(R.string.settings_export_config)) }
        TextButton(onClick = {
            onStatus(null)
            // Some file pickers hide application/json; */* keeps the file reachable.
            importLauncher.launch(arrayOf(ConfigFile.MIME_TYPE, "*/*"))
        }) { Text(stringResource(R.string.settings_import_config)) }
    }

    pendingImport?.let { uri ->
        ConfirmDialog(
            title = stringResource(R.string.settings_import_title),
            text = stringResource(R.string.settings_import_text),
            confirmLabel = stringResource(R.string.settings_import),
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
    // Keyed on [custom]: save, remove and a config-file import all move the
    // saved server, and an unkeyed remember would leave the fields showing what
    // was there before. Typing does not change [custom], so a half-typed address
    // is never thrown away.
    var draft by remember(custom) { mutableStateOf(custom ?: ServerConfig()) }
    var saved by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf<String?>(null) }
    // Read once and cleared locally on a tap, rather than re-read from prefs:
    // the whole point of the accept/decline pair (#177) is a one-time prompt,
    // and re-reading after a state-changing tap would just show it again for
    // the instant before the next composition catches up.
    var pendingRouting by remember { mutableStateOf(RoutingServer.pendingRoutingAnnouncement()) }
    var pendingGeocoder by remember { mutableStateOf(RoutingServer.pendingGeocoderAnnouncement()) }
    // Opens already expanded when anything inside it is set, so a split
    // deployment — or a server still on the deprecated realm field — does not
    // look unconfigured on the way back in.
    var showAdvanced by remember {
        mutableStateOf(
            listOf(draft.apiUrl, draft.routingUrl, draft.geocoderUrl, draft.idpIssuer)
                .any { it.isNotBlank() },
        )
    }

    SettingsSection(stringResource(R.string.settings_server), modifier) {
        LearnMore(
            stringResource(R.string.settings_server_summary),
            stringResource(R.string.settings_server_detail),
        )
        pendingRouting?.let { baseUrl ->
            AnnouncedServicePrompt(
                message = stringResource(R.string.settings_announced_routing, baseUrl),
                onAccept = { RoutingServer.acceptRoutingAnnouncement(); pendingRouting = null },
                onDecline = { RoutingServer.declineRoutingAnnouncement(); pendingRouting = null },
            )
        }
        pendingGeocoder?.let { baseUrl ->
            AnnouncedServicePrompt(
                message = stringResource(R.string.settings_announced_search, baseUrl),
                onAccept = { RoutingServer.acceptGeocoderAnnouncement(); pendingGeocoder = null },
                onDecline = { RoutingServer.declineGeocoderAnnouncement(); pendingGeocoder = null },
            )
        }
        CredentialTextField(
            value = draft.url,
            onValueChange = { draft = draft.copy(url = it); saved = false; invalid = null },
            label = stringResource(R.string.settings_server_url),
            keyboardType = KeyboardType.Uri,
            placeholder = "https://…",
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(stringResource(if (showAdvanced) R.string.settings_hide_advanced else R.string.settings_advanced))
        }
        if (showAdvanced) {
            ServerAdvanced(draft = draft, onDraftChange = { draft = it; saved = false; invalid = null })
        }
        invalid?.let {
            Text(
                stringResource(R.string.settings_invalid_address, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // Save sits after Advanced, not before it, so it reads as the commit for
        // everything above rather than just the URL field — see #353. It is
        // already saving all five addresses; only its position was wrong.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                invalid = draft.invalidAddress
                if (invalid != null) return@TextButton
                val addresses = listOf(
                    draft.url, draft.apiUrl, draft.routingUrl, draft.geocoderUrl, draft.idpIssuer,
                )
                val next = if (addresses.all { it.isBlank() }) null else draft.copy(enabled = true)
                if (next == null) RoutingServer.clearCustom() else RoutingServer.save(next)
                onCustomChange(next)
                saved = true
            }) { Text(stringResource(if (saved) R.string.settings_saved else R.string.settings_save_server)) }
            if (custom != null) {
                RemoveCustomServerButton(
                    builtInAvailable = builtInAvailable,
                    onRemove = {
                        RoutingServer.clearCustom()
                        onCustomChange(null)
                        saved = true
                    },
                )
            }
        }
    }
}

/**
 * The one-time consent issue #177 asks for: shown only when the API server
 * announces a routing/geocoder base on a *different* host than itself — an
 * announcement on the API's own host is accepted without asking, per
 * `RoutingServer.nextAnnouncedServiceState`'s host-match branch, so this never
 * appears for the common one-hostname deployment at all.
 *
 * A tap clears the local flag immediately rather than waiting on `custom` to
 * change, because accepting or declining touches neither [ServerConfig] nor
 * [custom] — the announced base is never written into the rider's own typed
 * configuration (#177's own rule 4), so nothing else on this screen would
 * otherwise notice the decision was made.
 */
@Composable
private fun AnnouncedServicePrompt(
    message: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onAccept) { Text(stringResource(R.string.settings_use_it)) }
            TextButton(onClick = onDecline) { Text(stringResource(R.string.settings_no_thanks)) }
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
            stringResource(R.string.settings_advanced_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CredentialTextField(
            value = draft.apiUrl, onValueChange = { onDraftChange(draft.copy(apiUrl = it)) },
            label = stringResource(R.string.settings_api_url),
            keyboardType = KeyboardType.Uri,
            placeholder = "https://api.example.com",
            modifier = Modifier.fillMaxWidth(),
        )
        CredentialTextField(
            value = draft.routingUrl, onValueChange = { onDraftChange(draft.copy(routingUrl = it)) },
            label = stringResource(R.string.settings_routing_url),
            keyboardType = KeyboardType.Uri,
            placeholder = "https://route.example.com",
            modifier = Modifier.fillMaxWidth(),
        )
        CredentialTextField(
            value = draft.geocoderUrl,
            onValueChange = { onDraftChange(draft.copy(geocoderUrl = it)) },
            label = stringResource(R.string.settings_search_url),
            keyboardType = KeyboardType.Uri,
            placeholder = "https://search.example.com",
            modifier = Modifier.fillMaxWidth(),
        )
        CredentialTextField(
            value = draft.idpIssuer, onValueChange = { onDraftChange(draft.copy(idpIssuer = it)) },
            label = stringResource(R.string.settings_realm_url),
            keyboardType = KeyboardType.Uri,
            placeholder = "https://idp.example.com/realms/detour",
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.settings_realm_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_public_search), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.settings_public_search_hint),
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
    TextButton(onClick = { confirming = true }) { Text(stringResource(R.string.settings_remove_custom)) }
    if (confirming) {
        ConfirmDialog(
            title = stringResource(R.string.settings_remove_custom_title),
            text = stringResource(
                if (builtInAvailable) {
                    R.string.settings_remove_custom_text_builtin
                } else {
                    R.string.settings_remove_custom_text_public
                },
            ),
            confirmLabel = stringResource(R.string.settings_remove),
            onConfirm = onRemove,
            onDismiss = { confirming = false },
        )
    }
}

/** Backup sync with the owner's server (see backend/README.md). */
@Composable
private fun SyncSection(signedInAs: String, modifier: Modifier = Modifier) {
    SettingsSection(stringResource(R.string.settings_backup_sync), modifier) {
        LearnMore(
            stringResource(R.string.settings_sync_summary),
            stringResource(R.string.settings_sync_detail),
        )
        Text(
            if (signedInAs.isBlank()) stringResource(R.string.settings_not_signed_in)
            else stringResource(R.string.settings_signed_in_as, signedInAs),
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
    SettingsSection(stringResource(R.string.settings_server_config_file), modifier) {
        LearnMore(
            stringResource(R.string.settings_config_summary),
            stringResource(R.string.settings_config_detail),
            warning = stringResource(R.string.settings_config_warning),
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
            Text(stringResource(if (open) R.string.settings_show_less else R.string.settings_learn_more))
        }
    }
}

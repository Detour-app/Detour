package com.jellemax.detour.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.Friends
import com.jellemax.detour.data.RouteFiles
import com.jellemax.detour.data.RouteGpx
import com.jellemax.detour.data.RouteShare
import com.jellemax.detour.data.RouteStop
import com.jellemax.detour.data.RouteStore
import com.jellemax.detour.data.SavedRoute
import com.jellemax.detour.data.TravelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hands one exported route to whichever app the user picks — same shape as
 *  TripDetailScreen's shareGpxIntent, for a route file instead of a trip's. */
private fun shareRouteGpxIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
    type = RouteGpx.MIME_TYPE
    putExtra(Intent.EXTRA_STREAM, uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/**
 * Google Maps' `maps/dir/` directions URL takes the long-form travel modes
 * below — NOT [TravelMode.gmapsMode]'s single-letter codes ("d"),
 * which only mean anything to the `google.navigation:q=` scheme
 * navigateGoogleMaps (MapScreen.kt) uses. Mapped explicitly so the two URL
 * schemes' spellings don't get conflated by a future "simplification".
 */
private fun gmapsDirectionsTravelMode(mode: TravelMode): String = when (mode) {
    TravelMode.MOTO -> "two-wheeler"
    TravelMode.CAR -> "driving"
}

/**
 * Sends a multi-stop route to an external maps app with real via points.
 * [seedRouteNavigation] in MapScreen.kt only ever hands the map a single
 * destination — startNavigation() there always re-fetches a plain two-point
 * route, which would silently drop everything but the last stop — so a route
 * with more than two stops leaves the app instead. Google Maps' directions
 * URL takes up to 9 waypoints, the same cap navigateRoundTrip (MapScreen.kt)
 * works under; unlike that one, this is a real point A → B route rather than
 * a loop back to the start.
 *
 * Returns an error message on failure (no app to open the link), null on
 * success, so the caller can surface it the same way every other action here
 * reports through `status` — MapScreen's own navigateGoogleMaps has the
 * equivalent guard for its scheme.
 */
private fun navigateStopsExternally(context: Context, stops: List<RouteStop>, mode: TravelMode): String? {
    val origin = stops.first().at
    val destination = stops.last().at
    val via = stops.subList(1, stops.size - 1).take(9)
    val waypointsParam = if (via.isEmpty()) "" else
        "&waypoints=" + Uri.encode(via.joinToString("|") { "${it.at.lat},${it.at.lon}" })
    val uri = Uri.parse(
        "https://www.google.com/maps/dir/?api=1" +
            "&origin=${origin.lat},${origin.lon}" +
            "&destination=${destination.lat},${destination.lon}" +
            "&travelmode=${gmapsDirectionsTravelMode(mode)}" +
            waypointsParam
    )
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        null
    } catch (e: ActivityNotFoundException) {
        "No app to open a multi-stop route"
    }
}

/** "N stops · 12.3 km · 25 min", trimmed to whatever the route actually knows. */
private fun routeSubtitle(route: SavedRoute): String {
    val parts = mutableListOf("${route.stops.size} stops")
    route.distanceMeters?.let { parts.add(formatDistanceKm(it)) }
    route.timeMs?.let { parts.add(formatDurationHistory(it)) }
    return parts.joinToString(" · ")
}

/**
 * Saved multi-stop routes: create, navigate, rename, delete, share as a file,
 * export, and pull in whatever friends have shared. Follows
 * [SavedPlacesScreen]'s shape (top bar, empty state, dialogs) for the same
 * kind of list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutesScreen(
    onBack: () -> Unit,
    onCreateNew: () -> Unit,
    onEdit: (SavedRoute) -> Unit,
    onNavigate: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { RouteStore.ensureLoaded() }
    val routes by RouteStore.routes.collectAsStateWithLifecycle()

    var renaming by remember { mutableStateOf<SavedRoute?>(null) }
    var deleting by remember { mutableStateOf<SavedRoute?>(null) }
    var sharingTo by remember { mutableStateOf<SavedRoute?>(null) }
    var menuOpenFor by remember { mutableStateOf<Long?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    var exportTarget by remember { mutableStateOf<SavedRoute?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(RouteGpx.MIME_TYPE)
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        // GPX for a route with a routed polyline can be thousands of
        // trackpoints; off the main thread the same way refresh() below does
        // its network + store work, so writing one doesn't jank a frame.
        scope.launch {
            status = try {
                withContext(Dispatchers.IO) { RouteFiles.export(context, uri, target) }
                "Exported ${target.name}"
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // RouteGpx.parseGpx runs several regex scans over the whole document —
        // fine for the small ConfigFile.kt does this pattern for, not fine for
        // a multi-thousand-point track on the main thread.
        scope.launch {
            status = try {
                val imported = withContext(Dispatchers.IO) { RouteFiles.import(context, uri) }
                if (imported != null) "Imported ${imported.name}"
                else "That file isn't a route Detour can read"
            } catch (e: Exception) {
                "Import failed: ${e.message}"
            }
        }
    }

    fun navigate(route: SavedRoute) {
        // Two stops (the common A-to-B case) keep the whole route through the
        // in-app path; more than that and the middle stops only survive a
        // hand-off to an external maps app — see navigateStopsExternally.
        if (route.stops.size <= 2) {
            seedRouteNavigation(route)
            onNavigate()
        } else {
            status = navigateStopsExternally(context, route.stops, route.mode)
        }
    }

    fun shareFile(route: SavedRoute) {
        status = try {
            val uri = RouteFiles.writeForShare(context, route)
            context.startActivity(Intent.createChooser(shareRouteGpxIntent(uri), "Share route"))
            null
        } catch (e: ActivityNotFoundException) {
            "No app to receive a GPX file"
        } catch (e: Exception) {
            "Share failed: ${e.message}"
        }
    }

    fun refresh() {
        refreshing = true
        status = null
        scope.launch {
            status = try {
                // Saving locally, minting a fresh id per route and draining the
                // server-side inbox all happen in RouteShare.pullInbox() now —
                // shared with iOS instead of each platform growing its own copy.
                val pulled = withContext(Dispatchers.IO) { RouteShare.pullInbox() }
                if (pulled == 0) "No new shared routes" else "Pulled $pulled shared route(s)"
            } catch (e: Exception) {
                "Refresh failed: ${e.message}"
            }
            refreshing = false
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SubScreenTopBar("Routes", onBack, scrollBehavior) {
                IconButton(onClick = {
                    status = null
                    importLauncher.launch(RouteFiles.IMPORT_MIME_TYPES)
                }) {
                    Icon(Icons.Filled.FileUpload, contentDescription = "Import route")
                }
                IconButton(enabled = !refreshing, onClick = { refresh() }) {
                    if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Refresh, contentDescription = "Pull shared routes")
                }
                IconButton(onClick = onCreateNew) {
                    Icon(Icons.Filled.Add, contentDescription = "New route")
                }
            }
        },
    ) { padding ->
        if (routes.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.Route, contentDescription = null,
                    Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("No saved routes yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Plan a multi-stop ride on the map and save it, or import one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(routes, key = { it.id }) { route ->
                        Card {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { navigate(route) }
                                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(route.mode.icon, contentDescription = null)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(route.name, style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold)
                                    Text(
                                        routeSubtitle(route),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (route.sharedBy.isNotEmpty()) {
                                        Text(
                                            "from ${route.sharedBy}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                IconButton(onClick = { navigate(route) }) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = "Navigate ${route.name}")
                                }
                                Box {
                                    IconButton(onClick = { menuOpenFor = route.id }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = "More for ${route.name}")
                                    }
                                    DropdownMenu(
                                        expanded = menuOpenFor == route.id,
                                        onDismissRequest = { menuOpenFor = null },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Rename") },
                                            onClick = { renaming = route; menuOpenFor = null },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Edit") },
                                            onClick = { menuOpenFor = null; onEdit(route) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Share as file") },
                                            onClick = { menuOpenFor = null; shareFile(route) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Export…") },
                                            onClick = {
                                                menuOpenFor = null
                                                exportTarget = route
                                                exportLauncher.launch(RouteGpx.fileName(route))
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Send to a friend") },
                                            onClick = { menuOpenFor = null; sharingTo = route },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete") },
                                            onClick = { menuOpenFor = null; deleting = route },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { route ->
        RenameRouteDialog(
            initial = route.name,
            onSave = { RouteStore.rename(route.id, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { route ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete route?") },
            text = { Text("\"${route.name}\" will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = { RouteStore.remove(route.id); deleting = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    sharingTo?.let { route ->
        ShareRouteToFriendDialog(
            onDismiss = { sharingTo = null },
            onShare = { username ->
                scope.launch {
                    status = try {
                        withContext(Dispatchers.IO) { RouteShare.share(username, route) }
                        "Sent \"${route.name}\" to $username"
                    } catch (e: Exception) {
                        "Send failed: ${e.message}"
                    }
                    sharingTo = null
                }
            },
        )
    }
}

@Composable
private fun RenameRouteDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename route") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picks an accepted friend to send a route to — the same friend list
 *  FriendsScreen shows, loaded fresh here since this dialog can open without
 *  ever having visited that screen. */
@Composable
private fun ShareRouteToFriendDialog(onDismiss: () -> Unit, onShare: (String) -> Unit) {
    var friends by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            friends = withContext(Dispatchers.IO) { Friends.lists().friends }
        } catch (e: Exception) {
            error = e.message ?: "Could not reach the server"
            friends = emptyList()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send to a friend") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val list = friends
                val err = error
                when {
                    list == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    err != null -> Text(err, color = MaterialTheme.colorScheme.error)
                    list.isEmpty() -> Text("Add a friend first, on the Friends screen.")
                    else -> list.forEach { name ->
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onShare(name) }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

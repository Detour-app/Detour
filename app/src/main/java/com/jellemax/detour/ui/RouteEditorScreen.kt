package com.jellemax.detour.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jellemax.detour.data.GeocodeResult
import com.jellemax.detour.data.Geocoder
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteStop
import com.jellemax.detour.data.RouteStore
import com.jellemax.detour.data.RoutingClient
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.SavedRoute
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.presentation.formatCoordinatePair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

// Padding around the fitted stop spread, same spirit as TripDetailScreen's.
private const val FIT_PADDING_DP = 40
// Single color for every stop pin: the ordered list below already carries the
// distinction between them, so per-index colors (as MapScreen uses for spin
// candidates, which have no such list) would be noise here.
private const val STOP_PIN_COLOR = 0xFF2F80ED.toInt()

/**
 * Create or edit a saved multi-stop route on the map: tap to append a stop,
 * reorder/remove below, name it, pick a mode, save. [editing] non-null opens
 * with its stops loaded and keeps its id; null starts a fresh route.
 *
 * Hosts its own [MapView]/[MapOverlays] rather than reusing MapScreen's — the
 * two screens' map lifecycles and click handling are different enough
 * (MapScreen tracks a live position and fog-of-war; this one only needs a
 * tap-to-append map) that sharing the MapView itself would mean threading
 * this screen's whole state through MapScreen's already-large composable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteEditorScreen(editing: SavedRoute?, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current

    var stops by remember { mutableStateOf(editing?.stops ?: emptyList()) }
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var mode by remember { mutableStateOf(editing?.mode ?: Settings.tripMode.value) }
    var polyline by remember { mutableStateOf(editing?.polyline ?: emptyList<LatLon>()) }
    var distanceMeters by remember { mutableStateOf(editing?.distanceMeters) }
    var timeMs by remember { mutableStateOf(editing?.timeMs) }
    var routing by remember { mutableStateOf(false) }
    var routingError by remember { mutableStateOf<String?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<GeocodeResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Debounced live search, same shape as SavedPlacesScreen's add-place dialog.
    // `near` biases results toward the route so far, once it has a stop to bias from.
    LaunchedEffect(searchQuery) {
        // Clearing the box while a request is still in flight cancels this
        // effect mid-call, so the early return has to drop the spinner too.
        if (searchQuery.length < 3) {
            searchResults = emptyList()
            searching = false
            searched = false
            return@LaunchedEffect
        }
        delay(400)
        searching = true
        searchResults = try {
            withContext(Dispatchers.IO) { Geocoder.search(searchQuery, stops.lastOrNull()?.at) }
        } catch (e: Exception) {
            emptyList()
        }
        searching = false
        searched = true
    }

    val serverConfig = remember { RoutingServer.load() }
    val avoidHighways by Settings.avoidHighways.collectAsStateWithLifecycle()
    val avoidSmallRoads by Settings.avoidSmallRoads.collectAsStateWithLifecycle()

    // Re-route on every stop/mode/preference change — the point of showing
    // distance/duration inline is that it always matches what's on screen.
    LaunchedEffect(stops, mode, avoidHighways, avoidSmallRoads) {
        if (stops.size < 2) {
            polyline = emptyList()
            distanceMeters = null
            timeMs = null
            routingError = null
            return@LaunchedEffect
        }
        routing = true
        routingError = null
        try {
            val result = withContext(Dispatchers.IO) {
                RoutingClient.routeVia(
                    serverConfig, stops.map { it.at }, mode.ghProfile, avoidHighways, avoidSmallRoads)
            }
            polyline = result.polyline
            distanceMeters = result.distanceMeters
            timeMs = result.timeMs
        } catch (e: Exception) {
            routingError = "Routing failed: ${e.message}"
        } finally {
            routing = false
        }
    }

    val themePref by Settings.theme.collectAsStateWithLifecycle()
    val darkTheme = isAppDarkTheme(themePref)
    val mapView = remember { MapView(context) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapOverlays by remember { mutableStateOf<MapOverlays?>(null) }

    // Same MapView lifecycle wiring as TripDetailScreen: onCreate/Start/Resume
    // up front, tear down in reverse on dispose.
    DisposableEffect(Unit) {
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        mapView.getMapAsync { map ->
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isLogoEnabled = false
            mapLibreMap = map
        }
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(darkTheme, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        map.setStyle(Style.Builder().fromUri(openFreeMapStyleUrl(darkTheme))) { style ->
            mapOverlays = MapOverlays(style, context, darkTheme)
        }
    }

    // Tap to append a stop. Registered once the map is ready; re-registering
    // on every stops change would leak listeners, so this reads `stops`
    // through the composable's own recomposition instead of capturing it —
    // simplest correct option given a tap only fires from user input, never
    // from a frame loop that would need a fresher value than a listener
    // re-add on each stops change would give it anyway.
    LaunchedEffect(mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        map.addOnMapClickListener { ll ->
            stops = stops + RouteStop(LatLon(ll.latitude, ll.longitude))
            true
        }
    }

    // Push the pins + route line whenever either changes — kept separate from
    // the camera fit below, which only reacts to the stop list itself:
    // fitting on every routed-polyline update would jump the camera mid-route
    // fetch instead of leaving it where the user left it.
    LaunchedEffect(stops, polyline, mapOverlays) {
        val overlays = mapOverlays ?: return@LaunchedEffect
        overlays.render(
            myLocation = null,
            destination = null,
            routePolyline = polyline,
            reachMeters = null,
            directionDeg = null,
            candidates = stops.map { CandidatePin(it.at, STOP_PIN_COLOR) },
            positionMarker = PositionMarker.Hide,
        )
    }

    // Center on the stops once there are any (editing, or after the first
    // tap); otherwise center on the device's last known location so the first
    // tap doesn't require panning across the world first. Best effort: a
    // missing permission or fix just leaves the map at its default view.
    val fitPaddingPx = with(LocalDensity.current) { FIT_PADDING_DP.dp.roundToPx() }
    LaunchedEffect(stops, mapOverlays) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (mapOverlays == null) return@LaunchedEffect
        if (stops.isNotEmpty()) {
            cameraForPoints(map, stops.map { it.at }, fitPaddingPx)
            return@LaunchedEffect
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return@LaunchedEffect
        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val loc = client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
                ?: client.lastLocation.await()
            loc?.let { cameraForPoints(map, listOf(LatLon(it.latitude, it.longitude)), fitPaddingPx) }
        } catch (e: SecurityException) {
            // No permission after all (revoked between the check and the
            // call) — leave the map at its default view.
        }
    }

    fun moveStop(index: Int, delta: Int) {
        val target = index + delta
        if (target !in stops.indices) return
        stops = stops.toMutableList().apply { add(target, removeAt(index)) }
    }

    fun removeStop(index: Int) {
        stops = stops.toMutableList().apply { removeAt(index) }
    }

    fun save() {
        val cleanedName = name.trim()
        val now = System.currentTimeMillis()
        RouteStore.save(
            SavedRoute(
                id = editing?.id ?: now,
                name = cleanedName,
                createdMs = editing?.createdMs ?: now,
                mode = mode,
                stops = stops,
                polyline = polyline,
                distanceMeters = distanceMeters,
                timeMs = timeMs,
                sharedBy = editing?.sharedBy ?: "",
            ),
        )
        onSaved()
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SubScreenTopBar(if (editing == null) "New route" else "Edit route", onBack, scrollBehavior)
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxWidth().height(260.dp)) {
                AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
                if (stops.isEmpty()) {
                    Text(
                        "Tap the map or search to add your first stop",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search for a place") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (searching) {
                    CircularProgressIndicator(Modifier.padding(top = 8.dp).size(20.dp), strokeWidth = 2.dp)
                } else if (searched && searchResults.isEmpty()) {
                    Text(
                        "No results",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    searchResults.take(5).forEach { result ->
                        Text(
                            result.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    stops = stops + RouteStop(result.location, result.name)
                                    searchQuery = ""
                                    searchResults = emptyList()
                                    searched = false
                                    keyboardController?.hide()
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(stops.size) { index ->
                    val stop = stops[index]
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        Text(
                            // Coordinates keep '.' whatever the rider's
                            // separator setting says: the pair is already
                            // comma-separated, so a comma decimal would read
                            // "50,85137, 5,69097". Shared with the Saved places
                            // subtitle so the two cannot drift.
                            stop.name.ifBlank { formatCoordinatePair(stop.at.lat, stop.at.lon) },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(enabled = index > 0, onClick = { moveStop(index, -1) }) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "Move stop ${index + 1} up")
                        }
                        IconButton(enabled = index < stops.lastIndex, onClick = { moveStop(index, 1) }) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = "Move stop ${index + 1} down")
                        }
                        IconButton(onClick = { removeStop(index) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove stop ${index + 1}")
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Route name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TravelMode.entries.forEach { m ->
                            FilterChip(
                                selected = m == mode,
                                onClick = { mode = m },
                                label = { Text(m.label) },
                                leadingIcon = { Icon(m.icon, contentDescription = null, Modifier.size(18.dp)) },
                            )
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        when {
                            stops.size < 2 -> Text(
                                "Add at least two stops to route between them.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            routing -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Routing…", style = MaterialTheme.typography.bodySmall)
                            }
                            routingError != null -> Text(
                                routingError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            else -> Text(
                                listOfNotNull(
                                    distanceMeters?.let { formatDistanceKm(it) },
                                    timeMs?.let { formatDurationHistory(it) },
                                ).joinToString(" · ").ifEmpty { "No route yet" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    Button(
                        onClick = { save() },
                        enabled = stops.size >= 2 && name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save route") }
                }
            }
        }
    }
}

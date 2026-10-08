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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jellemax.detour.R
import com.jellemax.detour.data.GeocodeResult
import com.jellemax.detour.data.Geocoder
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RouteFill
import com.jellemax.detour.data.RouteOrigin
import com.jellemax.detour.data.RouteStop
import com.jellemax.detour.data.RouteStore
import com.jellemax.detour.data.RoutingClient
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.SavedRoute
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.StopsTooLong
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.presentation.failureText
import com.jellemax.detour.presentation.formatCoordinatePair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
    var saveError by remember { mutableStateOf("") }
    var filling by remember { mutableStateOf(false) }
    var fillError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<GeocodeResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    // Why the result list is empty, in the rider's words — "No results" when
    // the geocoder had nothing, something else when it was never asked. Same
    // one-slot shape SearchIsland uses. Null while there is nothing to say.
    var searchStatus by remember { mutableStateOf<String?>(null) }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Debounced live search, same shape as SavedPlacesScreen's add-place dialog.
    // `near` biases results toward the route so far, once it has a stop to bias from.
    LaunchedEffect(searchQuery) {
        // Clearing the box while a request is still in flight cancels this
        // effect mid-call, so the early return has to drop the spinner too.
        if (searchQuery.length < 3) {
            searchResults = emptyList()
            searching = false
            searchStatus = null
            return@LaunchedEffect
        }
        delay(400)
        searching = true
        try {
            val hits = withContext(Dispatchers.IO) { Geocoder.search(searchQuery, stops.lastOrNull()?.at) }
            searchResults = hits
            searchStatus = if (hits.isEmpty()) context.getString(R.string.route_editor_no_results) else null
        } catch (e: CancellationException) {
            // The next keystroke cancelled us. Rethrowing rather than falling
            // into the catch below is what keeps a superseded search from
            // reporting itself as an empty one.
            throw e
        } catch (e: Exception) {
            // A geocoder that could not be reached is not an address that does
            // not exist, and the rider was being told the second thing.
            ensureActive()
            searchResults = emptyList()
            searchStatus = context.getString(R.string.route_editor_search_failed)
        }
        searching = false
    }

    // Names for stops that have only coordinates — tapped onto the map, or
    // imported from a GPX without names (#503). Display-only: a saved route keeps the
    // blank name it had, so the last stop's name vs route name fallback in
    // SpinResultHolder reads the same as before. A key holding null was
    // looked up and found nothing (offline, say); it shows coordinates and is
    // not retried while the editor stays open. A lookup cancelled by the next
    // stops change was never recorded, so the restarted effect picks it up.
    var lookedUpNames by remember { mutableStateOf(emptyMap<LatLon, String?>()) }
    LaunchedEffect(stops) {
        for (stop in stops) {
            if (stop.name.isNotBlank() || stop.at in lookedUpNames) continue
            val found = withContext(Dispatchers.IO) { Geocoder.reverse(stop.at) }
            lookedUpNames = lookedUpNames + (stop.at to found)
        }
    }

    val serverConfig = remember { RoutingServer.load() }
    val avoidHighways by Settings.avoidHighways.collectAsStateWithLifecycle()
    val avoidSmallRoads by Settings.avoidSmallRoads.collectAsStateWithLifecycle()

    // Re-route on every stop/mode/preference change — the point of showing
    // distance/duration inline is that it always matches what's on screen.
    LaunchedEffect(stops, mode, avoidHighways, avoidSmallRoads) {
        if (stops.size < 2) {
            // Removing a stop while a request is in flight cancels this effect
            // mid-call, so the early return has to drop the spinner too.
            polyline = emptyList()
            distanceMeters = null
            timeMs = null
            routing = false
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
        } catch (e: CancellationException) {
            // A stop or mode change superseded us. Falling into the catch below
            // would set routingError after the new run cleared it, and nothing
            // on its success path clears it again.
            throw e
        } catch (e: Exception) {
            // An IOException can surface in place of the cancellation when the
            // blocking call fails after we were superseded.
            ensureActive()
            routingError = failureText("Routing", e)
        }
        // Not in a finally: a cancelled run's finally can land after the run
        // that replaced it set routing = true, hiding its spinner.
        routing = false
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

    /**
     * Stretch the route to [minutes] of riding: the rider's own stops stay,
     * [RouteFill] inserts the rest. The result lands in `stops` like any
     * other edit, so the re-route effect above draws it and Save keeps it.
     */
    fun fill(minutes: Float) {
        scope.launch {
            filling = true
            fillError = null
            // A fill takes a few round trips to the server; if the rider has
            // tapped in another stop or switched vehicle meanwhile, their edit
            // wins - a fill routed for the old profile is not this route's.
            val from = stops
            val fromMode = mode
            try {
                val filled = withContext(Dispatchers.IO) {
                    RouteFill.fillRouted(
                        serverConfig, from, minutes, mode.ghProfile, avoidHighways, avoidSmallRoads)
                }
                if (stops == from && mode == fromMode) stops = filled.stops
            } catch (e: StopsTooLong) {
                fillError = context.getString(
                    R.string.route_editor_stops_too_long, formatDurationHistory(e.stopsMs),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fillError = failureText("Fill", e)
            } finally {
                filling = false
            }
        }
    }

    fun save() {
        val cleanedName = name.trim()
        val now = System.currentTimeMillis()
        // RouteStore.save writes routes.json straight through, so a full disk
        // or an unreadable account directory throws right here — on the main
        // thread, from a click handler. Leaving it uncaught took the app down;
        // popping back to the list would have been the quieter lie.
        val saved = runCatching {
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
                    // Edited stops still came from a spin; dropping the marker
                    // here would turn a saved spin into a planned route on save.
                    origin = editing?.origin ?: RouteOrigin.PLANNED,
                ),
            )
        }
        if (saved.isFailure) {
            saveError = context.getString(R.string.route_editor_save_failed)
            return
        }
        onSaved()
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SubScreenTopBar(
                stringResource(if (editing == null) R.string.route_editor_new else R.string.route_editor_edit),
                onBack,
                scrollBehavior,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxWidth().height(260.dp)) {
                AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
                if (stops.isEmpty()) {
                    Text(
                        stringResource(R.string.route_editor_empty_map),
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
                    label = { Text(stringResource(R.string.route_editor_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (searching) {
                    CircularProgressIndicator(Modifier.padding(top = 8.dp).size(20.dp), strokeWidth = 2.dp)
                } else if (searchStatus != null) {
                    Text(
                        searchStatus.orEmpty(),
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
                                    searchStatus = null
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
                            stop.name.ifBlank {
                                lookedUpNames[stop.at] ?: formatCoordinatePair(stop.at.lat, stop.at.lon)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(enabled = index > 0, onClick = { moveStop(index, -1) }) {
                            Icon(
                                Icons.Filled.ArrowUpward,
                                contentDescription = stringResource(R.string.route_editor_move_up, index + 1),
                            )
                        }
                        IconButton(enabled = index < stops.lastIndex, onClick = { moveStop(index, 1) }) {
                            Icon(
                                Icons.Filled.ArrowDownward,
                                contentDescription = stringResource(R.string.route_editor_move_down, index + 1),
                            )
                        }
                        IconButton(onClick = { removeStop(index) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.route_editor_remove_stop, index + 1),
                            )
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.route_editor_name)) },
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
                                stringResource(R.string.route_editor_need_two_stops),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            routing -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(
                                    stringResource(R.string.route_editor_routing),
                                    style = MaterialTheme.typography.bodySmall,
                                )
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
                                ).joinToString(" · ").ifEmpty { stringResource(R.string.route_editor_no_route) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // Keyed: it sits after the stops, so an unkeyed item shifts index
                // (and drops the rider's chosen minutes) whenever a fill adds or
                // removes stops.
                item(key = "fill") {
                    RouteFillControls(
                        canFill = RouteFill.mandatory(stops).isNotEmpty() && serverConfig.usable,
                        filling = filling,
                        error = fillError,
                        onFill = { fill(it) },
                        onClearFill = if (stops.none(RouteFill::isFill)) null else {
                            {
                                stops = RouteFill.mandatory(stops)
                                fillError = null
                            }
                        },
                    )
                }
                item {
                    Button(
                        onClick = { save() },
                        enabled = stops.size >= 2 && name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.route_editor_save)) }
                }
                if (saveError.isNotEmpty()) {
                    item {
                        Text(
                            saveError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

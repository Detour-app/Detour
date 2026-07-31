package com.jellemax.maproulette.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.maproulette.data.LatLon
import com.jellemax.maproulette.data.Settings
import com.jellemax.maproulette.data.Trip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

// Left/top/right padding around the fitted route, and extra bottom padding so
// the stats card at the foot of the screen doesn't sit over the route it's
// describing — a fixed estimate of the card's height rather than measuring
// it, same spirit as the paddings MapScreen fits its own camera to.
private const val FIT_PADDING_DP = 32
private const val FIT_BOTTOM_PADDING_DP = 170

/**
 * Trip history detail: the full driven route on a real map, with the trip's
 * stats in a glass card over the bottom. [HistoryScreen] only opens this for
 * trips a trace was matched to, so [loadTripTrace] coming back empty is not
 * expected in practice — handled anyway rather than assumed away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(trip: Trip, onBack: () -> Unit) {
    val context = LocalContext.current
    // Loaded off the main thread, same reasoning as HistoryScreen: reading and
    // JSON-parsing the trace store during composition would stall the first
    // frame. Null means "still loading".
    var trace by remember { mutableStateOf<List<LatLon>?>(null) }
    LaunchedEffect(trip.startTimeMs) {
        trace = withContext(Dispatchers.IO) { loadTripTrace(context, trip) }
    }

    val themePref by Settings.theme.collectAsStateWithLifecycle()
    val darkTheme = isAppDarkTheme(themePref)

    val mapView = remember { MapView(context) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapOverlays by remember { mutableStateOf<MapOverlays?>(null) }

    // MapView lifecycle, same pattern MapScreen uses: the map arrives
    // asynchronously, so effects that touch it guard on `mapLibreMap`.
    DisposableEffect(Unit) {
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        mapView.getMapAsync { map ->
            map.uiSettings.isCompassEnabled = false
            mapLibreMap = map
        }
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Rebuild the overlay layers whenever the style (re)loads — on first map
    // arrival, and again if the day/night theme flips while this is open.
    LaunchedEffect(darkTheme, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        map.setStyle(Style.Builder().fromUri(openFreeMapStyleUrl(darkTheme))) { style ->
            mapOverlays = MapOverlays(style, context, darkTheme)
        }
    }

    // Draw the route and fit the camera once both the trace and the overlays
    // are ready — the trace load and the style load race, so either one
    // arriving last is what should trigger this.
    val fitPaddingPx = with(LocalDensity.current) { FIT_PADDING_DP.dp.roundToPx() }
    val fitBottomPaddingPx = with(LocalDensity.current) { FIT_BOTTOM_PADDING_DP.dp.roundToPx() }
    LaunchedEffect(trace, mapOverlays) {
        val points = trace ?: return@LaunchedEffect
        val overlays = mapOverlays ?: return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        overlays.render(
            myLocation = null,
            destination = points.lastOrNull(),
            routePolyline = points,
            reachMeters = null,
            directionDeg = null,
            candidates = emptyList(),
            showPosition = false,
        )
        if (points.isNotEmpty()) cameraForPoints(map, points, fitPaddingPx, fitBottomPaddingPx)
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SubScreenTopBar(formatDate(trip.startTimeMs), onBack, scrollBehavior) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            val loaded = trace
            when {
                loaded == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                loaded.isEmpty() -> Text(
                    "No route recorded",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> {}
            }

            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .glassBorder(MaterialTheme.shapes.extraLarge),
                shape = MaterialTheme.shapes.extraLarge,
                colors = glassCardColors(),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${trip.mode.label} · ${formatDate(trip.startTimeMs)}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        tripStatLine(trip),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

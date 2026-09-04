package com.jellemax.detour.ui

import android.graphics.RectF
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.CELL_METERS
import com.jellemax.detour.data.Coverage
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.Municipality
import com.jellemax.detour.data.MunicipalityStore
import com.jellemax.detour.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.abs

// Left/top/right padding around the fitted boundaries; same figure
// TripDetailScreen fits its route to, no bottom card sitting over this one
// permanently so there's no separate bottom padding to reserve.
private const val FIT_PADDING_DP = 32

private const val SRC_COVERAGE = "coverage-map-src"
private const val LAYER_FILL = "coverage-map-fill"
private const val LAYER_LINE = "coverage-map-line"

private data class CoverageMapData(
    val entries: List<Coverage.Entry>,
    val municipalities: Map<Long, Municipality>,
)

/**
 * Full-screen "conquest map": every municipality driven into, filled by how
 * much of it has been covered. Reached from the Coverage section of
 * [BadgesScreen], which already computes the same [Coverage.compute] — this
 * screen calls it again rather than threading the result through navigation
 * state, but [Coverage] caches the result itself, so this is free unless the
 * trace or municipality data changed since.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverageMapScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // First call after trace/municipality data changes walks every trace
    // point against every boundary; still off the main thread for that case.
    val data by produceState<CoverageMapData?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val entries = Coverage.compute()
            val municipalities = MunicipalityStore.load().associateBy { it.id }
            CoverageMapData(entries, municipalities)
        }
    }
    val dataRef = rememberUpdatedState(data)

    val themePref by Settings.theme.collectAsStateWithLifecycle()
    val darkTheme = isAppDarkTheme(themePref)

    val mapView = remember { MapView(context) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var selected by remember { mutableStateOf<Coverage.Entry?>(null) }

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

    // Rebuilt whenever the style (re)loads, same as every other map screen —
    // first arrival, and again on a day/night flip. This screen owns its own
    // source/layers rather than MapOverlays: it's one polygon fill, not the
    // whole live-drive overlay set.
    LaunchedEffect(darkTheme, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        map.setStyle(Style.Builder().fromUri(openFreeMapStyleUrl(darkTheme))) { s ->
            s.addSource(GeoJsonSource(SRC_COVERAGE))
            s.addLayer(FillLayer(LAYER_FILL, SRC_COVERAGE).withProperties(
                PropertyFactory.fillColor(coverageFillExpression())))
            s.addLayer(LineLayer(LAYER_LINE, SRC_COVERAGE).withProperties(
                PropertyFactory.lineColor(if (darkTheme) "#E8B04B" else "#2F80ED"),
                PropertyFactory.lineWidth(1.5f),
                PropertyFactory.lineOpacity(0.8f)))
            style = s
        }
    }

    // Push the polygons once both the style and the (slow) coverage compute
    // are ready, whichever lands second, and fit the camera to what we drew.
    val fitPaddingPx = with(LocalDensity.current) { FIT_PADDING_DP.dp.roundToPx() }
    LaunchedEffect(data, style) {
        val d = data ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SRC_COVERAGE)?.setGeoJson(buildFeatureCollection(d))
        val boundaryPoints = d.entries.mapNotNull { d.municipalities[it.municipalityId] }
            .flatMap { it.rings.flatten() }
        if (boundaryPoints.isNotEmpty()) cameraForPoints(map, boundaryPoints, fitPaddingPx)
    }

    // Tap-to-select, added once per map instance (not per style reload, so a
    // theme flip can't stack a second listener on top of the first).
    LaunchedEffect(mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        map.addOnMapClickListener { ll ->
            val p = map.projection.toScreenLocation(ll)
            val tap = RectF(p.x - 12f, p.y - 12f, p.x + 12f, p.y + 12f)
            val id = map.queryRenderedFeatures(tap, LAYER_FILL)
                .firstOrNull()?.getNumberProperty("id")?.toLong()
            val found = id?.let { i -> dataRef.value?.entries?.find { it.municipalityId == i } }
            selected = found
            found != null
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SubScreenTopBar("Coverage map", onBack, scrollBehavior) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            when {
                data == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                data?.entries.isNullOrEmpty() -> Text(
                    "Drive somewhere first",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> {}
            }

            data?.entries?.let { entries ->
                SummaryPill(
                    entered = entries.size,
                    fullyCovered = entries.count { it.percent >= 100.0 },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp),
                )
            }

            selected?.let { entry ->
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
                        Text(entry.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "%.0f%% of %.1f km²".format(entry.percent, entry.areaKm2),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Cell count × cell side², in km² — the area math the "x% of N km²" card and
 *  nothing else in this file needs. */
private val Coverage.Entry.areaKm2: Double
    get() = totalCells * CELL_METERS * CELL_METERS / 1_000_000.0

@Composable
private fun SummaryPill(entered: Int, fullyCovered: Int, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.glassBorder(MaterialTheme.shapes.large),
        shape = MaterialTheme.shapes.large,
        colors = glassCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Text(
            "$entered municipalities · $fullyCovered fully covered",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** Fill colour ramp on the `percent` feature property: faint blue-grey at 0%,
 *  saturating to the app's amber "done" colour at 100%. Alpha climbs with the
 *  ramp too so an untouched municipality reads as barely-there rather than a
 *  flat colored block — and both ends stay legible over the pale positron
 *  basemap and the near-black one, since alpha (not just hue) carries the
 *  low-coverage end. */
private fun coverageFillExpression(): Expression = Expression.interpolate(
    Expression.linear(), Expression.get("percent"),
    Expression.stop(0, Expression.rgba(120, 140, 160, 0.10)),
    Expression.stop(50, Expression.rgba(90, 160, 220, 0.45)),
    Expression.stop(100, Expression.rgba(232, 176, 75, 0.80)),
)

private fun buildFeatureCollection(data: CoverageMapData): FeatureCollection {
    val features = data.entries.mapNotNull { entry ->
        val m = data.municipalities[entry.municipalityId] ?: return@mapNotNull null
        Feature.fromGeometry(m.toGeometry()).apply {
            addNumberProperty("id", entry.municipalityId)
            addStringProperty("name", entry.name)
            addNumberProperty("percent", entry.percent)
        }
    }
    return FeatureCollection.fromFeatures(features)
}

/** [Municipality.rings] mixes outer and inner rings with no role kept (see
 *  [MunicipalityStore]'s way-chaining) and the closing point is implied, not
 *  stored. MapLibre's fill tessellation reads the first ring of a polygon as
 *  its outer boundary and the rest as holes, so the roles have to be recovered
 *  geometrically: largest ring first, then each smaller ring is a hole of the
 *  first ring that encloses it, or an outer boundary of its own if none does.
 *
 *  "Largest ring wins" alone would be wrong for the boundaries that motivate
 *  this at all: a gemeente with detached exclaves (Baarle-Hertog is the
 *  extreme case) has several outer rings, and folding them all into one
 *  polygon would punch its exclaves out as holes instead of drawing them. */
private fun Municipality.toGeometry(): MultiPolygon {
    val groups = ArrayList<MutableList<List<LatLon>>>()
    // Largest first, so any ring that encloses this one has already been seen.
    for (ring in rings.sortedByDescending { ringArea(it) }) {
        val host = groups.firstOrNull { ringContains(it.first(), ring.first()) }
        if (host != null) host.add(ring) else groups.add(mutableListOf(ring))
    }
    return MultiPolygon.fromPolygons(groups.map { group ->
        Polygon.fromLngLats(group.map { ring ->
            (ring + ring.first()).map { p -> Point.fromLngLat(p.lon, p.lat) }
        })
    })
}

/** Even-odd ray cast against a single ring, with the closing segment implied —
 *  [Municipality.contains] does the same over all rings at once, which is the
 *  wrong question here: this one asks which ring a ring sits inside. */
private fun ringContains(ring: List<LatLon>, p: LatLon): Boolean {
    var inside = false
    for (i in ring.indices) {
        val a = ring[i]
        val b = ring[(i + 1) % ring.size]
        if ((a.lat > p.lat) != (b.lat > p.lat)) {
            val x = a.lon + (p.lat - a.lat) / (b.lat - a.lat) * (b.lon - a.lon)
            if (x > p.lon) inside = !inside
        }
    }
    return inside
}

/** Shoelace formula in degrees² — only used to rank ring sizes against each
 *  other within one municipality, so the missing latitude-scaling factor
 *  (constant there) doesn't matter. */
private fun ringArea(ring: List<LatLon>): Double {
    var sum = 0.0
    for (i in ring.indices) {
        val a = ring[i]
        val b = ring[(i + 1) % ring.size]
        sum += a.lon * b.lat - b.lon * a.lat
    }
    return abs(sum) / 2.0
}

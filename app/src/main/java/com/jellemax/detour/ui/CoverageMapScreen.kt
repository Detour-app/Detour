package com.jellemax.detour.ui

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.Municipality
import com.jellemax.detour.data.MunicipalityStore
import com.jellemax.detour.data.Settings
import com.jellemax.detour.presentation.CoverageEntryView
import com.jellemax.detour.presentation.CoveragePresenter
import com.jellemax.detour.presentation.CoverageState
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

/**
 * Full-screen "conquest map": every municipality driven into, filled by how
 * much of it has been covered. Reached from the Coverage section of
 * [BadgesScreen]. The chrome (explored count, legend, selection) is
 * [CoveragePresenter]'s; the municipality boundaries needed to draw the map
 * are not modelled there (see that presenter's doc) and are loaded here,
 * directly, same as before.
 */
@Composable
fun CoverageMapScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    val presenter = remember { CoveragePresenter() }
    val state by presenter.state.collectAsStateWithLifecycle()
    // CoveragePresenter.refresh() is `suspend` but never actually suspends — it
    // blocks on disk internally (Coverage.compute() plus MunicipalityStore.load()
    // for the denominator), so this hop to Dispatchers.IO is what keeps that off
    // the main thread.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { presenter.refresh() } }
    // presenter itself never changes identity (remember with no keys), but the
    // map click listener below is a native callback registered once and never
    // torn down (see the detour-compose-state-hazards skill, §2/§2b) — reading
    // through this indirection rather than closing over `presenter` directly
    // keeps that read-path uniform with the rest of the file's long-lived
    // listeners, so a later refactor that turns the listener install into a
    // top-level function (which WOULD freeze a directly-captured value) can't
    // silently go stale.
    val presenterRef = rememberUpdatedState(presenter)

    // Boundaries for the polygons themselves — CoveragePresenter's state has no
    // geometry in it on purpose (the map layers are this screen's own). First
    // call after municipality data changes is off the main thread here too.
    // MunicipalityStore.load() is memoised, so this and the presenter's own
    // internal load() above are not two disk reads — whichever runs first pays
    // the cost, the other is free.
    val municipalities by produceState<Map<Long, Municipality>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { MunicipalityStore.load().associateBy { it.id } }
    }

    val themePref by Settings.theme.collectAsStateWithLifecycle()
    val darkTheme = isAppDarkTheme(themePref)

    val mapView = remember { MapView(context) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }

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
    LaunchedEffect(state.loaded, municipalities, style) {
        if (!state.loaded) return@LaunchedEffect
        val munis = municipalities ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SRC_COVERAGE)?.setGeoJson(buildFeatureCollection(state.entries, munis))
        val boundaryPoints = state.entries.mapNotNull { munis[it.municipalityId] }.flatMap { it.rings.flatten() }
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
            presenterRef.value.select(id)
            id != null
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        when {
            !state.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.entries.isEmpty() -> Text(
                "Drive somewhere first",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> {}
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = onBack) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(glassContainerColor())
                        .glassBorder(CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back",
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Text(
                "Coverage",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                fontWeight = FontWeight.SemiBold,
            )
        }

        state.selected?.let { entry ->
            SelectedMunicipalityPill(
                entry,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 72.dp),
            )
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            state.selected?.let { entry -> SelectedMunicipalityCard(entry) }
            CoverageBottomSheet(state)
        }
    }
}

/** Small floating readout for the tapped municipality, over the map. */
@Composable
private fun SelectedMunicipalityPill(entry: CoverageEntryView, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.glassBorder(MaterialTheme.shapes.large),
        shape = MaterialTheme.shapes.large,
        colors = glassCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Text(
            "${entry.name} ${entry.percentLabel}",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** The tapped municipality's detail card, floating just above the bottom
 *  sheet. Same glass-card idiom this screen already used for its summary
 *  pill; only the values now come from [CoveragePresenter]'s state. */
@Composable
private fun SelectedMunicipalityCard(entry: CoverageEntryView, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
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
                "${entry.percentLabel} of ${entry.areaLabel}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Pinned-to-the-bottom-edge sheet: how many municipalities are explored, and
 *  a driven-percentage legend for the fill colour ramp on the map. */
@Composable
private fun CoverageBottomSheet(state: CoverageState, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    state.exploredLabel,
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.alignByBaseline(),
                )
                Text(
                    state.summarySuffix,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val primary = MaterialTheme.colorScheme.primary
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Brush.horizontalGradient(listOf(primary.copy(alpha = 0.06f), primary))),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "0% driven",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "100% driven",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
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

private fun buildFeatureCollection(
    entries: List<CoverageEntryView>,
    municipalities: Map<Long, Municipality>,
): FeatureCollection {
    val features = entries.mapNotNull { entry ->
        val m = municipalities[entry.municipalityId] ?: return@mapNotNull null
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

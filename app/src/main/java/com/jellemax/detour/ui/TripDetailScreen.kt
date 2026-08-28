package com.jellemax.detour.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.data.Gpx
import com.jellemax.detour.data.HighwayClass
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TraceStore
import com.jellemax.detour.data.Trip
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

// Left/top/right padding around the fitted route, and extra bottom padding so
// the stats card at the foot of the screen doesn't sit over the route it's
// describing — a fixed estimate of the card's height rather than measuring
// it, same spirit as the paddings MapScreen fits its own camera to.
private const val FIT_PADDING_DP = 32
private const val FIT_BOTTOM_PADDING_DP = 170

// Replay source/layer/image ids — a second, screen-local pair of sources on
// top of whatever MapOverlays already drew, same "one GeoJSON source per
// overlay kind" convention, just owned here since MapOverlays is off limits.
private const val SRC_REPLAY_TRAVELLED = "mr-replay-travelled"
private const val SRC_REPLAY_MARKER = "mr-replay-marker"
private const val IMG_REPLAY_MARKER = "mr-img-replay-marker"

// Every ride, however long, is normalized to about this many milliseconds of
// playback — a 5-minute trip and a 90-minute trip scrub over roughly the same
// slider length. "Compressed" is taken literally: the base rate is floored at
// 1x real time, so a trip shorter than the target just plays at 1x and
// finishes early rather than being stretched out to fill 30s. The 1x/2x/4x
// picker then multiplies that base rate — doubling steps, like any video
// player's speed control, so "2x" always means "twice whatever speed was
// already showing".
private const val REPLAY_TARGET_MS = 30_000L
private val REPLAY_SPEEDS = intArrayOf(1, 2, 4)

// A warm red reads as a distinct "you are here / already driven" marker
// against the route line MapOverlays draws underneath it, in whichever colour
// the user picked — see RouteColors in :shared.
private const val REPLAY_HIGHLIGHT_COLOR = "#FF5252"

/** Marker position, heading and instantaneous speed at some point into replay. */
private data class ReplaySample(
    val at: LatLon,
    val bearingDeg: Double,
    val speedMps: Double,
    val segmentIndex: Int,
)

/**
 * Cumulative ride-milliseconds at each trace point, strictly increasing. Real
 * GPS traces occasionally carry identical or out-of-order timestamps (a fix
 * reacquiring), so every inter-point gap is floored at 1 ms — replay can then
 * never divide by zero or run backwards. A trace with no usable timestamps at
 * all (the pre-tail format [TraceStore.parsePoints] reads back as -1, see its
 * doc comment) falls back to arbitrary even spacing: the shape of the ride
 * still plays back, just without real timing to compress.
 */
private fun buildReplayTimeline(points: List<TraceStore.TracePoint>): List<Long> {
    if (points.size < 2) return List(points.size) { 0L }
    if (points.any { it.timeMs < 0 }) return points.indices.map { it * 1_000L }
    val out = ArrayList<Long>(points.size)
    var cum = 0L
    out.add(0L)
    for (i in 1 until points.size) {
        cum += (points[i].timeMs - points[i - 1].timeMs).coerceAtLeast(1L)
        out.add(cum)
    }
    return out
}

/** Where the marker sits at [elapsedMs] into the (real, uncompressed) ride,
 *  linearly interpolated between the two trace points [elapsedMs] falls
 *  between. [timeline] is [points]' parallel array from [buildReplayTimeline]. */
private fun sampleReplay(
    points: List<TraceStore.TracePoint>,
    timeline: List<Long>,
    elapsedMs: Double,
): ReplaySample {
    val found = timeline.binarySearch(elapsedMs.toLong())
    val i = (if (found >= 0) found else -found - 2).coerceIn(0, points.size - 2)
    val a = points[i]
    val b = points[i + 1]
    val segMs = (timeline[i + 1] - timeline[i]).coerceAtLeast(1L)
    val t = ((elapsedMs - timeline[i]) / segMs).coerceIn(0.0, 1.0)
    val at = LatLon(a.at.lat + (b.at.lat - a.at.lat) * t, a.at.lon + (b.at.lon - a.at.lon) * t)
    val bearing = RoadRoulette.bearingDeg(a.at, b.at)
    // Speed from the segment's own recorded distance/time, not the stored
    // speedKmh field — replay should read as "what this segment just showed
    // you", not the original sensor sample. Floored at 1s of recorded time so
    // an identical/duplicate timestamp (see buildReplayTimeline) can't turn a
    // near-zero denominator into a nonsense spike; a trace with no usable
    // timestamps at all just reads 0.
    val speedMps = if (a.timeMs >= 0 && b.timeMs >= 0) {
        val seconds = (b.timeMs - a.timeMs).coerceAtLeast(1_000L) / 1000.0
        RoadRoulette.distanceMeters(a.at, b.at) / seconds
    } else 0.0
    return ReplaySample(at, bearing, speedMps, i)
}

/** Small nose-up triangle, rotated per-frame via iconRotate — same technique
 *  MapOverlays uses for the own-position marker (see mr-position in
 *  MapLibreMap.kt). Drawn procedurally rather than shipped as a drawable
 *  resource since this screen is the only place that needs it. */
private fun replayMarkerBitmap(density: Float): Bitmap {
    val size = (26 * density).toInt().coerceAtLeast(8)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val path = Path().apply {
        moveTo(size * 0.5f, 0f)
        lineTo(size * 0.92f, size.toFloat())
        lineTo(size * 0.08f, size.toFloat())
        close()
    }
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(REPLAY_HIGHLIGHT_COLOR)
    })
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = size * 0.1f
    })
    return bmp
}

/** Hands one exported track to whichever app the user picks. The read grant is
 *  what makes the content:// Uri usable on the other side — the provider is
 *  not exported, so without it the receiver sees nothing. */
private fun shareGpxIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "application/gpx+xml"
    putExtra(Intent.EXTRA_STREAM, uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

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
    val scope = rememberCoroutineScope()
    // Loaded off the main thread, same reasoning as HistoryScreen: reading and
    // JSON-parsing the trace store during composition would stall the first
    // frame. Null means "still loading". The timestamps come along because the
    // GPX export needs them; the map only reads the coordinates.
    var trace by remember { mutableStateOf<List<TraceStore.TracePoint>?>(null) }
    LaunchedEffect(trip.startTimeMs) {
        trace = withContext(Dispatchers.IO) { loadTripPoints(context, trip) }
    }

    val themePref by Settings.theme.collectAsStateWithLifecycle()
    val darkTheme = isAppDarkTheme(themePref)

    val mapView = remember { MapView(context) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapOverlays by remember { mutableStateOf<MapOverlays?>(null) }
    // Held separately from mapOverlays: the replay marker/travelled-line
    // sources are this screen's own, not something MapOverlays knows about
    // (see the "Do not modify MapLibreMap.kt" constraint), so they're set up
    // and pushed to directly against the raw Style.
    var mapStyle by remember { mutableStateOf<Style?>(null) }

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
            // Own sources/layers for replay, set up once per style load same as
            // MapOverlays does for its own — added after, so they draw on top of
            // the route MapOverlays.render puts down.
            style.addImage(
                IMG_REPLAY_MARKER,
                replayMarkerBitmap(context.resources.displayMetrics.density),
            )
            style.addSource(GeoJsonSource(SRC_REPLAY_TRAVELLED))
            style.addSource(GeoJsonSource(SRC_REPLAY_MARKER))
            style.addLayer(LineLayer("mr-replay-travelled-line", SRC_REPLAY_TRAVELLED).withProperties(
                PropertyFactory.lineColor(REPLAY_HIGHLIGHT_COLOR), PropertyFactory.lineWidth(7f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
            style.addLayer(SymbolLayer("mr-replay-marker-icon", SRC_REPLAY_MARKER).withProperties(
                PropertyFactory.iconImage(IMG_REPLAY_MARKER),
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
            mapStyle = style
        }
    }

    // Draw the route and fit the camera once both the trace and the overlays
    // are ready — the trace load and the style load race, so either one
    // arriving last is what should trigger this.
    val fitPaddingPx = with(LocalDensity.current) { FIT_PADDING_DP.dp.roundToPx() }
    val fitBottomPaddingPx = with(LocalDensity.current) { FIT_BOTTOM_PADDING_DP.dp.roundToPx() }
    LaunchedEffect(trace, mapOverlays) {
        val points = trace?.map { it.at } ?: return@LaunchedEffect
        val overlays = mapOverlays ?: return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        overlays.render(
            myLocation = null,
            destination = points.lastOrNull(),
            routePolyline = points,
            reachMeters = null,
            directionDeg = null,
            candidates = emptyList(),
            positionMarker = PositionMarker.Hide,
        )
        if (points.isNotEmpty()) cameraForPoints(map, points, fitPaddingPx, fitBottomPaddingPx)
    }

    // Timeline is derived once per trace load; sampling it every frame during
    // playback is cheap (a binary search), so it isn't worth memoizing further.
    val replayTimeline = remember(trace) { trace?.let { buildReplayTimeline(it) } }
    val rideDurationMs = replayTimeline?.lastOrNull() ?: 0L
    // A one- or zero-point trace has nothing to interpolate between; the
    // replay controls simply don't appear for it, same as the map already
    // showing "No route recorded" for an empty one.
    val canReplay = (trace?.size ?: 0) >= 2 && rideDurationMs > 0L

    var replaying by remember { mutableStateOf(false) }
    var rideElapsedMs by remember { mutableStateOf(0.0) }
    var speedIndex by remember { mutableIntStateOf(0) }
    // Turned off for good the moment the user drags the map — see the touch
    // listener below. Deliberately doesn't turn back on by itself: fighting a
    // gesture the user just made (by snapping back next frame) is worse than
    // leaving the camera exactly where they put it.
    var followCamera by remember { mutableStateOf(true) }
    // Segment last pushed to the travelled-line source, so its (potentially
    // long) polyline is only rewritten when replay crosses into a new segment
    // rather than every frame — see the comment on MapOverlays.setPosition in
    // MapLibreMap.kt for why rewriting a big GeoJSON line at 60fps is the kind
    // of thing that makes a map crawl. The marker itself is one point and gets
    // pushed every frame regardless; that part is cheap.
    // Keyed on the style too, not just the trace: a day/night flip mid-replay
    // rebuilds the sources empty, so the "already pushed this segment" memory
    // has to go with them or the travelled line stays blank until replay
    // happens to cross into the next segment.
    val lastPushedSegment = remember(trace, mapStyle) { intArrayOf(-1) }

    fun pushReplay(points: List<TraceStore.TracePoint>, sample: ReplaySample, moveCamera: Boolean) {
        val style = mapStyle ?: return
        (style.getSource(SRC_REPLAY_MARKER) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeature(
                Feature.fromGeometry(Point.fromLngLat(sample.at.lon, sample.at.lat)).apply {
                    addNumberProperty("bearing", sample.bearingDeg)
                }))
        if (sample.segmentIndex != lastPushedSegment[0]) {
            lastPushedSegment[0] = sample.segmentIndex
            (style.getSource(SRC_REPLAY_TRAVELLED) as? GeoJsonSource)?.setGeoJson(
                if (sample.segmentIndex >= 1) FeatureCollection.fromFeature(Feature.fromGeometry(
                    LineString.fromLngLats(points.subList(0, sample.segmentIndex + 1)
                        .map { Point.fromLngLat(it.at.lon, it.at.lat) })))
                else FeatureCollection.fromFeatures(emptyList()))
        }
        if (moveCamera) {
            val map = mapLibreMap ?: return
            setCamera(map, sample.at.lat, sample.at.lon, map.cameraPosition.zoom, map.cameraPosition.bearing.toFloat())
        }
    }

    // The playback clock. Only runs while playing; advancing rideElapsedMs is
    // all it does per frame, then hands the resulting sample to pushReplay —
    // one frame loop driving both the animation and the map writes, same
    // shape as the camera-easing loop in MapScreen. speedIndex is read via
    // rememberUpdatedState so changing the multiplier mid-play doesn't need
    // to restart (and re-key) this effect.
    val speedMultiplier = rememberUpdatedState(REPLAY_SPEEDS[speedIndex])
    val followState = rememberUpdatedState(followCamera)
    LaunchedEffect(replaying, trace, replayTimeline) {
        if (!replaying) return@LaunchedEffect
        val points = trace ?: return@LaunchedEffect
        val timeline = replayTimeline ?: return@LaunchedEffect
        if (points.size < 2 || timeline.last() <= 0L) {
            replaying = false
            return@LaunchedEffect
        }
        val rideDuration = timeline.last().toDouble()
        // See REPLAY_TARGET_MS: never slower than real time, only compressed.
        val baseAdvance = (rideDuration / REPLAY_TARGET_MS).coerceAtLeast(1.0)
        var lastNs = withFrameNanos { it }
        while (true) {
            val ns = withFrameNanos { it }
            // Clamped so a stalled frame doesn't jump the marker miles ahead.
            val dtMs = ((ns - lastNs) / 1_000_000.0).coerceIn(0.0, 100.0)
            lastNs = ns
            rideElapsedMs = (rideElapsedMs + dtMs * baseAdvance * speedMultiplier.value)
                .coerceAtMost(rideDuration)
            pushReplay(points, sampleReplay(points, timeline, rideElapsedMs), moveCamera = followState.value)
            if (rideElapsedMs >= rideDuration) {
                replaying = false
                return@LaunchedEffect
            }
        }
    }

    // Scrubbing (or the initial map-ready draw at rideElapsedMs = 0, which
    // plants the marker at the trip's start) moves the marker without
    // starting playback — the frame loop above already covers the playing
    // case, so this only needs to act while paused.
    // Null while playing so this effect isn't cancelled and relaunched every
    // frame (rideElapsedMs changes 60 times a second) for a coroutine that
    // would immediately bail — the frame loop owns the pushes then.
    val scrubbedTo = if (replaying) null else rideElapsedMs
    LaunchedEffect(scrubbedTo, mapStyle) {
        val elapsed = scrubbedTo ?: return@LaunchedEffect
        val points = trace ?: return@LaunchedEffect
        val timeline = replayTimeline ?: return@LaunchedEffect
        if (points.size < 2) return@LaunchedEffect
        pushReplay(points, sampleReplay(points, timeline, elapsed), moveCamera = false)
    }

    // Park following the instant the map is dragged or pinched — same
    // technique MapScreen uses (a camera-move listener would also fire on the
    // programmatic setCamera calls above and couldn't tell those from the
    // user), just without MapScreen's auto-resume: once the user has taken
    // the camera, replay leaves it alone for the rest of this session.
    DisposableEffect(mapView) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        mapView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_POINTER_DOWN -> followCamera = false
                MotionEvent.ACTION_MOVE ->
                    if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) followCamera = false
            }
            false
        }
        onDispose { mapView.setOnTouchListener(null) }
    }

    // Only ever set by a failed share; shown in the stats card because this
    // screen has no other error surface.
    var exportError by remember { mutableStateOf<String?>(null) }

    // Disabled until the trace is loaded: a trip with no recorded points has
    // nothing to put in a track or a card. Hoisted above the top bar so both
    // toolbar buttons and the card dialog (rendered below, outside the top
    // bar's own lambda scope) share the one value.
    val points = trace.orEmpty()
    var cardDialogOpen by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SubScreenTopBar(formatDate(trip.startTimeMs), onBack, scrollBehavior) {
                IconButton(
                    enabled = points.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            exportError = try {
                                val uri = withContext(Dispatchers.IO) {
                                    Gpx.writeForShare(context, trip, points)
                                }
                                context.startActivity(Intent.createChooser(
                                    shareGpxIntent(uri), "Export GPX"))
                                null
                            } catch (e: ActivityNotFoundException) {
                                "No app to receive a GPX file"
                            } catch (e: IOException) {
                                "Export failed: ${e.message}"
                            }
                        }
                    },
                ) {
                    Icon(Icons.Filled.Share, contentDescription = "Export GPX")
                }
                IconButton(
                    enabled = points.isNotEmpty(),
                    onClick = { cardDialogOpen = true },
                ) {
                    Icon(Icons.Filled.Image, contentDescription = "Share trip card")
                }
            }
        },
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
                    // Anywhere past the start of replay (playing, paused
                    // mid-way, or scrubbed there manually) this line reports
                    // the moment being shown instead of the whole trip's
                    // stats; back at rideElapsedMs == 0 it's exactly what the
                    // card showed before replay existed.
                    val replaySample = if (canReplay && rideElapsedMs > 0.0)
                        sampleReplay(trace!!, replayTimeline!!, rideElapsedMs) else null
                    Text(
                        if (replaySample != null)
                            "${formatSpeedKmh(replaySample.speedMps)} · " +
                                "${formatDuration(rideElapsedMs.toLong())} elapsed"
                        else tripStatLine(trip),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Road-type mix: share of this trip's distance on each highway
                    // class, skipping classes with nothing recorded. Empty entirely
                    // for an old trip, or one where the fetch never resolved.
                    if (trip.drivingStats.roadTypeMeters.isNotEmpty() && trip.distanceMeters > 0) {
                        Text(
                            HighwayClass.entries.mapNotNull { cls ->
                                val meters = trip.drivingStats.roadTypeMeters[cls] ?: return@mapNotNull null
                                val pct = (meters / trip.distanceMeters * 100.0).roundToInt()
                                "${cls.name.lowercase().replaceFirstChar { it.uppercase() }}: $pct%"
                            }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 0.0 is indistinguishable from "not measured" — same
                    // convention as maxGForce/maxLeanAngleDeg elsewhere on this
                    // screen — so only show a nonzero score.
                    if (trip.drivingStats.twistinessScore > 0.0) {
                        Text(
                            "Twistiness: ${(trip.drivingStats.twistinessScore * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (trip.drivingStats.pctOverLimit > 0.0) {
                        Text(
                            "${trip.drivingStats.pctOverLimit.roundToInt()}% over the limit",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (trip.drivingStats.obd2SpeedPct > 0.0) {
                        val obd2Pct = trip.drivingStats.obd2SpeedPct.roundToInt()
                        Text(
                            if (obd2Pct == 0) "OBD2 speed: <1% of the drive"
                            else "OBD2 speed: $obd2Pct% of the drive",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (trip.drivingStats.hardBrakeCount + trip.drivingStats.hardAccelCount +
                        trip.drivingStats.hardCornerCount > 0
                    ) {
                        Text(
                            "Not a score to chase — informational only.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    exportError?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (canReplay) {
                        Slider(
                            value = (rideElapsedMs / rideDurationMs.toDouble()).toFloat().coerceIn(0f, 1f),
                            onValueChange = { frac ->
                                // A drag on the scrubber is the user placing the
                                // marker themselves — let go of playback rather
                                // than fight it for the same rideElapsedMs value.
                                replaying = false
                                rideElapsedMs = (frac.toDouble() * rideDurationMs).coerceIn(0.0, rideDurationMs.toDouble())
                            },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            IconButton(onClick = {
                                // Replaying again after it ran to the end starts
                                // over rather than doing nothing at 100%.
                                if (!replaying && rideElapsedMs >= rideDurationMs) rideElapsedMs = 0.0
                                replaying = !replaying
                            }) {
                                Icon(
                                    if (replaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (replaying) "Pause replay" else "Play replay",
                                )
                            }
                            Text(
                                "${formatDuration(rideElapsedMs.toLong())} / ${formatDuration(rideDurationMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { speedIndex = (speedIndex + 1) % REPLAY_SPEEDS.size }) {
                                Text("${REPLAY_SPEEDS[speedIndex]}×")
                            }
                        }
                    }
                }
            }
        }
    }

    if (cardDialogOpen) {
        TripCardShareDialog(trip, points.map { it.at }, onDismiss = { cardDialogOpen = false })
    }
}

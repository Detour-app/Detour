package com.jellemax.detour.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jellemax.detour.data.syncQuietly
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.SyncClient
import com.jellemax.detour.data.Perf
import com.jellemax.detour.data.TraceStore
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.data.Trip
import com.jellemax.detour.data.TripStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/** A trip alongside the trace polyline it was recorded with, if one still
 *  exists — thumbnails need points, not just the trip's summary numbers. */
private data class HistoryEntry(val trip: Trip, val thumbnail: List<LatLon>?)

/** One decoded trace line: its points plus the timestamp window they span. */
internal data class TraceSegment(
    val points: List<TraceStore.TracePoint>,
    val startMs: Long,
    val endMs: Long,
)

/** Reads the raw trace lines through [TraceStore.parsePoints] rather than
 *  [TraceStore.loadAll], which drops the per-point timestamp — the one thing
 *  this screen needs to match a trace back to the trip that was running when it
 *  was recorded, and the one thing a GPX export can't be built without. */
private fun readTraceSegments(context: android.content.Context): List<TraceSegment> {
    // Uncached, unlike TraceStore.loadAll: this re-reads and re-parses the whole
    // of traces.jsonl on every history open, trip detail and GPX export, so it
    // grows with every ride and nothing memoises it. #84.
    val t = Perf.start()
    val segments = TraceStore.rawLines().mapNotNull { line ->
        val points = TraceStore.parsePoints(line) ?: return@mapNotNull null
        var start = Long.MAX_VALUE
        var end = Long.MIN_VALUE
        for (p in points) {
            if (p.timeMs < 0) continue // written before points carried a time
            if (p.timeMs < start) start = p.timeMs
            if (p.timeMs > end) end = p.timeMs
        }
        if (start == Long.MAX_VALUE) null else TraceSegment(points, start, end)
    }
    Perf.end(t, "HistoryScreen.readTraceSegments") {
        listOf("segments" to segments.size, "points" to segments.sumOf { it.points.size })
    }
    return segments
}

/** Slack added on both ends of a trip's window when matching it to trace
 *  lines, to cover the tracker's own startup lag between the trip actually
 *  starting and the first point landing in the buffer. */
private const val TRIP_MATCH_SLACK_MS = 10_000L

/** Every point recorded during [trip], stitched back together from however
 *  many trace lines it was split across. The tracker doesn't write one line
 *  per trip — it flushes its point buffer to [TraceStore] every 200 points,
 *  on a >500 m GPS gap, and on a STILL activity transition, so a single ride
 *  routinely spans several lines (200 points at the ~25 m decimation
 *  interval is only ~5 km, which is where every longer ride used to stop
 *  being drawn). A trip is therefore matched by
 *  *overlap* rather than by a single line's start falling inside its window:
 *  any segment whose [startMs, endMs] range overlaps the trip's window (with
 *  [TRIP_MATCH_SLACK_MS] slack on both ends) can hold some of the trip's
 *  points. Because the buffer isn't flushed when a trip begins either, the
 *  line that opens a trip can also carry idle points recorded before it — so
 *  once the overlapping segments are pooled and sorted, every point is
 *  re-checked against the trip's own window to trim those leading (and any
 *  trailing) points out. Finally, flushTrace(keepLast = true) repeats the
 *  boundary point as the first point of the next line, so an exact duplicate
 *  of the immediately preceding point (same time and coordinates) is dropped
 *  to avoid a seam in the reassembled trace. Shared by [matchThumbnails] and
 *  [loadTripPoints] so the two never disagree on which points belong to
 *  which trip. */
internal fun matchTripPoints(segments: List<TraceSegment>, trip: Trip): List<TraceStore.TracePoint> {
    val from = trip.startTimeMs - TRIP_MATCH_SLACK_MS
    val to = trip.endTimeMs + TRIP_MATCH_SLACK_MS
    val pooled = segments
        .filter { it.startMs <= to && it.endMs >= from }
        .sortedBy { it.startMs }
        .flatMap { it.points }
        .filter { it.timeMs in from..to }
    val result = ArrayList<TraceStore.TracePoint>(pooled.size)
    for (p in pooled) {
        val prev = result.lastOrNull()
        if (prev != null && prev.timeMs == p.timeMs && prev.at == p.at) continue
        result.add(p)
    }
    return result
}

private fun matchThumbnails(context: android.content.Context, trips: List<Trip>): Map<Long, List<LatLon>> {
    val segments = readTraceSegments(context)
    val result = HashMap<Long, List<LatLon>>()
    for (trip in trips) {
        val points = matchTripPoints(segments, trip)
        if (points.isEmpty()) continue
        // Cap the point count a thumbnail actually needs — a multi-hour ride
        // can carry thousands of points, all wasted on a 52dp canvas.
        val pts = if (points.size > 200) {
            val step = points.size / 200
            points.filterIndexed { i, _ -> i % step == 0 }
        } else points
        result[trip.startTimeMs] = pts.map { it.at }
    }
    return result
}

/** The full (undecimated) polyline driven during [trip], for [TripDetailScreen]
 *  — unlike the thumbnail map this isn't capped to 200 points, so it's loaded
 *  for one trip at a time on demand rather than held for the whole history
 *  list. Empty if no trace matches (shouldn't happen when the caller only
 *  opens trips whose thumbnail was already matched). */
fun loadTripTrace(context: android.content.Context, trip: Trip): List<LatLon> =
    loadTripPoints(context, trip).map { it.at }

/** The same trace with its timestamps kept, for the GPX export — a track
 *  without times is just a shape, and every tool that would receive one wants
 *  to know when it was ridden. */
fun loadTripPoints(context: android.content.Context, trip: Trip): List<TraceStore.TracePoint> =
    matchTripPoints(readTraceSegments(context), trip)

private val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
private fun monthKey(timeMs: Long) = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(timeMs)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenTrip: (Trip) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Loaded off the main thread: reading + JSON-parsing the store inside a
    // remember{} ran during composition and stalled the first frame (~125 ms on a
    // large history), which is what made opening and scrolling feel stuck. Null
    // means "still loading"; the reloads after an edit go through IO too.
    var entries by remember { mutableStateOf<List<HistoryEntry>?>(null) }
    fun reload() = scope.launch {
        entries = withContext(Dispatchers.IO) {
            val trips = TripStore.load()
            val thumbnails = matchThumbnails(context, trips)
            trips.map { HistoryEntry(it, thumbnails[it.startTimeMs]) }
        }
    }
    LaunchedEffect(Unit) { reload() }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SubScreenTopBar("Trip history", onBack, scrollBehavior) },
    ) { padding ->
        val loaded = entries
        if (loaded != null && loaded.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.History, contentDescription = null,
                    Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("No trips yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Track a drive or spin a destination — trips land here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (loaded != null) {
            // Trips are stored newest-first (TripStore.save prepends), so a
            // plain groupBy keeps that order and each month lands as one
            // contiguous run — no explicit sort needed.
            val byMonth = loaded.groupBy { monthKey(it.trip.startTimeMs) }
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((_, monthEntries) in byMonth) {
                    val totalKm = monthEntries.sumOf { it.trip.distanceMeters } / 1000.0
                    item {
                        Text(
                            "${monthFormat.format(monthEntries.first().trip.startTimeMs)} · " +
                                "${monthEntries.size} trips · ${"%,.0f".format(totalKm)} km",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp),
                        )
                    }
                    items(monthEntries, key = { it.trip.startTimeMs }) { entry ->
                        TripCard(
                            // Deleting a trip slides the rest up instead of snapping.
                            modifier = Modifier.animateItem(),
                            entry = entry,
                            onOpen = { onOpenTrip(entry.trip) },
                            onChangeMode = { newMode ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        TripStore.updateMode(entry.trip.startTimeMs, newMode)
                                    }
                                    reload()
                                    // Push the correction so it survives a reinstall / other devices.
                                    SyncClient.syncQuietly()
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        TripStore.delete(entry.trip.startTimeMs)
                                    }
                                    reload()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** One trip: a thumbnail of its trace (or the mode icon when none was kept), a
 *  title and one stat line, and a single overflow menu for the two edit actions
 *  that used to be their own icon buttons. Tapping the card opens the route
 *  detail screen, but only when there's a trace to show — a trip with no
 *  matched thumbnail has nothing to draw on a map either. */
@Composable
private fun TripCard(
    entry: HistoryEntry,
    onOpen: () -> Unit,
    onChangeMode: (TravelMode) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trip = entry.trip
    var menuOpen by remember { mutableStateOf(false) }
    var vehicleMenuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var cardDialogOpen by remember { mutableStateOf(false) }
    var cardPoints by remember { mutableStateOf<List<LatLon>?>(null) }
    Card(
        // The overflow IconButton below has its own clickable, so a tap on it
        // is consumed there and never reaches this one.
        modifier = if (entry.thumbnail != null) modifier.clickable(onClick = onOpen) else modifier,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val thumb = entry.thumbnail
                if (thumb != null) {
                    TraceThumbnail(
                        thumb,
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                } else {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            trip.mode.icon, contentDescription = null,
                            Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "${trip.mode.label} · ${formatDate(trip.startTimeMs)} – " +
                            formatTimeOfDay(trip.endTimeMs),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        tripStatLine(trip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Trip options",
                            Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Change vehicle") },
                            onClick = { menuOpen = false; vehicleMenuOpen = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Share trip card") },
                            onClick = { menuOpen = false; cardDialogOpen = true },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Delete, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error)
                            },
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                }
            }
        }
    }

    if (vehicleMenuOpen) {
        AlertDialog(
            onDismissRequest = { vehicleMenuOpen = false },
            title = { Text("Change vehicle") },
            text = {
                Column {
                    TravelMode.entries.forEach { m ->
                        Text(
                            m.label + if (m == trip.mode) " ✓" else "",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vehicleMenuOpen = false
                                    if (m != trip.mode) onChangeMode(m)
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vehicleMenuOpen = false }) { Text("Close") }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this trip?") },
            text = {
                Text("${trip.mode.label} · ${formatDate(trip.startTimeMs)} — " +
                    "${formatDistanceKm(trip.distanceMeters)}. This can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    if (cardDialogOpen) {
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            if (cardPoints == null) {
                cardPoints = withContext(Dispatchers.IO) { loadTripTrace(context, trip) }
            }
        }
        TripCardShareDialog(
            trip, cardPoints,
            onDismiss = { cardDialogOpen = false; cardPoints = null },
        )
    }
}

/** "duration · distance · avg X · top Y" plus lean/G when the vehicle tracks
 *  them — the numbers that used to be four separate labelled columns,
 *  collapsed to the one line a history row now has room for. */
fun tripStatLine(trip: Trip): String {
    val parts = mutableListOf(
        formatDurationHistory(trip.durationMs),
        formatDistanceKm(trip.distanceMeters),
        "avg " + formatSpeedKmh(trip.avgSpeedMps),
        "top " + formatSpeedKmh(trip.topSpeedMps),
    )
    if (trip.mode.tracksLean) parts += "lean " + formatLeanAngle(trip.maxLeanAngleDeg)
    if (trip.mode.tracksGForce) parts += "max " + formatGForce(trip.maxGForce)
    val ds = trip.drivingStats
    if (ds.hardBrakeCount > 0) parts += "${ds.hardBrakeCount} hard brake" + if (ds.hardBrakeCount == 1) "" else "s"
    if (ds.hardAccelCount > 0) parts += "${ds.hardAccelCount} hard accel" + if (ds.hardAccelCount == 1) "" else "s"
    if (ds.hardCornerCount > 0) parts += "${ds.hardCornerCount} hard corner" + if (ds.hardCornerCount == 1) "" else "s"
    if (ds.stopCount > 0) parts += "${ds.stopCount} stop" + if (ds.stopCount == 1) "" else "s"
    return parts.joinToString(" · ")
}

/** Draws the trip's trace as a simple normalized polyline — not a map, just a
 *  recognizable shape at a glance. Lat/lon are scaled independently to fill
 *  the thumbnail; at this size the distortion from true distance doesn't
 *  matter and equirectangular projection math would be wasted precision. */
@Composable
private fun TraceThumbnail(points: List<LatLon>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val lats = points.map { it.lat }
        val lons = points.map { it.lon }
        val latSpan = ((lats.max() - lats.min())).let { if (it > 1e-9) it else 1.0 }
        val lonSpan = ((lons.max() - lons.min())).let { if (it > 1e-9) it else 1.0 }
        val minLat = lats.min()
        val minLon = lons.min()
        val pad = size.minDimension * 0.18f
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        fun offsetOf(p: LatLon): Offset {
            val x = pad + ((p.lon - minLon) / lonSpan).toFloat() * w
            // Screen y grows downward; north (higher lat) should sit higher.
            val y = pad + (1f - ((p.lat - minLat) / latSpan).toFloat()) * h
            return Offset(x, y)
        }
        val path = Path()
        val start = offsetOf(points.first())
        path.moveTo(start.x, start.y)
        for (p in points.drop(1)) {
            val o = offsetOf(p)
            path.lineTo(o.x, o.y)
        }
        drawPath(
            path, color,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

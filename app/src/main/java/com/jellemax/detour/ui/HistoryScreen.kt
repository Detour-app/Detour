package com.jellemax.detour.ui

import android.util.Log
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.jellemax.detour.data.BadgeStore
import com.jellemax.detour.data.Logbook
import com.jellemax.detour.data.LogbookFilter
import com.jellemax.detour.data.LogbookItem
import com.jellemax.detour.data.LogbookMonth
import com.jellemax.detour.data.Municipality
import com.jellemax.detour.data.MunicipalityStore
import com.jellemax.detour.data.PlaceVisit
import com.jellemax.detour.data.TripInsights
import com.jellemax.detour.data.TripTitleStore
import java.text.SimpleDateFormat
import java.util.Locale

/** Every trip's trace, thumbnail-sized, plus the towns it entered — one walk of
 *  the trace store for the whole Logbook. */
private data class TripTraces(val thumbnails: Map<Long, List<LatLon>>, val visits: List<Pair<Trip, List<Municipality>>>)

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
private fun readTraceSegments(): List<TraceSegment> {
    // Parse is cached in TraceStore.loadAllPoints (keyed on its write version),
    // so a second history open / trip detail / GPX export in the same session
    // reuses it instead of re-reading the whole of traces.jsonl. Building the
    // [startMs, endMs] window per line is cheap and stays here.
    val t = Perf.start()
    val segments = TraceStore.loadAllPoints().mapNotNull { points ->
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

private fun matchTraces(trips: List<Trip>, municipalities: List<Municipality>): TripTraces {
    val segments = readTraceSegments()
    val thumbnails = HashMap<Long, List<LatLon>>()
    val visits = ArrayList<Pair<Trip, List<Municipality>>>(trips.size)
    for (trip in trips) {
        val points = matchTripPoints(segments, trip).map { it.at }
        if (points.isEmpty()) continue
        visits += trip to TripInsights.visitedInOrder(points, municipalities)
        // Cap the point count a thumbnail actually needs — a multi-hour ride
        // can carry thousands of points, all wasted on a thumbnail canvas.
        thumbnails[trip.startTimeMs] = if (points.size > 200) {
            val step = points.size / 200
            points.filterIndexed { i, _ -> i % step == 0 }
        } else points
    }
    return TripTraces(thumbnails, visits)
}

/** Everything the Logbook renders from, loaded together off the main thread. */
private data class LogbookData(
    val trips: List<Trip>,
    val thumbnails: Map<Long, List<LatLon>>,
    val places: Map<Long, List<PlaceVisit>>,
    val titles: Map<Long, String>,
    val milestones: List<LogbookItem.Milestone>,
)

private fun loadLogbook(): LogbookData {
    val trips = TripStore.loadStrict()
    val traces = matchTraces(trips, MunicipalityStore.load())
    return LogbookData(
        trips = trips,
        thumbnails = traces.thumbnails,
        places = Logbook.placesByTrip(traces.visits),
        titles = TripTitleStore.load(),
        milestones = BadgeStore.earned().map { (def, at) -> LogbookItem.Milestone(def.title, at) },
    )
}

/** The full (undecimated) polyline driven during [trip], for [TripDetailScreen]
 *  — unlike the thumbnail map this isn't capped to 200 points, so it's loaded
 *  for one trip at a time on demand rather than held for the whole history
 *  list. Empty if no trace matches (shouldn't happen when the caller only
 *  opens trips whose thumbnail was already matched). */
fun loadTripTrace(trip: Trip): List<LatLon> =
    loadTripPoints(trip).map { it.at }

/** The same trace with its timestamps kept, for the GPX export — a track
 *  without times is just a shape, and every tool that would receive one wants
 *  to know when it was ridden. */
fun loadTripPoints(trip: Trip): List<TraceStore.TracePoint> =
    matchTripPoints(readTraceSegments(), trip)

private val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
private val rideDateFormat = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault())

/** Height of a ride's route thumbnail, and of the current month's overlay. */
private val RIDE_THUMB_HEIGHT = 132.dp
private val MONTH_MAP_HEIGHT = 180.dp

/** How many towns a ride card stamps before counting the rest. */
private const val MAX_STAMPS = 4
private const val STICKER_TILT_DEG = 3f
private const val STAMP_TILT_DEG = 1.5f
private val NEW_PLACE_DARK = Color(0xFF6CC4A8)
private val NEW_PLACE_LIGHT = Color(0xFF1B7A62)

/** What the ⋮ menu on a ride asks for. One callback instead of three keeps
 *  [RideCard] under the seven-parameter gate (§8.4). */
private sealed interface RideEdit {
    data class Rename(val title: String) : RideEdit
    data class ChangeMode(val mode: TravelMode) : RideEdit
    data object Delete : RideEdit
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenTrip: (Trip) -> Unit) {
    val scope = rememberCoroutineScope()
    // Loaded off the main thread: parsing trips, traces and boundaries during
    // composition would stall the first frame. Null means the first load
    // hasn't landed — still running, or failed, which the message below tells
    // apart; the reloads after an edit go through IO too.
    var data by remember { mutableStateOf<LogbookData?>(null) }
    // One message for both failures: a failed load has no list to sit above and
    // a failed delete has a list but no other place to say so, and the two can't
    // be pending at once. Clearing it as a load starts stops a stale message
    // from outliving the state it described.
    var error by remember { mutableStateOf("") }
    fun reload() = scope.launch {
        error = ""
        // loadStrict (inside loadLogbook), not load: load() reads a corrupt
        // file as an empty list, and this is the one screen that can tell the
        // rider the difference.
        val result = withContext(Dispatchers.IO) { runCatching { loadLogbook() } }
        result.onSuccess { data = it }.onFailure {
            Log.w("DetourHistory", "logbook load failed", it)
            error = "Could not read your logbook from this device."
        }
    }
    LaunchedEffect(Unit) { reload() }

    fun edit(trip: Trip, edit: RideEdit) = scope.launch {
        when (edit) {
            is RideEdit.Rename -> withContext(Dispatchers.IO) { TripTitleStore.set(trip.startTimeMs, edit.title) }
            is RideEdit.ChangeMode -> {
                withContext(Dispatchers.IO) { TripStore.updateMode(trip.startTimeMs, edit.mode) }
                // Push the correction so it survives a reinstall / other devices.
                SyncClient.syncQuietly()
            }
            RideEdit.Delete -> {
                // TripStore.delete rewrites trips.json and the tombstone file;
                // either write can fail. Reloading anyway would redraw the row
                // with no hint that the delete never happened.
                val deleted = withContext(Dispatchers.IO) { runCatching { TripStore.delete(trip.startTimeMs) } }
                if (deleted.isFailure) {
                    error = "Could not delete that trip — it is still in your logbook. Try again."
                    return@launch
                }
            }
        }
        reload() // clears any message on its way in
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SubScreenTopBar("Logbook", onBack, scrollBehavior) },
    ) { padding ->
        val loaded = data
        val slot = Modifier.fillMaxSize().padding(padding)
        when {
            loaded == null && error.isNotEmpty() -> HistoryLoadFailed(error, onRetry = { reload() }, modifier = slot)
            loaded == null -> Box(slot, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            loaded.trips.isEmpty() -> NoTripsYet(slot)
            else -> LogbookList(loaded, error, onOpenTrip, ::edit, slot)
        }
    }
}

/** The filter chips over the month chapters. [error] sits above the list, not
 *  in it: a delete fails on the row the rider is looking at, which is rarely
 *  the first one, and a message inserted at the top of a scrolled list lands
 *  off screen. */
@Composable
private fun LogbookList(
    loaded: LogbookData,
    error: String,
    onOpenTrip: (Trip) -> Unit,
    onEdit: (Trip, RideEdit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by rememberSaveable { mutableStateOf(LogbookFilter.ALL) }
    // Chapters the rider opened by hand; the current month is always open.
    val openMonths = remember { mutableStateListOf<Int>() }
    val thisMonth = remember { Logbook.monthOf(System.currentTimeMillis()) }
    val months = remember(loaded, filter) {
        Logbook.build(loaded.trips, filter, loaded.places, loaded.titles, loaded.milestones)
    }
    Column(modifier) {
        if (error.isNotEmpty()) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in LogbookFilter.entries) {
                FilterChip(selected = f == filter, onClick = { filter = f }, label = { Text(f.label) })
            }
        }
        if (months.isEmpty()) {
            Text(
                "No ${filter.label.lowercase()} rides yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (month in months) {
                val ym = month.year * 100 + month.month
                val current = ym == thisMonth
                val open = current || ym in openMonths
                item(key = "m$ym") {
                    MonthHeader(
                        month, open, current,
                        overlay = if (!current) null
                        else month.rides.mapNotNull { loaded.thumbnails[it.trip.startTimeMs] },
                        onToggle = { if (!openMonths.remove(ym)) openMonths.add(ym) },
                    )
                }
                if (!open) continue
                items(month.items, key = { it.key() }) { item ->
                    when (item) {
                        is LogbookItem.Milestone -> MilestoneRow(item, Modifier.animateItem())
                        is LogbookItem.Ride -> RideCard(
                            modifier = Modifier.animateItem(),
                            ride = item,
                            thumbnail = loaded.thumbnails[item.trip.startTimeMs],
                            onOpen = { onOpenTrip(item.trip) },
                            onEdit = { onEdit(item.trip, it) },
                        )
                    }
                }
            }
        }
    }
}

/** A history that loaded and holds nothing — the state a new install is in,
 *  which is why it must not double as "still loading" or "the read failed".
 *  Same shape as the saved-places screen's own empty state. */
@Composable
private fun NoTripsYet(modifier: Modifier = Modifier) {
    Column(
        modifier,
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
            "Track a drive or spin a destination — your rides land here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The read failed: plain words for what went wrong and a way to run the load
 *  again, in the slot [NoTripsYet] would have filled. The exception itself goes
 *  to logcat — a rider can act on "try again", not on a stack trace. */
@Composable
private fun HistoryLoadFailed(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry) { Text("Try again") }
    }
}

private fun LogbookItem.key(): String = when (this) {
    is LogbookItem.Ride -> "r${trip.startTimeMs}"
    is LogbookItem.Milestone -> "b$atMs$title"
}

/** "September 2026" and its one-line summary. The current month also draws
 *  all its routes on one canvas; older months collapse to the line alone. */
@Composable
private fun MonthHeader(
    month: LogbookMonth,
    open: Boolean,
    current: Boolean,
    overlay: List<List<LatLon>>?,
    onToggle: () -> Unit,
) {
    val rides = month.rides.size
    val summary = listOfNotNull(
        if (rides == 1) "1 ride" else "$rides rides",
        formatDistanceKm(month.meters),
        month.newPlaces.takeIf { it > 0 }?.let { if (it == 1) "1 new place" else "$it new places" },
    ).joinToString(" · ")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = !current, onClick = onToggle)
            .padding(top = 8.dp, bottom = 2.dp, start = 4.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    monthFormat.format(month.items.first().atMs),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!current) {
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (open) "Collapse month" else "Expand month")
            }
        }
        if (!overlay.isNullOrEmpty()) {
            RouteMapThumbnail(
                overlay,
                Modifier
                    .fillMaxWidth()
                    .height(MONTH_MAP_HEIGHT)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
    }
}

@Composable
private fun MilestoneRow(m: LogbookItem.Milestone, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.EmojiEvents, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
        Text("Badge earned: ${m.title}", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary)
    }
}

/** A ride's one highlight ([LogbookItem.Ride.chip]), as a tilted sticker slapped
 *  on its map: the Logbook should read like a scrapbook, not a report. */
@Composable
private fun Sticker(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.rotate(STICKER_TILT_DEG),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 3.dp,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** The towns a ride passed through, as passport stamps: towns never ridden
 *  before first, dashed and in [newPlaceColor], then the familiar ones, up to
 *  [MAX_STAMPS] with the rest counted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceStamps(places: List<PlaceVisit>, modifier: Modifier = Modifier) {
    val ordered = places.filter { it.isNew } + places.filterNot { it.isNew }
    val shown = ordered.take(MAX_STAMPS)
    val newColor = newPlaceColor()
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        shown.forEachIndexed { i, place ->
            // Alternating tilt: stamps pressed by hand, not typeset.
            val tilt = if (i % 2 == 0) -STAMP_TILT_DEG else STAMP_TILT_DEG
            if (place.isNew) {
                Stamp(place.name, newColor, dashed = true, Modifier.rotate(tilt))
            } else {
                Stamp(place.name, MaterialTheme.colorScheme.onSurfaceVariant, dashed = false)
            }
        }
        if (ordered.size > shown.size) {
            Stamp("+${ordered.size - shown.size}", MaterialTheme.colorScheme.onSurfaceVariant, dashed = false)
        }
    }
}

@Composable
private fun Stamp(text: String, color: Color, dashed: Boolean, modifier: Modifier = Modifier) {
    val outline = if (dashed) color else MaterialTheme.colorScheme.outlineVariant
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        color = color,
        modifier = modifier
            .drawBehind {
                val w = 1.5.dp.toPx()
                val dash = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null
                drawRoundRect(
                    outline,
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(width = w, pathEffect = dash),
                )
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Teal for a town ridden for the first time — apart from the amber/blue
 *  primary, so "new" never reads as just another highlight. Darker on a
 *  light surface to keep the stamp text readable. */
@Composable
private fun newPlaceColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) NEW_PLACE_DARK else NEW_PLACE_LIGHT

/** The dialogs the ⋮ menu opens; at most one at a time. */
private enum class RideDialog { RENAME, VEHICLE, DELETE, CARD }

/** One ride: its route large, its title, when and how far, and at most one
 *  highlight chip. Everything else is on the trip screen. Tapping opens it,
 *  but only when there's a trace to show — a trip with no matched trace has
 *  nothing to draw on a map either. */
@Composable
private fun RideCard(
    ride: LogbookItem.Ride,
    thumbnail: List<LatLon>?,
    onOpen: () -> Unit,
    onEdit: (RideEdit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trip = ride.trip
    var menuOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<RideDialog?>(null) }
    fun open(d: RideDialog) {
        menuOpen = false
        dialog = d
    }
    Card(
        // The overflow IconButton below has its own clickable, so a tap on it
        // is consumed there and never reaches this one.
        modifier = if (thumbnail != null) modifier.clickable(onClick = onOpen) else modifier,
    ) {
        Column {
            if (thumbnail != null) {
                Box(Modifier.fillMaxWidth().height(RIDE_THUMB_HEIGHT)) {
                    RouteMapThumbnail(
                        listOf(thumbnail),
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                    ride.chip?.let { Sticker(it, Modifier.align(Alignment.TopEnd).padding(10.dp)) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ride.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${rideDateFormat.format(trip.startTimeMs)} · ${formatDistanceKm(trip.distanceMeters)} · " +
                            formatDurationHistory(trip.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // No map to stick it on: the sticker sits with the text instead.
                    if (thumbnail == null) ride.chip?.let { Sticker(it, Modifier.padding(top = 2.dp)) }
                    if (ride.places.isNotEmpty()) PlaceStamps(ride.places, Modifier.padding(top = 4.dp))
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Trip options", Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { open(RideDialog.RENAME) })
                        DropdownMenuItem(text = { Text("Change vehicle") }, onClick = { open(RideDialog.VEHICLE) })
                        DropdownMenuItem(text = { Text("Share trip card") }, onClick = { open(RideDialog.CARD) })
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Delete, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error)
                            },
                            onClick = { open(RideDialog.DELETE) },
                        )
                    }
                }
            }
        }
    }
    dialog?.let { RideDialogs(ride, it, onDismiss = { dialog = null }, onEdit = onEdit) }
}

@Composable
private fun RideDialogs(ride: LogbookItem.Ride, dialog: RideDialog, onDismiss: () -> Unit, onEdit: (RideEdit) -> Unit) {
    val trip = ride.trip
    when (dialog) {
        RideDialog.RENAME -> {
            // Opens with the title selected: typing replaces it, which is what
            // a rename almost always wants; a tap places the cursor to edit.
            var text by remember { mutableStateOf(TextFieldValue(ride.title, TextRange(0, ride.title.length))) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Rename ride") },
                text = { OutlinedTextField(text, { text = it }, singleLine = true) },
                confirmButton = {
                    TextButton(onClick = { onDismiss(); onEdit(RideEdit.Rename(text.text)) }) { Text("Save") }
                },
                dismissButton = {
                    // Blank goes back to the generated title (TripTitleStore.set).
                    if (ride.titleEdited) {
                        TextButton(onClick = { onDismiss(); onEdit(RideEdit.Rename("")) }) { Text("Use generated") }
                    } else {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                },
            )
        }
        RideDialog.VEHICLE -> AlertDialog(
            onDismissRequest = onDismiss,
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
                                    onDismiss()
                                    if (m != trip.mode) onEdit(RideEdit.ChangeMode(m))
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
        RideDialog.DELETE -> ConfirmDialog(
            title = "Delete this trip?",
            text = "${ride.title} — ${formatDistanceKm(trip.distanceMeters)}. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = { onEdit(RideEdit.Delete) },
            onDismiss = onDismiss,
        )
        RideDialog.CARD -> {
            var cardPoints by remember { mutableStateOf<List<LatLon>?>(null) }
            LaunchedEffect(Unit) { cardPoints = withContext(Dispatchers.IO) { loadTripTrace(trip) } }
            TripCardShareDialog(trip, cardPoints, onDismiss = onDismiss)
        }
    }
}

/** Litres per 100 km over the distance a fuel reading was actually live, or null
 *  when there was no fuel data, the trip was too short for the figure to mean
 *  anything, or the adapter covered too little of it (a partial measurement
 *  divided by the whole trip reads as an impossibly good number). */
fun tripFuelEconomyLper100Km(trip: Trip): Double? {
    val ds = trip.drivingStats
    if (ds.fuelMilliliters <= 0 || ds.fuelSampledMeters < 500) return null
    if (ds.fuelSampledMeters < trip.distanceMeters * FUEL_COVERAGE_MIN) return null
    return ds.fuelMilliliters * 100.0 / ds.fuelSampledMeters
}

/** The adapter has to have fed a fuel reading over at least this fraction of the
 *  trip's distance before the economy figure is shown. */
private const val FUEL_COVERAGE_MIN = 0.8

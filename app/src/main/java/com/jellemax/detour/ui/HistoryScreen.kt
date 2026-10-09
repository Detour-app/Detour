package com.jellemax.detour.ui

import android.util.Log
import androidx.annotation.StringRes
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.jellemax.detour.R
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.jellemax.detour.data.BadgeStore
import com.jellemax.detour.data.Logbook
import com.jellemax.detour.data.LogbookFilter
import com.jellemax.detour.data.LogbookItem
import com.jellemax.detour.data.LogbookMonth
import com.jellemax.detour.data.LogbookYear
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
private val weekFormat = SimpleDateFormat("d MMM", Locale.getDefault())

/** Height of a ride's route thumbnail, and of the current month's overlay. */
private val RIDE_THUMB_HEIGHT = 132.dp
private val MONTH_MAP_HEIGHT = 180.dp
private val COMPACT_THUMB_SIZE = 64.dp

/** A month needs this many rides before it is split into weeks. */
private const val WEEK_HEADERS_MIN_RIDES = 8

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
    val context = LocalContext.current
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
            error = context.getString(R.string.history_load_failed)
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
                    error = context.getString(R.string.history_delete_failed)
                    return@launch
                }
            }
        }
        reload() // clears any message on its way in
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { SubScreenTopBar(stringResource(R.string.history_title), onBack, scrollBehavior) },
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
    val now = remember { System.currentTimeMillis() }
    val thisMonth = remember { Logbook.monthOf(now) }
    val months = remember(loaded, filter) {
        Logbook.build(loaded.trips, filter, loaded.places, loaded.titles, loaded.milestones)
    }
    val year = remember(loaded, filter) { Logbook.year(loaded.trips, filter, loaded.places, now) }
    // The month by yyyymm, not the computed card, so it survives rotation; a
    // month the filter no longer shows simply closes it.
    var wrappedYm by rememberSaveable { mutableStateOf<Int?>(null) }
    months.firstOrNull { it.year * 100 + it.month == wrappedYm }?.let { month ->
        val wrapped = remember(month) { Logbook.wrapped(month) }
        MonthWrappedDialog(wrapped, onDismiss = { wrappedYm = null })
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
                FilterChip(selected = f == filter, onClick = { filter = f }, label = {
                    Text(stringResource(f.labelRes()))
                })
            }
        }
        if (months.isEmpty()) {
            Text(
                stringResource(filter.emptyRes()),
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
            if (months.isNotEmpty()) item(key = "year") { YearStrip(year) }
            for (month in months) {
                val ym = month.year * 100 + month.month
                val current = ym == thisMonth
                val open = current || ym in openMonths
                item(key = "m$ym") {
                    MonthHeader(
                        month, open, current,
                        overlay = if (!current) null
                        else Logbook.mapFocus(month.rides.mapNotNull { loaded.thumbnails[it.trip.startTimeMs] }),
                        onToggle = { if (!openMonths.remove(ym)) openMonths.add(ym) },
                        onShowWrap = { wrappedYm = ym },
                    )
                }
                if (!open) continue
                monthItems(month, now, loaded, onOpenTrip, onEdit)
            }
        }
    }
}

/** One open month's rides and badges, with week headers when it's busy
 *  enough to need somewhere to land. */
private fun LazyListScope.monthItems(
    month: LogbookMonth,
    now: Long,
    loaded: LogbookData,
    onOpenTrip: (Trip) -> Unit,
    onEdit: (Trip, RideEdit) -> Unit,
) {
    val ym = month.year * 100 + month.month
    val weekly = month.rides.size >= WEEK_HEADERS_MIN_RIDES
    var lastWeek: Long? = null
    for (entry in month.items) {
        val week = Logbook.weekStartMs(entry.atMs)
        if (weekly && week != lastWeek) {
            lastWeek = week
            item(key = "w$ym-$week") { WeekHeader(week, now, Modifier.animateItem()) }
        }
        item(key = entry.key()) {
            when (entry) {
                is LogbookItem.Milestone -> MilestoneRow(entry, Modifier.animateItem())
                is LogbookItem.Ride -> RideCard(
                    modifier = Modifier.animateItem(),
                    ride = entry,
                    thumbnail = loaded.thumbnails[entry.trip.startTimeMs],
                    onOpen = { onOpenTrip(entry.trip) },
                    onEdit = { onEdit(entry.trip, it) },
                )
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
        Text(stringResource(R.string.history_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.history_empty_text),
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
        TextButton(onClick = onRetry) { Text(stringResource(R.string.history_try_again)) }
    }
}

private fun LogbookItem.key(): String = when (this) {
    is LogbookItem.Ride -> "r${trip.startTimeMs}"
    is LogbookItem.Milestone -> "b$atMs$title"
}

/** This year so far over the chapters: km, towns, rides, and the weekly
 *  streak, which carries over from last year. */
@Composable
private fun YearStrip(year: LogbookYear) {
    ListCard {
        Text(
            stringResource(R.string.history_year_so_far, year.year),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp, start = 8.dp, end = 8.dp)) {
            StatCell("${(year.meters / 1000).toLong()}", "km", Modifier.weight(1f))
            StatCell(
                "${year.towns}", pluralStringResource(R.plurals.history_towns_unit, year.towns), Modifier.weight(1f),
            )
            StatCell(
                "${year.rides}", pluralStringResource(R.plurals.history_rides_unit, year.rides), Modifier.weight(1f),
            )
            StatCell("${year.weekStreak}", stringResource(R.string.history_week_streak), Modifier.weight(1f))
        }
    }
}

/** "September 2026" and its summary as pills, the month's distance held up
 *  against a trip from Gent (#517). The current month also draws all its
 *  routes on one canvas; older months collapse to the pills alone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthHeader(
    month: LogbookMonth,
    open: Boolean,
    current: Boolean,
    overlay: List<List<LatLon>>?,
    onToggle: () -> Unit,
    onShowWrap: () -> Unit,
) {
    val rides = month.rides.size
    val summary = listOfNotNull(
        pluralStringResource(R.plurals.history_rides, rides, rides),
        formatDistanceKm(month.meters),
        month.newPlaces.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.history_new_places, it, it) },
    )
    val comparison = Logbook.distanceComparison(month.meters)
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
                FlowRow(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val colors = MaterialTheme.colorScheme
                    for (s in summary) MonthPill(s, colors.surfaceContainerHigh, colors.onSurfaceVariant)
                    comparison?.let { MonthPill(it, colors.secondaryContainer, colors.onSecondaryContainer) }
                }
            }
            IconButton(onClick = onShowWrap) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = stringResource(R.string.history_month_wrapped))
            }
            if (!current) {
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(
                        if (open) R.string.history_collapse_month else R.string.history_expand_month,
                    ))
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
private fun MonthPill(text: String, color: Color, contentColor: Color) {
    Surface(shape = CircleShape, color = color, contentColor = contentColor) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@StringRes
private fun LogbookFilter.labelRes() = when (this) {
    LogbookFilter.ALL -> R.string.history_filter_all
    LogbookFilter.MOTO -> R.string.history_filter_moto
    LogbookFilter.CAR -> R.string.history_filter_car
}

/** A sentence per filter, not one with the label spliced in: the Dutch noun
 *  doesn't lowercase into the English slot. */
@StringRes
private fun LogbookFilter.emptyRes() = when (this) {
    LogbookFilter.ALL -> R.string.history_no_rides
    LogbookFilter.MOTO -> R.string.history_no_moto_rides
    LogbookFilter.CAR -> R.string.history_no_car_rides
}

/** "This week", "Last week", then "Week of 7 Sept". */
@Composable
private fun WeekHeader(weekStartMs: Long, nowMs: Long, modifier: Modifier = Modifier) {
    val label = when (Logbook.weeksAgo(weekStartMs, nowMs)) {
        0 -> stringResource(R.string.history_this_week)
        1 -> stringResource(R.string.history_last_week)
        else -> stringResource(R.string.history_week_of, weekFormat.format(weekStartMs))
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 12.dp),
    )
}

/** A badge earned, as a card worth stopping on rather than a line of text. */
@Composable
private fun MilestoneRow(m: LogbookItem.Milestone, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.onPrimary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.EmojiEvents, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column {
                Text(
                    stringResource(R.string.history_badge_unlocked),
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                )
                Text(m.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

/** The dialogs the ⋮ menu opens; at most one at a time. */
private enum class RideDialog { RENAME, VEHICLE, DELETE, CARD }

/** One ride. A [LogbookItem.Ride.standout] ride gets the full card — its
 *  route large, a highlight sticker, town stamps; the everyday rest a compact
 *  row, so the rides worth remembering aren't lost among forty identical
 *  commutes. Everything else is on the trip screen. Tapping opens it, but only
 *  when there's a trace to show — a trip with no matched trace has nothing to
 *  draw on a map either. */
@Composable
private fun RideCard(
    ride: LogbookItem.Ride,
    thumbnail: List<LatLon>?,
    onOpen: () -> Unit,
    onEdit: (RideEdit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dialog by remember { mutableStateOf<RideDialog?>(null) }
    val menu: @Composable () -> Unit = { RideMenu(onPick = { dialog = it }) }
    // The overflow IconButton has its own clickable, so a tap on it is
    // consumed there and never reaches the card's.
    val cardModifier = if (thumbnail != null) modifier.clickable(onClick = onOpen) else modifier
    if (ride.standout) FullRideCard(ride, thumbnail, menu, cardModifier)
    else CompactRideRow(ride, thumbnail, menu, cardModifier)
    dialog?.let { RideDialogs(ride, it, onDismiss = { dialog = null }, onEdit = onEdit) }
}

@Composable
private fun FullRideCard(
    ride: LogbookItem.Ride,
    thumbnail: List<LatLon>?,
    menu: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier) {
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
                    RideMeta(ride.trip)
                    // No map to stick it on: the sticker sits with the text instead.
                    if (thumbnail == null) ride.chip?.let { Sticker(it, Modifier.padding(top = 2.dp)) }
                    if (ride.stampsWorthShowing) PlaceStamps(ride.places, Modifier.padding(top = 4.dp))
                }
                menu()
            }
        }
    }
}

/** An everyday trip: a small map, its title and the numbers, one row. */
@Composable
private fun CompactRideRow(
    ride: LogbookItem.Ride,
    thumbnail: List<LatLon>?,
    menu: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (thumbnail != null) {
                RouteMapThumbnail(
                    listOf(thumbnail),
                    Modifier
                        .size(COMPACT_THUMB_SIZE)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ride.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                RideMeta(ride.trip)
            }
            menu()
        }
    }
}

@Composable
private fun RideMeta(trip: Trip) {
    Text(
        "${rideDateFormat.format(trip.startTimeMs)} · ${formatDistanceKm(trip.distanceMeters)} · " +
            formatDurationHistory(trip.durationMs),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The ⋮ menu on a ride; [onPick] opens the chosen dialog. */
@Composable
private fun RideMenu(onPick: (RideDialog) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun pick(d: RideDialog) {
        open = false
        onPick(d)
    }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.history_trip_options),
                Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.history_rename)) }, onClick = { pick(RideDialog.RENAME) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.history_change_vehicle)) },
                onClick = { pick(RideDialog.VEHICLE) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.history_share_card)) }, onClick = { pick(RideDialog.CARD) },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.history_delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = { pick(RideDialog.DELETE) },
            )
        }
    }
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
                title = { Text(stringResource(R.string.history_rename_title)) },
                text = { OutlinedTextField(text, { text = it }, singleLine = true) },
                confirmButton = {
                    TextButton(onClick = { onDismiss(); onEdit(RideEdit.Rename(text.text)) }) {
                        Text(stringResource(R.string.history_save))
                    }
                },
                dismissButton = {
                    // Blank goes back to the generated title (TripTitleStore.set).
                    if (ride.titleEdited) {
                        TextButton(onClick = { onDismiss(); onEdit(RideEdit.Rename("")) }) {
                            Text(stringResource(R.string.history_use_generated))
                        }
                    } else {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.history_cancel)) }
                    }
                },
            )
        }
        RideDialog.VEHICLE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.history_change_vehicle)) },
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
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.history_close)) } },
        )
        RideDialog.DELETE -> ConfirmDialog(
            title = stringResource(R.string.history_delete_title),
            text = stringResource(R.string.history_delete_text, ride.title, formatDistanceKm(trip.distanceMeters)),
            confirmLabel = stringResource(R.string.history_delete),
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

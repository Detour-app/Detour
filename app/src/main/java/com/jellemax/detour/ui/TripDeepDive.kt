package com.jellemax.detour.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jellemax.detour.data.DistanceSample
import com.jellemax.detour.data.HighwayClass
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.Municipality
import com.jellemax.detour.data.PlaceVisit
import com.jellemax.detour.data.RidingEvent
import com.jellemax.detour.data.RidingEventKind
import com.jellemax.detour.data.TraceStore
import com.jellemax.detour.data.Trip
import com.jellemax.detour.data.TripInsights
import kotlin.math.abs
import kotlin.math.roundToInt

/** One thing worth pinning on the trip map (#444): a point, or for the best
 *  stretch the run of trace points it covers. [color] is shared by the pin and
 *  its list row so the two read as one. */
data class TripHighlight(
    val id: String,
    val label: String,
    val value: String,
    val color: String,
    val at: LatLon,
    val stretch: List<LatLon>? = null,
)

private const val COLOR_TOP_SPEED = "#E53935"
private const val COLOR_LEAN = "#FB8C00"
private const val COLOR_CORNER = "#8E24AA"
private const val COLOR_BRAKE = "#1E88E5"
const val COLOR_BEST_STRETCH = "#43A047"

/** Everything the detail screen loads besides the trace itself — off the main
 *  thread, because both reads parse files and places walks every earlier point. */
data class TripDetailExtras(
    val municipalities: List<Municipality>,
    val places: List<PlaceVisit>,
)

private fun isCorner(e: RidingEvent) =
    e.kind == RidingEventKind.HARD_CORNER_LEAN || e.kind == RidingEventKind.HARD_CORNER_TURN

private fun eventValue(e: RidingEvent): String = when (e.kind) {
    RidingEventKind.HARD_BRAKE, RidingEventKind.HARD_ACCEL -> "%.1f m/s²".format(abs(e.magnitude))
    RidingEventKind.HARD_CORNER_LEAN -> formatLeanAngle(abs(e.magnitude)) + if (e.magnitude < 0) " left" else " right"
    RidingEventKind.HARD_CORNER_TURN -> "%.0f°/s".format(abs(e.magnitude))
}

private fun eventLabel(k: RidingEventKind): String = when (k) {
    RidingEventKind.HARD_BRAKE -> "Hard braking"
    RidingEventKind.HARD_ACCEL -> "Hard acceleration"
    RidingEventKind.HARD_CORNER_LEAN, RidingEventKind.HARD_CORNER_TURN -> "Hard corner"
}

/** Deepest lean, top speed, hardest corner, hardest braking and the best
 *  stretch — whichever of them this trip actually recorded. Places lead the
 *  story view, so top speed is listed, never headlined (#428, #431). */
fun tripHighlights(trip: Trip, points: List<TraceStore.TracePoint>, bestStretch: IntRange?): List<TripHighlight> {
    val m = trip.moments
    val out = mutableListOf<TripHighlight>()
    if (bestStretch != null) {
        val stretch = points.subList(bestStretch.first, bestStretch.last + 1).map { it.at }
        out += TripHighlight(
            "stretch", "Best stretch", "twistiest ${formatDistanceKm(TripInsights.BEST_STRETCH_METERS)}",
            COLOR_BEST_STRETCH, stretch[stretch.size / 2], stretch,
        )
    }
    m.maxLean?.takeIf { trip.mode.tracksLean && it.value != 0.0 }?.let {
        out += TripHighlight("lean", "Deepest lean",
            formatLeanAngle(abs(it.value)) + if (it.value < 0) " left" else " right", COLOR_LEAN, it.at)
    }
    m.events.filter(::isCorner).maxByOrNull { abs(it.magnitude) }?.let {
        out += TripHighlight("corner", "Hardest corner", eventValue(it), COLOR_CORNER, it.at)
    }
    m.events.filter { it.kind == RidingEventKind.HARD_BRAKE }.minByOrNull { it.magnitude }?.let {
        out += TripHighlight("brake", "Hardest braking", eventValue(it), COLOR_BRAKE, it.at)
    }
    m.topSpeed?.let { out += TripHighlight("speed", "Top speed", formatSpeedKmh(it.value), COLOR_TOP_SPEED, it.at) }
    return out
}

@Composable
fun HighlightRow(h: TripHighlight, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(h.color))))
        Text(h.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(h.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** "Oudenaarde, Kluisbergen (new) and Ronse" — new places marked, the rest
 *  plain. Null when no boundary was learned along the route. */
fun placesLine(places: List<PlaceVisit>): String? {
    if (places.isEmpty()) return null
    val names = places.map { if (it.isNew) "${it.name} (new)" else it.name }
    return if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
}

/** The collapsed "Deep dive" (#444): every number the trip recorded, grouped. */
@Composable
fun TripDeepDive(
    trip: Trip,
    points: List<TraceStore.TracePoint>,
    extras: TripDetailExtras?,
    leanOffsetDeg: Float,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable { open = !open }
                .padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Deep dive", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (open) "Collapse deep dive" else "Expand deep dive")
        }
        AnimatedVisibility(open) {
            // Derived once per open, not per recomposition: splits and the
            // lean summary each walk the whole trace.
            val sections = remember(points) { DeepDiveNumbers.of(points) }
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                OverviewSection(trip, sections)
                SpeedSection(trip, sections)
                if (trip.mode.tracksLean || trip.mode.tracksGForce) CorneringSection(trip, sections, leanOffsetDeg)
                EventsSection(trip, points, extras)
                SplitsSection(sections)
                RoadsSection(trip)
                StopsSection(trip, extras)
                EngineSection(trip)
                RecordingSection(trip, points)
            }
        }
    }
}

/** The trace-derived figures, computed together so opening the dive walks the
 *  trace a fixed number of times. */
private class DeepDiveNumbers(
    val movingMs: Long,
    val profile: List<DistanceSample>,
    val splits: List<com.jellemax.detour.data.TripSplit>,
    val bestSplitIndex: Int?,
    val speedBands: List<com.jellemax.detour.data.SpeedBand>,
    val lean: com.jellemax.detour.data.LeanSummary?,
) {
    companion object {
        fun of(points: List<TraceStore.TracePoint>): DeepDiveNumbers {
            val splits = TripInsights.splits(points)
            return DeepDiveNumbers(
                movingMs = TripInsights.movingMs(points),
                profile = TripInsights.profile(points),
                splits = splits,
                bestSplitIndex = TripInsights.bestSplit(splits)?.index,
                speedBands = TripInsights.speedBands(points),
                lean = TripInsights.lean(points),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Stat(label: String, value: String, emphasis: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasis) FontWeight.Bold else FontWeight.Normal)
    }
}

/** A share bar: label, the time, and its fraction of [totalMs]. */
@Composable
private fun ShareRow(label: String, ms: Long, totalMs: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(formatDurationHistory(ms), style = MaterialTheme.typography.bodySmall)
        }
        LinearProgressIndicator(
            progress = { if (totalMs > 0) (ms.toFloat() / totalMs).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun OverviewSection(trip: Trip, n: DeepDiveNumbers) {
    Column {
        SectionTitle("Overview")
        Stat("Distance", formatDistanceKm(trip.distanceMeters))
        Stat("Total time", formatDurationHistory(trip.durationMs))
        Stat("Moving time", formatDurationHistory(n.movingMs))
        val ds = trip.drivingStats
        Stat("Stops", if (ds.stopCount == 0) "none" else "${ds.stopCount} · ${formatDurationHistory(ds.idleMs)}")
        Stat("Average speed", formatSpeedKmh(trip.avgSpeedMps))
        if (n.movingMs > 0) Stat("Moving average", formatSpeedKmh(trip.distanceMeters / (n.movingMs / 1000.0)))
        if (ds.twistinessScore > 0.0) Stat("Twistiness", "${(ds.twistinessScore * 100).roundToInt()}%")
    }
}

@Composable
private fun SpeedSection(trip: Trip, n: DeepDiveNumbers) {
    Column {
        SectionTitle("Speed")
        DistanceChart(n.profile.map { it.meters to it.speedKmh }, "km/h")
        val moving = n.speedBands.sumOf { it.ms }
        for (b in n.speedBands) if (b.ms > 0) {
            ShareRow(if (b.toKmh == null) "${b.fromKmh}+ km/h" else "${b.fromKmh}–${b.toKmh} km/h", b.ms, moving)
        }
        val ds = trip.drivingStats
        if (ds.secondsOverLimit > 0) {
            Stat(
                "Over the posted limit",
                "${formatDurationHistory(ds.secondsOverLimit * 1000)} · ${ds.pctOverLimit.roundToInt()}%",
            )
        }
        Stat("Top speed", formatSpeedKmh(trip.topSpeedMps))
        val source = if (ds.obd2SpeedPct > 0.0) "OBD2 ${ds.obd2SpeedPct.roundToInt()}% · GPS the rest" else "GPS"
        Stat("Speed source", source)
    }
}

@Composable
private fun CorneringSection(trip: Trip, n: DeepDiveNumbers, leanOffsetDeg: Float) {
    Column {
        SectionTitle("Cornering")
        val lean = n.lean
        if (trip.mode.tracksLean && lean != null) {
            DistanceChart(n.profile.mapNotNull { s -> s.leanDeg?.let { s.meters to it } }, "lean °", signed = true)
            Stat("Deepest left", formatLeanAngle(lean.maxLeftDeg))
            Stat("Deepest right", formatLeanAngle(lean.maxRightDeg))
            val leaned = lean.leftMs + lean.rightMs
            if (leaned > 0) {
                ShareRow("Leaning left", lean.leftMs, leaned)
                ShareRow("Leaning right", lean.rightMs, leaned)
            }
            for (b in lean.bands) if (b.ms > 0) {
                ShareRow(if (b.toDeg == null) "${b.fromDeg}°+" else "${b.fromDeg}–${b.toDeg}°", b.ms, leaned)
            }
            Stat("Mount offset (current calibration)", formatLeanAngle(leanOffsetDeg.toDouble()))
        }
        if (trip.mode.tracksGForce && trip.maxGForce > 0.0) Stat("Max g", formatGForce(trip.maxGForce))
    }
}

@Composable
private fun EventsSection(trip: Trip, points: List<TraceStore.TracePoint>, extras: TripDetailExtras?) {
    val ds = trip.drivingStats
    val counted = ds.hardBrakeCount + ds.hardAccelCount + ds.hardCornerCount
    if (counted == 0) return
    Column {
        SectionTitle("Riding events")
        Text("Not a score to chase — informational only.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Stat("Hard braking", "${ds.hardBrakeCount}")
        Stat("Hard acceleration", "${ds.hardAccelCount}")
        Stat("Hard corners", "${ds.hardCornerCount}")
        val line = remember(points) { points.map { it.at } }
        val shown = trip.moments.events.take(MAX_EVENT_ROWS)
        for (e in shown) {
            val km = TripInsights.metersAlong(line, e.at)?.let { formatDistanceKm(it) }
            val place = extras?.let { TripInsights.placeAt(e.at, it.municipalities) }
            Stat(
                listOfNotNull(eventLabel(e.kind), place, km?.let { "at $it" }).joinToString(" · "),
                eventValue(e),
            )
        }
        val unlisted = counted - shown.size
        if (unlisted > 0) {
            Text("$unlisted more not listed", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The dive is a list, not a log: past this many a rider scrolls a wall. */
private const val MAX_EVENT_ROWS = 30

@Composable
private fun SplitsSection(n: DeepDiveNumbers) {
    if (n.splits.size < 2) return
    Column {
        SectionTitle("Splits · every ${formatDistanceKm(TripInsights.SPLIT_METERS)}")
        for (s in n.splits) {
            val best = s.index == n.bestSplitIndex
            val parts = listOfNotNull(
                formatDurationHistory(s.durationMs),
                formatSpeedKmh(s.avgSpeedMps),
                s.maxLeanDeg?.let { formatLeanAngle(it) },
                "twisty ${(s.twistiness * 100).roundToInt()}%",
            )
            Stat(
                "${formatDistanceKm(s.startMeters)}–${formatDistanceKm(s.startMeters + s.distanceMeters)}" +
                    if (best) " · best" else "",
                parts.joinToString(" · "),
                emphasis = best,
            )
        }
    }
}

@Composable
private fun RoadsSection(trip: Trip) {
    val roads = trip.drivingStats.roadTypeMeters
    if (roads.values.sum() <= 0.0) return
    Column {
        SectionTitle("Roads")
        for (c in HighwayClass.entries) roads[c]?.takeIf { it > 0 }?.let {
            Stat(TripInsights.roadWords(c).replaceFirstChar { ch -> ch.uppercase() }, formatDistanceKm(it))
        }
    }
}

@Composable
private fun StopsSection(trip: Trip, extras: TripDetailExtras?) {
    val stops = trip.moments.stops
    if (stops.isEmpty()) return
    Column {
        SectionTitle("Stops")
        for (s in stops) {
            val place = extras?.let { TripInsights.placeAt(s.at, it.municipalities) }
            Stat(
                listOfNotNull(place, formatTimeOfDay(s.startMs)).joinToString(" · "),
                // Clock form, not formatDurationHistory: most stops are under a
                // minute, and that one floors them to "0 min".
                formatDuration(s.durationMs),
            )
        }
    }
}

@Composable
private fun EngineSection(trip: Trip) {
    val d = trip.drivingStats
    if (d.maxRpm <= 0.0 && d.fuelMilliliters <= 0) return
    Column {
        SectionTitle("Engine (OBD2)")
        if (d.maxRpm > 0.0) {
            Stat("Peak rpm", "${d.maxRpm.roundToInt()}")
            Stat("Average rpm", "${d.avgRpm.roundToInt()}")
            Stat("Max throttle", "${d.maxThrottlePct.roundToInt()}%")
            Stat("Wide-open throttle", "${d.pctWideOpenThrottle.roundToInt()}% of samples")
        }
        tripFuelEconomyLper100Km(trip)?.let {
            Stat("Fuel", (if (d.fuelEstimated) "~" else "") + formatFuelPer100Km(it))
            if (d.fuelEstimated) {
                Text(
                    "A MAF-based estimate — this vehicle has no direct fuel-rate PID, so it tracks " +
                        "engine load, not the injectors, and can drift. Tune it per vehicle " +
                        "under the OBD2 adapter settings.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecordingSection(trip: Trip, points: List<TraceStore.TracePoint>) {
    Column {
        SectionTitle("Recording")
        Stat("Vehicle", trip.mode.label)
        Stat("Stored points (every 25 m)", "${points.size}")
        val gaps = remember(points) { TripInsights.signalGaps(points.map { it.at }) }
        Stat("Signal gaps", if (gaps == 0) "none" else "$gaps")
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text("Export GPX and the share card from the buttons at the top.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * A line over distance, scaled to its own range. [signed] draws a zero line
 * through the middle (lean: left below, right above). No axes beyond the range
 * labels: it is a shape to read, the exact numbers are in the rows under it.
 */
@Composable
private fun DistanceChart(samples: List<Pair<Double, Double>>, unit: String, signed: Boolean = false) {
    if (samples.size < 2) return
    val color = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outlineVariant
    val maxX = samples.last().first.coerceAtLeast(1.0)
    val top = (if (signed) samples.maxOf { abs(it.second) } else samples.maxOf { it.second }).coerceAtLeast(1.0)
    val bottom = if (signed) -top else 0.0
    Column {
        Canvas(Modifier.fillMaxWidth().height(96.dp).padding(vertical = 4.dp)) {
            fun y(v: Double) = ((top - v) / (top - bottom) * size.height).toFloat()
            if (signed) drawLine(axis, Offset(0f, y(0.0)), Offset(size.width, y(0.0)))
            val path = Path()
            samples.forEachIndexed { i, (x, v) ->
                val px = (x / maxX * size.width).toFloat()
                if (i == 0) path.moveTo(px, y(v)) else path.lineTo(px, y(v))
            }
            drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                if (signed) "±${top.roundToInt()} $unit" else "0–${top.roundToInt()} $unit",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(formatDistanceKm(maxX), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

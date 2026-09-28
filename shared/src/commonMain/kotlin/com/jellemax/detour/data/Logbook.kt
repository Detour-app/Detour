package com.jellemax.detour.data

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One row of the Logbook timeline (#444). */
sealed interface LogbookItem {
    val atMs: Long

    data class Ride(
        val trip: Trip,
        val title: String,
        /** Whether [title] is the rider's own edit rather than the generated one. */
        val titleEdited: Boolean,
        /** At most one, and only when something stands out. */
        val chip: String?,
        val places: List<PlaceVisit>,
    ) : LogbookItem {
        override val atMs get() = trip.startTimeMs

        /** A ride worth a full card: it earned a highlight or found a new
         *  town. The rest — commutes, errands — collapse to a compact row, or
         *  a Logbook of daily drives is forty screens of identical maps. */
        val standout: Boolean get() = chip != null || places.any { it.isNew }

        /** Stamps earn their space when one is new, or there's a route to
         *  tell; one familiar town repeats on every commute and says nothing. */
        val stampsWorthShowing: Boolean get() = places.any { it.isNew } || places.size >= 2
    }

    /** A badge earned, shown between the rides it was earned among. */
    data class Milestone(val title: String, override val atMs: Long) : LogbookItem
}

/** A month chapter, newest first, its items newest first. */
data class LogbookMonth(
    val year: Int,
    /** 1..12 */
    val month: Int,
    val items: List<LogbookItem>,
) {
    val rides: List<LogbookItem.Ride> get() = items.filterIsInstance<LogbookItem.Ride>()
    val meters: Double get() = rides.sumOf { it.trip.distanceMeters }
    val newPlaces: Int get() = rides.sumOf { r -> r.places.count { it.isNew } }
}

/** The Logbook's filter chips. [ALL] includes the modes that are neither. */
enum class LogbookFilter(val label: String) {
    ALL("All"), MOTO("Moto"), CAR("Car");

    fun matches(mode: TravelMode) = when (this) {
        ALL -> true
        MOTO -> mode == TravelMode.MOTO
        CAR -> mode == TravelMode.CAR
    }
}

/**
 * Titles, highlight chips and chapters for the Logbook (#444). Pure: the
 * caller loads trips, places, edited titles and badges and passes them in.
 */
object Logbook {

    /** "Longest yet" needs this many earlier rides of the same vehicle, or the
     *  first few rides would all claim it. */
    const val LONGEST_MIN_EARLIER = 3

    /** Places named in a ride title before it would stop reading as a title. */
    private const val TITLE_PLACES = 2

    /** A ride whose middle lies further than this from where the month's rides
     *  cluster is left off the month map, or one trip abroad zooms the whole
     *  month out to country scale. */
    const val MONTH_MAP_FOCUS_METERS = 50_000.0

    fun timeOfDay(hour: Int): String = when (hour) {
        in 5..11 -> "morning"
        in 12..16 -> "afternoon"
        in 17..21 -> "evening"
        else -> "night"
    }

    private fun weekday(d: DayOfWeek) = d.name.lowercase().replaceFirstChar { it.uppercase() }

    /**
     * A ride: "Sunday afternoon ride through Ronse and Kluisbergen", naming the
     * new places first because those are the story. Any other vehicle leads with
     * the time of day, so the same commute doesn't read identically every day:
     * "Evening drive from Gent to Aalst", "Morning drive around Gent".
     */
    internal fun autoTitle(trip: Trip, places: List<PlaceVisit>, zone: TimeZone): String {
        val dt = Instant.fromEpochMilliseconds(trip.startTimeMs).toLocalDateTime(zone)
        val names = places.map { it.name }
        if (trip.mode != TravelMode.MOTO) {
            val noun = if (trip.mode == TravelMode.CAR) "drive" else "${trip.mode.label.lowercase()} trip"
            val lead = "${timeOfDay(dt.hour).replaceFirstChar { it.uppercase() }} $noun"
            return when {
                names.size >= 2 -> "$lead from ${names.first()} to ${names.last()}"
                names.size == 1 -> "$lead around ${names.single()}"
                else -> "${weekday(dt.dayOfWeek)} ${timeOfDay(dt.hour)} $noun"
            }
        }
        val base = "${weekday(dt.dayOfWeek)} ${timeOfDay(dt.hour)} ride"
        val named = (places.filter { it.isNew } + places.filterNot { it.isNew }).take(TITLE_PLACES).map { it.name }
        return when (named.size) {
            0 -> base
            1 -> "$base through ${named[0]}"
            else -> "$base through ${named[0]} and ${named[1]}"
        }
    }

    /**
     * The one chip a ride earns, if any, first match wins: the first ride on
     * this vehicle, new places, the longest ride yet on it, the twistiest ride
     * of its month. [tripsNewestFirst] is the whole history, unfiltered, so a
     * filter never changes what counts as "first" or "longest".
     */
    internal fun chips(
        tripsNewestFirst: List<Trip>,
        places: Map<Long, List<PlaceVisit>>,
        zone: TimeZone,
    ): Map<Long, String> {
        val out = HashMap<Long, String>()
        val oldestFirst = tripsNewestFirst.sortedBy { it.startTimeMs }
        val longestSoFar = HashMap<TravelMode, Double>()
        val countSoFar = HashMap<TravelMode, Int>()
        val twistiestOfMonth = tripsNewestFirst
            .filter { it.mode.tracksLean && it.drivingStats.twistinessScore > 0.0 }
            .groupBy { monthOf(it.startTimeMs, zone) }
            .filterValues { it.size >= 2 }
            .mapValues { (_, trips) -> trips.maxBy { it.drivingStats.twistinessScore }.startTimeMs }
            .values.toSet()
        for (t in oldestFirst) {
            val earlier = countSoFar[t.mode] ?: 0
            val longest = longestSoFar[t.mode] ?: 0.0
            val newPlaces = places[t.startTimeMs].orEmpty().count { it.isNew }
            val chip = when {
                earlier == 0 -> "First ${t.mode.label.lowercase()} trip"
                newPlaces > 0 -> if (newPlaces == 1) "1 new place" else "$newPlaces new places"
                earlier >= LONGEST_MIN_EARLIER && t.distanceMeters > longest -> "Longest yet"
                t.startTimeMs in twistiestOfMonth -> "Twistiest this month"
                else -> null
            }
            if (chip != null) out[t.startTimeMs] = chip
            countSoFar[t.mode] = earlier + 1
            if (t.distanceMeters > longest) longestSoFar[t.mode] = t.distanceMeters
        }
        return out
    }

    /**
     * Which places each trip passed, new ones marked, from each trip's own
     * [visits] (municipalities in the order entered). A place is new on the
     * earliest trip that entered it. Traces recorded outside any trip don't
     * count, so a town first seen that way reads as new on its first trip.
     */
    fun placesByTrip(visits: List<Pair<Trip, List<Municipality>>>): Map<Long, List<PlaceVisit>> {
        val seen = HashSet<Long>()
        val out = HashMap<Long, List<PlaceVisit>>()
        for ((trip, towns) in visits.sortedBy { it.first.startTimeMs }) {
            out[trip.startTimeMs] = towns.map { PlaceVisit(it.name, seen.add(it.id)) }
        }
        return out
    }

    /** [build] in the device's own zone — `app/` has no kotlinx-datetime on
     *  its classpath, so the zone stays an internal seam for tests. */
    fun build(
        trips: List<Trip>,
        filter: LogbookFilter,
        places: Map<Long, List<PlaceVisit>>,
        editedTitles: Map<Long, String>,
        milestones: List<LogbookItem.Milestone>,
    ): List<LogbookMonth> = build(trips, filter, places, editedTitles, milestones, TimeZone.currentSystemDefault())

    internal fun build(
        trips: List<Trip>,
        filter: LogbookFilter,
        places: Map<Long, List<PlaceVisit>>,
        editedTitles: Map<Long, String>,
        milestones: List<LogbookItem.Milestone>,
        zone: TimeZone,
    ): List<LogbookMonth> {
        val chips = chips(trips, places, zone)
        val rides = trips.filter { filter.matches(it.mode) }.map { t ->
            val p = places[t.startTimeMs].orEmpty()
            val edited = editedTitles[t.startTimeMs]
            LogbookItem.Ride(t, edited ?: autoTitle(t, p, zone), edited != null, chips[t.startTimeMs], p)
        }
        // Milestones belong to the rider, not to a vehicle, so every filter
        // shows them — but only in a month it has rides for: a chapter of
        // badges alone under "Moto" reads as "0 rides" with nothing to open.
        return (rides + milestones)
            .sortedByDescending { it.atMs }
            .groupBy { monthOf(it.atMs, zone) }
            .map { (ym, items) -> LogbookMonth(ym / 100, ym % 100, items) }
            .filter { it.rides.isNotEmpty() }
    }

    /** The title a ride shows everywhere: the rider's own edit, else the
     *  generated one, in the device's zone. */
    fun title(trip: Trip, places: List<PlaceVisit>, edited: String?): String =
        edited ?: autoTitle(trip, places, TimeZone.currentSystemDefault())

    /**
     * The month map's lines minus far-off outliers: kept are the lines whose
     * middle point lies within [MONTH_MAP_FOCUS_METERS] of the median of all
     * the middles — the median, not the mean, so the outliers don't drag the
     * centre towards themselves.
     */
    fun mapFocus(lines: List<List<LatLon>>): List<List<LatLon>> {
        val mids = lines.filter { it.isNotEmpty() }.map { it[it.size / 2] }
        if (mids.size < 3) return lines
        val centre = LatLon(mids.map { it.lat }.sorted()[mids.size / 2], mids.map { it.lon }.sorted()[mids.size / 2])
        return lines.filter {
            it.isNotEmpty() && RoadRoulette.distanceMeters(it[it.size / 2], centre) <= MONTH_MAP_FOCUS_METERS
        }
    }

    /** Whole weeks (Monday-based) between [ms] and [nowMs]: 0 this week, 1 last. */
    fun weeksAgo(ms: Long, nowMs: Long): Int = weeksAgo(ms, nowMs, TimeZone.currentSystemDefault())

    internal fun weeksAgo(ms: Long, nowMs: Long, zone: TimeZone): Int =
        // Rounded, not floored: a week holding a DST switch is an hour short.
        ((weekStartMs(nowMs, zone) - weekStartMs(ms, zone)).toDouble() / WEEK_MS).roundToInt()

    /** Midnight on the Monday of [ms]'s week. */
    fun weekStartMs(ms: Long): Long = weekStartMs(ms, TimeZone.currentSystemDefault())

    internal fun weekStartMs(ms: Long, zone: TimeZone): Long {
        val date = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date
        val monday = date.minus(date.dayOfWeek.ordinal, DateTimeUnit.DAY)
        return monday.atStartOfDayIn(zone).toEpochMilliseconds()
    }

    private const val WEEK_MS = 7 * 24 * 3_600_000L

    /** yyyymm, so it sorts and groups as one number. */
    fun monthOf(ms: Long): Int = monthOf(ms, TimeZone.currentSystemDefault())

    internal fun monthOf(ms: Long, zone: TimeZone): Int {
        val dt = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
        return dt.year * 100 + dt.monthNumber
    }
}

/**
 * Titles the rider typed over the generated ones, keyed by trip start time.
 *
 * Its own file rather than a field on the trip: trips.json is replaced
 * wholesale by every sync response (see [TripStore.replaceRaw]), so an edit
 * stored there would need the same override bookkeeping vehicle-mode edits
 * carry. The cost is that titles stay on this device (and its backup) and
 * don't follow the rider to a second phone.
 */
object TripTitleStore {
    private const val FILE_NAME = "trip_titles.json"

    fun load(): Map<Long, String> {
        val f = accountFile(FILE_NAME)
        if (!f.exists()) return emptyMap()
        return runCatching { decode(f.readText()) }.getOrDefault(emptyMap())
    }

    /** A blank [title] goes back to the generated one. */
    fun set(startTimeMs: Long, title: String) {
        val all = load().toMutableMap()
        if (title.isBlank()) all.remove(startTimeMs) else all[startTimeMs] = title.trim()
        accountFile(FILE_NAME).writeText(encode(all))
    }

    internal fun decode(text: String): Map<Long, String> =
        jsonObjectOf(text).entries.associate { (k, v) -> k.toLong() to v.jsonPrimitive.content }

    internal fun encode(titles: Map<Long, String>): String =
        buildJsonObject {
            for ((k, v) in titles) put(k.toString(), JsonPrimitive(v))
        }.string()
}

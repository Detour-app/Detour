package com.jellemax.detour.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import okio.IOException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class LatLon(val lat: Double, val lon: Double)

/** An OSM way: parallel lists of node ids and coordinates. */
data class OverpassWay(val nodes: List<Long>, val points: List<LatLon>)

/**
 * Picks a random point on a road within a radius, using Detour's own
 * `/api/roads` endpoint (OpenStreetMap data, issue #302).
 *
 * For large radii it does NOT download every road in the circle (which can be
 * tens of MB over a city). Instead it samples a random sub-area (uniform by
 * area) and queries only a small circle around it, widening the search when
 * the sampled spot has no roads. Within the fetched roads the point is chosen
 * uniformly by road length.
 */
object RoadRoulette {

    suspend fun randomRoadPoint(
        center: LatLon,
        radiusMeters: Double,
        highwayRegex: String,
        bearingDeg: Double? = null,
        explored: ExploredArea? = null,
        minRadiusMeters: Double = 0.0,
    ): LatLon {
        // Small circles are cheap to fetch whole.
        if (radiusMeters <= 1500) {
            return pickPoint(
                fetchRoads(center, radiusMeters, highwayRegex),
                center, radiusMeters, bearingDeg, explored, minRadiusMeters,
            ) ?: throw IOException("No roads found within radius")
        }

        var lastError: IOException? = null
        for (attempt in 0 until 4) {
            // Prefer sampling sub-areas the fog of war hasn't uncovered yet.
            val sample = generateSequence {
                randomPointInCircle(center, radiusMeters, bearingDeg, minRadiusMeters)
            }.take(6).firstOrNull { explored?.isExplored(it) != true }
                ?: randomPointInCircle(center, radiusMeters, bearingDeg, minRadiusMeters)
            // 600 m, 2.4 km, 5.4 km, 9.6 km — widen only if the spot was empty.
            val searchRadius = min(600.0 * (attempt + 1) * (attempt + 1), radiusMeters)
            try {
                val ways = fetchRoads(sample, searchRadius, highwayRegex)
                pickPoint(ways, center, radiusMeters, bearingDeg, explored, minRadiusMeters)
                    ?.let { return it }
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("No roads found within radius")
    }

    /**
     * Uniform-by-area random point in the [minRadiusMeters, radiusMeters] annulus
     * (an inner radius of 0 is just the full circle); with [bearingDeg] set,
     * constrained to a ±45° wedge in that compass direction.
     */
    fun randomPointInCircle(
        center: LatLon,
        radiusMeters: Double,
        bearingDeg: Double? = null,
        minRadiusMeters: Double = 0.0,
    ): LatLon {
        val theta = if (bearingDeg == null) {
            Random.nextDouble(2 * PI)
        } else {
            toRadians(bearingDeg) + Random.nextDouble(-PI / 4, PI / 4)
        }
        val minR = minRadiusMeters.coerceIn(0.0, radiusMeters)
        val r = sqrt(minR * minR + Random.nextDouble() * (radiusMeters * radiusMeters - minR * minR))
        return offset(center, r, theta)
    }

    /** Compass bearing from [from] to [to], degrees 0–360 (0 = north). */
    fun bearingDeg(from: LatLon, to: LatLon): Double {
        val dLat = to.lat - from.lat
        val dLon = (to.lon - from.lon) * cos(toRadians(from.lat))
        return (toDegrees(atan2(dLon, dLat)) + 360.0) % 360.0
    }

    fun withinWedge(center: LatLon, p: LatLon, bearingDeg: Double, halfAngleDeg: Double): Boolean {
        val diff = abs(bearingDeg(center, p) - bearingDeg) % 360.0
        return min(diff, 360.0 - diff) <= halfAngleDeg
    }

    /** Point at [distanceMeters] from [center] in direction [bearingRad]. */
    fun offset(center: LatLon, distanceMeters: Double, bearingRad: Double): LatLon {
        val dLat = (distanceMeters * cos(bearingRad)) / 111_320.0
        val dLon = (distanceMeters * sin(bearingRad)) /
            (111_320.0 * cos(toRadians(center.lat)))
        return LatLon(center.lat + dLat, center.lon + dLon)
    }

    /**
     * Length-weighted random point on the given ways, restricted to the main
     * circle. Already-explored segments keep only a fraction of their weight,
     * so undiscovered roads win most of the time.
     */
    private fun pickPoint(
        ways: List<OverpassWay>,
        center: LatLon,
        radiusMeters: Double,
        bearingDeg: Double? = null,
        explored: ExploredArea? = null,
        minRadiusMeters: Double = 0.0,
    ): LatLon? {
        data class Segment(val a: LatLon, val b: LatLon, val weight: Double)

        val segments = ArrayList<Segment>()
        for (way in ways) {
            val pts = way.points
            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                val mid = LatLon((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
                val dist = distanceMeters(center, mid)
                if (dist in minRadiusMeters..radiusMeters &&
                    (bearingDeg == null || withinWedge(center, mid, bearingDeg, 50.0))
                ) {
                    val factor = if (explored?.isExplored(mid) == true)
                        ExploredArea.EXPLORED_WEIGHT else 1.0
                    segments.add(Segment(a, b, distanceMeters(a, b) * factor))
                }
            }
        }
        if (segments.isEmpty()) return null

        val total = segments.sumOf { it.weight }
        if (total <= 0.0) return segments.first().a
        var pick = Random.nextDouble(total)
        for (seg in segments) {
            if (pick <= seg.weight) {
                val t = if (seg.weight == 0.0) 0.0 else pick / seg.weight
                return LatLon(
                    seg.a.lat + (seg.b.lat - seg.a.lat) * t,
                    seg.a.lon + (seg.b.lon - seg.a.lon) * t,
                )
            }
            pick -= seg.weight
        }
        return segments.last().b
    }

    /**
     * Drivable ways matching [highwayRegex] within [radiusMeters] of [center] — spin's
     * random-road feature (issue #382, phase 5 of #302) and `RoundTripPlanner`'s sector fetch.
     *
     * Backed by this deployment's own `/api/roads` endpoint (issue #380/#382). No disk cache
     * in front of it — a spin tap is user-initiated, not a background prefetch loop, so there
     * is nothing to survive a restart for (issue #382's own reasoning). An empty answer is
     * returned as-is: the bbox genuinely having no matching road is a real result, and every
     * caller here already handles an empty list by widening the search or trying the next
     * sector.
     */
    suspend fun fetchRoads(
        center: LatLon,
        radiusMeters: Double,
        highwayRegex: String,
    ): List<OverpassWay> {
        val backendBase = RoutingServer.roadsBase(RoutingServer.loadCustom())
        if (backendBase.isBlank()) return emptyList()
        return try {
            fetchRoadsViaBackend(backendBase, center, radiusMeters, highwayRegex)
        } catch (e: IOException) {
            emptyList()
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    /** The bbox fetch against this deployment's own drivable-road endpoint (issue #380/#382) —
     *  the wire shape is `RoadWayDto`/`RoadsBboxResponse`
     *  (`backend/Detour/Detour.Api/Contracts/RoadContracts.cs`). [parseRoadsResponse] is the
     *  pure half, split out for the same reason [speedLimitWaysViaBackend]'s is. */
    private suspend fun fetchRoadsViaBackend(
        base: String,
        center: LatLon,
        radiusMeters: Double,
        highwayRegex: String,
    ): List<OverpassWay> {
        val bbox = bboxDegrees(center, radiusMeters)
        val url = "$base/api/roads?minLat=${bbox.minLat}&minLon=${bbox.minLon}" +
            "&maxLat=${bbox.maxLat}&maxLon=${bbox.maxLon}"
        val body = jsonObjectOf(rawGet(url, headers = RoutingServer.userAgentHeaders()))
        return parseRoadsResponse(body, highwayRegex)
    }

    /** [fetchRoadsViaBackend]'s pure half. internal, not private, so commonTest can feed it
     *  canned response bodies. The node ids [OverpassWay] carries are synthetic (`0L`, one per
     *  point) since this table does not store them — `RoundTripPlanner`'s junction detection
     *  treats that as "no junction here" rather than crashing. */
    internal fun parseRoadsResponse(body: JsonObject, highwayRegex: String): List<OverpassWay> {
        val regex = Regex(highwayRegex)
        val ways = ArrayList<OverpassWay>()
        for (el in (body.optArray("ways") ?: JsonArrayEmpty).objects()) {
            val tag = el.optString("highway").takeIf { it.isNotBlank() } ?: continue
            if (!regex.matches(tag)) continue
            val polyline = el.optArray("polyline") ?: continue
            val pts = polyline.arrays().map { LatLon(it.optDouble(0), it.optDouble(1)) }
            if (pts.size >= 2) ways.add(OverpassWay(List(pts.size) { 0L }, pts))
        }
        return ways
    }

    /** Road classes a car/moto can legally be on; excludes the footways,
     *  cycleways, service roads and tracks that used to hijack the badge. */
    internal const val DRIVABLE_HIGHWAYS = "motorway|trunk|primary|secondary|tertiary|" +
        "unclassified|residential|living_street|" +
        "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link"

    /** Beyond this the road is not the one we are on, whatever the backend returned. */
    internal const val MAX_SNAP_METERS = 25.0

    /** A road counts as "the one we're on" when it runs within this many degrees
     *  of our heading, in either direction of travel. */
    internal const val HEADING_TOLERANCE_DEG = 40.0

    /** A drivable way with a known posted limit, for local speed-limit snapping. */
    data class SpeedLimitWay(val kmh: Double, val points: List<LatLon>)

    /** Radius fetched around you for ambient speed-limit snapping. Big enough
     *  that a single fetch covers a few minutes of city driving. */
    const val SPEED_PREFETCH_RADIUS_M = 1500.0

    /**
     * Every drivable way with a parseable `maxspeed` within [radiusMeters] of [center]. Fetched
     * once for an area, then handed to [snapSpeedLimitKmh] per GPS fix so the posted sign
     * changes the instant you cross onto a new road — no network round-trip in the loop.
     *
     * Backed by this deployment's own `/api/speedlimits` endpoint (issue #379); a disk cache
     * ([SpeedLimitStore]) from an earlier successful fetch covers a backend call that fails
     * outright. Null on any failure, empty only when the area really has no tagged road.
     */
    suspend fun speedLimitWays(
        center: LatLon,
        radiusMeters: Double = SPEED_PREFETCH_RADIUS_M,
    ): List<SpeedLimitWay>? {
        val backendBase = RoutingServer.speedLimitsBase(RoutingServer.loadCustom())
        if (backendBase.isBlank()) return SpeedLimitStore.load(center, radiusMeters)

        val fromBackend = try {
            speedLimitWaysViaBackend(backendBase, center, radiusMeters)
        } catch (e: IOException) {
            null
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        if (fromBackend != null && fromBackend.isNotEmpty()) {
            SpeedLimitStore.save(center, radiusMeters, fromBackend)
            return fromBackend
        }
        return SpeedLimitStore.load(center, radiusMeters)
    }

    /** A bounding box [radiusMeters] around [center], in degrees — shared by every backend bbox
     *  fetch ([SpeedCameras.nearViaBackend], [speedLimitWaysViaBackend]) so the lat/lon-degree
     *  conversion has one place to be correct. */
    internal data class BboxDegrees(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

    internal fun bboxDegrees(center: LatLon, radiusMeters: Double): BboxDegrees {
        val degLat = radiusMeters / 111_320.0
        val degLon = radiusMeters / (111_320.0 * cos(center.lat * PI / 180))
        return BboxDegrees(center.lat - degLat, center.lon - degLon, center.lat + degLat, center.lon + degLon)
    }

    /** The bbox fetch against this deployment's own speed-limit-way endpoint (issue #379) — the
     *  wire shape is `SpeedLimitWayDto`/`SpeedLimitsBboxResponse`
     *  (`backend/Detour/Detour.Api/Contracts/SpeedLimitContracts.cs`). The parse itself is
     *  [parseSpeedLimitsResponse], split out for the same reason [SpeedCameras.parseCamerasResponse] is. */
    private suspend fun speedLimitWaysViaBackend(base: String, center: LatLon, radiusMeters: Double): List<SpeedLimitWay> {
        val bbox = bboxDegrees(center, radiusMeters)
        val url = "$base/api/speedlimits?minLat=${bbox.minLat}&minLon=${bbox.minLon}" +
            "&maxLat=${bbox.maxLat}&maxLon=${bbox.maxLon}"
        val body = jsonObjectOf(rawGet(url, headers = RoutingServer.userAgentHeaders()))
        return parseSpeedLimitsResponse(body)
    }

    /** [speedLimitWaysViaBackend]'s pure half. internal, not private, so commonTest can feed it
     *  canned response bodies — [speedLimitWaysViaBackend] itself needs a live backend. */
    internal fun parseSpeedLimitsResponse(body: JsonObject): List<SpeedLimitWay> {
        val ways = ArrayList<SpeedLimitWay>()
        for (el in (body.optArray("ways") ?: JsonArrayEmpty).objects()) {
            val kmh = el.optDouble("maxSpeedKmh").takeIf { !it.isNaN() } ?: continue
            val polyline = el.optArray("polyline") ?: continue
            val pts = polyline.arrays().map { LatLon(it.optDouble(0), it.optDouble(1)) }
            if (pts.size >= 2) ways.add(SpeedLimitWay(kmh, pts))
        }
        return ways
    }

    /**
     * Posted limit for [point] snapped locally against a prefetched [ways] set.
     * A road must run roughly along [headingDeg] to win, so the cross street
     * and frontage road are rejected — no network call, so it's cheap enough
     * to run on every fix.
     */
    fun snapSpeedLimitKmh(
        point: LatLon,
        headingDeg: Double?,
        ways: List<SpeedLimitWay>,
    ): Double? {
        var aligned: Double? = null
        var alignedDist = Double.MAX_VALUE
        var nearest: Double? = null
        var nearestDist = Double.MAX_VALUE
        for (way in ways) {
            for (j in 0 until way.points.size - 1) {
                val a = way.points[j]
                val b = way.points[j + 1]
                val d = distanceToSegmentMeters(point, a, b)
                if (d > MAX_SNAP_METERS) continue
                if (d < nearestDist) {
                    nearestDist = d
                    nearest = way.kmh
                }
                if (headingDeg != null && d < alignedDist && alignsWith(a, b, headingDeg)) {
                    alignedDist = d
                    aligned = way.kmh
                }
            }
        }
        return aligned ?: nearest
    }

    /** Distance from [p] to the segment [a]→[b], on a local flat projection. */
    fun distanceToSegmentMeters(p: LatLon, a: LatLon, b: LatLon): Double {
        val mPerLat = 111_320.0
        val mPerLon = mPerLat * cos(toRadians(p.lat))
        val ax = (a.lon - p.lon) * mPerLon
        val ay = (a.lat - p.lat) * mPerLat
        val bx = (b.lon - p.lon) * mPerLon
        val by = (b.lat - p.lat) * mPerLat
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        // Project the origin (p) onto A→B, clamped to the segment.
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy)
    }

    /** True when segment [a]→[b] runs along [headingDeg], either way round. */
    internal fun alignsWith(a: LatLon, b: LatLon, headingDeg: Double): Boolean {
        val dLat = b.lat - a.lat
        val dLon = (b.lon - a.lon) * cos(toRadians(a.lat))
        if (dLat == 0.0 && dLon == 0.0) return false
        val segDeg = (toDegrees(atan2(dLon, dLat)) + 360.0) % 360.0
        var diff = abs(segDeg - headingDeg) % 360.0
        if (diff > 180.0) diff = 360.0 - diff
        return diff <= HEADING_TOLERANCE_DEG || diff >= 180.0 - HEADING_TOLERANCE_DEG
    }

    private val ZONE_RE = Regex("""zone:?(\d+)$""")

    /**
     * OSM `maxspeed` values that map to a definite number. Deliberately refuses
     * anything ambiguous — showing the wrong limit is worse than showing none —
     * so "none", "signals", "variable" and country `:rural` (80 in NL, 100 in DE)
     * all return null.
     */
    internal fun parseMaxSpeed(raw: String): Double? {
        val v = raw.trim().lowercase()
        v.toDoubleOrNull()?.let { return it }
        if (v.endsWith("mph")) {
            return v.removeSuffix("mph").trim().toDoubleOrNull()?.times(1.60934)
        }
        if (v.endsWith("km/h") || v.endsWith("kmh")) {
            return v.removeSuffix("km/h").removeSuffix("kmh").trim().toDoubleOrNull()
        }
        ZONE_RE.find(v)?.let { return it.groupValues[1].toDoubleOrNull() } // "nl:zone30"
        return when (v.substringAfter(':', "")) {
            "urban" -> 50.0 // 50 across the EU; the only safe implicit default
            "living_street" -> 20.0
            else -> null
        }
    }

    /** Plain-URL GET against this deployment's own backend endpoints. Goes through [Http]. */
    suspend fun rawGet(url: String, headers: Map<String, String>): String =
        Http.get(url, headers)

    fun distanceMeters(a: LatLon, b: LatLon): Double {
        val r = 6_371_000.0
        val dLat = toRadians(b.lat - a.lat)
        val dLon = toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(toRadians(a.lat)) * cos(toRadians(b.lat)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
}

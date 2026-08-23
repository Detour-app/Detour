package com.jellemax.detour.data

import io.ktor.http.encodeURLParameter
import kotlinx.serialization.SerializationException
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
 * Picks a random point on a road within a radius, using the Overpass API
 * (OpenStreetMap data).
 *
 * For large radii it does NOT download every road in the circle (which can be
 * tens of MB over a city). Instead it samples a random sub-area (uniform by
 * area) and queries only a small circle around it, widening the search when
 * the sampled spot has no roads. Within the fetched roads the point is chosen
 * uniformly by road length.
 */
object RoadRoulette {

    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )

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

    suspend fun fetchRoads(
        center: LatLon,
        radiusMeters: Double,
        highwayRegex: String,
        endpointOffset: Int = 0,
    ): List<OverpassWay> {
        val query = """
            [out:json][timeout:10];
            way(around:${radiusMeters.toInt()},${center.lat},${center.lon})["highway"~"$highwayRegex"];
            out geom;
        """.trimIndent()

        return parseWays(rawQuery(query, endpointOffset))
    }

    /** Road classes a car/moto/bike can legally be on; excludes the footways,
     *  cycleways, service roads and tracks that used to hijack the badge. */
    internal const val DRIVABLE_HIGHWAYS = "motorway|trunk|primary|secondary|tertiary|" +
        "unclassified|residential|living_street|" +
        "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link"

    /** Beyond this the road is not the one we are on, whatever Overpass returned. */
    internal const val MAX_SNAP_METERS = 25.0

    /** A road counts as "the one we're on" when it runs within this many degrees
     *  of our heading, in either direction of travel. */
    internal const val HEADING_TOLERANCE_DEG = 40.0

    /**
     * Posted speed limit (km/h) of the road [point] is on, via Overpass — for the
     * speed HUD while driving with no active route (which would otherwise carry
     * this from GraphHopper's path details). Null when nothing drivable is close
     * enough, or the tag isn't a value we can trust ("none", "signals", …).
     *
     * A plain nearest-way search picks up the parallel frontage road, the side
     * street you are passing, or the motorway you are driving under, so when
     * [headingDeg] is known a road must also run roughly along our heading;
     * only if nothing lines up do we fall back to the closest drivable road.
     */
    suspend fun nearestSpeedLimitKmh(
        point: LatLon,
        headingDeg: Double? = null,
        radiusMeters: Double = MAX_SNAP_METERS,
    ): Double? {
        val query = "[out:json][timeout:8];" +
            "way(around:${radiusMeters.toInt()},${point.lat},${point.lon})" +
            "[\"maxspeed\"][\"highway\"~\"^($DRIVABLE_HIGHWAYS)$\"];" +
            "out tags geom;"
        val json = try {
            rawQuery(query)
        } catch (e: IOException) {
            return null
        }
        val elements = jsonObjectOf(json).optArray("elements") ?: return null

        var aligned: Double? = null
        var alignedDist = Double.MAX_VALUE
        var nearest: Double? = null
        var nearestDist = Double.MAX_VALUE

        for (el in elements.objects()) {
            val raw = el.optObject("tags")?.optString("maxspeed")
                ?.takeIf { it.isNotBlank() } ?: continue
            val kmh = parseMaxSpeed(raw) ?: continue
            val geometry = el.optArray("geometry")?.objects() ?: continue
            for (j in 0 until geometry.size - 1) {
                val a = geometry[j].let { LatLon(it.optDouble("lat"), it.optDouble("lon")) }
                val b = geometry[j + 1].let { LatLon(it.optDouble("lat"), it.optDouble("lon")) }
                // Distance to the road itself, not to whichever node happened to
                // be mapped: a straight way can have its nodes hundreds of metres
                // apart and still pass right under us.
                val d = distanceToSegmentMeters(point, a, b)
                if (d > MAX_SNAP_METERS) continue
                if (d < nearestDist) {
                    nearestDist = d
                    nearest = kmh
                }
                if (headingDeg != null && d < alignedDist && alignsWith(a, b, headingDeg)) {
                    alignedDist = d
                    aligned = kmh
                }
            }
        }
        return aligned ?: nearest
    }

    /** A drivable way with a known posted limit, for local speed-limit snapping. */
    data class SpeedLimitWay(val kmh: Double, val points: List<LatLon>)

    /** Radius fetched around you for ambient speed-limit snapping. Big enough
     *  that a single fetch covers a few minutes of city driving. */
    const val SPEED_PREFETCH_RADIUS_M = 1500.0

    /**
     * Every drivable way with a parseable `maxspeed` within [radiusMeters] of
     * [center]. Fetched once for an area, then handed to [snapSpeedLimitKmh]
     * per GPS fix so the posted sign changes the instant you cross onto a new
     * road — no network round-trip in the loop. Null on any failure, empty only
     * when the area really has no tagged road — see the null-vs-empty comment
     * just below.
     */
    suspend fun speedLimitWays(
        center: LatLon,
        radiusMeters: Double = SPEED_PREFETCH_RADIUS_M,
    ): List<SpeedLimitWay>? {
        val query = "[out:json][timeout:15];" +
            "way(around:${radiusMeters.toInt()},${center.lat},${center.lon})" +
            "[\"maxspeed\"][\"highway\"~\"^($DRIVABLE_HIGHWAYS)$\"];" +
            "out tags geom;"
        // Null on any failure, empty only when the area really has no tagged
        // road: [SpeedCameras.near]'s contract, and what lets the caller back off
        // after a refusal instead of retrying on the throttle forever. A busy
        // Overpass answers 200 with an HTML "runtime error" page, so the parse
        // fails on a perfectly good HTTP response - the same three catches
        // SpeedCameras.near documents, which this used to let escape.
        val json = try {
            rawQuery(query)
        } catch (e: IOException) {
            return null
        }
        val elements = try {
            jsonObjectOf(json).optArray("elements")
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        } ?: return emptyList()
        val ways = ArrayList<SpeedLimitWay>(elements.size)
        for (el in elements.objects()) {
            val kmh = el.optObject("tags")?.optString("maxspeed")
                ?.takeIf { it.isNotBlank() }?.let { parseMaxSpeed(it) } ?: continue
            val geometry = el.optArray("geometry") ?: continue
            val pts = geometry.objects().map { LatLon(it.optDouble("lat"), it.optDouble("lon")) }
            if (pts.size >= 2) ways.add(SpeedLimitWay(kmh, pts))
        }
        return ways
    }

    /**
     * Posted limit for [point] snapped locally against a prefetched [ways] set.
     * Same alignment logic as [nearestSpeedLimitKmh] — a road must run roughly
     * along [headingDeg] to win, so the cross street and frontage road are
     * rejected — but with no network call, so it's cheap enough to run on
     * every fix.
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

    /** Runs an Overpass query, rotating across mirrors on failure. */
    suspend fun rawQuery(query: String, endpointOffset: Int = 0): String {
        var lastError: IOException? = null
        for (i in ENDPOINTS.indices) {
            val endpoint = ENDPOINTS[(i + endpointOffset) % ENDPOINTS.size]
            try {
                return post(endpoint, query)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("All Overpass endpoints failed")
    }

    private suspend fun post(endpoint: String, query: String): String = try {
        Http.request(
            method = "POST",
            url = endpoint,
            body = "data=${query.encodeURLParameter()}",
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                // Overpass usage policy asks for an identifying user agent.
                "User-Agent" to "Detour/${BuildDefaults.versionName}",
            ),
            readTimeoutMs = 12_000,
        )
    } catch (e: HttpStatusException) {
        throw IOException("Overpass API error: HTTP ${e.code}")
    }

    private fun parseWays(json: String): List<OverpassWay> {
        val elements = jsonObjectOf(json).optArray("elements") ?: return emptyList()
        val ways = ArrayList<OverpassWay>(elements.size)
        for (el in elements.objects()) {
            val geometry = el.optArray("geometry") ?: continue
            val points = geometry.objects().map {
                LatLon(it.optDouble("lat"), it.optDouble("lon"))
            }
            val nodeArray = el.optArray("nodes")
            val nodes = if (nodeArray != null && nodeArray.size == points.size) {
                nodeArray.indices.map { nodeArray.optLong(it) }
            } else {
                List(points.size) { 0L } // no node info; 0 is never a junction id
            }
            if (points.size >= 2) ways.add(OverpassWay(nodes, points))
        }
        return ways
    }

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

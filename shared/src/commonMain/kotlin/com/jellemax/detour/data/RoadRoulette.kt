package com.jellemax.detour.data

import io.ktor.http.encodeURLParameter
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

    /** internal, not private, so the mirror-fallthrough test can count them:
     *  the budget below is a slice per mirror and there is no other way to
     *  assert that the slices add up to it. */
    internal val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )

    /**
     * The window one [rawQuery] gets, and by default what it splits across the
     * mirrors — see [MIRROR_TIMEOUT_MS].
     *
     * **Three callers deliberately spend it all on each mirror instead**, by
     * passing it as `timeoutMs`: [overpassWays], [PoiRoulette] and
     * [SpeedCameras.near]. What they have in common is a query heavy enough that
     * a slice expires while the answer is still coming, and an expired slice is
     * indistinguishable from an empty area — so the slice does not make them
     * fail faster, it makes them fail *wrongly*. So this is not a hard ceiling
     * on a whole call: with two mirrors those three can cost twice this.
     */
    internal const val QUERY_BUDGET_MS = 12_000L

    /**
     * One mirror's share of [QUERY_BUDGET_MS].
     *
     * The budget used to be handed to each mirror in turn, so a primary that
     * accepted the connection and then stalled ate the whole window before the
     * second mirror was even tried — long enough that the camera and
     * speed-limit prefetches had given up and backed off, and the rider saw an
     * empty map rather than an error. A slice each means a dead primary costs a
     * fraction of the window instead of all of it.
     */
    internal val MIRROR_TIMEOUT_MS = QUERY_BUDGET_MS / ENDPOINTS.size

    /**
     * The `[timeout:]` hint every Overpass query in this module carries, in
     * seconds. Matched to [MIRROR_TIMEOUT_MS], because a server still grinding
     * on a query the client has already abandoned only burns the rate-limit
     * slot the retry needs.
     */
    internal val SERVER_TIMEOUT_S = MIRROR_TIMEOUT_MS / 1000

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
     * Same fallback chain as [speedLimitWays]: this deployment's own `/api/roads` first, when
     * announced, and only Overpass when that fails outright. Unlike [speedLimitWays] there is no
     * disk cache in front of the backend call — a spin tap is user-initiated, not a background
     * prefetch loop, so there is nothing to survive a restart for (issue #382's own reasoning).
     * An *empty* backend answer is returned as-is (not a failure to fall through on): the bbox
     * genuinely having no matching road is exactly what Overpass would also have said, and
     * every caller here already handles an empty list by widening the search or trying the next
     * sector, not by reaching for another data source.
     */
    suspend fun fetchRoads(
        center: LatLon,
        radiusMeters: Double,
        highwayRegex: String,
        endpointOffset: Int = 0,
    ): List<OverpassWay> {
        val backendBase = RoutingServer.roadsBase(RoutingServer.loadCustom())
        if (backendBase.isNotBlank()) {
            val fromBackend = try {
                fetchRoadsViaBackend(backendBase, center, radiusMeters, highwayRegex)
            } catch (e: IOException) {
                null
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            if (fromBackend != null) return fromBackend
        }
        return fetchRoadsViaOverpass(center, radiusMeters, highwayRegex, endpointOffset)
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
     *  canned response bodies. [highwayRegex] is applied the same way the Overpass query's own
     *  `["highway"~"$highwayRegex"]` tag filter is — the backend returns every drivable class in
     *  the bbox, and matching the mode-specific subset stays a client-side judgement call, same
     *  as it always was. The node ids [OverpassWay] carries are synthetic (`0L`, one per point)
     *  since this table does not store them — the same "no node info" fallback
     *  [parseWays] already uses for an Overpass element with no `nodes` array, which
     *  `RoundTripPlanner`'s junction detection already treats as "no junction here" rather than
     *  crashing. */
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

    /**
     * Overpass-only. [fetchRoads] is the entry point every caller should use — it tries the
     * backend's own `/api/roads` first (when the deployment announces one, issue #380/#382) and
     * only reaches here once that call fails outright.
     */
    suspend fun fetchRoadsViaOverpass(
        center: LatLon,
        radiusMeters: Double,
        highwayRegex: String,
        endpointOffset: Int = 0,
    ): List<OverpassWay> {
        // The whole budget per mirror, not a slice: a spin's fourth attempt
        // asks for every road within 9.6 km with geometry, which a healthy
        // mirror answers in seconds rather than the one the slice allows, and
        // a slice that expires on a mirror mid-answer reads as "no roads here".
        val query = """
            [out:json][timeout:${QUERY_BUDGET_MS / 1000}];
            way(around:${radiusMeters.toInt()},${center.lat},${center.lon})["highway"~"$highwayRegex"];
            out geom;
        """.trimIndent()

        val key = cacheKey("roads:$highwayRegex", center, radiusMeters)
        return parseWays(OverpassCache.fetch(key) { rawQuery(query, endpointOffset, timeoutMs = QUERY_BUDGET_MS) })
    }

    /** Road classes a car/moto can legally be on; excludes the footways,
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
        val query = "[out:json][timeout:$SERVER_TIMEOUT_S];" +
            "way(around:${radiusMeters.toInt()},${point.lat},${point.lon})" +
            "[\"maxspeed\"][\"highway\"~\"^($DRIVABLE_HIGHWAYS)$\"];" +
            "out tags geom;"
        val key = cacheKey("speedlimit-point", point, radiusMeters)
        val json = try {
            OverpassCache.fetch(key) { rawQuery(query) }
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
     * Every drivable way with a parseable `maxspeed` within [radiusMeters] of [center]. Fetched
     * once for an area, then handed to [snapSpeedLimitKmh] per GPS fix so the posted sign
     * changes the instant you cross onto a new road — no network round-trip in the loop.
     *
     * Same fallback chain as [SpeedCameras.near] (issue #379, phase 3 of #302): this
     * deployment's own `/api/speedlimits` first, when announced; a disk cache
     * ([SpeedLimitStore]) from an earlier successful fetch when the backend is
     * reachable-in-principle but this call failed; Overpass only once both come up empty. An
     * install that has announced nothing resolves [RoutingServer.speedLimitsBase] to blank and
     * goes straight to Overpass, unchanged from before this path existed.
     *
     * Null on any failure, empty only when the area really has no tagged road — see
     * [SpeedCameras.near]'s doc for why an empty *backend* answer is treated as a miss rather
     * than trusted as "no roads here", and falls through the same way.
     */
    suspend fun speedLimitWays(
        center: LatLon,
        radiusMeters: Double = SPEED_PREFETCH_RADIUS_M,
    ): List<SpeedLimitWay>? {
        val backendBase = RoutingServer.speedLimitsBase(RoutingServer.loadCustom())
        if (backendBase.isBlank()) return speedLimitWaysViaOverpass(center, radiusMeters)

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
        return SpeedLimitStore.load(center, radiusMeters) ?: speedLimitWaysViaOverpass(center, radiusMeters)
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
     * Overpass-only. [speedLimitWays] is the entry point every caller should use — it tries the
     * backend's own `/api/speedlimits` first (when the deployment announces one, issue #379)
     * and only reaches here once that path, and the disk cache behind it, both come up empty.
     */
    suspend fun speedLimitWaysViaOverpass(
        center: LatLon,
        radiusMeters: Double = SPEED_PREFETCH_RADIUS_M,
    ): List<SpeedLimitWay>? {
        val query = "[out:json][timeout:$SERVER_TIMEOUT_S];" +
            "way(around:${radiusMeters.toInt()},${center.lat},${center.lon})" +
            "[\"maxspeed\"][\"highway\"~\"^($DRIVABLE_HIGHWAYS)$\"];" +
            "out tags geom;"
        // Null on any failure, empty only when the area really has no tagged
        // road: [SpeedCameras.near]'s contract, and what lets the caller back off
        // after a refusal instead of retrying on the throttle forever. A busy
        // Overpass answers 200 with an HTML "runtime error" page, so the parse
        // fails on a perfectly good HTTP response - the same three catches
        // SpeedCameras.near documents, which this used to let escape.
        val key = cacheKey("speedlimit-ways", center, radiusMeters)
        val json = try {
            OverpassCache.fetch(key) { rawQuery(query) }
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

    /**
     * Runs an Overpass query, rotating across mirrors until one actually
     * answers ([isOverpassAnswer]). Each mirror gets [MIRROR_TIMEOUT_MS] and no
     * more, so a primary that is down but not *refusing* costs its slice rather
     * than the whole budget.
     *
     * Sequential, not raced: two requests per query would double what this app
     * asks of a volunteer-run API for the sake of the seconds a slice already
     * saves, and Overpass's usage policy is the reason [post] identifies us at
     * all. Ktor would make the race short to write; it is the bill that rules
     * it out, not the code.
     */
    suspend fun rawQuery(
        query: String,
        endpointOffset: Int = 0,
        timeoutMs: Long = MIRROR_TIMEOUT_MS,
    ): String {
        var lastError: IOException? = null
        for (endpoint in mirrorOrder(endpointOffset)) {
            try {
                val body = post(endpoint, query, timeoutMs)
                if (isOverpassAnswer(body)) return body
                // A refusal the mirror dressed as a 200, so it never reached
                // the catch below: the next mirror is still worth asking.
                // Passing it on instead left every caller to read it as "no
                // data here" with a healthy mirror sitting untried.
                lastError = IOException("Overpass returned no answer")
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("All Overpass endpoints failed")
    }

    /** Plain-URL GET, for callers that hit a single backend rather than
     *  Overpass's rotating mirrors (currently [SpeedCameras]'s backend camera
     *  fetch). Goes through [Http], the same client [post] uses, rather than
     *  standing up a second HTTP client configuration. */
    suspend fun rawGet(url: String, headers: Map<String, String>): String =
        Http.get(url, headers)

    /** The mirrors to try, in order, starting [offset] into the list — so the
     *  parallel sector fetches of a round trip don't all open on the same one. */
    internal fun mirrorOrder(offset: Int): List<String> =
        ENDPOINTS.indices.map { ENDPOINTS[(it + offset).mod(ENDPOINTS.size)] }

    /**
     * Whether [body] is an answer, rather than one of the two ways a mirror
     * says no with a 200 on it — see [rawQuery], which tries the next mirror
     * when this is false.
     *
     * The first is the HTML "runtime error" page a busy server sends, which
     * the caller cannot parse. The second is the dangerous one, and is the way
     * a server-side timeout comes back to a query carrying `[out:json]` —
     * which every query here does: a perfectly well-formed envelope with an
     * empty `elements` and a top-level `remark`
     * ("runtime error: Query timed out ..."). That one parses, so it reaches
     * the caller as "this area has nothing in it": the prefetch resets its
     * backoff, marks the area held and never asks again, which is exactly the
     * silence this whole file is about. Overpass also uses `remark` for
     * warnings served alongside partial data, and partial is not an answer for
     * a prefetch either, so any remark at all sends us to the next mirror.
     *
     * Parsed rather than scanned for `"remark"`: `remark` is an OSM tag too,
     * and `out tags` prints the ones mappers wrote, so a substring test would
     * throw away good answers. It costs a second parse of a body the caller
     * parses again — small against the network call that produced it, and
     * against the alternative of handing every caller a JsonObject it would
     * have to re-shape.
     */
    internal fun isOverpassAnswer(body: String): Boolean {
        if (!body.trimStart().startsWith('{')) return false
        val root = try {
            jsonObjectOf(body)
        } catch (e: SerializationException) {
            return false
        } catch (e: IllegalArgumentException) {
            return false
        }
        return root.optString("remark").isBlank()
    }

    private suspend fun post(endpoint: String, query: String, timeoutMs: Long): String = try {
        Http.request(
            method = "POST",
            url = endpoint,
            body = "data=${query.encodeURLParameter()}",
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                // Overpass usage policy asks for an identifying user agent.
                "User-Agent" to "Detour/${BuildDefaults.versionName}",
            ),
            readTimeoutMs = timeoutMs,
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

package com.jellemax.detour.data

import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okio.IOException

data class RouteResult(
    /** Full route geometry (road-following when from GraphHopper). */
    val polyline: List<LatLon>,
    /** Sampled via points for the Google Maps handoff (max 9 supported). */
    val waypoints: List<LatLon>,
    /** Total loop length, if the router reported it. */
    val distanceMeters: Double?,
    /** Turn-by-turn instructions; empty when not from GraphHopper. */
    val instructions: List<NavInstruction> = emptyList(),
    /** Estimated travel time, if the router reported it. */
    val timeMs: Long? = null,
    /** Posted speed limit per polyline segment range, if the router reported it. */
    val speedLimits: List<SpeedLimitSegment> = emptyList(),
)

/** Posted speed limit (km/h) for polyline[fromIndex until toIndex]; null where unknown. */
data class SpeedLimitSegment(val fromIndex: Int, val toIndex: Int, val kmh: Double?)

/** One GraphHopper turn instruction; indices point into the polyline. */
data class NavInstruction(
    val text: String,
    val distanceMeters: Double,
    /**
     * GraphHopper sign code. The full set, because a comment saying "-3..3" is
     * what the iOS arrow table was once written against:
     * -98/-8 U-turn (left), 8 U-turn right, -7/7 keep left/right,
     * -3..3 sharp-left through sharp-right with 0 straight on,
     * 4 finish, 5 via reached, 6 roundabout.
     */
    val sign: Int,
    val startIndex: Int,
    val endIndex: Int,
    /** Roundabout exit to take when [sign] is 6; 0 when not a roundabout. */
    val exitNumber: Int = 0,
)

data class ServerConfig(
    val url: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val enabled: Boolean = false,
) {
    val usable: Boolean get() = enabled && url.isNotBlank()
}

/**
 * Client for a self-hosted GraphHopper instance,
 * optionally behind Cloudflare Access. Configured by the user in the app; the
 * URL and token live only in app-private preferences, never in the repo/APK.
 */
object RoutingServer {

    private const val PREFS = "routing_server"

    fun bakedDefaults(): ServerConfig = ServerConfig(
        url = BuildDefaults.routingUrl,
        clientId = BuildDefaults.routingCfId,
        clientSecret = BuildDefaults.routingCfSecret,
        enabled = BuildDefaults.routingUrl.isNotBlank(),
    )

    /** Effective config: user's custom server if set, else baked defaults. */
    fun load(): ServerConfig = loadCustom() ?: bakedDefaults()

    /** The user's own server settings, or null when using built-in defaults. */
    fun loadCustom(): ServerConfig? {
        val p = prefs(PREFS)
        val s = securePrefs()
        CredentialMigration.step(p, s, CredentialMigration.SERVER_GROUP)
        val url = p.string("url")
        if (!p.bool("saved", false) || url.isBlank()) return null
        return ServerConfig(
            url = url,
            clientId = s.string("clientId"),
            clientSecret = s.string("clientSecret"),
            enabled = true,
        )
    }

    fun save(config: ServerConfig) {
        prefs(PREFS).apply {
            put("saved", true)
            put("url", config.url.trim())
        }
        securePrefs().apply {
            put("clientId", config.clientId.trim())
            put("clientSecret", config.clientSecret.trim())
        }
    }

    /** Clearing the secure store wholesale would take the session with it, so the two
     *  Cloudflare keys are removed by name. */
    fun clearCustom() {
        prefs(PREFS).clear()
        securePrefs().apply {
            CredentialMigration.SERVER_GROUP.keys.forEach { remove(it.name) }
        }
    }

    /** Cloudflare Access service-token headers, absent when not behind Access. */
    private fun headers(config: ServerConfig): Map<String, String> = buildMap {
        put("User-Agent", "Detour/${BuildDefaults.versionName}")
        if (config.clientId.isNotBlank()) {
            put("CF-Access-Client-Id", config.clientId)
            put("CF-Access-Client-Secret", config.clientSecret)
        }
    }

    suspend fun roundTrip(
        config: ServerConfig,
        start: LatLon,
        distanceMeters: Double,
        seed: Long,
        headingDeg: Double? = null,
        avoidSmallRoads: Boolean = false,
    ): RouteResult {
        // Long loops can point past the graph's map edge or into road-sparse
        // areas ("could not find a valid point"). Shrink and reroll direction
        // until routable; the UI reports the real loop length.
        var dist = distanceMeters
        var s = seed
        var lastError: IOException? = null
        repeat(4) {
            try {
                return requestRoundTrip(config, start, dist, s, headingDeg, avoidSmallRoads)
            } catch (e: IOException) {
                lastError = e
                dist *= 0.75
                s = kotlin.random.Random.nextLong()
            }
        }
        throw lastError ?: IOException("Round trip failed")
    }

    private suspend fun requestRoundTrip(
        config: ServerConfig,
        start: LatLon,
        distanceMeters: Double,
        seed: Long,
        headingDeg: Double?,
        avoidSmallRoads: Boolean = false,
    ): RouteResult {
        if (!avoidSmallRoads) {
            return fetchRoute(
                config,
                config.url.trimEnd('/') +
                    "/route?profile=moto" +
                    "&point=${start.lat},${start.lon}" +
                    "&algorithm=round_trip" +
                    "&round_trip.distance=${distanceMeters.toInt()}" +
                    "&round_trip.seed=$seed" +
                    (headingDeg?.let { "&heading=${it.toInt()}" } ?: "") +
                    "&points_encoded=false&details=max_speed",
            )
        }
        // A loop is where this matters most: left to itself, round_trip strings
        // together whatever is nearby, which around here means farm lanes.
        val body = buildJsonObject {
            put("profile", "moto")
            putJsonArray("points") {
                addJsonArray { add(start.lon); add(start.lat) }
            }
            put("algorithm", "round_trip")
            // Flat hint keys, exactly as in the query string. Nested under a
            // "round_trip" object they are silently ignored and every loop comes
            // back as GraphHopper's 10 km default.
            put("round_trip.distance", distanceMeters.toInt())
            put("round_trip.seed", seed)
            put("points_encoded", false)
            putJsonArray("details") { add("max_speed") }
            put("ch.disable", true)
            putJsonObject("custom_model") {
                put("priority", preferenceRules(avoidHighways = false, avoidSmallRoads = true))
            }
            headingDeg?.let { h -> putJsonArray("heading") { add(h.toInt()) } }
        }
        return fetchRoute(config, config.url.trimEnd('/') + "/route", body.string())
    }

    /**
     * Priority rules for the routing preferences, or an empty list when neither
     * is on. Multipliers, never zero: a house sits on a residential street and
     * the destination itself may be down a lane, so these roads have to stay
     * usable — just expensive enough that a route only takes them when there is
     * no reasonable alternative.
     */
    private fun preferenceRules(
        avoidHighways: Boolean,
        avoidSmallRoads: Boolean,
    ) = buildJsonArray {
        if (avoidHighways) {
            addJsonObject {
                put("if", "road_class == MOTORWAY || road_class == TRUNK")
                put("multiply_by", 0.05)
            }
        }
        if (avoidSmallRoads) {
            // Belgium's landelijke wegen: narrow, badly surfaced, full of
            // 90° farm-track corners. Tertiary and up are left alone; the
            // unclassified layer is where the misery lives, so it takes the
            // heaviest penalty that still leaves it routable.
            addJsonObject {
                put("if", "road_class == UNCLASSIFIED || road_class == RESIDENTIAL")
                put("multiply_by", 0.2)
            }
            addJsonObject {
                put("if", "road_class == LIVING_STREET || road_class == SERVICE")
                put("multiply_by", 0.1)
            }
            // Unpaved: never worth it on two wheels or four.
            addJsonObject {
                put("if", "road_class == TRACK || road_class == PATH")
                put("multiply_by", 0.02)
            }
        }
    }

    /**
     * Turn-by-turn route between two points, for in-app navigation. Delegates
     * to [routeVia] so there is a single query-building path for the two-point
     * and multi-stop cases.
     */
    suspend fun route(
        config: ServerConfig,
        from: LatLon,
        to: LatLon,
        profile: String,
        avoidHighways: Boolean = false,
        avoidSmallRoads: Boolean = false,
    ): RouteResult = routeVia(config, listOf(from, to), profile, avoidHighways, avoidSmallRoads)

    /**
     * Turn-by-turn route through an ordered list of stops (a saved multi-point
     * route, or the plain two-point case via [route]). [avoidHighways]
     * downgrades motorways/trunks (only matters for the car profile; moto and
     * bike never use them anyway); [avoidSmallRoads] pushes the route onto
     * roads worth driving instead of the nearest lane through a field. Either
     * one switches to a POST with a custom model, which needs flexible
     * routing — hence `ch.disable`.
     */
    suspend fun routeVia(
        config: ServerConfig,
        points: List<LatLon>,
        profile: String,
        avoidHighways: Boolean = false,
        avoidSmallRoads: Boolean = false,
    ): RouteResult {
        if (points.size < 2) throw IOException("routeVia needs at least two points")
        val rules = preferenceRules(avoidHighways, avoidSmallRoads)
        if (rules.isEmpty()) {
            val query = buildString {
                append(config.url.trimEnd('/'))
                append("/route?profile=").append(profile)
                for (p in points) append("&point=${p.lat},${p.lon}")
                append("&points_encoded=false&details=max_speed")
            }
            return fetchRoute(config, query)
        }
        val body = buildJsonObject {
            put("profile", profile)
            putJsonArray("points") {
                for (p in points) addJsonArray { add(p.lon); add(p.lat) }
            }
            put("points_encoded", false)
            putJsonArray("details") { add("max_speed") }
            put("ch.disable", true)
            putJsonObject("custom_model") { put("priority", rules) }
        }
        return fetchRoute(config, config.url.trimEnd('/') + "/route", body.string())
    }

    private suspend fun fetchRoute(
        config: ServerConfig,
        url: String,
        postBody: String? = null,
    ): RouteResult {
        val text = try {
            Http.request(
                method = if (postBody != null) "POST" else "GET",
                url = url,
                body = postBody,
                headers = headers(config),
                readTimeoutMs = 20_000,
            )
        } catch (e: HttpStatusException) {
            throw IOException("Routing server error: HTTP ${e.code}")
        }
        return parseRoute(text)
    }

    private fun parseRoute(text: String): RouteResult {
        val path = jsonObjectOf(text).optArray("paths")?.optObject(0)
            ?: throw IOException("Routing server returned no route")
        val coords = path.optObject("points")?.optArray("coordinates") ?: JsonArrayEmpty
        val polyline = ArrayList<LatLon>(coords.size)
        for (c in coords.arrays()) { // GeoJSON order: [lon, lat]
            polyline.add(LatLon(c.optDouble(1), c.optDouble(0)))
        }
        if (polyline.size < 2) throw IOException("Routing server returned an empty route")

        val instructions = path.optArray("instructions")?.objects().orEmpty().map { ins ->
            val interval = ins.optArray("interval") ?: JsonArrayEmpty
            NavInstruction(
                text = ins.optString("text"),
                distanceMeters = ins.optDouble("distance", 0.0),
                sign = ins.optInt("sign"),
                startIndex = interval.optInt(0),
                endIndex = interval.optInt(1),
                // Only present on roundabout instructions, and negative when
                // GraphHopper can't tell which exit; 0 means "don't show one".
                exitNumber = ins.optInt("exit_number").coerceAtLeast(0),
            )
        }

        val speedLimits = path.optObject("details")?.optArray("max_speed")?.arrays().orEmpty()
            .map { seg ->
                SpeedLimitSegment(
                    fromIndex = seg.optInt(0),
                    toIndex = seg.optInt(1),
                    kmh = if (seg.isNull(2)) null else seg.optDouble(2),
                )
            }

        return RouteResult(
            polyline = polyline,
            waypoints = sampleInterior(polyline, 8),
            distanceMeters = path.optDouble("distance").takeIf { !it.isNaN() },
            instructions = instructions,
            timeMs = path.optLong("time").takeIf { it > 0 },
            speedLimits = speedLimits,
        )
    }

    /**
     * Random road destination via the server: pick a random coordinate in the
     * circle and let GraphHopper snap it to the nearest routable road. Retries
     * a few times if the snap lands far outside the circle (water, forests).
     * With [explored] set, undiscovered spots are strongly preferred; an
     * explored result is only used when every attempt landed on known roads.
     */
    suspend fun randomRoadDestination(
        config: ServerConfig,
        center: LatLon,
        radiusMeters: Double,
        bearingDeg: Double? = null,
        explored: ExploredArea? = null,
        profile: String = "moto",
        minRadiusMeters: Double = 0.0,
    ): LatLon {
        var best: LatLon? = null
        var exploredHit: LatLon? = null
        repeat(4) {
            val target = generateSequence {
                RoadRoulette.randomPointInCircle(center, radiusMeters, bearingDeg, minRadiusMeters)
            }.take(6).firstOrNull { explored?.isExplored(it) != true }
                ?: RoadRoulette.randomPointInCircle(center, radiusMeters, bearingDeg, minRadiusMeters)
            val snapped = snapToRoad(config, center, target, profile) ?: return@repeat
            val dist = RoadRoulette.distanceMeters(center, snapped)
            if (dist < minRadiusMeters) return@repeat // too close, discard entirely
            if (dist <= radiusMeters * 1.15) {
                if (explored?.isExplored(snapped) != true) return snapped
                if (exploredHit == null) exploredHit = snapped
            } else {
                best = snapped
            }
        }
        return exploredHit ?: best
            ?: throw IOException("Routing server could not find a road")
    }

    private suspend fun snapToRoad(
        config: ServerConfig,
        from: LatLon,
        to: LatLon,
        profile: String,
    ): LatLon? {
        val url = config.url.trimEnd('/') +
            "/route?profile=$profile" +
            "&point=${from.lat},${from.lon}" +
            "&point=${to.lat},${to.lon}" +
            "&points_encoded=false"
        val body = try {
            Http.get(url, headers(config), readTimeoutMs = 15_000)
        } catch (e: HttpStatusException) {
            return null // unroutable target: caller retries
        }
        val snapped = jsonObjectOf(body).optArray("paths")?.optObject(0)
            ?.optObject("snapped_waypoints")?.optArray("coordinates") ?: return null
        val last = snapped.optArray(snapped.size - 1) ?: return null // [lon, lat]
        return LatLon(last.optDouble(1), last.optDouble(0))
    }

    /** [count] evenly spaced interior points, excluding start and end. */
    private fun sampleInterior(line: List<LatLon>, count: Int): List<LatLon> {
        val n = line.size
        if (n <= 2) return emptyList()
        return (1..count)
            .map { i -> line[(i * (n - 1)) / (count + 1)] }
            .distinct()
    }
}

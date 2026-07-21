package com.jellemax.maproulette.data

import android.content.Context
import com.jellemax.maproulette.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

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
    /** GraphHopper sign code: -3..3 turns, 0 straight, 4 finish, 6 roundabout… */
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
 * Client for a self-hosted GraphHopper instance (see server/CLAUDE_SETUP_GUIDE.md),
 * optionally behind Cloudflare Access. Configured by the user in the app; the
 * URL and token live only in app-private preferences, never in the repo/APK.
 */
object RoutingServer {

    private const val PREFS = "routing_server"

    fun bakedDefaults(): ServerConfig = ServerConfig(
        url = BuildConfig.ROUTING_URL,
        clientId = BuildConfig.ROUTING_CF_ID,
        clientSecret = BuildConfig.ROUTING_CF_SECRET,
        enabled = BuildConfig.ROUTING_URL.isNotBlank(),
    )

    /** Effective config: user's custom server if set, else baked defaults. */
    fun load(context: Context): ServerConfig = loadCustom(context) ?: bakedDefaults()

    /** The user's own server settings, or null when using built-in defaults. */
    fun loadCustom(context: Context): ServerConfig? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = p.getString("url", "") ?: ""
        if (!p.getBoolean("saved", false) || url.isBlank()) return null
        return ServerConfig(
            url = url,
            clientId = p.getString("clientId", "") ?: "",
            clientSecret = p.getString("clientSecret", "") ?: "",
            enabled = true,
        )
    }

    fun save(context: Context, config: ServerConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("saved", true)
            .putString("url", config.url.trim())
            .putString("clientId", config.clientId.trim())
            .putString("clientSecret", config.clientSecret.trim())
            .apply()
    }

    fun clearCustom(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun roundTrip(
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

    private fun requestRoundTrip(
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
        val body = JSONObject()
            .put("profile", "moto")
            .put("points", JSONArray()
                .put(JSONArray().put(start.lon).put(start.lat)))
            .put("algorithm", "round_trip")
            // Flat hint keys, exactly as in the query string. Nested under a
            // "round_trip" object they are silently ignored and every loop comes
            // back as GraphHopper's 10 km default.
            .put("round_trip.distance", distanceMeters.toInt())
            .put("round_trip.seed", seed)
            .put("points_encoded", false)
            .put("details", JSONArray().put("max_speed"))
            .put("ch.disable", true)
            .put("custom_model", JSONObject()
                .put("priority", preferenceRules(avoidHighways = false, avoidSmallRoads = true)))
        headingDeg?.let { body.put("heading", JSONArray().put(it.toInt())) }
        return fetchRoute(config, config.url.trimEnd('/') + "/route", body.toString())
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
    ): JSONArray {
        val rules = JSONArray()
        if (avoidHighways) {
            rules.put(JSONObject()
                .put("if", "road_class == MOTORWAY || road_class == TRUNK")
                .put("multiply_by", 0.05))
        }
        if (avoidSmallRoads) {
            // Belgium's landelijke wegen: narrow, badly surfaced, full of
            // 90° farm-track corners. Tertiary and up are left alone; the
            // unclassified layer is where the misery lives, so it takes the
            // heaviest penalty that still leaves it routable.
            rules.put(JSONObject()
                .put("if", "road_class == UNCLASSIFIED || road_class == RESIDENTIAL")
                .put("multiply_by", 0.2))
            rules.put(JSONObject()
                .put("if", "road_class == LIVING_STREET || road_class == SERVICE")
                .put("multiply_by", 0.1))
            // Unpaved: never worth it on two wheels or four.
            rules.put(JSONObject()
                .put("if", "road_class == TRACK || road_class == PATH")
                .put("multiply_by", 0.02))
        }
        return rules
    }

    /**
     * Turn-by-turn route between two points, for in-app navigation.
     * [avoidHighways] downgrades motorways/trunks (only matters for the car
     * profile; moto and bike never use them anyway); [avoidSmallRoads] pushes
     * the route onto roads worth driving instead of the nearest lane through a
     * field. Either one switches to a POST with a custom model, which needs
     * flexible routing — hence `ch.disable`.
     */
    fun route(
        config: ServerConfig,
        from: LatLon,
        to: LatLon,
        profile: String,
        avoidHighways: Boolean = false,
        avoidSmallRoads: Boolean = false,
    ): RouteResult {
        val rules = preferenceRules(avoidHighways, avoidSmallRoads)
        if (rules.length() == 0) {
            return fetchRoute(
                config,
                config.url.trimEnd('/') +
                    "/route?profile=$profile" +
                    "&point=${from.lat},${from.lon}" +
                    "&point=${to.lat},${to.lon}" +
                    "&points_encoded=false&details=max_speed",
            )
        }
        val body = JSONObject()
            .put("profile", profile)
            .put("points", JSONArray()
                .put(JSONArray().put(from.lon).put(from.lat))
                .put(JSONArray().put(to.lon).put(to.lat)))
            .put("points_encoded", false)
            .put("details", JSONArray().put("max_speed"))
            .put("ch.disable", true)
            .put("custom_model", JSONObject().put("priority", rules))
        return fetchRoute(config, config.url.trimEnd('/') + "/route", body.toString())
    }

    private fun fetchRoute(
        config: ServerConfig,
        url: String,
        postBody: String? = null,
    ): RouteResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("User-Agent", "MapRoulette/1.4")
            if (config.clientId.isNotBlank()) {
                conn.setRequestProperty("CF-Access-Client-Id", config.clientId)
                conn.setRequestProperty("CF-Access-Client-Secret", config.clientSecret)
            }
            if (postBody != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(postBody.toByteArray()) }
            }
            if (conn.responseCode != 200) {
                throw IOException("Routing server error: HTTP ${conn.responseCode}")
            }
            val stream = if (conn.contentEncoding == "gzip") {
                GZIPInputStream(conn.inputStream)
            } else {
                conn.inputStream
            }
            return parseRoute(stream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRoute(json: String): RouteResult {
        val path = JSONObject(json).getJSONArray("paths").getJSONObject(0)
        val coords = path.getJSONObject("points").getJSONArray("coordinates")
        val polyline = ArrayList<LatLon>(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i) // GeoJSON order: [lon, lat]
            polyline.add(LatLon(c.getDouble(1), c.getDouble(0)))
        }
        if (polyline.size < 2) throw IOException("Routing server returned an empty route")
        val instructions = ArrayList<NavInstruction>()
        path.optJSONArray("instructions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val ins = arr.getJSONObject(i)
                val interval = ins.getJSONArray("interval")
                instructions.add(NavInstruction(
                    text = ins.optString("text"),
                    distanceMeters = ins.optDouble("distance", 0.0),
                    sign = ins.optInt("sign"),
                    startIndex = interval.getInt(0),
                    endIndex = interval.getInt(1),
                    // Only present on roundabout instructions, and negative when
                    // GraphHopper can't tell which exit; 0 means "don't show one".
                    exitNumber = ins.optInt("exit_number").coerceAtLeast(0),
                ))
            }
        }
        val speedLimits = ArrayList<SpeedLimitSegment>()
        path.optJSONObject("details")?.optJSONArray("max_speed")?.let { arr ->
            for (i in 0 until arr.length()) {
                val seg = arr.getJSONArray(i)
                speedLimits.add(SpeedLimitSegment(
                    fromIndex = seg.getInt(0),
                    toIndex = seg.getInt(1),
                    kmh = if (seg.isNull(2)) null else seg.getDouble(2),
                ))
            }
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
    fun randomRoadDestination(
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

    private fun snapToRoad(
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
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("User-Agent", "MapRoulette/1.4")
            if (config.clientId.isNotBlank()) {
                conn.setRequestProperty("CF-Access-Client-Id", config.clientId)
                conn.setRequestProperty("CF-Access-Client-Secret", config.clientSecret)
            }
            if (conn.responseCode != 200) return null // unroutable target: caller retries
            val stream = if (conn.contentEncoding == "gzip") {
                GZIPInputStream(conn.inputStream)
            } else {
                conn.inputStream
            }
            val body = stream.bufferedReader().readText()
            val snapped = JSONObject(body).getJSONArray("paths").getJSONObject(0)
                .getJSONObject("snapped_waypoints").getJSONArray("coordinates")
            val last = snapped.getJSONArray(snapped.length() - 1) // [lon, lat]
            return LatLon(last.getDouble(1), last.getDouble(0))
        } finally {
            conn.disconnect()
        }
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

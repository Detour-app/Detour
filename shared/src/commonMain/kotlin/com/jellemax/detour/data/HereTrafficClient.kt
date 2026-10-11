package com.jellemax.detour.data

import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject

/**
 * HERE Traffic API v7, called straight from the device with the rider's own key
 * (#637: bring your own key — the Detour API is not involved and holds no HERE
 * key). Flow and incidents for one bounding box at a time; *which* boxes and
 * *how often* is [com.jellemax.detour.drive.TrafficCorridor]'s, not this file's.
 *
 * **A class, not an object**, unlike the other clients here: the key is the one
 * thing every call needs and the one thing that may never leak, so it is held
 * privately rather than passed through every call site. [forKey] returns null for
 * a blank key, so a rider with no key never has a client at all (#637: "absent,
 * not degraded").
 *
 * **The key never leaves this class except in the request URL.** HERE takes it
 * only as the `apiKey` query parameter, so the URL carries it, and the URL is
 * exactly what transport errors quote: ktor's timeout text is "Request timeout
 * has expired [url=…]". That is why every failure is folded into a bare [Fetch]
 * case here instead of being thrown — no exception, cause or response body
 * crosses this boundary, so nothing a caller logs or shows can carry the key.
 * [toString] is overridden for the same reason.
 *
 * [fetch] is a parameter so commonTest can drive the failure mapping with a
 * sentinel key and no network; real callers never pass it.
 */
class HereTrafficClient internal constructor(
    private val key: String,
    private val baseUrl: String = BASE_URL,
    private val fetch: suspend (String) -> String = { Http.get(it) },
) {

    /** How bad an incident is, in HERE's own four steps, least severe first.
     *  [UNKNOWN] is a value this client does not know yet — HERE says the lists
     *  may grow, and a new value must not drop the incident. */
    enum class Criticality { LOW, MINOR, MAJOR, CRITICAL, UNKNOWN }

    /**
     * One reported incident.
     *
     * [kind] is HERE's `type` verbatim (`accident`, `construction`,
     * `roadClosure`, …) rather than an enum: the map slice picks an icon from it,
     * and an unknown kind still gets the generic one. [at] is the first point of
     * the incident's shape — where along the road it starts — and [shape] the
     * whole stretch it covers. [startMs]/[endMs] are null when HERE omitted or
     * misspelt them, not "now".
     */
    data class Incident(
        val id: String,
        val kind: String,
        val criticality: Criticality,
        val at: LatLon,
        val shape: List<LatLon>,
        val roadClosed: Boolean,
        val description: String,
        val startMs: Long?,
        val endMs: Long?,
    )

    /**
     * Current flow on one road link, in one direction of travel — HERE's shape
     * points run the way the traffic does.
     *
     * One HERE flow item spans several links that are not guaranteed to be
     * consecutive (a split location, a non-traversable link in the middle), so
     * each link becomes its own segment carrying the item's flow, rather than
     * one polyline that would join across the gap.
     *
     * Speeds are m/s, as HERE sends them. [speedMps] is capped at [freeFlowMps]
     * by HERE; [jamFactor] runs 0 (free) to 10 (closed).
     */
    data class FlowSegment(
        val shape: List<LatLon>,
        val speedMps: Double,
        val freeFlowMps: Double,
        val jamFactor: Double,
        val closed: Boolean,
    )

    /**
     * What one request came back with. The three failures are distinct because
     * they back off differently ([com.jellemax.detour.drive.TrafficCorridor]):
     * a refused key stops until the rider changes it, a spent quota waits long,
     * anything else is a blip. None carries a message or a cause — see the class
     * doc for why.
     */
    sealed interface Fetch<out T> {
        data class Ok<T>(val value: T) : Fetch<T>

        /** 401 or 403: the key is wrong, revoked or not entitled to Traffic. */
        data object BadKey : Fetch<Nothing>

        /** 429: the key's allocation is spent, or it is being rate-limited. */
        data object Quota : Fetch<Nothing>

        /** No answer, a 5xx, or a body this client could not read. */
        data object Failed : Fetch<Nothing>
    }

    /** One rectangle to ask about. HERE refuses one wider or taller than a
     *  degree; [com.jellemax.detour.drive.TrafficCorridor]'s tiles are well
     *  inside that. */
    data class Box(val west: Double, val south: Double, val east: Double, val north: Double)

    suspend fun flow(box: Box): Fetch<List<FlowSegment>> = get(url("flow", box), ::parseFlow)

    suspend fun incidents(box: Box): Fetch<List<Incident>> = get(url("incidents", box), ::parseIncidents)

    private suspend fun <T> get(url: String, parse: (String) -> T): Fetch<T> {
        val body = catchingCancellable { fetch(url) }.getOrElse { e ->
            return if (e is HttpStatusException) statusFetch(e.code) else Fetch.Failed
        }
        return catchingCancellable { Fetch.Ok(parse(body)) }.getOrElse { Fetch.Failed }
    }

    internal fun url(feed: String, box: Box): String =
        "$baseUrl/$feed?in=bbox:${box.west},${box.south},${box.east},${box.north}" +
            "&locationReferencing=shape&apiKey=$key"

    /** Never the key, whatever a log line or a debugger prints this as. */
    override fun toString(): String = "HereTrafficClient"

    companion object {
        const val BASE_URL = "https://data.traffic.hereapi.com/v7"

        /** Jam factor HERE reserves for a closed road. */
        private const val CLOSED_JAM_FACTOR = 10.0

        /** The client for [key], or null when there is no key to use. */
        fun forKey(key: String): HereTrafficClient? =
            key.trim().takeIf { it.isNotEmpty() }?.let { HereTrafficClient(it) }

        internal fun statusFetch(code: Int): Fetch<Nothing> = when (code) {
            401, 403 -> Fetch.BadKey
            429 -> Fetch.Quota
            else -> Fetch.Failed
        }

        /** The pure half of [flow], split out so commonTest can feed it recorded
         *  bodies. A result with no shape is skipped: it cannot be drawn or
         *  matched to the route. */
        internal fun parseFlow(body: String): List<FlowSegment> =
            results(body).flatMap { item ->
                val current = item.optObject("currentFlow")
                val speed = current?.optDouble("speed") ?: Double.NaN
                val freeFlow = current?.optDouble("freeFlow") ?: Double.NaN
                if (current == null || speed.isNaN() || freeFlow.isNaN()) return@flatMap emptyList()
                val jam = current.optDouble("jamFactor", 0.0)
                val closed = current.optString("traversability") == "closed" || jam >= CLOSED_JAM_FACTOR
                links(item).map { FlowSegment(it, speed, freeFlow, jam, closed) }
            }

        /** The pure half of [incidents]. An incident with no shape has no place
         *  to put a marker, so it is skipped rather than drawn at 0,0. */
        internal fun parseIncidents(body: String): List<Incident> =
            results(body).mapNotNull { item ->
                val details = item.optObject("incidentDetails")
                val shape = links(item).flatten()
                if (details == null || shape.isEmpty()) return@mapNotNull null
                Incident(
                    id = details.optString("id"),
                    kind = details.optString("type", "other"),
                    criticality = criticality(details.optString("criticality")),
                    at = shape.first(),
                    shape = shape,
                    roadClosed = details.optBoolean("roadClosed"),
                    description = details.optObject("description")?.optString("value")
                        ?: details.optObject("summary")?.optString("value").orEmpty(),
                    startMs = epochMs(details.optString("startTime")),
                    endMs = epochMs(details.optString("endTime")),
                )
            }

        private fun results(body: String): List<JsonObject> =
            (jsonObjectOf(body).optArray("results") ?: JsonArrayEmpty).objects()

        private fun links(item: JsonObject): List<List<LatLon>> {
            val links = item.optObject("location")?.optObject("shape")?.optArray("links") ?: return emptyList()
            return links.objects().mapNotNull { link ->
                val points = (link.optArray("points") ?: JsonArrayEmpty).objects().mapNotNull { p ->
                    val lat = p.optDouble("lat")
                    val lng = p.optDouble("lng")
                    if (lat.isNaN() || lng.isNaN()) null else LatLon(lat, lng)
                }
                points.takeIf { it.isNotEmpty() }
            }
        }

        private fun criticality(raw: String): Criticality = when (raw) {
            "low" -> Criticality.LOW
            "minor" -> Criticality.MINOR
            "major" -> Criticality.MAJOR
            "critical" -> Criticality.CRITICAL
            else -> Criticality.UNKNOWN
        }

        private fun epochMs(raw: String): Long? =
            if (raw.isEmpty()) null else runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull()
    }
}

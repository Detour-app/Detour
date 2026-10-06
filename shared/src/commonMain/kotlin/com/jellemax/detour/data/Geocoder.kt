package com.jellemax.detour.data

import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import okio.IOException

data class GeocodeResult(val name: String, val location: LatLon)

/**
 * Address/place search via Photon, an OSM-backed geocoder built for type-ahead.
 * Unlike Nominatim's importance-only ranking, Photon blends the query match with
 * proximity to [near], so nearby streets and POIs surface first while a famous far
 * city still ranks where it belongs — one call, no bounded vs. unbounded juggling.
 * It also indexes POIs (shops, stations), so "colruyt" finds the nearest store.
 *
 * The endpoint is resolved per request: the user's self-hosted Photon (Settings) if
 * set, else the one baked into the app, else the public komoot instance as a
 * fallback.
 */
object Geocoder {

    private const val PUBLIC = "https://photon.komoot.io"

    /** Effective Photon base URL: the one server address (Settings) → baked → public. */
    private fun baseUrl(): String =
        RoutingServer.geocoderBase(RoutingServer.loadCustom()).ifBlank { PUBLIC }

    // @Throws(Exception::class): called directly from iosApp/Detour — see
    // the doc on [SyncClient.sync] for why `Exception` and not just
    // `IOException`.
    @Throws(Exception::class)
    suspend fun search(query: String, near: LatLon?, limit: Int = 8): List<GeocodeResult> {
        var lastError: IOException? = null
        for (base in endpoints()) {
            try {
                return fetch(base, query, near, limit)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Search failed")
    }

    /**
     * The name of the place at [at] — "Kerkstraat, Gent, België" — via Photon's
     * `/reverse`, for a spin pick or a tapped route stop that has only
     * coordinates. Null when no endpoint answers with a usable name (offline,
     * a self-hosted proxy that does not route `/reverse`, open water): every
     * caller then shows the coordinates or its own placeholder, so a failed
     * lookup is not an error worth surfacing. Only cancellation propagates.
     *
     * Same endpoints, in the same order and under the same public-fallback
     * consent, as [search].
     */
    suspend fun reverse(at: LatLon): String? {
        for (base in endpoints()) {
            try {
                val url = "$base/reverse?lat=${at.lat}&lon=${at.lon}&limit=1"
                val body = Http.get(url, userAgent(), readTimeoutMs = REVERSE_READ_TIMEOUT_MS)
                reverseLabel(body)?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unreachable, an HTTP error, or a non-JSON page from a proxy
                // that does not route /reverse: try the next endpoint.
            }
        }
        return null
    }

    /** First feature's label in a Photon `/reverse` answer; null when it has none. */
    internal fun reverseLabel(json: String): String? = parse(json).firstOrNull()?.name

    // A reverse lookup only decorates a result that is already on screen, so
    // it gets a shorter leash than a search the rider is waiting on.
    private const val REVERSE_READ_TIMEOUT_MS = 5_000L

    /**
     * The custom/baked instance, then the public one if it is down — but only
     * when the user has allowed it (Settings): that fallback sends the query
     * and an approximate location to a third party, which someone who bothered
     * to self-host precisely wants to avoid. When the primary already is
     * public there is nothing to add either way.
     */
    private fun endpoints(): List<String> {
        val primary = baseUrl().trimEnd('/')
        return if (primary == PUBLIC || !Settings.geocoderPublicFallback.value) {
            listOf(primary)
        } else {
            listOf(primary, PUBLIC)
        }
    }

    private fun userAgent() = mapOf("User-Agent" to "Detour/${BuildDefaults.versionName}")

    private suspend fun fetch(
        base: String,
        query: String,
        near: LatLon?,
        limit: Int,
    ): List<GeocodeResult> {
        // lat/lon biases ranking toward the user without hard-restricting the area.
        val bias = near?.let { "&lat=${it.lat}&lon=${it.lon}" } ?: ""
        val url = "$base/api/?q=" + query.encodeURLParameter() + "&limit=$limit" + bias

        val body = try {
            Http.get(url, userAgent(), readTimeoutMs = 10_000)
        } catch (e: HttpStatusException) {
            throw IOException("Search failed: HTTP ${e.code}")
        }
        return dedupe(parse(body))
    }

    private fun parse(json: String): List<GeocodeResult> {
        val features = jsonObjectOf(json).optArray("features") ?: return emptyList()
        val results = ArrayList<GeocodeResult>(features.size)
        for (feature in features.objects()) {
            val coords = feature.optObject("geometry")?.optArray("coordinates") ?: continue
            val props = feature.optObject("properties") ?: continue
            // GeoJSON coordinates are [lon, lat].
            val location = LatLon(coords.optDouble(1), coords.optDouble(0))
            val label = label(props)
            if (label.isBlank()) continue
            results.add(GeocodeResult(label, location))
        }
        return results
    }

    // Photon sometimes returns the same place twice under slightly different OSM
    // tags (a POI and the building it sits in, e.g.) — same name, a few metres
    // apart. Rather than guess at which tag is "the real one", just drop a later
    // result that shares a name with, and sits within, this radius of an earlier
    // one; the earlier (higher-ranked) result wins.
    private const val DEDUPE_RADIUS_METERS = 250.0

    private fun dedupe(results: List<GeocodeResult>): List<GeocodeResult> {
        // Compare the primary part of the label, not the whole thing: the same
        // place comes back as "Kortrijk, België" and "Kortrijk, West-Vlaanderen,
        // België" (city vs municipality tags), and only the part before the
        // first comma is the place's own name.
        fun primary(r: GeocodeResult) = r.name.substringBefore(",").trim()
        val kept = ArrayList<GeocodeResult>(results.size)
        for (result in results) {
            val isDuplicate = kept.any { seen ->
                primary(seen) == primary(result) &&
                    RoadRoulette.distanceMeters(seen.location, result.location) <= DEDUPE_RADIUS_METERS
            }
            if (!isDuplicate) kept.add(result)
        }
        return kept
    }

    /** A concise "primary, locality, country" label from Photon's address fields. */
    private fun label(props: JsonObject): String {
        fun field(key: String) = props.optString(key).takeIf { it.isNotBlank() }

        val name = field("name")
        val street = field("street")
        val house = field("housenumber")
        val primary = name
            ?: street?.let { if (house != null) "$it $house" else it }
            ?: field("city") ?: field("county") ?: field("state") ?: return ""

        val locality = field("city") ?: field("county") ?: field("state")
        // Photon returns country multilingually ("België / Belgique / Belgien"); keep the first.
        val country = field("country")?.substringBefore(" /")?.trim()

        return listOfNotNull(primary, locality, country)
            .filter { it.isNotBlank() }
            .distinct()
            .take(3)
            .joinToString(", ")
    }
}

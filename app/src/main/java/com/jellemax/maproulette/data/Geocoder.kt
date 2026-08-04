package com.jellemax.maproulette.data

import android.content.Context
import com.jellemax.maproulette.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

data class GeocodeResult(val name: String, val location: LatLon)

/**
 * Address/place search via Photon, an OSM-backed geocoder built for type-ahead.
 * Unlike Nominatim's importance-only ranking, Photon blends the query match with
 * proximity to [near], so nearby streets and POIs surface first while a famous far
 * city still ranks where it belongs — one call, no bounded vs. unbounded juggling.
 * It also indexes POIs (shops, stations), so "colruyt" finds the nearest store.
 *
 * The endpoint is resolved per request: the user's self-hosted Photon (Settings) if
 * set, else the one baked into the APK, else the public komoot instance as a
 * fallback. A self-hosted instance sits behind the same Cloudflare Access service
 * token as the routing server, so those credentials are reused here.
 */
object Geocoder {

    private const val PUBLIC = "https://photon.komoot.io"

    /** Effective Photon base URL: the one server address (Settings) → baked → public. */
    private fun baseUrl(context: Context): String {
        RoutingServer.loadCustom(context)?.url?.takeIf { it.isNotBlank() }?.let { return it }
        BuildConfig.GEOCODER_URL.takeIf { it.isNotBlank() }?.let { return it }
        return PUBLIC
    }

    fun search(context: Context, query: String, near: LatLon?, limit: Int = 8): List<GeocodeResult> {
        val primary = baseUrl(context).trimEnd('/')
        // If a custom/baked instance is down, fail over to the public one so search
        // keeps working — but only when the user has allowed it (Settings): that
        // fallback sends the query and an approximate location to a third party,
        // which someone who bothered to self-host precisely wants to avoid. When
        // the primary already is public there is nothing to add either way.
        val endpoints = if (primary == PUBLIC || !Settings.geocoderPublicFallback.value) {
            listOf(primary)
        } else {
            listOf(primary, PUBLIC)
        }
        // A self-hosted Photon is protected by the routing server's CF Access token.
        val access = RoutingServer.load(context)

        var lastError: IOException? = null
        for (base in endpoints) {
            try {
                return fetch(base, query, near, limit, access.takeIf { base != PUBLIC })
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Search failed")
    }

    private fun fetch(
        base: String,
        query: String,
        near: LatLon?,
        limit: Int,
        access: ServerConfig?,
    ): List<GeocodeResult> {
        // lat/lon biases ranking toward the user without hard-restricting the area.
        val bias = near?.let { "&lat=${it.lat}&lon=${it.lon}" } ?: ""
        val url = "$base/api/?q=" +
            URLEncoder.encode(query, "UTF-8") + "&limit=$limit" + bias

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("User-Agent", "MapRoulette/1.11 (personal Android app)")
            if (access != null && access.clientId.isNotBlank()) {
                conn.setRequestProperty("CF-Access-Client-Id", access.clientId)
                conn.setRequestProperty("CF-Access-Client-Secret", access.clientSecret)
            }
            if (conn.responseCode != 200) {
                throw IOException("Search failed: HTTP ${conn.responseCode}")
            }
            val stream = if (conn.contentEncoding == "gzip") {
                GZIPInputStream(conn.inputStream)
            } else {
                conn.inputStream
            }
            return dedupe(parse(stream.bufferedReader().readText()))
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String): List<GeocodeResult> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        val results = ArrayList<GeocodeResult>(features.length())
        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i)
            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val props = feature.optJSONObject("properties") ?: continue
            // GeoJSON coordinates are [lon, lat].
            val location = LatLon(coords.getDouble(1), coords.getDouble(0))
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
    private fun label(props: JSONObject): String {
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

package com.jellemax.detour.data

import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import okio.IOException

/**
 * One search hit.
 *
 * @property name the "primary, locality, country" label every surface titles the row with, and
 *   the name a picked place is stored and spoken under.
 * @property secondary the line under it that tells same-named hits apart — "Place de la Station 1,
 *   5000 Namur" — with nothing [name] already says; empty when Photon gave nothing more (#1006).
 * @property kind what the place is, for the row's icon and label.
 */
data class GeocodeResult(
    val name: String,
    val location: LatLon,
    val secondary: String = "",
    val kind: PlaceKind = PlaceKind.PLACE,
) {

    /**
     * Whether [other] is this same place: the same primary name (the part before the first comma)
     * within [SAME_PLACE_RADIUS_METERS]. A name alone is not enough — two branches of one chain in
     * one city share a label, "Colruyt, Gent, België" — so every merge of results (Photon's own
     * duplicates, recents against live hits, the recents store) asks this, never `name ==` (#1004).
     */
    fun isSamePlaceAs(other: GeocodeResult): Boolean =
        primaryName() == other.primaryName() &&
            RoadRoulette.distanceMeters(location, other.location) <= SAME_PLACE_RADIUS_METERS

    /** Metres from [from] to this place, for the row's distance; null without a point to measure from. */
    fun distanceFrom(from: LatLon?): Double? = from?.let { RoadRoulette.distanceMeters(it, location) }

    // The same place comes back as "Kortrijk, België" and "Kortrijk, West-Vlaanderen, België"
    // (city vs municipality tags); only the part before the first comma is the place's own name.
    private fun primaryName() = name.substringBefore(",").trim()

    companion object {
        // Photon sometimes returns one place twice under slightly different OSM tags (a POI and
        // the building it sits in, e.g.) a few metres apart; further than this is another place.
        const val SAME_PLACE_RADIUS_METERS = 250.0
    }
}

/**
 * What a search hit is, from Photon's `osm_key` / `osm_value` / `type` — coarse on purpose: a row
 * has room for one icon and one word, and four rows of "Namur, België" (the city, the province,
 * the arrondissement, the station) need exactly that to be told apart (#1006).
 */
enum class PlaceKind {
    ADDRESS, STREET, TOWN, REGION, FUEL, PARKING, FOOD, SHOP, STATION, PLACE;

    companion object {
        private val BY_AMENITY = mapOf(
            "fuel" to FUEL, "parking" to PARKING, "bus_station" to STATION,
            "restaurant" to FOOD, "cafe" to FOOD, "fast_food" to FOOD, "bar" to FOOD, "pub" to FOOD,
            "biergarten" to FOOD,
        )
        private val BY_KEY = mapOf(
            "shop" to SHOP, "railway" to STATION, "public_transport" to STATION,
            "highway" to STREET, "boundary" to REGION,
        )
        private val TOWN_VALUES =
            setOf("city", "town", "village", "hamlet", "suburb", "quarter", "neighbourhood", "locality")
        private val REGION_TYPES = setOf("county", "state", "country")

        // Photon's address points are place=house or building=*, both with type "house".
        private val ADDRESS_KEYS = setOf("place", "building")

        /** The kind for one Photon feature's properties; [PLACE] when nothing more specific fits. */
        fun of(osmKey: String, osmValue: String, type: String): PlaceKind =
            (if (osmKey == "amenity") BY_AMENITY[osmValue] else BY_KEY[osmKey]) ?: when {
                type == "street" -> STREET
                type == "house" && osmKey in ADDRESS_KEYS -> ADDRESS
                osmKey == "place" && osmValue in TOWN_VALUES -> TOWN
                type in REGION_TYPES -> REGION
                else -> PLACE
            }
    }
}

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

    /** How many hits a search lists. */
    const val RESULT_LIMIT = 8

    /** Effective Photon base URL: the one server address (Settings) → baked → public. */
    private fun baseUrl(): String =
        RoutingServer.geocoderBase(RoutingServer.loadCustom()).ifBlank { PUBLIC }

    // @Throws(Exception::class): called directly from iosApp/Detour — see
    // the doc on [SyncClient.sync] for why `Exception` and not just
    // `IOException`.
    @Throws(Exception::class)
    suspend fun search(query: String, near: LatLon?, limit: Int = RESULT_LIMIT): List<GeocodeResult> =
        search(query, near, limit, language = null)

    /**
     * [search] with names asked for in [language] (an ISO 639-1 code, the device's) when Photon
     * has it, so a country comes back as "Belgium" rather than "België / Belgique / Belgien".
     * Its own overload rather than a defaulted parameter: Swift sees no Kotlin defaults, and
     * iOS calls the three-argument form.
     */
    @Throws(Exception::class)
    suspend fun search(query: String, near: LatLon?, limit: Int, language: String?): List<GeocodeResult> {
        var lastError: IOException? = null
        val lang = photonLanguage(language)
        for (base in endpoints()) {
            try {
                // Ask for more than we keep: dedupe folds Photon's near-duplicates, and the
                // rows left over would otherwise come up short (#1006).
                return fetch(base, query, near, maxOf(limit, FETCH_LIMIT), lang).take(limit)
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
     * and an approximate location (or, for [reverse], the exact point being
     * named) to a third party, which someone who bothered
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

    // Photon's default build indexes these name languages; any other `lang` is an HTTP 400.
    // A self-hosted instance may index fewer — [fetch] retries without it then.
    private val PHOTON_LANGUAGES = setOf("en", "de", "fr", "it")

    /** How many hits to ask Photon for before [dedupe]; [search]'s `limit` is how many to keep. */
    private const val FETCH_LIMIT = 12

    internal fun photonLanguage(language: String?): String? =
        language?.lowercase()?.substringBefore('-')?.substringBefore('_')?.takeIf { it in PHOTON_LANGUAGES }

    private fun userAgent() = mapOf("User-Agent" to "Detour/${BuildDefaults.versionName}")

    private suspend fun fetch(
        base: String,
        query: String,
        near: LatLon?,
        limit: Int,
        lang: String?,
    ): List<GeocodeResult> {
        // lat/lon biases ranking toward the user without hard-restricting the area.
        val bias = near?.let { "&lat=${it.lat}&lon=${it.lon}" } ?: ""
        val url = "$base/api/?q=" + query.encodeURLParameter() + "&limit=$limit" + bias

        val body = try {
            Http.get(url + (lang?.let { "&lang=$it" } ?: ""), userAgent(), readTimeoutMs = 10_000)
        } catch (e: HttpStatusException) {
            // A self-hosted Photon imported without this language answers 400 "Language is not
            // supported": ask again in its default names rather than fail the search.
            if (lang == null || e.code != HTTP_BAD_REQUEST) throw IOException("Search failed: HTTP ${e.code}")
            return fetch(base, query, near, limit, lang = null)
        }
        return dedupe(parse(body))
    }

    private const val HTTP_BAD_REQUEST = 400

    internal fun parse(json: String): List<GeocodeResult> {
        val features = jsonObjectOf(json).optArray("features") ?: return emptyList()
        val results = ArrayList<GeocodeResult>(features.size)
        for (feature in features.objects()) {
            val coords = feature.optObject("geometry")?.optArray("coordinates") ?: continue
            val props = feature.optObject("properties") ?: continue
            // GeoJSON coordinates are [lon, lat].
            val location = LatLon(coords.optDouble(1), coords.optDouble(0))
            val label = label(props)
            if (label.isBlank()) continue
            val kind = PlaceKind.of(
                props.optString("osm_key"), props.optString("osm_value"), props.optString("type"),
            )
            results.add(GeocodeResult(label, location, secondary(props, label), kind))
        }
        return results
    }

    // Rather than guess at which OSM tag is "the real one", drop a later result that is the same
    // place as an earlier one; the earlier (higher-ranked) result wins.
    private fun dedupe(results: List<GeocodeResult>): List<GeocodeResult> {
        val kept = ArrayList<GeocodeResult>(results.size)
        for (result in results) if (kept.none { it.isSamePlaceAs(result) }) kept.add(result)
        return kept
    }

    /**
     * The row's second line: street and house number, then district and postcode + city — only
     * the parts [label] does not already say, so a city reads "" and a station "Place de la
     * Station 1, 5000 Namur". Three "Kerkstraat, Gent" streets differ here by postcode.
     */
    private fun secondary(props: JsonObject, label: String): String {
        fun field(key: String) = props.optString(key).takeIf { it.isNotBlank() }

        val labelParts = label.split(",").map { it.trim() }
        val name = field("name")
        val street = field("street")?.let { s -> field("housenumber")?.let { "$s $it" } ?: s }
            ?.takeIf { name != null }
        val city = field("city")
        val district = field("district")?.takeIf { it != city }
        val town = listOfNotNull(field("postcode"), city).joinToString(" ").ifBlank { null }
        return listOfNotNull(street, district, town)
            .filter { it !in labelParts }
            .distinct()
            .joinToString(", ")
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

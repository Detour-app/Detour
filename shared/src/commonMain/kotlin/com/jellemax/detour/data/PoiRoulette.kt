package com.jellemax.detour.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import okio.IOException
import kotlin.random.Random

/** Destination flavors for a spin. ROAD is the classic random road point. */
enum class PoiKind(val label: String, val selectors: List<String>) {
    ROAD("Road", emptyList()),
    VIEWPOINT("Viewpoint", listOf("""nwr["tourism"="viewpoint"]""")),
    FOOD(
        "Food & drink",
        listOf("""nwr["amenity"~"^(cafe|restaurant|pub|bar|ice_cream)$"]"""),
    ),
    SIGHT(
        "Sight",
        listOf(
            """nwr["historic"~"^(castle|ruins|monument|fort|memorial)$"]""",
            """nwr["tourism"="attraction"]""",
        ),
    ),
}

data class Poi(val location: LatLon, val name: String)

/** Picks a random point of interest within the radius. */
object PoiRoulette {

    suspend fun randomPoi(
        center: LatLon,
        radiusMeters: Double,
        kind: PoiKind,
        bearingDeg: Double?,
        explored: ExploredArea? = null,
        minRadiusMeters: Double = 0.0,
    ): Poi {
        val candidates = fetchPois(center, radiusMeters, kind)
        val allPois = if (bearingDeg == null) candidates
            else candidates.filter { RoadRoulette.withinWedge(center, it.location, bearingDeg, 50.0) }
        if (allPois.isEmpty()) {
            throw IOException("No ${kind.label.lowercase()} found here — try a larger radius")
        }
        val pois = if (minRadiusMeters <= 0.0) allPois
            else allPois.filter { RoadRoulette.distanceMeters(center, it.location) >= minRadiusMeters }
        if (pois.isEmpty()) {
            throw IOException(
                "No ${kind.label.lowercase()} found past the minimum distance — " +
                    "try a larger radius or a smaller minimum")
        }
        // Prefer POIs in undiscovered territory; visited ones keep a small chance.
        val fresh = if (explored == null) pois
            else pois.filter { !explored.isExplored(it.location) }
        return if (fresh.isNotEmpty() && Random.nextDouble() >= ExploredArea.EXPLORED_WEIGHT) {
            fresh[Random.nextInt(fresh.size)]
        } else {
            pois[Random.nextInt(pois.size)]
        }
    }

    /**
     * POIs of [kind] within [radiusMeters] of [center] — spin's random-POI feature (issue #383,
     * phase 5 of #302).
     *
     * Same fallback chain as [RoadRoulette.fetchRoads] (issue #380/#382): this deployment's own
     * `/api/pois` first, when announced, and only Overpass when that fails outright. No disk
     * cache in front of the backend call, for the same reason `fetchRoads`' own doc gives: a
     * spin tap is user-initiated, not a background prefetch loop, so there is nothing to survive
     * a restart for (issue #382's reasoning, carried over unchanged for #383). An *empty* backend
     * answer is returned as-is (not a failure to fall through on) — the bbox genuinely having no
     * matching POI is exactly what Overpass would also have said, and [randomPoi] already treats
     * an empty result as "try a larger radius", not a data-source problem.
     */
    private suspend fun fetchPois(center: LatLon, radiusMeters: Double, kind: PoiKind): List<Poi> {
        val backendBase = RoutingServer.poisBase(RoutingServer.loadCustom())
        if (backendBase.isNotBlank()) {
            val fromBackend = try {
                fetchPoisViaBackend(backendBase, center, radiusMeters, kind)
            } catch (e: IOException) {
                null
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            if (fromBackend != null) return fromBackend
        }
        return fetchPoisViaOverpass(center, radiusMeters, kind)
    }

    /** The bbox fetch against this deployment's own point-of-interest endpoint (issue #383) —
     *  the wire shape is `PoiDto`/`PoisBboxResponse`
     *  (`backend/Detour/Detour.Api/Contracts/PoiContracts.cs`). [parsePoisResponse] is the pure
     *  half, split out for the same reason [RoadRoulette.parseRoadsResponse]'s is. */
    private suspend fun fetchPoisViaBackend(
        base: String,
        center: LatLon,
        radiusMeters: Double,
        kind: PoiKind,
    ): List<Poi> {
        val bbox = RoadRoulette.bboxDegrees(center, radiusMeters)
        val url = "$base/api/pois?minLat=${bbox.minLat}&minLon=${bbox.minLon}" +
            "&maxLat=${bbox.maxLat}&maxLon=${bbox.maxLon}&kind=${kind.name.lowercase()}"
        val body = jsonObjectOf(RoadRoulette.rawGet(url, headers = RoutingServer.userAgentHeaders()))
        return parsePoisResponse(body, kind)
    }

    /** [fetchPoisViaBackend]'s pure half. internal, not private, so commonTest can feed it
     *  canned response bodies. A POI with a blank `name` (no OSM `name` tag) falls back to
     *  [kind]'s own label — the same rule [fetchPoisViaOverpass] already applies to an Overpass
     *  element with no `name` tag. */
    internal fun parsePoisResponse(body: JsonObject, kind: PoiKind): List<Poi> {
        val pois = ArrayList<Poi>()
        for (el in (body.optArray("pois") ?: JsonArrayEmpty).objects()) {
            val name = el.optString("name").takeUnless { it.isBlank() } ?: kind.label
            pois.add(Poi(LatLon(el.optDouble("lat"), el.optDouble("lon")), name))
        }
        return pois
    }

    /**
     * Overpass-only. [fetchPois] is the entry point every caller should use — it tries the
     * backend's own `/api/pois` first (when the deployment announces one, issue #383) and only
     * reaches here once that call fails outright.
     */
    suspend fun fetchPoisViaOverpass(center: LatLon, radiusMeters: Double, kind: PoiKind): List<Poi> {
        val around = "(around:${radiusMeters.toInt()},${center.lat},${center.lon})"
        val query = """
            [out:json][timeout:${RoadRoulette.QUERY_BUDGET_MS / 1000}];
            (${kind.selectors.joinToString("") { "$it$around;" }});
            out center 300;
        """.trimIndent()

        val key = cacheKey("poi:${kind.name}", center, radiusMeters)
        val elements = jsonObjectOf(
            OverpassCache.fetch(key) { RoadRoulette.rawQuery(query, timeoutMs = RoadRoulette.QUERY_BUDGET_MS) },
        ).optArray("elements") ?: JsonArrayEmpty
        val pois = ArrayList<Poi>(elements.size)
        for (el in elements.objects()) {
            val lat: Double
            val lon: Double
            if (el.has("lat")) {
                lat = el.optDouble("lat")
                lon = el.optDouble("lon")
            } else {
                val c = el.optObject("center") ?: continue
                lat = c.optDouble("lat")
                lon = c.optDouble("lon")
            }
            val name = el.optObject("tags")?.optString("name").takeUnless { it.isNullOrBlank() }
                ?: kind.label
            pois.add(Poi(LatLon(lat, lon), name))
        }
        return pois
    }
}

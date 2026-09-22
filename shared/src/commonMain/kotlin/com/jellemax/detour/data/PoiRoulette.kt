package com.jellemax.detour.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import okio.IOException
import kotlin.random.Random

/** Destination flavors for a spin. ROAD is the classic random road point. */
enum class PoiKind(val label: String) {
    ROAD("Road"),
    VIEWPOINT("Viewpoint"),
    FOOD("Food & drink"),
    SIGHT("Sight"),
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
     * Backed by this deployment's own `/api/pois` endpoint (issue #383). No disk cache in front
     * of the backend call — a spin tap is user-initiated, not a background prefetch loop, so
     * there is nothing to survive a restart for. An empty answer is returned as-is: the bbox
     * genuinely having no matching POI is a real result, and [randomPoi] already treats an
     * empty result as "try a larger radius", not a data-source problem. An install with no
     * announced endpoint gets an empty list.
     */
    private suspend fun fetchPois(center: LatLon, radiusMeters: Double, kind: PoiKind): List<Poi> {
        val backendBase = RoutingServer.poisBase(RoutingServer.loadCustom())
        if (backendBase.isBlank()) return emptyList()
        return try {
            fetchPoisViaBackend(backendBase, center, radiusMeters, kind)
        } catch (e: IOException) {
            emptyList()
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
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
     *  [kind]'s own label. */
    internal fun parsePoisResponse(body: JsonObject, kind: PoiKind): List<Poi> {
        val pois = ArrayList<Poi>()
        for (el in (body.optArray("pois") ?: JsonArrayEmpty).objects()) {
            val name = el.optString("name").takeUnless { it.isBlank() } ?: kind.label
            pois.add(Poi(LatLon(el.optDouble("lat"), el.optDouble("lon")), name))
        }
        return pois
    }
}

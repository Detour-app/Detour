package com.jellemax.detour.data

import java.io.IOException
import org.json.JSONObject
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

/** Picks a random point of interest within the radius via Overpass. */
object PoiRoulette {

    fun randomPoi(
        center: LatLon,
        radiusMeters: Double,
        kind: PoiKind,
        bearingDeg: Double?,
        explored: ExploredArea? = null,
        minRadiusMeters: Double = 0.0,
    ): Poi {
        val around = "(around:${radiusMeters.toInt()},${center.lat},${center.lon})"
        val query = """
            [out:json][timeout:15];
            (${kind.selectors.joinToString("") { "$it$around;" }});
            out center 300;
        """.trimIndent()

        val elements = JSONObject(RoadRoulette.rawQuery(query)).getJSONArray("elements")
        val allPois = ArrayList<Poi>(elements.length())
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val lat: Double
            val lon: Double
            if (el.has("lat")) {
                lat = el.getDouble("lat")
                lon = el.getDouble("lon")
            } else {
                val c = el.optJSONObject("center") ?: continue
                lat = c.getDouble("lat")
                lon = c.getDouble("lon")
            }
            val location = LatLon(lat, lon)
            if (bearingDeg != null &&
                !RoadRoulette.withinWedge(center, location, bearingDeg, 50.0)
            ) continue
            val name = el.optJSONObject("tags")?.optString("name").takeUnless { it.isNullOrBlank() }
                ?: kind.label
            allPois.add(Poi(location, name))
        }
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
}

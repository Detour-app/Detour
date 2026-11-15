package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Pins [RouteGpx] to the fixtures web/src/gpx/routeGpx.ts is tested against
 * (testdata/web-parity/README.md): `gpx/export/` for [RouteGpx.buildGpx] and
 * [RouteGpx.fileName], `gpx/import/` for [RouteGpx.parseRouteFile]. A route
 * exported from the planner then imports on the phone with the same stops, and
 * the other way round.
 */
class WebParityGpxTest {

    @Test
    fun everyExportFixtureWritesTheGpxTheWebPlannerWrites() {
        for (case in webParityCases("gpx/export")) {
            val input = jsonObjectOf(case.input)
            val route = SavedRoute(
                id = 0,
                name = input.optString("name"),
                createdMs = 0,
                mode = TravelMode.CAR,
                stops = input.optArray("stops")!!.objects().map {
                    RouteStop(LatLon(it.optDouble("lat"), it.optDouble("lon")), it.optString("name"))
                },
                polyline = input.optArray("polyline")!!.map {
                    val pair = it.jsonArray
                    LatLon(pair[0].jsonPrimitive.double, pair[1].jsonPrimitive.double)
                },
                distanceMeters = null,
                timeMs = null,
            )
            val written = buildJsonObject {
                put("fileName", RouteGpx.fileName(route))
                put("gpx", RouteGpx.buildGpx(route))
            }
            assertEquals(canonical(case.expected), canonical(written), "fixture ${case.name}")
        }
    }

    @Test
    fun everyImportFixtureReadsAsTheWebPlannerReadsIt() {
        for (case in webParityCases("gpx/import")) {
            val input = jsonObjectOf(case.input)
            val parsed = RouteGpx.parseRouteFile(
                text = input.optString("file"),
                nowMs = input.getValue("nowMs").jsonPrimitive.long,
            )
            assertEquals(canonical(case.expected), canonical(routeParityView(parsed)), "fixture ${case.name}")
        }
    }
}

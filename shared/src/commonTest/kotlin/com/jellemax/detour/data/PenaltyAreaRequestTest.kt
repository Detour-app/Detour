package com.jellemax.detour.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [routeViaRequest] with a [PenaltyArea] (#918): null leaves today's GET and
 * POST byte for byte; set, it always posts, with the area as a closed-ring,
 * `[lon, lat]` MultiPolygon and its `in_` rule after the preference rules.
 */
class PenaltyAreaRequestTest {

    private val base = "https://gh.example"
    private val points = listOf(LatLon(50.88, 4.70), LatLon(50.80, 4.90))
    private val moto = TravelMode.MOTO.ghProfile

    private val plainGet = "$base/route?profile=moto&point=50.88,4.7&point=50.8,4.9" +
        "&points_encoded=false&details=max_speed&details=roundabout"

    // An open ring (no repeated first point) and an already closed one.
    private val openRing = listOf(LatLon(50.0, 4.0), LatLon(50.0, 4.1), LatLon(50.1, 4.1))
    private val closedRing = listOf(LatLon(51.0, 5.0), LatLon(51.0, 5.1), LatLon(51.1, 5.1), LatLon(51.0, 5.0))
    private val area = PenaltyArea("outbound", listOf(openRing, closedRing), multiplyBy = 0.1)

    private fun body(request: RouteRequest): JsonObject =
        Json.parseToJsonElement(assertNotNull(request.body)).jsonObject

    @Test
    fun noAreaKeepsThePlainGet() {
        val fast = RoutePreferences(curvy = CurvyPreset.FAST)
        assertEquals(RouteRequest(plainGet, null), routeViaRequest(base, points, moto, fast, heading = null, avoid = null))
        assertEquals(
            routeViaRequest(base, points, moto, fast, heading = null),
            routeViaRequest(base, points, moto, fast, heading = null, avoid = null),
        )
    }

    @Test
    fun noAreaKeepsThePost() {
        val balanced = RoutePreferences(curvy = CurvyPreset.BALANCED)
        val expected = RouteRequest(
            "$base/route",
            """{"profile":"moto","points":[[4.7,50.88],[4.9,50.8]],"points_encoded":false,""" +
                """"details":["max_speed","roundabout"],"ch.disable":true,""" +
                """"custom_model":{"priority":[""" +
                """{"if":"road_class == MOTORWAY || road_class == TRUNK","multiply_by":0.5},""" +
                """{"if":"curvature >= 0.98","multiply_by":0.7}]}}""",
        )
        assertEquals(expected, routeViaRequest(base, points, moto, balanced, heading = null, avoid = null))
    }

    @Test
    fun anAreaPostsEvenWithoutRules() {
        val request = routeViaRequest(base, points, moto, RoutePreferences(curvy = CurvyPreset.FAST), null, area)
        assertEquals("$base/route", request.url)
        val json = body(request)
        assertEquals(true, json["ch.disable"]?.jsonPrimitive?.boolean)
        val priority = json["custom_model"]!!.jsonObject["priority"]!!.jsonArray
        assertEquals(1, priority.size)
        assertEquals("in_outbound", priority[0].jsonObject["if"]!!.jsonPrimitive.content)
        assertEquals(0.1, priority[0].jsonObject["multiply_by"]!!.jsonPrimitive.double)
    }

    @Test
    fun theAreaRuleComesAfterThePreferenceRules() {
        val prefs = RoutePreferences(avoidTolls = true, curvy = CurvyPreset.BALANCED)
        val priority = body(routeViaRequest(base, points, moto, prefs, null, area))["custom_model"]!!
            .jsonObject["priority"]!!.jsonArray
        assertEquals(prefs.rulesFor(moto).size + 1, priority.size)
        assertEquals(prefs.rulesFor(moto), JsonArray(priority.dropLast(1)))
        assertEquals("in_outbound", priority.last().jsonObject["if"]!!.jsonPrimitive.content)
    }

    @Test
    fun theAreaIsOneMultiPolygonWithClosedLonLatRings() {
        val areas = body(routeViaRequest(base, points, moto, RoutePreferences(), null, area))["custom_model"]!!
            .jsonObject["areas"]!!.jsonObject
        assertEquals("FeatureCollection", areas["type"]!!.jsonPrimitive.content)
        val features = areas["features"]!!.jsonArray
        assertEquals(1, features.size)
        val feature = features[0].jsonObject
        assertEquals("outbound", feature["id"]!!.jsonPrimitive.content)
        val geometry = feature["geometry"]!!.jsonObject
        assertEquals("MultiPolygon", geometry["type"]!!.jsonPrimitive.content)
        val polygons = geometry["coordinates"]!!.jsonArray
        assertEquals(2, polygons.size)
        val rings = polygons.map { poly ->
            poly.jsonArray.single().jsonArray.map { c ->
                val pair = c.jsonArray
                LatLon(pair[1].jsonPrimitive.double, pair[0].jsonPrimitive.double) // [lon, lat]
            }
        }
        assertEquals(openRing + openRing.first(), rings[0])
        assertEquals(closedRing, rings[1])
    }

    @Test
    fun aHeadingStillRidesAlong() {
        val json = body(routeViaRequest(base, points, moto, RoutePreferences(), HeadingHint(90.0, 120), area))
        assertEquals(90, json["headings"]!!.jsonArray.single().jsonPrimitive.content.toInt())
        assertNull(json["algorithm"])
    }
}

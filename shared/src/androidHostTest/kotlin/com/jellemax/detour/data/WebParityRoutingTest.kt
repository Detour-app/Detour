package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Pins [routeViaRequest], with [RoutePreferences.supportedBy] in front of it as the phone's
 * Settings.navRoutePreferences has, to the fixtures web/src/routing/request.ts is tested
 * against (testdata/web-parity/README.md). A change to the request here — a rule, a
 * multiplier, GET vs POST — fails this until the fixture follows, and then vitest until the
 * planner's copy does.
 */
class WebParityRoutingTest {

    @Test
    fun everyFixtureBuildsTheRequestTheWebPlannerBuilds() {
        for (case in webParityCases("routing")) {
            val input = jsonObjectOf(case.input)
            val request = routeViaRequest(
                base = "",
                points = input.optArray("points")!!.objects()
                    .map { LatLon(it.optDouble("lat"), it.optDouble("lon")) },
                profile = TravelMode.valueOf(input.optString("mode")).ghProfile,
                preferences = preferences(input.optObject("preferences")!!)
                    .supportedBy(input.optArray("encodedValues")?.map { it.jsonPrimitive.content }?.toSet()),
                heading = null,
            )
            assertEquals(canonical(case.expected), canonical(requestView(request)), "fixture ${case.name}")
        }
    }

    /** A fixture's `preferences`: only the options it names, the rest at their defaults. */
    private fun preferences(o: JsonObject): RoutePreferences {
        val d = RoutePreferences()
        return RoutePreferences(
            avoidHighways = o.optBoolean("avoidHighways", d.avoidHighways),
            avoidSmallRoads = o.optBoolean("avoidSmallRoads", d.avoidSmallRoads),
            avoidTolls = o.optBoolean("avoidTolls", d.avoidTolls),
            avoidFerries = o.optBoolean("avoidFerries", d.avoidFerries),
            avoidUnpaved = o.optBoolean("avoidUnpaved", d.avoidUnpaved),
            curvy = decodeCurvyPreset(o.optString("curvy", d.curvy.name)),
        )
    }

    /** web/src/routing/request.ts `requestView`: the path, the query as ordered pairs with
     *  `point` split into numbers, the body parsed. */
    private fun requestView(request: RouteRequest): JsonElement {
        val path = request.url.substringBefore('?')
        val query = request.url.substringAfter('?', missingDelimiterValue = "")
        return buildJsonObject {
            put("method", if (request.body == null) "GET" else "POST")
            put("path", path)
            put(
                "query",
                buildJsonArray {
                    if (query.isNotEmpty()) for (pair in query.split('&')) addJsonArray {
                        val key = pair.substringBefore('=')
                        val value = pair.substringAfter('=', missingDelimiterValue = "")
                        add(key)
                        if (key == "point") {
                            add(JsonArray(value.split(',').map { JsonPrimitive(it.toDouble()) }))
                        } else {
                            add(value)
                        }
                    }
                },
            )
            put("body", request.body?.let { json.parseToJsonElement(it) } ?: JsonNull)
        }
    }
}

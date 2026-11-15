package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Pins [routeFromJson] to the fixtures web/src/routes.ts is tested against
 * (testdata/web-parity/README.md), so the web planner's copy of the rule and
 * this one cannot drift apart unnoticed.
 *
 * Host-test only because it reads the fixtures off the real filesystem with
 * java.io ([webParityCases]); the rule under test is common.
 */
class WebParityRoutesTest {

    @Test
    fun everyFixtureParsesAsTheWebPlannerParsesIt() {
        for (case in webParityCases("routes")) {
            assertEquals(
                canonical(case.expected),
                canonical(parityView(routeFromJson(jsonObjectOf(case.input)))),
                "fixture ${case.name}",
            )
        }
    }

    /** web/src/routes.ts `parityView`: everything the parse decides, in a
     *  shape both languages can build. */
    private fun parityView(route: SavedRoute?): JsonElement {
        if (route == null) return JsonNull
        return buildJsonObject {
            put("id", route.id)
            put("name", route.name)
            put("createdMs", route.createdMs)
            put("mode", route.mode.name)
            putJsonArray("stops") {
                for (s in route.stops) addJsonObject {
                    put("lat", s.at.lat)
                    put("lon", s.at.lon)
                    put("name", s.name)
                }
            }
            putJsonArray("polyline") {
                for (p in route.polyline) addJsonArray {
                    add(JsonPrimitive(p.lat))
                    add(JsonPrimitive(p.lon))
                }
            }
            put("distanceMeters", route.distanceMeters)
            put("timeMs", route.timeMs)
            put("sharedBy", route.sharedBy)
            put("origin", route.origin.name)
            put("editedAtMs", route.editedAtMs)
        }
    }
}

package com.jellemax.detour.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Pins [routeFromJson] to the fixtures web/src/routes.ts is tested against
 * (testdata/web-parity/README.md), so the web planner's copy of the rule and
 * this one cannot drift apart unnoticed.
 *
 * Host-test only because it reads the fixtures off the real filesystem with
 * java.io; the rule under test is common. The directory comes in as a system
 * property from shared/build.gradle.kts, which also declares it a task input,
 * so a fixture-only change re-runs this.
 */
class WebParityRoutesTest {

    private val dir = File(
        System.getProperty(FIXTURES_PROPERTY)
            ?: error("$FIXTURES_PROPERTY is not set; run through Gradle"),
        "routes",
    )

    @Test
    fun everyFixtureParsesAsTheWebPlannerParsesIt() {
        val cases = dir.listFiles { f -> f.name.endsWith(INPUT) }.orEmpty()
            .map { it.name.removeSuffix(INPUT) }
            .sorted()
        assertTrue(cases.isNotEmpty(), "no fixtures in $dir")

        for (name in cases) {
            val input = jsonObjectOf(File(dir, "$name$INPUT").readText())
            val expected = json.parseToJsonElement(File(dir, "$name$EXPECTED").readText())
            assertEquals(
                canonical(expected),
                canonical(parityView(routeFromJson(input))),
                "fixture $name",
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

    /** Compares numbers by value, so `1` in a fixture equals the `1.0` a
     *  Double prints, and object keys without order. */
    private fun canonical(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> e.mapValues { canonical(it.value) }
        is JsonArray -> e.map { canonical(it) }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            else -> e.content.toDouble()
        }
    }

    private companion object {
        const val FIXTURES_PROPERTY = "detour.webParityDir"
        const val INPUT = ".input.json"
        const val EXPECTED = ".expected.json"
    }
}

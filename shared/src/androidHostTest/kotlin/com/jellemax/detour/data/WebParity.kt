package com.jellemax.detour.data

import java.io.File
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
 * The fixture pairs in one folder of testdata/web-parity/ (its README.md), what the web
 * planner's copies of shared rules are tested against: each case's name, input and expected
 * output. The directory comes in as a system property from shared/build.gradle.kts, which also
 * declares it a task input, so a fixture-only change re-runs the tests reading it.
 */
internal fun webParityCases(folder: String): List<WebParityCase> {
    val root = System.getProperty(WEB_PARITY_PROPERTY)
        ?: error("$WEB_PARITY_PROPERTY is not set; run through Gradle")
    val dir = File(root, folder)
    val cases = dir.listFiles { f -> f.name.endsWith(INPUT) }.orEmpty()
        .map { it.name.removeSuffix(INPUT) }
        .sorted()
        .map { name ->
            WebParityCase(
                name = name,
                input = File(dir, "$name$INPUT").readText(),
                expected = json.parseToJsonElement(File(dir, "$name$EXPECTED").readText()),
            )
        }
    assertTrue(cases.isNotEmpty(), "no fixtures in $dir")
    return cases
}

internal class WebParityCase(val name: String, val input: String, val expected: JsonElement)

/** Compares numbers by value, so `1` in a fixture equals the `1.0` a Double prints, and object
 *  keys without order. */
internal fun canonical(e: JsonElement): Any? = when (e) {
    is JsonNull -> null
    is JsonObject -> e.mapValues { canonical(it.value) }
    is JsonArray -> e.map { canonical(it) }
    is JsonPrimitive -> when {
        e.isString -> e.content
        e.booleanOrNull != null -> e.booleanOrNull
        else -> e.content.toDouble()
    }
}

/** web/src/routes.ts `parityView`: everything a route parse decides, in a shape both
 *  languages can build. The `routes/` and `gpx/import/` fixtures expect this. */
internal fun routeParityView(route: SavedRoute?): JsonElement {
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

private const val WEB_PARITY_PROPERTY = "detour.webParityDir"
private const val INPUT = ".input.json"
private const val EXPECTED = ".expected.json"

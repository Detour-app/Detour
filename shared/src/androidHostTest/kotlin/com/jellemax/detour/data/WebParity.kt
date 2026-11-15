package com.jellemax.detour.data

import java.io.File
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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

private const val WEB_PARITY_PROPERTY = "detour.webParityDir"
private const val INPUT = ".input.json"
private const val EXPECTED = ".expected.json"

package com.jellemax.detour.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * The lenient, forgiving half of org.json, rebuilt on kotlinx.serialization.
 *
 * The wire formats here are other people's: GraphHopper, Photon, the sync
 * server. They carry optional fields, fields that are null on some responses
 * and absent on others, and arrays of mixed types (`[from, to, null]` speed
 * limits). Modelling those as @Serializable classes means a class per shape
 * plus a default for every optional field, and one unexpected key takes the
 * whole parse down. The old code read them positionally with opt* accessors,
 * which is the right shape for this data — so the accessors are what gets
 * ported, not the parsing style.
 *
 * Every accessor below returns the default rather than throwing when the key
 * is missing *or* the wrong type, which is what org.json's opt* did.
 */

internal val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    // The sync server omits fields it has no value for rather than sending
    // null, and encoding defaults back would grow every upload for nothing.
    explicitNulls = false
    encodeDefaults = false
}

/** Parses [text] as an object. Throws when it is not valid JSON. */
internal fun jsonObjectOf(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

/** Parses [text] as an array. Throws when it is not valid JSON. */
internal fun jsonArrayOf(text: String): JsonArray = json.parseToJsonElement(text).jsonArray

// --- object accessors -------------------------------------------------------

internal fun JsonObject.optString(key: String, def: String = ""): String =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: def

internal fun JsonObject.optDouble(key: String, def: Double = Double.NaN): Double =
    (this[key] as? JsonPrimitive)?.doubleOrNull ?: def

internal fun JsonObject.optInt(key: String, def: Int = 0): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: def

internal fun JsonObject.optLong(key: String, def: Long = 0L): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: def

internal fun JsonObject.optBoolean(key: String, def: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: def

internal fun JsonObject.optObject(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.optArray(key: String): JsonArray? = this[key] as? JsonArray

/** Present *and* non-null. `null` in JSON reads as absent everywhere here. */
internal fun JsonObject.has(key: String): Boolean =
    this[key] != null && this[key] !is JsonNull

// --- array accessors --------------------------------------------------------

internal fun JsonArray.optString(i: Int, def: String = ""): String =
    (getOrNull(i) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: def

internal fun JsonArray.optDouble(i: Int, def: Double = Double.NaN): Double =
    (getOrNull(i) as? JsonPrimitive)?.doubleOrNull ?: def

internal fun JsonArray.optInt(i: Int, def: Int = 0): Int =
    (getOrNull(i) as? JsonPrimitive)?.intOrNull ?: def

internal fun JsonArray.optLong(i: Int, def: Long = 0L): Long =
    (getOrNull(i) as? JsonPrimitive)?.longOrNull ?: def

internal fun JsonArray.optObject(i: Int): JsonObject? = getOrNull(i) as? JsonObject

internal fun JsonArray.optArray(i: Int): JsonArray? = getOrNull(i) as? JsonArray

/** JSON null, or off the end of the array. */
internal fun JsonArray.isNull(i: Int): Boolean {
    val e = getOrNull(i) ?: return true
    return e is JsonNull
}

/** Objects in an array, skipping anything that is not one. */
internal fun JsonArray.objects(): List<JsonObject> = filterIsInstance<JsonObject>()

/** Arrays in an array, skipping anything that is not one. */
internal fun JsonArray.arrays(): List<JsonArray> = filterIsInstance<JsonArray>()

// --- building ---------------------------------------------------------------

/** Stand-in for a missing array, so callers can keep reading positionally
 *  instead of branching on null at every access. */
internal val JsonArrayEmpty = JsonArray(emptyList())

/** Renders back to wire format. */
internal fun JsonElement.string(): String = json.encodeToString(JsonElement.serializer(), this)

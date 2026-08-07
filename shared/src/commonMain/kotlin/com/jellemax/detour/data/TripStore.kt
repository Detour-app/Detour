package com.jellemax.detour.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class Trip(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val distanceMeters: Double,
    val topSpeedMps: Double,
    val maxLeanAngleDeg: Double = 0.0,
    val maxGForce: Double = 0.0,
    val destinationLat: Double?,
    val destinationLon: Double?,
    /** Which vehicle this was. Trips saved before modes existed read as CAR. */
    val mode: TravelMode = TravelMode.CAR,
) {
    val durationMs: Long get() = endTimeMs - startTimeMs
    val avgSpeedMps: Double
        get() = if (durationMs > 0) distanceMeters / (durationMs / 1000.0) else 0.0
}

/** Persists finished trips as a JSON array in app-private storage. */
object TripStore {

    private const val FILE_NAME = "trips.json"
    private const val DELETED_FILE_NAME = "deleted_trips.json"
    private const val EDITED_FILE_NAME = "edited_modes.json"

    fun save(trip: Trip) {
        writeAll(listOf(trip) + load())
    }

    /**
     * Correct a misclassified trip's vehicle (keyed by unique start time). The
     * override is also recorded locally and re-applied in [replaceRaw], so the
     * /sync merge — which returns the server's union and replaces the local
     * store — can't revert the edit before the server has accepted it. Once the
     * server echoes the same mode back, the override clears itself.
     */
    fun updateMode(startTimeMs: Long, mode: TravelMode) {
        val trips = load()
        if (trips.none { it.startTimeMs == startTimeMs }) return
        val overrides = modeOverrides()
        overrides[startTimeMs] = mode.name
        writeModeOverrides(overrides)
        writeAll(trips.map {
            if (it.startTimeMs == startTimeMs) it.copy(mode = mode) else it
        })
    }

    /**
     * Delete a trip (e.g. a false-positive auto-detection). The start time is
     * also tombstoned, so the server's copy — the /sync merge returns the union
     * and replaces the local store — can't quietly bring it back on the next
     * sync. Tombstones are honoured in [replaceRaw].
     */
    fun delete(startTimeMs: Long) {
        val tombstones = tombstones()
        tombstones.add(startTimeMs)
        writeTombstones(tombstones)
        writeAll(load().filterNot { it.startTimeMs == startTimeMs })
    }

    private fun writeAll(trips: List<Trip>) {
        val array = buildJsonArray {
            for (t in trips) add(encode(t))
        }
        appFile(FILE_NAME).writeText(array.string())
    }

    private fun encode(t: Trip): JsonObject = buildJsonObject {
        put("startTimeMs", t.startTimeMs)
        put("endTimeMs", t.endTimeMs)
        put("distanceMeters", t.distanceMeters)
        put("topSpeedMps", t.topSpeedMps)
        put("maxLeanAngleDeg", t.maxLeanAngleDeg)
        put("maxGForce", t.maxGForce)
        put("destinationLat", t.destinationLat?.let { JsonPrimitive(it) } ?: JsonNull)
        put("destinationLon", t.destinationLon?.let { JsonPrimitive(it) } ?: JsonNull)
        put("mode", t.mode.name)
    }

    fun load(): List<Trip> {
        val f = appFile(FILE_NAME)
        if (!f.exists()) return emptyList()
        return try {
            jsonArrayOf(f.readText()).objects().map { o ->
                Trip(
                    startTimeMs = o.optLong("startTimeMs"),
                    endTimeMs = o.optLong("endTimeMs"),
                    distanceMeters = o.optDouble("distanceMeters", 0.0),
                    topSpeedMps = o.optDouble("topSpeedMps", 0.0),
                    maxLeanAngleDeg = o.optDouble("maxLeanAngleDeg", 0.0),
                    maxGForce = o.optDouble("maxGForce", 0.0),
                    destinationLat = if (!o.has("destinationLat")) null
                        else o.optDouble("destinationLat").takeIf { !it.isNaN() },
                    destinationLon = if (!o.has("destinationLon")) null
                        else o.optDouble("destinationLon").takeIf { !it.isNaN() },
                    mode = TravelMode.of(o.optString("mode")),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Raw stored JSON array, for server sync. */
    fun rawJson(): String {
        val f = appFile(FILE_NAME)
        return if (f.exists()) f.readText() else "[]"
    }

    /**
     * Overwrite the store with a merged JSON array from the sync server, after
     * dropping deleted trips (tombstones) and re-applying local vehicle-mode
     * edits — otherwise the server's copy would revert an edit or resurrect a
     * deletion on every sync. A mode override clears itself once the server
     * echoes the same value back, so it never masks a genuine later change.
     */
    fun replaceRaw(json: String) {
        val incoming = jsonArrayOf(json) // validate before overwriting
        val tombstones = tombstones()
        val overrides = modeOverrides()
        if (tombstones.isEmpty() && overrides.isEmpty()) {
            appFile(FILE_NAME).writeText(json)
            return
        }
        var overridesChanged = false
        val kept = buildJsonArray {
            for (o in incoming.objects()) {
                val start = o.optLong("startTimeMs")
                if (start in tombstones) continue
                val wanted = overrides[start]
                when {
                    wanted == null -> add(o)
                    o.optString("mode") == wanted -> {
                        overrides.remove(start) // server caught up; stop overriding
                        overridesChanged = true
                        add(o)
                    }
                    // Keep the local correction. JsonObject is immutable, so the
                    // replacement is a copy with the one key swapped rather than
                    // an in-place put.
                    else -> add(JsonObject(o + ("mode" to JsonPrimitive(wanted))))
                }
            }
        }
        if (overridesChanged) writeModeOverrides(overrides)
        appFile(FILE_NAME).writeText(kept.string())
    }

    private fun tombstones(): MutableSet<Long> {
        val f = appFile(DELETED_FILE_NAME)
        if (!f.exists()) return mutableSetOf()
        return try {
            val array = jsonArrayOf(f.readText())
            array.indices.map { array.optLong(it) }.toMutableSet()
        } catch (e: Exception) {
            mutableSetOf()
        }
    }

    private fun writeTombstones(ids: Set<Long>) {
        val array = buildJsonArray { ids.forEach { add(it) } }
        appFile(DELETED_FILE_NAME).writeText(array.string())
    }

    /** Local vehicle-mode corrections, startTimeMs → mode name, pending until
     *  the server echoes them back. */
    private fun modeOverrides(): MutableMap<Long, String> {
        val f = appFile(EDITED_FILE_NAME)
        if (!f.exists()) return mutableMapOf()
        return try {
            jsonObjectOf(f.readText()).entries
                .associateTo(mutableMapOf()) { (k, v) -> k.toLong() to v.toString().trim('"') }
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun writeModeOverrides(map: Map<Long, String>) {
        val o = buildJsonObject {
            map.forEach { (start, mode) -> put(start.toString(), mode) }
        }
        appFile(EDITED_FILE_NAME).writeText(o.string())
    }
}

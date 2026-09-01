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
    val drivingStats: DrivingStats = DrivingStats(),
) {
    val durationMs: Long get() = endTimeMs - startTimeMs
    val avgSpeedMps: Double
        get() = if (durationMs > 0) distanceMeters / (durationMs / 1000.0) else 0.0
}

/** Per-trip driving-behavior stats (maxke24/Detour#61). All thresholds that feed
 *  these are provisional — not yet calibrated against real recorded trips. A trip
 *  saved before this existed decodes with every field at its zero/empty default,
 *  indistinguishable from "recorded and found nothing" — same caveat [Trip.maxGForce]
 *  already carries. */
data class DrivingStats(
    val hardBrakeCount: Int = 0,
    val hardAccelCount: Int = 0,
    val hardCornerCount: Int = 0,
    val secondsOverLimit: Long = 0,
    val pctOverLimit: Double = 0.0,
    val roadTypeMeters: Map<HighwayClass, Double> = emptyMap(),
    val twistinessScore: Double = 0.0,
    val stopCount: Int = 0,
    val idleMs: Long = 0,
)

/** Persists finished trips as a JSON array in app-private storage. */
object TripStore {

    private const val FILE_NAME = "trips.json"
    private const val DELETED_FILE_NAME = "deleted_trips.json"
    private const val EDITED_FILE_NAME = "edited_modes.json"

    fun save(trip: Trip) {
        val t = Perf.start()
        val existing = load()
        writeAll(listOf(trip) + existing)
        // Before recordSaved, so the number is this function's own cost — a full
        // load plus a full re-encode — and not the totals record folded in.
        Perf.end(t, "TripStore.save") { listOf("trips" to existing.size) }
        // After the write, not before: a totals record counting a trip the
        // file does not hold is the one drift the TTL would carry for a day.
        RiderTotals.recordSaved(trip)
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
     * Fold a post-hoc [DrivingStats] update into an already-saved trip (keyed by
     * unique start time) — e.g. the twistiness score, computed after [save] so
     * the trip itself isn't held hostage to an unbounded trace parse. A no-op if
     * the trip isn't found (deleted, or the save that should have preceded this
     * lost a race — either way there's nothing to update).
     *
     * Deliberately NOT a second [save] call: [save] has no dedup
     * (`writeAll(listOf(trip) + load())`), so calling it again here would add a
     * duplicate entry with the same startTimeMs rather than update the existing
     * one.
     */
    fun updateDrivingStats(startTimeMs: Long, drivingStats: DrivingStats) {
        val trips = load()
        if (trips.none { it.startTimeMs == startTimeMs }) return
        writeAll(trips.map { if (it.startTimeMs == startTimeMs) it.copy(drivingStats = drivingStats) else it })
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
        // The removed trip may have held the top speed, the longest ride or
        // the deepest lean, and a maximum cannot be walked backwards from the
        // record alone. A recompute is the only correct answer.
        RiderTotals.invalidate()
    }

    private fun writeAll(trips: List<Trip>) {
        val array = buildJsonArray {
            for (t in trips) add(encode(t))
        }
        accountFile(FILE_NAME).writeText(array.string())
    }

    internal fun encode(t: Trip): JsonObject = buildJsonObject {
        put("startTimeMs", t.startTimeMs)
        put("endTimeMs", t.endTimeMs)
        put("distanceMeters", t.distanceMeters)
        put("topSpeedMps", t.topSpeedMps)
        put("maxLeanAngleDeg", t.maxLeanAngleDeg)
        put("maxGForce", t.maxGForce)
        put("destinationLat", t.destinationLat?.let { JsonPrimitive(it) } ?: JsonNull)
        put("destinationLon", t.destinationLon?.let { JsonPrimitive(it) } ?: JsonNull)
        put("mode", t.mode.name)
        put("drivingStats", encodeDrivingStats(t.drivingStats))
    }

    private fun encodeDrivingStats(d: DrivingStats): JsonObject = buildJsonObject {
        put("hardBrakeCount", d.hardBrakeCount)
        put("hardAccelCount", d.hardAccelCount)
        put("hardCornerCount", d.hardCornerCount)
        put("secondsOverLimit", d.secondsOverLimit)
        put("pctOverLimit", d.pctOverLimit)
        put("roadTypeMeters", buildJsonObject { d.roadTypeMeters.forEach { (k, v) -> put(k.name, v) } })
        put("twistinessScore", d.twistinessScore)
        put("stopCount", d.stopCount)
        put("idleMs", d.idleMs)
    }

    private fun decodeDrivingStats(o: JsonObject?): DrivingStats {
        if (o == null) return DrivingStats()
        return DrivingStats(
            hardBrakeCount = o.optLong("hardBrakeCount").toInt(),
            hardAccelCount = o.optLong("hardAccelCount").toInt(),
            hardCornerCount = o.optLong("hardCornerCount").toInt(),
            secondsOverLimit = o.optLong("secondsOverLimit"),
            pctOverLimit = o.optDouble("pctOverLimit", 0.0),
            roadTypeMeters = o.optObject("roadTypeMeters")?.let { rt ->
                HighwayClass.entries.mapNotNull { cls ->
                    val v = rt.optDouble(cls.name, Double.NaN)
                    if (v.isNaN()) null else cls to v
                }.toMap()
            } ?: emptyMap(),
            twistinessScore = o.optDouble("twistinessScore", 0.0),
            stopCount = o.optLong("stopCount").toInt(),
            idleMs = o.optLong("idleMs"),
        )
    }

    fun load(): List<Trip> {
        val t = Perf.start()
        val f = accountFile(FILE_NAME)
        if (!f.exists()) {
            Perf.end(t, "TripStore.load") { listOf("trips" to 0, "bytes" to 0) }
            return emptyList()
        }
        val text = f.readText()
        val trips = try {
            jsonArrayOf(text).objects().map { decodeTrip(it) }
        } catch (e: Exception) {
            emptyList()
        }
        Perf.end(t, "TripStore.load") {
            listOf("trips" to trips.size, "bytes" to text.length)
        }
        return trips
    }

    internal fun decodeTrip(o: JsonObject): Trip = Trip(
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
        drivingStats = decodeDrivingStats(o.optObject("drivingStats")),
    )

    /** Raw stored JSON array, for server sync. */
    fun rawJson(): String {
        val f = accountFile(FILE_NAME)
        return if (f.exists()) f.readText() else "[]"
    }

    /** Start instants deleted on this device, uploaded with the sync payload so
     *  the deletion reaches every other device instead of only being filtered
     *  out of the merge here. See [delete]. */
    fun deletedStartTimes(): Set<Long> = tombstones()

    /**
     * Overwrite the store with a merged JSON array from the sync server, after
     * dropping deleted trips (tombstones) and re-applying local vehicle-mode
     * edits — otherwise the server's copy would revert an edit or resurrect a
     * deletion on every sync. A mode override clears itself once the server
     * echoes the same value back, so it never masks a genuine later change.
     */
    fun replaceRaw(json: String) {
        val t = Perf.start()
        val incoming = jsonArrayOf(json) // validate before overwriting
        val tombstones = tombstones()
        val overrides = modeOverrides()
        if (tombstones.isEmpty() && overrides.isEmpty()) {
            accountFile(FILE_NAME).writeText(json)
            RiderTotals.invalidate()
            Perf.end(t, "TripStore.replaceRaw") {
                listOf("trips" to incoming.objects().count(), "bytes" to json.length)
            }
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
        val text = kept.string()
        accountFile(FILE_NAME).writeText(text)
        Perf.end(t, "TripStore.replaceRaw") {
            listOf("trips" to kept.size, "bytes" to text.length)
        }
        // The merge is the server's union against ours: trips can arrive and
        // trips can vanish, so no increment is even definable from here. Both
        // exits invalidate.
        RiderTotals.invalidate()
    }

    private fun tombstones(): MutableSet<Long> {
        val f = accountFile(DELETED_FILE_NAME)
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
        accountFile(DELETED_FILE_NAME).writeText(array.string())
    }

    /** Local vehicle-mode corrections, startTimeMs → mode name, pending until
     *  the server echoes them back. */
    private fun modeOverrides(): MutableMap<Long, String> {
        val f = accountFile(EDITED_FILE_NAME)
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
        accountFile(EDITED_FILE_NAME).writeText(o.string())
    }
}

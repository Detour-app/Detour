package com.jellemax.detour.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Where and when a trip's notable moments happened (#444), recorded live from
 * the full-rate fix and sensor streams — the only place they can come from.
 * The stored trace is decimated to 25 m, so the fix that set the top speed or
 * the sample that set the deepest lean is usually not in it, and nothing
 * read back from `traces.jsonl` can put a pin where it happened.
 *
 * A trip saved before this existed, or recorded on iOS, decodes as the empty
 * default: every peak null and both lists empty. Unlike the zero defaults on
 * [DrivingStats], that is distinguishable from "recorded and found nothing"
 * only for the peaks — an empty [events] list reads the same either way, so
 * check it against the counts in [DrivingStats] before calling a trip calm.
 */
data class TripMoments(
    /** [TripPeak.value] in m/s. Same speed that set [Trip.topSpeedMps]. */
    val topSpeed: TripPeak? = null,
    /** [TripPeak.value] in degrees, signed: positive is leaning right. */
    val maxLean: TripPeak? = null,
    /** [TripPeak.value] in g. */
    val maxG: TripPeak? = null,
    /** In the order they happened. Capped — see `MomentRecorder.MAX_EVENTS`;
     *  the counts in [DrivingStats] keep counting past the cap. */
    val events: List<RidingEvent> = emptyList(),
    /** Mid-trip stops, in order. Capped — see `MomentRecorder.MAX_STOPS`. */
    val stops: List<TripStop> = emptyList(),
)

/**
 * One peak and where it was set. [at] is the position of the latest fix when
 * [value] was reached: exact for a fix-driven peak (speed), up to one fix
 * interval behind for a sensor-driven one (lean, g) — tens of metres at speed,
 * which is close enough to pin on a map and not close enough to name a corner.
 *
 * [value] can be lower than the matching maximum on [Trip] when that maximum
 * was reached before the trip's first fix, when there was nowhere to pin it.
 */
data class TripPeak(val at: LatLon, val timeMs: Long, val value: Double)

/** Stored by name in trips.json: never rename a constant. */
enum class RidingEventKind {
    /** [RidingEvent.magnitude]: acceleration in m/s², negative. */
    HARD_BRAKE,

    /** [RidingEvent.magnitude]: acceleration in m/s². */
    HARD_ACCEL,

    /** A lean-detected corner (moto). [RidingEvent.magnitude]: the deepest
     *  lean through the corner in degrees, signed like [TripMoments.maxLean]. */
    HARD_CORNER_LEAN,

    /** A heading-rate corner (car). [RidingEvent.magnitude]: the fastest
     *  heading change through the corner, in degrees per second. */
    HARD_CORNER_TURN,
}

/** One hard brake, acceleration or corner, pinned where it started. */
data class RidingEvent(
    val kind: RidingEventKind,
    val at: LatLon,
    val timeMs: Long,
    val magnitude: Double,
)

/** One mid-trip stop: where the vehicle came to rest and for how long. The
 *  window is the one `StopDetector` counted into [DrivingStats.idleMs]. */
data class TripStop(val at: LatLon, val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

// --- trips.json encoding, called from TripStore -------------------------------
// Positions are flat lat/lon keys, like every other object in trips.json.

internal fun encodeTripMoments(m: TripMoments): JsonObject = buildJsonObject {
    put("topSpeed", encodePeak(m.topSpeed))
    put("maxLean", encodePeak(m.maxLean))
    put("maxG", encodePeak(m.maxG))
    put("events", buildJsonArray {
        for (e in m.events) add(buildJsonObject {
            put("kind", e.kind.name)
            put("lat", e.at.lat)
            put("lon", e.at.lon)
            put("timeMs", e.timeMs)
            put("magnitude", e.magnitude)
        })
    })
    put("stops", buildJsonArray {
        for (s in m.stops) add(buildJsonObject {
            put("lat", s.at.lat)
            put("lon", s.at.lon)
            put("startMs", s.startMs)
            put("endMs", s.endMs)
        })
    })
}

/** Lenient like the rest of trips.json: a missing object is the empty default,
 *  and an entry that doesn't parse — an event kind a newer build wrote, a
 *  position without a number — is dropped rather than failing the trip. */
internal fun decodeTripMoments(o: JsonObject?): TripMoments {
    if (o == null) return TripMoments()
    return TripMoments(
        topSpeed = decodePeak(o.optObject("topSpeed")),
        maxLean = decodePeak(o.optObject("maxLean")),
        maxG = decodePeak(o.optObject("maxG")),
        events = o.optArray("events")?.objects().orEmpty().mapNotNull { e ->
            val kind = RidingEventKind.entries.firstOrNull { it.name == e.optString("kind") }
            val at = e.position()
            if (kind == null || at == null) null
            else RidingEvent(kind, at, e.optLong("timeMs"), e.optDouble("magnitude", 0.0))
        },
        stops = o.optArray("stops")?.objects().orEmpty().mapNotNull { s ->
            s.position()?.let { TripStop(it, s.optLong("startMs"), s.optLong("endMs")) }
        },
    )
}

private fun encodePeak(p: TripPeak?): JsonElement = if (p == null) JsonNull else buildJsonObject {
    put("lat", p.at.lat)
    put("lon", p.at.lon)
    put("timeMs", p.timeMs)
    put("value", p.value)
}

private fun decodePeak(o: JsonObject?): TripPeak? {
    val at = o?.position() ?: return null
    val value = o.optDouble("value")
    return if (value.isNaN()) null else TripPeak(at, o.optLong("timeMs"), value)
}

private fun JsonObject.position(): LatLon? {
    val lat = optDouble("lat")
    val lon = optDouble("lon")
    return if (lat.isNaN() || lon.isNaN()) null else LatLon(lat, lon)
}

package com.jellemax.detour.data

/** One step of a [walkRoute]: where the walker is and which way it faces. */
data class WalkStep(val at: LatLon, val bearingDeg: Double)

/**
 * Points every [stepMeters] along [polyline], from its first point to (and
 * including) its last — the car's test drive feeds one per second through the
 * nav screen's fix handler. Zero-length segments are skipped; a polyline of
 * fewer than two points walks nowhere.
 */
fun walkRoute(polyline: List<LatLon>, stepMeters: Double): Sequence<WalkStep> = sequence {
    require(stepMeters > 0.0) { "stepMeters must be positive" }
    var into = 0.0 // metres past the start of the current segment
    var last: WalkStep? = null
    for ((a, b) in polyline.zipWithNext()) {
        val seg = RoadRoulette.distanceMeters(a, b)
        if (seg <= 0.0) continue
        val bearing = RoadRoulette.bearingDeg(a, b)
        while (into < seg) {
            val f = into / seg
            yield(WalkStep(LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f), bearing))
            into += stepMeters
        }
        into -= seg
        last = WalkStep(b, bearing)
    }
    last?.let { yield(it) }
}

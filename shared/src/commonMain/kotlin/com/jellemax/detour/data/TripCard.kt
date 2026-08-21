package com.jellemax.detour.data

import kotlin.math.cos
import kotlin.math.sqrt

/** One point in card space: 0..1 on both axes, top-left origin. */
data class CardPoint(val x: Float, val y: Float)

/**
 * Everything a trip card renderer needs, already geometry-normalized and
 * privacy-trimmed. [points] and [destination] are empty/null when the trip
 * has no matched trace, or when a point/the destination fell inside the
 * trimmed span — the renderer draws a stats-only layout in that case, not
 * an error (see the design spec's "trips with no trace" note).
 */
data class CardData(
    val trip: Trip,
    val points: List<CardPoint>,
    val destination: CardPoint?,
) {
    /** Non-null only for a mode that actually records lean — a car's number
     *  would be the phone sliding in its cradle, not the vehicle. */
    val peakLeanDeg: Double? get() = if (trip.mode.tracksLean) trip.maxLeanAngleDeg else null
    val peakGForce: Double? get() = if (trip.mode.tracksGForce) trip.maxGForce else null
}

/**
 * Builds a [CardData] from a trip's reassembled trace. This is geometry only
 * — callers pass in whatever `loadTripTrace`/`matchTripPoints` already
 * produced; this function does no I/O and knows nothing about TraceStore.
 */
object TripCardGeometry {

    /** Distance trimmed from each end of the drawn polyline by default — a
     *  route card is a picture of where you live, and the driveway is the
     *  first and last thing on it. */
    const val TRIM_METERS: Double = 500.0

    fun build(trip: Trip, points: List<LatLon>, full: Boolean = false): CardData {
        if (points.isEmpty()) return CardData(trip, emptyList(), null)

        val cumulative = cumulativeDistances(points)
        val total = cumulative.last()
        val trimStart = if (full) 0.0 else TRIM_METERS
        val trimEnd = if (full) total else (total - TRIM_METERS)

        // A trip shorter than 2x the trim distance has no untrimmed middle
        // left — draw nothing rather than a negative-length span.
        if (!full && trimEnd <= trimStart) return CardData(trip, emptyList(), null)

        val kept = points.indices.filter { cumulative[it] in trimStart..trimEnd }
        if (kept.isEmpty()) return CardData(trip, emptyList(), null)

        val keptPoints = kept.map { points[it] }
        val minLat = keptPoints.minOf { it.lat }
        val maxLat = keptPoints.maxOf { it.lat }
        val minLon = keptPoints.minOf { it.lon }
        val maxLon = keptPoints.maxOf { it.lon }

        // Longitude degrees shrink with latitude; without this a route
        // running mostly east-west would look stretched. Cheap enough for a
        // card-sized box that a full projection isn't worth it.
        val latSpan = (maxLat - minLat).coerceAtLeast(1e-9)
        val lonSpan = ((maxLon - minLon) * cos(toRadians((minLat + maxLat) / 2))).coerceAtLeast(1e-9)
        val span = maxOf(latSpan, lonSpan)

        fun normalize(p: LatLon): CardPoint {
            val nx = ((p.lon - minLon) * cos(toRadians((minLat + maxLat) / 2))) / span
            val ny = 1f - ((p.lat - minLat) / span).toFloat() // screen y grows downward
            return CardPoint(nx.toFloat().coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
        }

        val normalizedPoints = keptPoints.map(::normalize)
        val destLat = trip.destinationLat
        val destLon = trip.destinationLon
        val destination = if (destLat != null && destLon != null) {
            val destCumulative = nearestCumulativeDistance(points, cumulative, destLat, destLon)
            if (destCumulative in trimStart..trimEnd) normalize(LatLon(destLat, destLon)) else null
        } else null

        return CardData(trip, normalizedPoints, destination)
    }

    /** Running distance (meters) at each point, index-aligned with [points]. */
    private fun cumulativeDistances(points: List<LatLon>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            out[i] = out[i - 1] + haversineMeters(points[i - 1], points[i])
        }
        return out
    }

    /** The trimmed span uses distance *along the trace*, so the destination
     *  (which isn't necessarily on the trace) is placed at the cumulative
     *  distance of the trace point nearest to it — the trim check then
     *  reuses the exact same [trimStart]..[trimEnd] window as the polyline. */
    private fun nearestCumulativeDistance(
        points: List<LatLon>, cumulative: DoubleArray, lat: Double, lon: Double,
    ): Double {
        var bestIdx = 0
        var bestDist = Double.MAX_VALUE
        for (i in points.indices) {
            val d = haversineMeters(points[i], LatLon(lat, lon))
            if (d < bestDist) { bestDist = d; bestIdx = i }
        }
        return cumulative[bestIdx]
    }

    private fun haversineMeters(a: LatLon, b: LatLon): Double {
        val r = 6_371_000.0
        val dLat = toRadians(b.lat - a.lat)
        val dLon = toRadians(b.lon - a.lon)
        val la1 = toRadians(a.lat)
        val la2 = toRadians(b.lat)
        val h = sinSq(dLat / 2) + cos(la1) * cos(la2) * sinSq(dLon / 2)
        return 2 * r * kotlin.math.asin(sqrt(h))
    }

    private fun sinSq(x: Double): Double = kotlin.math.sin(x).let { it * it }
}

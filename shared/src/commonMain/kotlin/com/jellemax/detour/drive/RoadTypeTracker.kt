package com.jellemax.detour.drive

import com.jellemax.detour.data.HighwayClass
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.jsonObjectOf
import com.jellemax.detour.data.objects
import com.jellemax.detour.data.optArray
import com.jellemax.detour.data.optDouble
import com.jellemax.detour.data.optObject
import com.jellemax.detour.data.optString
import kotlinx.serialization.SerializationException
import okio.IOException

/**
 * Road-type-mix accumulation for maxke24/Detour#61 — a sibling of
 * `SpeedLimitTracker`, not a reuse of its fetch: that one's Overpass query
 * filters on `["maxspeed"]`, which would undercount every untagged
 * residential street. This queries `["highway"]` alone.
 *
 * Same `State`/`needsWays`/`fetchStarted`/`withWays`/`onFix` shape as
 * `SpeedLimitTracker`, clock-free — including the failure-backoff counter,
 * since a persistently refused Overpass mirror is exactly as expensive to
 * retry every [FETCH_THROTTLE_MS] here as it is there.
 */
object RoadTypeTracker {
    const val FETCH_RADIUS_M = 1500.0
    const val FETCH_MARGIN_M = 500.0
    const val FETCH_THROTTLE_MS = 10_000L

    data class ClassifiedWay(val highwayClass: HighwayClass, val points: List<LatLon>)

    data class State(
        val ways: List<ClassifiedWay> = emptyList(),
        val waysCenter: LatLon? = null,
        val lastFetchMs: Long = 0L,
        val meters: Map<HighwayClass, Double> = emptyMap(),
        /** Consecutive failed fetches. Any answer at all resets it, including an
         *  empty one — an area with no tagged road is a success, not a blip.
         *  Same field/semantics as [SpeedLimitTracker.State.failures]. */
        val failures: Int = 0,
    )

    /** Ceiling on the backed-off gap, shared with [SpeedLimitTracker] — a
     *  persistently refused Overpass mirror must not turn this fetch into a
     *  10-second retry timer for the rest of the trip, the exact failure mode
     *  [SpeedLimitTracker]'s own KDoc names as already paid for once. */
    private val MAX_BACKOFF_MS = SpeedLimitTracker.MAX_BACKOFF_MS

    fun needsWays(state: State, at: LatLon, nowMs: Long): Boolean {
        val fromCenter = state.waysCenter?.let { RoadRoulette.distanceMeters(it, at) } ?: Double.MAX_VALUE
        return fromCenter > FETCH_RADIUS_M - FETCH_MARGIN_M &&
            nowMs - state.lastFetchMs > backoffDelayMs(FETCH_THROTTLE_MS, MAX_BACKOFF_MS, state.failures)
    }

    fun fetchStarted(state: State, nowMs: Long): State = state.copy(lastFetchMs = nowMs)

    /** Null on any failure, empty only when the area really has no drivable way — same
     *  null-vs-empty contract `RoadRoulette.speedLimitWays` documents, and for the same
     *  reason: collapsing the two into one `emptyList()` would make [withWays] treat a
     *  failed fetch as "confirmed no roads here", which moves [State.waysCenter] and stops
     *  ever retrying near this position. */
    suspend fun fetchWays(center: LatLon, radiusMeters: Double = FETCH_RADIUS_M): List<ClassifiedWay>? {
        val query = "[out:json][timeout:15];" +
            "way(around:${radiusMeters.toInt()},${center.lat},${center.lon})" +
            "[\"highway\"~\"^(${RoadRoulette.DRIVABLE_HIGHWAYS})$\"];" +
            "out tags geom;"
        val json = try {
            RoadRoulette.rawQuery(query)
        } catch (e: IOException) {
            return null
        }
        // A busy Overpass mirror answers 200 with an HTML "runtime error" page, so parsing
        // fails on a perfectly good HTTP response — the same two catches
        // RoadRoulette.speedLimitWays needs for the same reason (RoadRoulette.kt:285-291).
        val elements = try {
            jsonObjectOf(json).optArray("elements")
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        } ?: return null
        val ways = ArrayList<ClassifiedWay>(elements.size)
        for (el in elements.objects()) {
            val tag = el.optObject("tags")?.optString("highway")?.takeIf { it.isNotBlank() } ?: continue
            val cls = HighwayClass.of(tag) ?: continue
            val geometry = el.optArray("geometry") ?: continue
            val pts = geometry.objects().map { LatLon(it.optDouble("lat"), it.optDouble("lon")) }
            if (pts.size >= 2) ways.add(ClassifiedWay(cls, pts))
        }
        return ways
    }

    /** A **null** [ways] is a failed fetch: keep everything as-is but count the failure,
     *  which backs [needsWays] off — same shape as `SpeedLimitTracker.withWays`, and for
     *  the same reason: a flat [FETCH_THROTTLE_MS] retry against a persistently refused
     *  Overpass mirror is up to ~720 attempts on a 2h drive against a shared public
     *  budget. An **empty** [ways] is the area genuinely having no drivable way: [State.ways]
     *  is a no-op, but [State.waysCenter] still moves to [center] and [State.failures] still
     *  resets — any real answer, even an empty one, means the mirror is working, and
     *  otherwise [needsWays] would stay true forever over a real untagged stretch. */
    fun withWays(state: State, ways: List<ClassifiedWay>?, center: LatLon): State = when {
        ways == null -> state.copy(failures = state.failures + 1)
        ways.isEmpty() -> state.copy(waysCenter = center, failures = 0)
        else -> state.copy(ways = ways, waysCenter = center, failures = 0)
    }

    /** Snaps [at] to the nearest/aligned classified way (same two-pass logic as
     *  `RoadRoulette.snapSpeedLimitKmh`) and attributes [distanceSinceLastFixMeters]
     *  to its class. A fix that matches nothing leaves [State.meters] unchanged. */
    fun onFix(
        state: State,
        at: LatLon,
        headingDeg: Double?,
        distanceSinceLastFixMeters: Double,
    ): State {
        var aligned: HighwayClass? = null
        var alignedDist = Double.MAX_VALUE
        var nearest: HighwayClass? = null
        var nearestDist = Double.MAX_VALUE
        for (way in state.ways) {
            for (j in 0 until way.points.size - 1) {
                val a = way.points[j]
                val b = way.points[j + 1]
                val d = RoadRoulette.distanceToSegmentMeters(at, a, b)
                if (d > RoadRoulette.MAX_SNAP_METERS) continue
                if (d < nearestDist) { nearestDist = d; nearest = way.highwayClass }
                if (headingDeg != null && d < alignedDist && RoadRoulette.alignsWith(a, b, headingDeg)) {
                    alignedDist = d; aligned = way.highwayClass
                }
            }
        }
        val cls = aligned ?: nearest ?: return state
        val updated = state.meters.toMutableMap()
        updated[cls] = (updated[cls] ?: 0.0) + distanceSinceLastFixMeters
        return state.copy(meters = updated)
    }
}

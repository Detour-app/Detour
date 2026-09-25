package com.jellemax.detour.drive

import com.jellemax.detour.data.HighwayClass
import com.jellemax.detour.data.JsonArrayEmpty
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.arrays
import com.jellemax.detour.data.jsonObjectOf
import com.jellemax.detour.data.objects
import com.jellemax.detour.data.optArray
import com.jellemax.detour.data.optDouble
import com.jellemax.detour.data.optString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import okio.IOException

/**
 * Road-type-mix accumulation for maxke24/Detour#61 — a sibling of
 * `SpeedLimitTracker`, not a reuse of its fetch: that one's backend query
 * filters on `["maxspeed"]`, which would undercount every untagged
 * residential street. This queries `["highway"]` alone.
 *
 * Same `State`/`needsWays`/`fetchStarted`/`withWays`/`onFix` shape as
 * `SpeedLimitTracker`, clock-free — including the failure-backoff counter,
 * since a persistently unreachable backend is exactly as expensive to
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
     *  persistently unreachable backend must not turn this fetch into a
     *  10-second retry timer for the rest of the trip, the exact failure mode
     *  [SpeedLimitTracker]'s own KDoc names as already paid for once. */
    private val MAX_BACKOFF_MS = SpeedLimitTracker.MAX_BACKOFF_MS

    fun needsWays(state: State, at: LatLon, nowMs: Long): Boolean {
        val fromCenter = state.waysCenter?.let { RoadRoulette.distanceMeters(it, at) } ?: Double.MAX_VALUE
        return fromCenter > FETCH_RADIUS_M - FETCH_MARGIN_M &&
            nowMs - state.lastFetchMs > backoffDelayMs(FETCH_THROTTLE_MS, MAX_BACKOFF_MS, state.failures)
    }

    fun fetchStarted(state: State, nowMs: Long): State = state.copy(lastFetchMs = nowMs)

    /**
     * Null on any failure, empty only when the area really has no drivable way — collapsing the
     * two into one `emptyList()` would make [withWays] treat a failed fetch as "confirmed no
     * roads here", which moves [State.waysCenter] and stops ever retrying near this position.
     *
     * Backed by this deployment's own `/api/roads` endpoint (issue #380); [RoadTypeStore]'s disk
     * cache from an earlier successful fetch covers a backend call that fails outright. An
     * *empty* backend answer is trusted and returned as-is (also cached) rather than falling
     * through: the bbox genuinely having no drivable way is a real answer, and [withWays]
     * already reads an empty [ClassifiedWay] list as "confirmed no roads here", not as a miss.
     */
    suspend fun fetchWays(center: LatLon, radiusMeters: Double = FETCH_RADIUS_M): List<ClassifiedWay>? {
        val backendBase = RoutingServer.roadsBase(RoutingServer.loadCustom())
        if (backendBase.isBlank()) return RoadTypeStore.load(center, radiusMeters)

        val fromBackend = try {
            fetchWaysViaBackend(backendBase, center, radiusMeters)
        } catch (e: IOException) {
            null
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        if (fromBackend != null) {
            RoadTypeStore.save(center, radiusMeters, fromBackend)
            return fromBackend
        }
        return RoadTypeStore.load(center, radiusMeters)
    }

    /** The bbox fetch against this deployment's own drivable-road endpoint (issue #380) — the
     *  wire shape is `RoadWayDto`/`RoadsBboxResponse`
     *  (`backend/Detour/Detour.Api/Contracts/RoadContracts.cs`). [parseRoadsResponse] is the
     *  pure half, split out for the same reason `RoadRoulette.speedLimitWaysViaBackend`'s is. */
    private suspend fun fetchWaysViaBackend(base: String, center: LatLon, radiusMeters: Double): List<ClassifiedWay> {
        val bbox = RoadRoulette.bboxDegrees(center, radiusMeters)
        val url = "$base/api/roads?minLat=${bbox.minLat}&minLon=${bbox.minLon}" +
            "&maxLat=${bbox.maxLat}&maxLon=${bbox.maxLon}"
        val body = jsonObjectOf(RoadRoulette.rawGet(url, headers = RoutingServer.userAgentHeaders()))
        return parseRoadsResponse(body)
    }

    /** [fetchWaysViaBackend]'s pure half. internal, not private, so commonTest can feed it
     *  canned response bodies. Every way the backend returns is kept — unlike
     *  `RoadRoulette.parseRoadsResponse` (spin's own parse of the same endpoint), this consumer
     *  wants every drivable class, not a mode-specific subset — and bucketed through
     *  [HighwayClass.of]. A tag that bucket doesn't recognise is dropped. */
    internal fun parseRoadsResponse(body: JsonObject): List<ClassifiedWay> {
        val ways = ArrayList<ClassifiedWay>()
        for (el in (body.optArray("ways") ?: JsonArrayEmpty).objects()) {
            val tag = el.optString("highway").takeIf { it.isNotBlank() } ?: continue
            val cls = HighwayClass.of(tag) ?: continue
            val polyline = el.optArray("polyline") ?: continue
            val pts = polyline.arrays().map { LatLon(it.optDouble(0), it.optDouble(1)) }
            if (pts.size >= 2) ways.add(ClassifiedWay(cls, pts))
        }
        return ways
    }

    /** A **null** [ways] is a failed fetch: keep everything as-is but count the failure,
     *  which backs [needsWays] off — same shape as `SpeedLimitTracker.withWays`, and for
     *  the same reason: a flat [FETCH_THROTTLE_MS] retry against a persistently unreachable
     *  backend is up to ~720 attempts on a 2h drive. An **empty** [ways] is the area genuinely
     *  having no drivable way: [State.ways]
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

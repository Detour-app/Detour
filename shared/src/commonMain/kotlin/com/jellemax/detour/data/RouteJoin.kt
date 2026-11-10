package com.jellemax.detour.data

/**
 * Joins two A→B legs that meet at a via point into one [RouteResult], so a loop
 * built as "out to a point and back" (#889) can be followed, saved and cached like
 * any other route. Pure: no I/O.
 */
object RouteJoin {

    /** GraphHopper's sign for "via reached"; its finish is [SIGN_FINISH]. */
    private const val SIGN_VIA = 5
    private const val SIGN_FINISH = 4

    /** What GraphHopper itself says at the only via of a two-leg route. */
    internal const val VIA_TEXT = "Waypoint 1 reached"

    /**
     * Google Maps takes at most 9 via points ([RouteResult.waypoints]). Weighted to
     * the return: a saved loop is re-routed through its waypoints with no penalty
     * on the way out, so the return needs enough of them to come back the same way.
     */
    private const val OUT_WAYPOINTS = 2
    private const val BACK_WAYPOINTS = 6

    /**
     * [out] followed by [back], which starts where [out] ends: the shared vertex is
     * kept once, so [back]'s instruction and speed-limit indices shift by
     * `out.polyline.size - 1`. [out]'s finish becomes a via at the joint. Distance
     * and time are summed, or null when either leg doesn't know its own.
     */
    fun join(out: RouteResult, back: RouteResult, via: LatLon): RouteResult {
        val offset = (out.polyline.size - 1).coerceAtLeast(0)
        val outInstructions = out.instructions.map { ins ->
            if (ins.sign != SIGN_FINISH) ins
            else ins.copy(
                text = VIA_TEXT,
                sign = SIGN_VIA,
                distanceMeters = 0.0,
                startIndex = offset,
                endIndex = offset,
            )
        }
        val backInstructions = back.instructions.map {
            it.copy(startIndex = it.startIndex + offset, endIndex = it.endIndex + offset)
        }
        val backLimits = back.speedLimits.map {
            it.copy(fromIndex = it.fromIndex + offset, toIndex = it.toIndex + offset)
        }
        val waypoints = RoutingClient.sampleInterior(out.polyline, OUT_WAYPOINTS) +
            via +
            RoutingClient.sampleInterior(back.polyline, BACK_WAYPOINTS)
        return RouteResult(
            polyline = out.polyline + back.polyline.drop(1),
            waypoints = waypoints,
            distanceMeters = out.distanceMeters?.let { o -> back.distanceMeters?.let { o + it } },
            instructions = outInstructions + backInstructions,
            timeMs = out.timeMs?.let { o -> back.timeMs?.let { o + it } },
            speedLimits = out.speedLimits + backLimits,
        )
    }
}

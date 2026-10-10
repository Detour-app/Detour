package com.jellemax.detour.data

/**
 * Keeping a spin result as a [SavedRoute] and riding a saved loop again (#589).
 *
 * A destination spin is stored as its two ends and rides like any two-stop
 * route. A loop is stored as its start, the via points the router sampled
 * along it, and its start again; riding it routes through those stops
 * in-app ([RoutingClient.routeVia]) rather than handing a ten-stop route to an
 * external maps app, which is what a planned route with that many stops gets.
 */
object SavedSpins {

    /** How a rider who is not at a saved loop's start gets onto it. */
    enum class LoopJoin {
        /** Ride to the loop's start first, then the whole loop from there. */
        VIA_START,

        /** Ride to the loop's nearest stored stop, then the whole loop in its
         *  saved direction, ending back at that stop. */
        NEAREST,
    }

    /**
     * A [SavedRoute] for a spin result. [start] is where the spin's route
     * begins; [destination] is null for a loop, whose stops then come from
     * [route]'s sampled via points.
     */
    fun fromSpin(
        id: Long,
        name: String,
        mode: TravelMode,
        start: LatLon,
        destination: LatLon?,
        route: RouteResult?,
    ): SavedRoute {
        val stops = if (destination != null) {
            listOf(RouteStop(start), RouteStop(destination, name))
        } else {
            listOf(RouteStop(start)) + route?.waypoints.orEmpty().map { RouteStop(it) } + RouteStop(start)
        }
        return SavedRoute(
            id = id,
            name = name,
            createdMs = id,
            mode = mode,
            stops = stops,
            polyline = route?.polyline.orEmpty(),
            distanceMeters = route?.distanceMeters,
            timeMs = route?.timeMs,
            origin = RouteOrigin.SPIN,
        )
    }

    /** Whether [route] is a saved loop, ridden in-app through its stops. A
     *  two-stop spin (or a loop saved with no via points) is a plain A-to-B
     *  route and takes the existing destination path. A spin route edited so
     *  it no longer ends where it starts is not a loop either: joining it at
     *  its nearest stop would ride past its end and back to its start. */
    fun isLoop(route: SavedRoute): Boolean =
        route.origin == RouteOrigin.SPIN && route.stops.size > 2 &&
            route.stops.first().at == route.stops.last().at

    /** Whether [at] is close enough to [route]'s start to ride it without
     *  asking how to join it. */
    fun atStart(route: SavedRoute, at: LatLon, radiusMeters: Double): Boolean =
        RoadRoulette.distanceMeters(at, route.stops.first().at) <= radiusMeters

    /**
     * The points to route through for riding a loop with [stops] from [at].
     * The loop is ridden once, in its saved order, whichever way it is joined.
     */
    fun loopRoutingPoints(stops: List<LatLon>, at: LatLon, join: LoopJoin): List<LatLon> = when (join) {
        LoopJoin.VIA_START -> listOf(at) + stops
        LoopJoin.NEAREST -> {
            // The stored loop closes on its start; drop that repeat so the
            // ring has each stop once, then rotate it to the nearest one.
            val ring = if (stops.size > 1 && stops.first() == stops.last()) stops.dropLast(1) else stops
            val k = ring.indices.minBy { RoadRoulette.distanceMeters(at, ring[it]) }
            listOf(at) + ring.drop(k) + ring.take(k + 1)
        }
    }
}

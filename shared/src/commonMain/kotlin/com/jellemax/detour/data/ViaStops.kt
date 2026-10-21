package com.jellemax.detour.data

/**
 * Riding a saved planned route in-app through its middle stops (#736).
 *
 * Per the owner's decisions on the issue: guidance starts from wherever the
 * rider is, not from the route's own first stop; every reroute goes through
 * the stops not yet passed rather than straight to the last one; and a stop
 * counts as passed once the rider's progress along the line goes past the
 * stop's projection onto it — not by coming within some radius of the stop's
 * own coordinate, which a stop off the road is never within. The map holds
 * the list and a [Ride]; the rules are here so another surface rides a
 * planned route the same way.
 *
 * A saved spin loop is not ridden through this: it has its own join choice
 * ([SavedSpins.loopRoutingPoints]) and no destination to arrive at.
 */
object ViaStops {

    /**
     * The stops Go routes through on the way to [route]'s last one: all but
     * the first and the last. The first is where the route was planned from,
     * dropped the same way a two-stop route has always dropped it — the line
     * starts at the rider.
     */
    fun of(route: SavedRoute): List<LatLon> =
        if (route.stops.size <= 2) emptyList()
        else route.stops.subList(1, route.stops.size - 1).map { it.at }

    /** The points for a fresh line from [from] through [vias] to [destination]. */
    fun points(from: LatLon, vias: List<LatLon>, destination: LatLon): List<LatLon> =
        listOf(from) + vias + destination

    /**
     * Where a rider is along [line], forward only: [along] is a windowed
     * [NavEngine.advance] snap that started at the line's first point.
     *
     * Forward only because a global nearest-point snap cannot tell the legs of
     * a route that rides the same road twice apart. A round trip (home → store
     * → home) ends where it starts, and the first fix after Go is as near its
     * last metre as its first: a global snap would put the rider past the
     * store before they have moved. Every line Go and a reroute fetch starts
     * at the rider, so starting at metre zero is where they are.
     */
    data class Ride(val line: List<LatLon>, val along: NavEngine.Along)

    /**
     * [ride] moved on to the rider at [at]. A different [line] (Go, a
     * reroute) starts over from its first point.
     *
     * The windowed snap covers fix to fix. When it can't see the rider — a
     * gap in fixes, or a [Ride] lost with the screen while the line it was on
     * lives on — the rest of the line from where the rider last was is
     * searched instead, still forward only. A snap more than [maxOffMeters]
     * off the line is not taken: a rider off the line has made no progress
     * along it, and the reroute that follows starts a new line anyway.
     */
    fun follow(ride: Ride?, line: List<LatLon>, at: LatLon, maxOffMeters: Double): Ride {
        val from = if (ride != null && ride.line === line) ride.along
            else NavEngine.advance(line, line.firstOrNull() ?: at, null)
        val windowed = NavEngine.advance(line, at, from)
        val next = if (windowed.offRouteMeters <= maxOffMeters && !windowed.beyondWindow) windowed
            else snapOnward(line, from, at)
        return Ride(line, if (next.offRouteMeters <= maxOffMeters) next else from)
    }

    /**
     * What is left of [vias] once the rider is [ride] along its line: a stop
     * is passed once the rider is further along than the stop's projection
     * ([projectedMeters]). Passing one passes every stop before it, so a
     * rider sent past one is not sent back for it; stops not passed yet stay,
     * in order.
     */
    fun ahead(vias: List<LatLon>, ride: Ride): List<LatLon> {
        val passed = projectedMeters(ride.line, vias).count { it < ride.along.meters }
        return vias.drop(passed)
    }

    /**
     * How far along [line] each of [vias] projects, in order: the nearest
     * point on the line to the stop, measured as [NavEngine.advance] measures
     * the rider, so the two compare.
     *
     * Each stop is looked for only from the previous one's projection onward.
     * Where the line rides the same road twice, a later stop on it would
     * otherwise land on the earlier leg and be passed too soon. The result is
     * never decreasing, which is what lets [ahead] count.
     */
    fun projectedMeters(line: List<LatLon>, vias: List<LatLon>): List<Double> {
        var from = NavEngine.advance(line, line.firstOrNull() ?: return vias.map { 0.0 }, null)
        return vias.map { via -> snapOnward(line, from, via).also { from = it }.meters }
    }

    /** The nearest point to [p] on [line] from [from]'s segment to the end, as
     *  an [NavEngine.Along] on the whole line. */
    private fun snapOnward(line: List<LatLon>, from: NavEngine.Along, p: LatLon): NavEngine.Along {
        val snap = NavEngine.advance(line.subList(from.index, line.size), p, null)
        return snap.copy(
            index = from.index + snap.index,
            meters = from.indexMeters + snap.meters,
            lineMeters = from.lineMeters,
            indexMeters = from.indexMeters + snap.indexMeters,
        )
    }
}

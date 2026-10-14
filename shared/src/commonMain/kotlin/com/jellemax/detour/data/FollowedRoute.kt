package com.jellemax.detour.data

import kotlin.concurrent.Volatile

/**
 * The saved spin the rider just sent to the map, held until the Go that rides
 * it (#590), which hands its id to the trip that records the ride as
 * [Trip.spinRouteId].
 *
 * Process-scoped, like the spin result it travels next to: the Routes screen
 * offers it and the map claims it, and neither can ask the other. The map
 * does not carry the id through its own state; instead [claim] checks that
 * what Go is about to ride is still what was offered, so a rider who re-spins
 * or picks somewhere else before tapping Go never gets that trip attributed
 * to the spin.
 */
object FollowedRoute {
    private class Offer(val routeId: Long, val destination: LatLon?, val route: RouteResult?)

    @Volatile
    private var offer: Offer? = null

    /**
     * [saved] was just handed to the map, as [destination] (a two-stop spin,
     * re-routed live at Go) or as the already-routed [route] with no
     * destination (a loop). Only a [RouteOrigin.SPIN] route is remembered; any
     * other route replaces the offer with nothing.
     */
    fun offer(saved: SavedRoute, destination: LatLon?, route: RouteResult?) {
        offer = if (saved.origin == RouteOrigin.SPIN && (destination != null || route != null)) {
            Offer(saved.id, destination, route)
        } else {
            null
        }
    }

    /**
     * The saved spin Go is riding, given the map's [destination] and [route]
     * at that moment; null when the map moved on to something else. A
     * destination spin matches on its destination alone, since Go re-fetches
     * the route from wherever the rider is; a loop matches on the route it was
     * seeded with. Consumed either way: the next Go is a new choice.
     */
    fun claim(destination: LatLon?, route: RouteResult?): Long? {
        val o = offer ?: return null
        offer = null
        val same = if (o.destination != null) destination == o.destination
        else destination == null && route != null && route == o.route
        return o.routeId.takeIf { same }
    }
}

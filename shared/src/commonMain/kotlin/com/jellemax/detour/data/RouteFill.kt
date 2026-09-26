package com.jellemax.detour.data

import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okio.IOException

/**
 * Stretches a planned route to a riding time: the rider's stops stay fixed and
 * in order, and the spare time is filled by bending each leg out through an
 * inserted via point ("home → the café → home, one hour").
 *
 * Geometry first, router second. Each leg of straight length L gets an apex
 * point off to one side, at the offset that lengthens that leg's straight-line
 * path by its share of the spare distance ([apexOffsetMeters]); a leg with no
 * length — a loop's start and end, or a lone stop — gets a triangle instead.
 * The router then reports the real time, and the offsets are rescaled by how
 * far the spare time it produced was from the spare time wanted, for up to
 * [MAX_ROUNDS] rounds. [VARIANTS] layouts (side of the road, apex position)
 * are tried side by side, and the curviest one that fits the time wins, the
 * same rule [LoopDuration.pick] applies to a timed spin.
 *
 * All apexes bend to the same side *of the direction of travel*, so an
 * out-and-back (home → X → home) comes out as a loop rather than riding the
 * same bulge twice.
 *
 * Inserted stops are ordinary [RouteStop]s named [FILL_NAME], so a saved route
 * needs no format change and navigation follows them like any other stop;
 * [mandatory] strips them again before a re-fill.
 */
object RouteFill {

    const val FILL_NAME = "Auto fill"

    /** Below this a leg has no usable direction to bend away from. */
    private const val DEGENERATE_LEG_METERS = 300.0
    private const val MAX_ROUNDS = 3
    private const val VARIANTS = 3
    /** Road distance over straight-line distance, as a first guess only:
     *  the rounds after the first are scaled off what the router reported. */
    private const val ROAD_FACTOR = 1.3
    private const val GUESS_METERS_PER_MS = 50.0 / 3600.0 // 50 km/h
    private const val MIN_SCALE = 0.4
    private const val MAX_SCALE = 2.5

    data class Filled(val stops: List<RouteStop>, val route: RouteResult)

    /** Where one variant bends its legs: [side] +1 right of travel, -1 left;
     *  [along] how far down each leg the apex sits; [looseBearingDeg] which
     *  way a leg with no direction of its own opens its triangle. */
    internal data class Layout(val side: Int, val along: Double, val looseBearingDeg: Double)

    fun isFill(stop: RouteStop): Boolean = stop.name == FILL_NAME

    /** The rider's own stops, with any earlier fill removed. */
    fun mandatory(stops: List<RouteStop>): List<RouteStop> = stops.filterNot(::isFill)

    /**
     * Offset from a leg of straight length [legMeters] that lengthens its
     * straight-line path by [extraMeters]: the apex h of the isosceles detour
     * 2·√((L/2)² + h²) − L = extra. A degenerate leg rides an equilateral
     * triangle of side h out and back, which adds 3h.
     */
    internal fun apexOffsetMeters(legMeters: Double, extraMeters: Double): Double {
        if (extraMeters <= 0) return 0.0
        if (legMeters < DEGENERATE_LEG_METERS) return extraMeters / 3
        val half = legMeters / 2
        val hyp = (extraMeters + legMeters) / 2
        return sqrt(hyp * hyp - half * half)
    }

    /**
     * [stops] with fill points inserted to add about [extraMeters] of straight
     * line, shared across legs by their length. When every leg is degenerate
     * (a lone stop, or a loop with nothing in between) they share it equally.
     */
    internal fun insertFill(stops: List<RouteStop>, extraMeters: Double, layout: Layout): List<RouteStop> {
        if (stops.size < 2) return stops
        val legs = stops.zipWithNext { a, b -> RoadRoulette.distanceMeters(a.at, b.at) }
        val weights = if (legs.any { it >= DEGENERATE_LEG_METERS }) {
            legs.map { if (it >= DEGENERATE_LEG_METERS) it else 0.0 }
        } else {
            legs.map { 1.0 }
        }
        val total = weights.sum()
        val out = ArrayList<RouteStop>(stops.size * 3)
        out.add(stops.first())
        for (i in legs.indices) {
            val a = stops[i].at
            val b = stops[i + 1].at
            val h = apexOffsetMeters(legs[i], extraMeters * weights[i] / total)
            if (h > 0) {
                if (legs[i] < DEGENERATE_LEG_METERS) {
                    val t = toRadians(layout.looseBearingDeg)
                    out.add(RouteStop(RoadRoulette.offset(a, h, t), FILL_NAME))
                    out.add(RouteStop(RoadRoulette.offset(a, h, t + layout.side * PI / 3), FILL_NAME))
                } else {
                    val mid = LatLon(
                        a.lat + (b.lat - a.lat) * layout.along,
                        a.lon + (b.lon - a.lon) * layout.along,
                    )
                    val bearing = toRadians(RoadRoulette.bearingDeg(a, b)) + layout.side * PI / 2
                    out.add(RouteStop(RoadRoulette.offset(mid, h, bearing), FILL_NAME))
                }
            }
            out.add(stops[i + 1])
        }
        return out
    }

    /**
     * Fills [stops] out to [minutes] of riding, routing every candidate through
     * [route] (the caller's `RoutingClient.routeVia` with its profile and
     * preferences). A single stop is treated as a loop from and back to it.
     *
     * Throws [StopsTooLong] when the stops alone already take longer than the
     * target, and [IOException] when no layout could be routed at all.
     */
    suspend fun fill(
        stops: List<RouteStop>,
        minutes: Float,
        route: suspend (List<LatLon>) -> RouteResult,
        random: Random = Random,
    ): Filled {
        val own = ownStops(stops)
        val targetMs = LoopDuration.targetMs(minutes)

        val moves = own.zipWithNext().any { (a, b) ->
            RoadRoulette.distanceMeters(a.at, b.at) >= DEGENERATE_LEG_METERS
        }
        val base = if (moves) route(own.map { it.at }) else null
        if (base != null && LoopDuration.fits(base, minutes)) return Filled(own, base)
        val baseMs = base?.timeMs ?: 0L
        if (baseMs > targetMs) throw StopsTooLong(baseMs)
        val firstGuess = (targetMs - baseMs) * metersPerMs(base) / ROAD_FACTOR

        val layouts = List(VARIANTS) {
            Layout(
                side = if (random.nextBoolean()) 1 else -1,
                along = random.nextDouble(0.35, 0.65),
                looseBearingDeg = random.nextDouble(360.0),
            )
        }
        val tried = coroutineScope {
            layouts.map { layout ->
                async { calibrate(own, layout, firstGuess, baseMs, minutes, route) }
            }.awaitAll()
        }.flatten()
        if (tried.isEmpty()) throw IOException("Could not route a filled version of these stops")

        val scored = tried.map { it.route to Curviness.routeScore(it.route.polyline, it.route.instructions) }
        val chosen = LoopDuration.pick(scored, minutes)!!.first
        return tried.first { it.route === chosen }
    }

    /** The rider's stops without any earlier fill; a lone stop becomes a loop
     *  from and back to it. */
    private fun ownStops(stops: List<RouteStop>): List<RouteStop> {
        val own = mandatory(stops)
        if (own.isEmpty()) throw IOException("Add a stop to fill a route from")
        return if (own.size == 1) own + own else own
    }

    /** The base route's own average speed, or the 50 km/h guess when there is
     *  no base route (a lone stop) or it came back without distance or time. */
    private fun metersPerMs(base: RouteResult?): Double {
        val d = base?.distanceMeters
        val t = base?.timeMs
        return if (d != null && t != null && t > 0) d / t else GUESS_METERS_PER_MS
    }

    /**
     * [fill] routed through [RoutingClient.routeVia] with the given profile and
     * preferences — the call both apps make. Swift cannot supply [fill]'s
     * `suspend` function parameter (it lowers to `KotlinSuspendFunction1`),
     * which is why this entry point exists rather than each app passing its
     * own lambda.
     */
    @Throws(Exception::class)
    suspend fun fillRouted(
        config: ServerConfig,
        stops: List<RouteStop>,
        minutes: Float,
        profile: String,
        avoidHighways: Boolean,
        avoidSmallRoads: Boolean,
    ): Filled = fill(stops, minutes, route = { points ->
        RoutingClient.routeVia(config, points, profile, avoidHighways, avoidSmallRoads)
    })

    /** One layout's rounds: route, compare the spare time gained with the
     *  spare time wanted, rescale, stop once it fits. A routing failure ends
     *  this layout with whatever it already has; the others still count. */
    private suspend fun calibrate(
        own: List<RouteStop>,
        layout: Layout,
        firstGuess: Double,
        baseMs: Long,
        minutes: Float,
        route: suspend (List<LatLon>) -> RouteResult,
    ): List<Filled> {
        val spareMs = LoopDuration.targetMs(minutes) - baseMs
        val out = ArrayList<Filled>()
        var extra = firstGuess
        repeat(MAX_ROUNDS) {
            val stops = insertFill(own, extra, layout)
            val result = try {
                route(stops.map { it.at })
            } catch (e: IOException) {
                return out
            }
            out.add(Filled(stops, result))
            if (LoopDuration.fits(result, minutes)) return out
            val gained = (result.timeMs ?: return out) - baseMs
            extra *= if (gained <= 0) MAX_SCALE
            else (spareMs.toDouble() / gained).coerceIn(MIN_SCALE, MAX_SCALE)
        }
        return out
    }
}

/** The rider's own stops already ride longer than the time asked for, so
 *  there is nothing to fill. Carries the time so the screen can word it. */
class StopsTooLong(val stopsMs: Long) : IOException("Stops alone take longer than the target time")

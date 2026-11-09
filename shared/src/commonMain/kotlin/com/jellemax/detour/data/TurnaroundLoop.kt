package com.jellemax.detour.data

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What a loop via a chosen turnaround point is asked for (#889): out from
 * [from] to [turnaround], and home again another way. [profile] and
 * [preferences] shape both legs, exactly as they shape an A→B route.
 *
 * A class rather than four parameters for the reason [LoopRequest] is one: a
 * Swift caller builds one readable value, and a fifth option (a rough total
 * length, say — an open question on #889) is a field here, not another
 * parameter on [TurnaroundLoop.plan].
 */
data class TurnaroundRequest(
    val from: LatLon,
    val turnaround: LatLon,
    val profile: String,
    val preferences: RoutePreferences,
)

/**
 * A loop the rider shapes by where it turns around rather than by how long it
 * is (#889). The way out is an ordinary route to the turnaround point; the way
 * back is routed with a custom model that makes the outbound leg's roads
 * expensive, rolled at a few penalty strengths, and the roll that overlaps the
 * way out least — curviest among those that overlap little enough — is kept.
 *
 * A penalty rather than an exclusion: the last kilometre out of the rider's
 * own street is usually the only way in, and a forbidden area there would
 * make the loop unroutable instead of merely sharing it.
 *
 * Same split as [LoopSpin]: the public [plan] wires in [RoutingClient], an
 * internal overload takes the router as functions, so the pick rule and the
 * failure sentences run in `commonTest` against fakes. Owns no dispatcher —
 * the Android caller wraps it in `Dispatchers.IO`, Swift calls it as
 * `async throws`.
 */
object TurnaroundLoop {

    /**
     * The share of the way back (outside [NEAR_ENDS_M] of either end) that may
     * run along the way out before the loop counts as an out-and-back. 15 %,
     * #889's acceptance target: enough slack for a junction or a bridge both
     * legs have to use, not enough to hide riding the same valley road twice.
     */
    internal const val MAX_OVERLAP = 0.15

    /**
     * How close to the way out a stretch of the way back has to be to count
     * as the same road, and the half-width of the corridor the penalty covers.
     * 40 m: wider than a dual carriageway's two halves are apart, so riding
     * back on the other side of one is still "the same road", and narrower
     * than the gap to a parallel road in all but town grids.
     */
    internal const val SAME_ROAD_M = 40.0

    /**
     * Overlap this close to either end of the way back is not counted: near
     * home and near the turnaround point, the rider often has one road in and
     * no choice about it. About a kilometre, as #889 proposed.
     */
    internal const val NEAR_ENDS_M = 1_000.0

    /**
     * Penalty-area count cap. Every area is a polygon GraphHopper tests each
     * edge against, and every one adds a term to the rule's expression, so a
     * long winding way out is simplified until it fits rather than sent as a
     * rectangle per vertex. 48 keeps the request a few kilobytes.
     */
    internal const val MAX_AREAS = 48

    /**
     * The penalty strengths rolled for the way back, one request each, in
     * parallel. Weak first: 0.3 keeps the way back near the fastest line home
     * when a different road is close by; 0.02 is what it takes to push the
     * route a valley over. In between is where most good loops land.
     */
    private val PENALTIES = listOf(0.3, 0.1, 0.02)

    /**
     * A way back longer than this many times the way out is a tour of its own,
     * not "home another way". Such a roll is kept only when every roll is.
     */
    internal const val MAX_STRETCH = 2.5

    /**
     * Straight-line distance a turnaround point needs from the start. Closer
     * than twice [NEAR_ENDS_M] and the whole way back sits inside the
     * uncounted ends, so the overlap could not be judged at all.
     */
    internal const val MIN_REACH_M = 2 * NEAR_ENDS_M

    /** The text the way out's arrival instruction takes once it is a via. */
    internal const val TURNAROUND_TEXT = "Turnaround point reached"

    /**
     * A planned loop. [overlap] is the share of the way back (outside the
     * ends) that runs along the way out, 0..1. [warning] is non-null when that
     * is over [MAX_OVERLAP] — the rider gets the loop and the reason it is
     * not as different as they asked, rather than a silent out-and-back.
     */
    data class Result(val route: RouteResult, val overlap: Double, val warning: String?)

    /**
     * Throws [SpinFailure] with a sentence for the rider when there is no
     * loop: no routing server, a turnaround point too close to judge, no road
     * to the point, no road back, or the whole plan running past
     * [SPIN_TIMEOUT_MS] with no way back in hand. A [CancellationException]
     * propagates: a cancelled plan is the rider leaving, not a failure.
     */
    @Throws(Exception::class)
    suspend fun plan(config: ServerConfig, request: TurnaroundRequest): Result = plan(
        request,
        serverUsable = RoutingServer.routingUsable(config),
        outbound = {
            RoutingClient.route(config, request.from, request.turnaround, request.profile, request.preferences)
        },
        back = { start, penalty ->
            RoutingClient.routeWithPenalty(
                config, start, request.from, request.profile, request.preferences, penalty,
            )
        },
    )

    /**
     * [plan] with its I/O passed in: [outbound] routes the way out, [back]
     * routes from the given start (the way out's end, so the legs join) home
     * with the given penalty in its custom model.
     */
    internal suspend fun plan(
        request: TurnaroundRequest,
        serverUsable: Boolean,
        outbound: suspend () -> RouteResult,
        back: suspend (start: LatLon, penalty: AreaPenalty) -> RouteResult,
        timeoutMs: Long = SPIN_TIMEOUT_MS,
    ): Result {
        requirePlannable(request, serverUsable)
        var out: RouteResult? = null
        // Every way back, recorded as it lands, so the time cap firing during
        // the rolls still rides the best one that came back (#507's rule).
        val landed = mutableListOf<ScoredReturn>()
        val landedLock = Mutex()
        return withSpinTimeout(
            onTimeout = {
                val o = out
                val best = o?.let { pickReturn(landed, it.distanceMeters) }
                if (o != null && best != null) resultOf(o, best)
                else throw SpinFailure(spinTimeoutMessage(null, roundTrip = false, serverUsable = true))
            },
            timeoutMs = timeoutMs,
        ) {
            val o = wayOut(outbound)
            out = o
            val corridor = corridor(o.polyline)
            val rolls = coroutineScope {
                PENALTIES.map { strength ->
                    async {
                        runCatching {
                            val r = back(o.polyline.last(), corridor.penalty(strength))
                            ScoredReturn(
                                route = r,
                                overlap = overlapFraction(o.polyline, r.polyline),
                                curviness = Curviness.routeScore(r.polyline, r.instructions),
                            )
                        }.onSuccess { landedLock.withLock { landed += it } }
                    }
                }.awaitAll()
            }
            val returns = rolls.mapNotNull { it.getOrNull() }
            val best = pickReturn(returns, o.distanceMeters) ?: noWayBack(rolls.map { it.exceptionOrNull() })
            resultOf(o, best)
        }
    }

    /** The two refusals made before any request. There is no approximate
     *  fallback, unlike LoopSpin's sampled loop: without a router, nothing
     *  can be made to pass the rider's point. */
    private fun requirePlannable(request: TurnaroundRequest, serverUsable: Boolean) {
        if (!serverUsable) throw SpinFailure("Couldn't plan the loop: ${failureReason(NoRoutingServerException())}")
        if (RoadRoulette.distanceMeters(request.from, request.turnaround) < MIN_REACH_M) {
            throw SpinFailure("That point is too close to turn around at — pick one further out.")
        }
    }

    /** [outbound], with a failure worded for the rider; a cancellation is the rider leaving. */
    private suspend fun wayOut(outbound: suspend () -> RouteResult): RouteResult = try {
        outbound()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SpinFailure("Couldn't route to that point: ${failureReason(e)}")
    }

    /** Every way back failed: one of them cancelling is the rider, not the server. */
    private fun noWayBack(errors: List<Throwable?>): Nothing {
        errors.firstOrNull { it is CancellationException }?.let { throw it }
        throw SpinFailure("Couldn't find a road back from that point: ${loopFailureReason(errors)}")
    }

    private fun resultOf(out: RouteResult, back: ScoredReturn): Result = Result(
        route = joinLegs(out, back.route),
        overlap = back.overlap,
        warning = if (back.overlap <= MAX_OVERLAP) null else {
            "No separate road back from there — ${(back.overlap * 100).roundToInt()} % of the way back " +
                "retraces the way out."
        },
    )
}

/** One rolled way back with what [pickReturn] judges it by. */
internal data class ScoredReturn(val route: RouteResult, val overlap: Double, val curviness: Double)

/**
 * Which way back to ride. Among the rolls that overlap the way out by at most
 * [TurnaroundLoop.MAX_OVERLAP] and are not stretched past
 * [TurnaroundLoop.MAX_STRETCH] times its length, the curviest — overlap is the
 * bar to clear, bends are what make the ride. When none clears it, the
 * least-overlapping roll that is not stretched, so a valley with one road
 * still gets the shortest honest answer rather than a two-hour detour. Null
 * only for an empty list.
 */
internal fun pickReturn(returns: List<ScoredReturn>, outboundMeters: Double?): ScoredReturn? {
    fun stretched(r: ScoredReturn): Boolean {
        val back = r.route.distanceMeters ?: return false
        return outboundMeters != null && back > TurnaroundLoop.MAX_STRETCH * outboundMeters
    }
    val fits = returns.filter { it.overlap <= TurnaroundLoop.MAX_OVERLAP && !stretched(it) }
    if (fits.isNotEmpty()) return fits.maxBy { it.curviness }
    return returns.filterNot(::stretched).ifEmpty { returns }.minByOrNull { it.overlap }
}

/**
 * The share of [back]'s length, ignoring [endsMeters] at either end, that runs
 * within [nearMeters] of [outbound]. 0 when nothing is left to judge once the
 * ends are taken off.
 *
 * Measured in steps of at most [STEP_M] along [back], not per vertex: a router
 * draws a straight road as one long segment, and judging that by its midpoint
 * alone would count a road that only crosses the way out as running along it.
 */
internal fun overlapFraction(
    outbound: List<LatLon>,
    back: List<LatLon>,
    nearMeters: Double = TurnaroundLoop.SAME_ROAD_M,
    endsMeters: Double = TurnaroundLoop.NEAR_ENDS_M,
): Double {
    if (outbound.size < 2 || back.size < 2) return 0.0
    val index = SegmentGrid(outbound, nearMeters)
    var total = 0.0
    for (i in 0 until back.size - 1) total += RoadRoulette.distanceMeters(back[i], back[i + 1])
    var along = 0.0
    var judged = 0.0
    var shared = 0.0
    for (i in 0 until back.size - 1) {
        val a = back[i]
        val b = back[i + 1]
        val len = RoadRoulette.distanceMeters(a, b)
        val steps = ceil(len / STEP_M).toInt().coerceAtLeast(1)
        val piece = len / steps
        for (k in 0 until steps) {
            val t = (k + 0.5) / steps
            val s = along + t * len
            if (s < endsMeters || s > total - endsMeters) continue
            judged += piece
            val p = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            if (index.within(p, nearMeters)) shared += piece
        }
        along += len
    }
    return if (judged == 0.0) 0.0 else shared / judged
}

private const val STEP_M = 25.0

private const val M_PER_DEG_LAT = 111_320.0

/**
 * [line]'s segments bucketed into square cells, so "is this point near the
 * way out" looks at a handful of segments instead of all of them — the way
 * back is judged every [STEP_M], and two 2,000-point legs would otherwise be
 * millions of distance checks on a phone. Each segment is filed under every
 * cell its bounding box, grown by twice [nearMeters], touches; a point within
 * [nearMeters] of a segment therefore always finds it in its own cell. Twice,
 * not once, because the cells are laid out in one flat projection about
 * [line]'s first point and the final check is not, and over a long loop the
 * two scales drift apart by a few percent.
 */
private class SegmentGrid(private val line: List<LatLon>, nearMeters: Double) {
    private val ref = line.first()
    private val mPerLon = M_PER_DEG_LAT * cos(toRadians(ref.lat))
    private val cell = maxOf(250.0, nearMeters * 4)
    private val cells = HashMap<Long, MutableList<Int>>()

    init {
        val grow = 2 * nearMeters
        for (i in 0 until line.size - 1) {
            val (ax, ay) = xy(line[i])
            val (bx, by) = xy(line[i + 1])
            val x0 = floor((minOf(ax, bx) - grow) / cell).toInt()
            val x1 = floor((maxOf(ax, bx) + grow) / cell).toInt()
            val y0 = floor((minOf(ay, by) - grow) / cell).toInt()
            val y1 = floor((maxOf(ay, by) + grow) / cell).toInt()
            for (cx in x0..x1) for (cy in y0..y1) cells.getOrPut(key(cx, cy)) { mutableListOf() } += i
        }
    }

    fun within(p: LatLon, meters: Double): Boolean {
        val (x, y) = xy(p)
        val near = cells[key(floor(x / cell).toInt(), floor(y / cell).toInt())] ?: return false
        return near.any { i -> RoadRoulette.distanceToSegmentMeters(p, line[i], line[i + 1]) <= meters }
    }

    private fun xy(p: LatLon) = Pair((p.lon - ref.lon) * mPerLon, (p.lat - ref.lat) * M_PER_DEG_LAT)

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
}

/**
 * The way out as penalty areas: [segments] of a simplified copy of it, each
 * to be covered by a rectangle [halfWidthMeters] either side and past either
 * end. The rectangles overlap at the joints, so they go to GraphHopper as one
 * feature each rather than one multipolygon, which JTS would call invalid.
 */
internal data class Corridor(val segments: List<Pair<LatLon, LatLon>>, val halfWidthMeters: Double) {

    /** The areas plus a priority rule multiplying by [strength] inside any of them. */
    fun penalty(strength: Double): AreaPenalty {
        val areas = buildJsonObject {
            put("type", "FeatureCollection")
            putJsonArray("features") {
                segments.forEachIndexed { i, (a, b) ->
                    addJsonObject {
                        put("type", "Feature")
                        put("id", areaId(i))
                        putJsonObject("properties") {}
                        putJsonObject("geometry") {
                            put("type", "Polygon")
                            putJsonArray("coordinates") {
                                addJsonArray {
                                    for (c in rectangle(a, b, halfWidthMeters)) addJsonArray { add(c.lon); add(c.lat) }
                                }
                            }
                        }
                    }
                }
            }
        }
        val rule = buildJsonObject {
            put("if", segments.indices.joinToString(" || ") { "in_${areaId(it)}" })
            put("multiply_by", strength)
        }
        return AreaPenalty(areas, rule)
    }

    private fun areaId(i: Int) = "outbound$i"
}

/**
 * [line] as a [Corridor] of at most [maxAreas] segments that still covers
 * every point of [line] within [halfWidthMeters]. Douglas–Peucker keeps every
 * dropped point within its tolerance of the simplified line, so widening each
 * rectangle by that tolerance is what keeps the coverage honest; the tolerance
 * doubles until the segment count fits, which on a long twisty way out trades
 * a wider corridor for a request GraphHopper will take.
 */
internal fun corridor(
    line: List<LatLon>,
    halfWidthMeters: Double = TurnaroundLoop.SAME_ROAD_M,
    maxAreas: Int = TurnaroundLoop.MAX_AREAS,
): Corridor {
    var tolerance = halfWidthMeters / 2
    var simple = simplify(line, tolerance)
    while (simple.size - 1 > maxAreas) {
        tolerance *= 2
        simple = simplify(line, tolerance)
    }
    return Corridor(simple.zipWithNext(), halfWidthMeters + tolerance)
}

/** Douglas–Peucker over [line] in a flat projection about its first point;
 *  iterative, because a 5,000-point route would recurse that deep. */
internal fun simplify(line: List<LatLon>, toleranceMeters: Double): List<LatLon> {
    if (line.size <= 2) return line
    val ref = line.first()
    val mPerLon = M_PER_DEG_LAT * cos(toRadians(ref.lat))
    val xs = DoubleArray(line.size) { (line[it].lon - ref.lon) * mPerLon }
    val ys = DoubleArray(line.size) { (line[it].lat - ref.lat) * M_PER_DEG_LAT }
    val keep = BooleanArray(line.size)
    keep[0] = true
    keep[line.lastIndex] = true
    val spans = ArrayDeque<Pair<Int, Int>>()
    spans.addLast(0 to line.lastIndex)
    while (spans.isNotEmpty()) {
        val (a, b) = spans.removeLast()
        var far = -1
        var farD = toleranceMeters
        for (i in a + 1 until b) {
            val d = pointToSegment(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
            if (d > farD) { far = i; farD = d }
        }
        if (far < 0) continue
        keep[far] = true
        spans.addLast(a to far)
        spans.addLast(far to b)
    }
    return line.filterIndexed { i, _ -> keep[i] }
}

private fun pointToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}

/** The closed ring of a rectangle around [a]→[b], [half] metres either side
 *  and past either end, so neighbouring rectangles cover the joint between. */
internal fun rectangle(a: LatLon, b: LatLon, half: Double): List<LatLon> {
    val mPerLon = M_PER_DEG_LAT * cos(toRadians((a.lat + b.lat) / 2))
    val bx = (b.lon - a.lon) * mPerLon
    val by = (b.lat - a.lat) * M_PER_DEG_LAT
    val len = hypot(bx, by)
    // A zero-length segment (a duplicated vertex) has no direction; any will do.
    val ux = if (len == 0.0) 1.0 else bx / len
    val uy = if (len == 0.0) 0.0 else by / len
    val nx = -uy
    val ny = ux
    fun at(x: Double, y: Double) = LatLon(a.lat + y / M_PER_DEG_LAT, a.lon + x / mPerLon)
    val start = at(-ux * half + nx * half, -uy * half + ny * half)
    return listOf(
        start,
        at(bx + ux * half + nx * half, by + uy * half + ny * half),
        at(bx + ux * half - nx * half, by + uy * half - ny * half),
        at(-ux * half - nx * half, -uy * half - ny * half),
        start,
    )
}

/**
 * [out] then [back] as one route, the way a spun loop is one: one polyline,
 * [back]'s instruction and speed-limit indices moved past [out]'s, and [out]'s
 * arrival turned into a via (sign 5) so guidance carries on instead of
 * announcing the finish at the turnaround point. [back] starts where [out]
 * ends, so its first point is dropped rather than doubled.
 *
 * The Google Maps hand-off waypoints are four from each leg with the
 * turnaround between — nine, that hand-off's limit — so an external app is
 * also made to pass the point and come back the other way.
 */
internal fun joinLegs(out: RouteResult, back: RouteResult): RouteResult {
    val shift = out.polyline.size - 1
    val turnaround = out.polyline.last()
    return RouteResult(
        polyline = out.polyline + back.polyline.drop(1),
        waypoints = RoutingClient.sampleInterior(out.polyline, 4) + turnaround +
            RoutingClient.sampleInterior(back.polyline, 4),
        distanceMeters = out.distanceMeters?.let { o -> back.distanceMeters?.let { o + it } },
        instructions = out.instructions.map {
            if (it.sign == SIGN_FINISH) it.copy(sign = SIGN_VIA_REACHED, text = TurnaroundLoop.TURNAROUND_TEXT) else it
        } + back.instructions.map { it.copy(startIndex = it.startIndex + shift, endIndex = it.endIndex + shift) },
        timeMs = out.timeMs?.let { o -> back.timeMs?.let { o + it } },
        speedLimits = out.speedLimits +
            back.speedLimits.map { it.copy(fromIndex = it.fromIndex + shift, toIndex = it.toIndex + shift) },
    )
}

/** GraphHopper's sign codes for "arrived" and "via reached"; see [NavInstruction.sign]. */
private const val SIGN_FINISH = 4
private const val SIGN_VIA_REACHED = 5

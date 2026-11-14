package com.jellemax.detour.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What a turnaround loop is asked for: ride from [from] out to the rider's pin
 * [turnaround] and back, on [profile] with [preferences]. No length or time —
 * the pin is the size (owner decision 1A on #889).
 */
data class TurnaroundRequest(
    val from: LatLon,
    val turnaround: LatLon,
    val profile: String,
    val preferences: RoutePreferences,
)

/**
 * A turnaround loop, start to finish (#889): route out to the pin, then route
 * back three times with the way out penalised by a different amount, and keep
 * the return that repeats least of the way out — the curviest of those that
 * repeat at most [MAX_GOOD_OVERLAP] of it, else the one that repeats least.
 * Overlap is allowed, not refused (owner decision 2A): some pins only have one
 * road in, and the rider is told how much repeats instead.
 *
 * Needs the rider's routing server; there is no backend fallback, and never a
 * silent out-and-back on the same road. Owns no dispatcher — commonMain has
 * none — so the Android caller wraps it in `Dispatchers.IO` and Swift calls it
 * as `async throws`, as with [LoopSpin].
 *
 * The router is handed to an internal overload of [build], so the choosing and
 * failure rules run in `commonTest` against fakes; the public [build] wires in
 * [RoutingClient].
 */
object TurnaroundLoop {

    /**
     * Priority multipliers for the three returns, strongest last. 0.2 still
     * takes the way out when the alternative is a long way round; 0.03 nearly
     * forbids it. Three, in parallel, for the reason [LoopSpin] rolls three:
     * which penalty gives the best return depends on the roads, and the
     * requests cost latency only on a saturated server.
     */
    private val RETURN_PENALTIES = listOf(0.2, 0.1, 0.03)

    /**
     * Share of the return that may repeat the way out and still count as a
     * different road home. Public so the app's overlap note (#921) uses the
     * same line rather than a copy of it.
     */
    const val MAX_GOOD_OVERLAP = 0.15

    /** The custom-model area name for the way out ([PenaltyArea.id]). */
    private const val OUTBOUND_AREA_ID = "outbound"

    /**
     * A built loop. [overlap] (0..1, [RouteOverlap.fraction]) is how much of the
     * return repeats the way out; the app words a note for the rider from it
     * when it is over [MAX_GOOD_OVERLAP], in its own string resources rather than in shared.
     */
    data class Result(val route: RouteResult, val overlap: Double)

    /**
     * Throws [SpinFailure] with a sentence for the rider when there is no loop:
     * no usable routing server, the way out failed, every way back failed, or
     * the build ran past [SPIN_TIMEOUT_MS] with no way back in hand. A
     * [CancellationException] propagates: a cancelled build is the rider
     * leaving, not a failure to report.
     */
    @Throws(Exception::class)
    suspend fun build(config: ServerConfig, request: TurnaroundRequest): Result = build(
        serverUsable = RoutingServer.routingUsable(config),
        outbound = {
            RoutingClient.route(config, request.from, request.turnaround, request.profile, request.preferences)
        },
        back = { avoid ->
            if (avoid == null) {
                RoutingClient.route(config, request.turnaround, request.from, request.profile, request.preferences)
            } else {
                RoutingClient.routeAvoiding(
                    config, request.turnaround, request.from, request.profile, request.preferences, avoid,
                )
            }
        },
    )

    /**
     * [build] with its I/O passed in: [outbound] routes start → pin, [back]
     * routes pin → start keeping off the given area, or with nothing to keep
     * off when it is handed null (a way out with no length has no corridor).
     */
    internal suspend fun build(
        serverUsable: Boolean,
        outbound: suspend () -> RouteResult,
        back: suspend (PenaltyArea?) -> RouteResult,
        timeoutMs: Long = SPIN_TIMEOUT_MS,
    ): Result {
        if (!serverUsable) throw SpinFailure(NO_SERVER_MESSAGE)
        var out: RouteResult? = null
        // Every return that came back, recorded as each one lands rather than
        // when all three have: they run in parallel (on Dispatchers.IO on
        // Android, hence the lock), and one stalled return must not cost the
        // rider the two that already landed. Read only after the cap has
        // cancelled and joined every request.
        val landed = mutableListOf<Scored>()
        val landedLock = Mutex()
        return withSpinTimeout(
            onTimeout = {
                val way = out
                val best = pick(landed)
                if (way == null || best == null) {
                    throw SpinFailure(spinTimeoutMessage(serverError = null, roundTrip = false, serverUsable = true))
                }
                join(way, best)
            },
            timeoutMs = timeoutMs,
        ) {
            val way = routeOut(outbound)
            out = way
            val returns = routeBack(way, back) { landedLock.withLock { landed += it } }
            join(way, pick(returns) ?: error("routeBack returned no return"))
        }
    }

    /** The way out, or a [SpinFailure] saying why there is none. */
    private suspend fun routeOut(outbound: suspend () -> RouteResult): RouteResult = try {
        outbound()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SpinFailure("$OUTBOUND_FAILED_PREFIX${failureReason(e)}")
    }

    /**
     * The ways back from [way]'s end, each scored and handed to [onReturn] the
     * moment it lands. One per [RETURN_PENALTIES] entry; a single unpenalised
     * one when [way] has no corridor. Never empty: when every one failed it
     * throws a [SpinFailure] worded by [loopFailureReason].
     */
    private suspend fun routeBack(
        way: RouteResult,
        back: suspend (PenaltyArea?) -> RouteResult,
        onReturn: suspend (Scored) -> Unit,
    ): List<Scored> {
        val corridor = PenaltyCorridor.polygons(way.polyline)
        val areas = if (corridor.isEmpty()) listOf(null)
        else RETURN_PENALTIES.map { PenaltyArea(OUTBOUND_AREA_ID, corridor, it) }
        val returns = coroutineScope {
            areas.map { area ->
                async {
                    runCatching { score(way, back(area)) }.onSuccess { onReturn(it) }
                }
            }.awaitAll()
        }
        // One of them cancelling is the rider, not the server.
        returns.firstNotNullOfOrNull { it.exceptionOrNull() as? CancellationException }?.let { throw it }
        val scored = returns.mapNotNull { it.getOrNull() }
        if (scored.isEmpty()) {
            throw SpinFailure("$RETURN_FAILED_PREFIX${loopFailureReason(returns.map { it.exceptionOrNull() })}")
        }
        return scored
    }

    private fun score(way: RouteResult, ret: RouteResult) = Scored(
        ret,
        overlap = RouteOverlap.fraction(way.polyline, ret.polyline),
        curviness = Curviness.routeScore(ret.polyline, ret.instructions),
    )

    /**
     * Which return to ride: the curviest of those repeating at most
     * [MAX_GOOD_OVERLAP] of the way out, else the one repeating least. Null only
     * for an empty list.
     */
    private fun pick(returns: List<Scored>): Scored? =
        returns.filter { it.overlap <= MAX_GOOD_OVERLAP }.maxByOrNull { it.curviness }
            ?: returns.minByOrNull { it.overlap }

    /** [way] and [ret] as one loop, with the pin — [way]'s snapped end — as the via. */
    private fun join(way: RouteResult, ret: Scored): Result {
        val via = way.polyline.lastOrNull() ?: ret.route.polyline.first()
        return Result(RouteJoin.join(way, ret.route, via), ret.overlap)
    }

    private class Scored(val route: RouteResult, val overlap: Double, val curviness: Double)

    internal const val NO_SERVER_MESSAGE =
        "A turnaround loop needs a routing server. Add one in Settings → Servers & sync."
    internal const val OUTBOUND_FAILED_PREFIX = "No route to the turnaround: "
    internal const val RETURN_FAILED_PREFIX = "No route back from the turnaround: "
}

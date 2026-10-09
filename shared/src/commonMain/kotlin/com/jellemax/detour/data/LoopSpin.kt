package com.jellemax.detour.data

import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What a loop spin is asked for. [minutes] non-null sizes the loop by riding
 * time ([LoopDuration]); null sizes it by [lengthMeters].
 *
 * A class rather than six parameters so Swift and Kotlin both build one
 * readable call, and so [LoopSpin.spin] stays under §8.4's parameter gate.
 */
data class LoopRequest(
    val from: LatLon,
    val lengthMeters: Double,
    val minutes: Float?,
    val headingDeg: Double?,
    val preferences: RoutePreferences,
    val highwayRegex: String,
)

/**
 * A round-trip spin, start to finish: roll a few server loops and keep the
 * curviest (or, sized by time, the curviest that fits the time, re-rolling
 * once at a calibrated length), and fall back to [RoundTripPlanner]'s
 * backend-sampled loop when the server gives nothing.
 *
 * Lifted out of the Android app's `map/SpinRun.kt` so iOS spins the same loop
 * through the same rules instead of a Swift copy of them. Owns no dispatcher —
 * commonMain has none — so the Android caller wraps it in `Dispatchers.IO` and
 * Swift calls it as `async throws`.
 *
 * The router and the fallback are functions handed to an internal overload of
 * [spin], so the choosing rules run in `commonTest` against fakes; the public
 * [spin] is the one that wires in [RoutingClient] and [RoundTripPlanner].
 */
object LoopSpin {

    /**
     * How many round trips to roll before picking one. GraphHopper's
     * round_trip is seed-driven and its curvature weighting only biases the
     * search, so seeds differ a lot in how much of the loop is actually bends —
     * rolling a few and keeping the curviest is what turns "avoids motorways"
     * into a ride worth taking. Three: the requests run in parallel, so this
     * costs latency only when the server is already saturated, and the gain
     * flattens out after ~3 rolls.
     */
    private const val ROLLS = 3

    /**
     * A spun loop. [warning] is non-null when the loop is the backend-sampled
     * fallback and the routing server had already failed — the rider gets a
     * usable loop *and* the reason the better one did not happen, which a bare
     * success or a bare error would each lose half of.
     */
    data class Result(val route: RouteResult, val warning: String?)

    /**
     * Throws [SpinFailure] with a sentence for the rider when there is no loop
     * at all, including when the spin runs past [SPIN_TIMEOUT_MS]
     * ([spinTimeoutMessage]); a network failure on the way throws as it came.
     * A [CancellationException] propagates: a cancelled spin is the rider
     * leaving, not a failure to report.
     */
    @Throws(Exception::class)
    suspend fun spin(config: ServerConfig, request: LoopRequest): Result = spin(
        request,
        serverUsable = config.usable,
        roll = { meters ->
            RoutingClient.roundTrip(
                config, request.from, meters, Random.nextLong(),
                headingDeg = request.headingDeg,
                preferences = request.preferences,
            )
        },
        fallback = { meters ->
            RoundTripPlanner.plan(
                request.from, meters / 4.0, request.highwayRegex,
                bearingDeg = request.headingDeg,
            )
        },
    )

    /**
     * [spin] with its I/O passed in: [roll] asks the server for one loop of the
     * given length (its own seed each call), [fallback] samples waypoints for a
     * loop of the given length when the server gives nothing.
     */
    internal suspend fun spin(
        request: LoopRequest,
        serverUsable: Boolean,
        roll: suspend (lengthMeters: Double) -> RouteResult,
        fallback: suspend (lengthMeters: Double) -> List<LatLon>,
        timeoutMs: Long = SPIN_TIMEOUT_MS,
    ): Result {
        val minutes = request.minutes
        val tripMeters = minutes?.let { LoopDuration.guessMeters(it) } ?: request.lengthMeters
        var serverError: String? = null
        // Every loop that came back, recorded as each roll finishes rather than
        // when all of them have: the rolls run in parallel (on Dispatchers.IO
        // on Android, hence the lock), and one stalled roll must not cost the
        // rider the two that already landed (#507 review).
        val rolled = mutableListOf<Pair<RouteResult, Double>>()
        val rolledLock = Mutex()
        val keep: suspend (Pair<RouteResult, Double>) -> Unit = { rolledLock.withLock { rolled += it } }
        // One cap over the rolls and the fallback together (#507): a slow
        // server used to spend its own timeout and then hand the fallback 45 s
        // more. When the cap fires with a loop already rolled — one roll of
        // the first round stalled, or the timed re-roll did — the best of what
        // came back is what the rider gets, by the same rule as a finished
        // spin. Otherwise the message is read then, so a server failure that
        // came first is still what the rider is told. `rolled` is read only
        // after the cap has cancelled and joined every roll.
        return withSpinTimeout(
            onTimeout = {
                bestOf(rolled, minutes)?.let { Result(it, warning = null) }
                    ?: throw SpinFailure(spinTimeoutMessage(serverError, roundTrip = true, serverUsable = serverUsable))
            },
            timeoutMs = timeoutMs,
        ) {
            if (serverUsable) {
                val report: (String) -> Unit = { serverError = it }
                val loops = rollLoops(tripMeters, roll, keep, report)
                val best = when {
                    loops == null -> null
                    minutes == null -> bestOf(loops, minutes = null)
                    else -> pickTimed(minutes, loops, roll, keep, report)
                }
                if (best != null) return@withSpinTimeout Result(best, warning = null)
            }

            val wps = fallback(tripMeters)
            val approximate = RouteResult(
                polyline = listOf(request.from) + wps + request.from,
                waypoints = wps,
                distanceMeters = null,
            )
            Result(approximate, serverError?.let { "Server route failed: $it Showing an approximate loop instead." })
        }
    }

    /**
     * Which of [scored] loops to ride: the curviest for a distance spin
     * ([minutes] null), [LoopDuration.pick]'s choice for a timed one. Null only
     * for an empty list.
     */
    private fun bestOf(scored: List<Pair<RouteResult, Double>>, minutes: Float?): RouteResult? =
        if (minutes == null) scored.maxByOrNull { it.second }?.first
        else LoopDuration.pick(scored, minutes)?.first

    /**
     * Rolls [ROLLS] independent round trips, each paired with its curviness
     * score; which one to ride is the caller's choice. Each loop goes to
     * [onLoop] the moment its roll lands, so a spin cut off by its time cap
     * still has it. Null when none came back, having told [onServerError] why.
     */
    private suspend fun rollLoops(
        tripMeters: Double,
        roll: suspend (Double) -> RouteResult,
        onLoop: suspend (Pair<RouteResult, Double>) -> Unit,
        onServerError: (String) -> Unit,
    ): List<Pair<RouteResult, Double>>? {
        val rolls = try {
            coroutineScope {
                (1..ROLLS).map {
                    async {
                        runCatching {
                            val loop = roll(tripMeters)
                            loop to Curviness.routeScore(loop.polyline, loop.instructions)
                        }.onSuccess { onLoop(it) }
                    }
                }.awaitAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onServerError(failureReason(e))
            return null
        }

        val loops = rolls.mapNotNull { it.getOrNull() }
        if (loops.isNotEmpty()) return loops

        // Every roll failed. One of them cancelling is the rider, not the server.
        val first = rolls.firstNotNullOfOrNull { it.exceptionOrNull() }
        if (first is CancellationException) throw first
        onServerError(loopFailureReason(rolls.map { it.exceptionOrNull() }))
        return null
    }

    /**
     * The time-sized choice: keep a loop from [firstRolls] (rolled at
     * [LoopDuration.guessMeters]) if one lands near [minutes], otherwise
     * re-roll once at the length the first rolls' reported times suggest, and
     * take the best of both rounds.
     *
     * One retry, not a search: the rescale is proportional to the router's own
     * estimate, so a second round nearly always fits, and a third would put the
     * rider's wait past the spin timeout for a few minutes' difference. The
     * second round failing is not an error — the first round's loops are still
     * loops. They already went to [onLoop] as they landed, so if the spin's
     * time cap fires during the re-roll the spin rides the best of them.
     */
    private suspend fun pickTimed(
        minutes: Float,
        firstRolls: List<Pair<RouteResult, Double>>,
        roll: suspend (Double) -> RouteResult,
        onLoop: suspend (Pair<RouteResult, Double>) -> Unit,
        onServerError: (String) -> Unit,
    ): RouteResult? {
        val first = LoopDuration.pick(firstRolls, minutes)
        if (first != null && LoopDuration.fits(first.first, minutes)) return first.first
        val retryMeters = LoopDuration.rescaledMeters(
            minutes, LoopDuration.guessMeters(minutes), firstRolls.map { it.first },
        ) ?: return first?.first
        val retry = rollLoops(retryMeters, roll, onLoop, onServerError).orEmpty()
        return LoopDuration.pick(firstRolls + retry, minutes)?.first
    }
}

/**
 * What to tell the rider when a spin ran out of time.
 *
 * Pure, and separate from the spin itself, because it is the one part of a
 * spin failure that is a *decision* rather than an I/O result: three different
 * situations produce three different sentences, and the branch that matters
 * most is the first — a fallback timeout must not hide that the rider's own
 * routing server was the thing that actually failed.
 */
fun spinTimeoutMessage(
    serverError: String?,
    roundTrip: Boolean,
    serverUsable: Boolean,
): String = when {
    serverError != null -> "Server route failed: $serverError The fallback timed out too."
    roundTrip && !serverUsable -> "No routing server configured — public servers timed out"
    else -> "The routing server is too slow or unreachable — try again"
}

/**
 * Why every roll of a round trip failed, as one sentence.
 *
 * The rolls are independent requests against the same server, so when they all
 * fail they have almost always failed the same way; reporting the first is
 * both accurate and shorter than reporting three copies of it. Worded by
 * [failureReason], never the exception's own text, which could be a URL or a
 * parser's internals (#487).
 */
fun loopFailureReason(errors: List<Throwable?>): String =
    errors.firstNotNullOfOrNull { it }?.let(::failureReason) ?: "no route came back."

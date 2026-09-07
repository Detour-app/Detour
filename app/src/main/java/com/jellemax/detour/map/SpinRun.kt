package com.jellemax.detour.map

import com.jellemax.detour.data.Curviness
import com.jellemax.detour.data.ExploredArea
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.PoiKind
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.RoundTripPlanner
import com.jellemax.detour.data.RoutingClient
import com.jellemax.detour.data.ServerConfig
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.data.pickThreeCandidates
import com.jellemax.detour.ui.CURVY_CANDIDATES
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** What a spin produced. */
sealed interface SpinOutcome {
    /**
     * A round-trip loop. [warning] is non-null when the loop is the Overpass
     * fallback and the routing server had already failed — the rider gets a
     * usable loop *and* the reason the better one did not happen, which a bare
     * success or a bare error would each lose half of.
     */
    data class Loop(val route: RouteResult, val warning: String?) : SpinOutcome

    /** A point-to-point spread to choose from. */
    data class Candidates(val candidates: List<RouteCandidate>) : SpinOutcome

    /** Nothing usable came back; [message] is what the rider is told. */
    data class Failed(val message: String) : SpinOutcome
}

/** Everything the sheet's controls contribute to a spin. */
data class SpinParams(
    val mode: TravelMode,
    val radiusKm: Float,
    val minRadiusKm: Float,
    val poiKind: PoiKind,
    val directionDeg: Float?,
)

/**
 * What to tell the rider when the fallback timed out too.
 *
 * Pure, and separate from [runSpin], because it is the one part of a spin
 * failure that is a *decision* rather than an I/O result: three different
 * situations produce three different sentences, and the branch that matters
 * most is the first — a fallback timeout must not hide that the rider's own
 * routing server was the thing that actually failed.
 */
fun spinTimeoutMessage(
    serverError: String?,
    roundTrip: Boolean,
    serverUsable: Boolean,
): String = when {
    serverError != null -> "Server route failed ($serverError); fallback timed out too"
    roundTrip && !serverUsable -> "No routing server configured — public servers timed out"
    else -> "Road servers are slow right now — try again"
}

/**
 * Why every roll of a round trip failed, as one sentence.
 *
 * The rolls are independent requests against the same server, so when they all
 * fail they have almost always failed the same way; reporting the first is
 * both accurate and shorter than reporting three copies of it.
 */
fun loopFailureReason(errors: List<Throwable?>): String =
    errors.firstNotNullOfOrNull { it }
        ?.let { it.message ?: it::class.simpleName }
        ?: "no route"

/**
 * Runs one spin and reports what came back.
 *
 * Lifted out of `MapScreen.spin()`, which held all of this inside a composable
 * where nothing could reach it: the loop-vs-candidates split, the roll-and-keep-
 * the-curviest strategy, the Overpass fallback and four different failure
 * sentences were a hundred lines of decision welded to a `scope.launch` and a
 * dozen `var`s.
 *
 * This function does I/O but owns no state and touches no Compose: it takes the
 * origin and the sheet's settings, and returns one [SpinOutcome]. The caller
 * still owns `spinning`, the haptic buzz and the camera framing, because those
 * are all things a *screen* does with a result rather than parts of producing
 * one.
 *
 * [CancellationException] is rethrown rather than mapped to [SpinOutcome.Failed]
 * — a cancelled spin is the rider leaving or pressing cancel, not a failure to
 * report, and swallowing it here would break the caller's `finally`.
 */
suspend fun runSpin(
    serverConfig: ServerConfig,
    from: LatLon,
    params: SpinParams,
): SpinOutcome {
    var serverError: String? = null
    return try {
        // Bias destinations toward territory the fog has not uncovered.
        val explored = withContext(Dispatchers.IO) { ExploredArea.load() }
        if (params.mode.roundTrip) {
            runLoopSpin(serverConfig, from, params) { serverError = it }
        } else {
            // pickThreeCandidates has no Dispatchers.IO of its own (commonMain
            // has none by design — iOS calls it the same way); withContext here
            // is what keeps the three rolls off the main thread on Android.
            val results = withContext(Dispatchers.IO) {
                pickThreeCandidates(
                    serverConfig, from, params.radiusKm.toDouble() * 1000.0,
                    params.minRadiusKm.toDouble() * 1000.0, params.mode, params.poiKind,
                    params.directionDeg?.toDouble(), explored,
                )
            }
            SpinOutcome.Candidates(results)
        }
    } catch (e: TimeoutCancellationException) {
        // Before the generic CancellationException branch: a timeout is a
        // TimeoutCancellationException, and catching cancellation first would
        // rethrow it and lose the message.
        SpinOutcome.Failed(
            spinTimeoutMessage(serverError, params.mode.roundTrip, serverConfig.usable)
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SpinOutcome.Failed(e.message ?: "Failed to find a road")
    }
}

/**
 * Rolls [CURVY_CANDIDATES] independent round trips and keeps the curviest.
 *
 * Split out of [runSpin] so the loop branch does not nest four levels deep
 * inside it, and so the two ways a roll can end — every attempt failed, or the
 * rider cancelled — are answered here rather than by throws threaded up through
 * the caller's try.
 *
 * Returns null when no loop came back, having told [onServerError] why, so the
 * caller can fall back to an Overpass-planned loop and still say what the
 * server did. A [CancellationException] propagates: a cancelled spin is the
 * rider leaving, not a failure to report.
 */
private suspend fun rollBestLoop(
    serverConfig: ServerConfig,
    from: LatLon,
    tripMeters: Double,
    params: SpinParams,
    onServerError: (String) -> Unit,
): RouteResult? {
    val rolls = try {
        coroutineScope {
            (1..CURVY_CANDIDATES).map {
                async(Dispatchers.IO) {
                    runCatching {
                        val loop = RoutingClient.roundTrip(
                            serverConfig, from, tripMeters, Random.nextLong(),
                            headingDeg = params.directionDeg?.toDouble(),
                            avoidSmallRoads = Settings.avoidSmallRoads.value,
                        )
                        // Scored here so it stays off the main thread with the
                        // request that produced it.
                        loop to Curviness.routeScore(loop.polyline, loop.instructions)
                    }
                }
            }.awaitAll()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onServerError(e.message ?: e::class.simpleName ?: "unknown")
        return null
    }

    val loops = rolls.mapNotNull { it.getOrNull() }
    if (loops.isNotEmpty()) return loops.maxBy { it.second }.first

    // Every roll failed. One of them cancelling is the rider, not the server.
    val first = rolls.firstNotNullOfOrNull { it.exceptionOrNull() }
    if (first is CancellationException) throw first
    onServerError(loopFailureReason(rolls.map { it.exceptionOrNull() }))
    return null
}

/**
 * The round-trip branch of [runSpin]: a server loop if one comes back, an
 * Overpass-planned approximation if none does.
 *
 * Its own function so [runSpin] reads as the two outcomes a spin has rather
 * than as four levels of nesting. The fallback is not a failure — a rider on a
 * dead server still gets a loop — so the server's reason travels as a warning
 * on the result instead of replacing it.
 */
private suspend fun runLoopSpin(
    serverConfig: ServerConfig,
    from: LatLon,
    params: SpinParams,
    onServerError: (String) -> Unit,
): SpinOutcome {
    val tripMeters = params.radiusKm * 1000.0
    var serverError: String? = null
    var result: RouteResult? = null
    if (serverConfig.usable) {
        result = rollBestLoop(serverConfig, from, tripMeters, params) {
            serverError = it
            onServerError(it)
        }
    }
    if (result != null) return SpinOutcome.Loop(result, warning = null)

    val wps = RoundTripPlanner.plan(
        from, tripMeters / 4.0, params.mode.highwayRegex,
        bearingDeg = params.directionDeg?.toDouble(),
    )
    val approximate = RouteResult(
        polyline = listOf(from) + wps + from,
        waypoints = wps,
        distanceMeters = null,
    )
    val warning = serverError?.let { "Server route failed ($it) — approximate loop instead" }
    return SpinOutcome.Loop(approximate, warning)
}

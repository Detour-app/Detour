package com.jellemax.detour.map

import com.jellemax.detour.data.ExploredArea
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.LoopRequest
import com.jellemax.detour.data.LoopSpin
import com.jellemax.detour.data.PoiKind
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.ServerConfig
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.data.pickThreeCandidates
import com.jellemax.detour.data.spinTimeoutMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext

/** What a spin produced. */
sealed interface SpinOutcome {
    /**
     * A round-trip loop. [warning] is non-null when the loop is the backend-sampled
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
    /** Round trips only: size the loop to this many minutes of riding instead
     *  of [radiusKm] of length. Null means by length. */
    val loopMinutes: Float? = null,
)

/**
 * Runs one spin and reports what came back.
 *
 * Lifted out of `MapScreen.spin()`, which held all of this inside a composable
 * where nothing could reach it: the loop-vs-candidates split, the roll-and-keep-
 * the-curviest strategy, the backend-sampled fallback and four different failure
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
    return try {
        // Bias destinations toward territory the fog has not uncovered.
        val explored = withContext(Dispatchers.IO) { ExploredArea.load() }
        if (params.mode.roundTrip) {
            // The loop itself - rolls, curviest/timed pick, Overpass fallback,
            // its own timeout sentence - is shared LoopSpin, the same code iOS
            // spins; commonMain has no dispatcher, so IO is chosen here.
            val loop = withContext(Dispatchers.IO) {
                LoopSpin.spin(
                    serverConfig,
                    LoopRequest(
                        from = from,
                        lengthMeters = params.radiusKm * 1000.0,
                        minutes = params.loopMinutes,
                        headingDeg = params.directionDeg?.toDouble(),
                        avoidSmallRoads = Settings.avoidSmallRoads.value,
                        highwayRegex = params.mode.highwayRegex,
                    ),
                )
            }
            SpinOutcome.Loop(loop.route, loop.warning)
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
        // Only the candidates branch gets here: LoopSpin turns its own
        // fallback timeout into a sentence before returning.
        SpinOutcome.Failed(
            spinTimeoutMessage(serverError = null, params.mode.roundTrip, serverConfig.usable)
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SpinOutcome.Failed(e.message ?: "Failed to find a road")
    }
}

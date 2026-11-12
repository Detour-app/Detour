package com.jellemax.detour.map

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.ServerConfig
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.data.TurnaroundLoop
import com.jellemax.detour.data.TurnaroundRequest
import com.jellemax.detour.presentation.spinFailureText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Share of the return repeating the way out above which the rider is told (#889). */
private const val OVERLAP_NOTE_ABOVE = 0.15

/** What "Loop via here" asks the router for: [from] out to [turnaround] and
 *  back on [mode]'s profile, with the dock's curvy preset on both legs. */
fun turnaroundRequest(from: LatLon, turnaround: LatLon, mode: TravelMode) =
    TurnaroundRequest(from, turnaround, mode.ghProfile, Settings.navRoutePreferences())

/**
 * Builds the loop [request] asks for — out to the rider's pin and back on
 * other roads (#889, "Loop via here" on the navigation dock) — and reports it
 * the way [runSpin] reports a loop spin, so the map lands it on the same path:
 * Go, Save loop and tile caching all take a [SpinOutcome.Loop] already.
 *
 * Any mode the dock has selected (owner decision 3B), with the dock's curvy
 * preset on both legs (4A): [turnaroundRequest] reads
 * [Settings.navRoutePreferences], what the A→B route it replaces would use.
 *
 * [overlapNote] words the note for a return that repeats more than 15 % of
 * the way out, given the whole percentage; it is the caller's because the
 * sentence is a string resource. A [com.jellemax.detour.data.SpinFailure]
 * maps to [SpinOutcome.Failed] with its sentence, like any other failure;
 * [CancellationException] is rethrown, for the reason [runSpin] gives.
 *
 * [build] is there for the tests; callers leave it.
 */
suspend fun runTurnaroundLoop(
    serverConfig: ServerConfig,
    request: TurnaroundRequest,
    build: suspend (ServerConfig, TurnaroundRequest) -> TurnaroundLoop.Result = TurnaroundLoop::build,
    overlapNote: (percent: Int) -> String,
): SpinOutcome = try {
    // commonMain owns no dispatcher, so IO is chosen here, as runSpin does.
    val loop = withContext(Dispatchers.IO) { build(serverConfig, request) }
    SpinOutcome.Loop(
        loop.route,
        warning = loop.overlap.takeIf { it > OVERLAP_NOTE_ABOVE }?.let { overlapNote((it * 100).roundToInt()) },
    )
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    SpinOutcome.Failed(spinFailureText(e))
}

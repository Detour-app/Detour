package com.jellemax.detour.presentation

import com.jellemax.detour.data.HereTrafficClient
import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.nowMs
import com.jellemax.detour.data.Settings
import com.jellemax.detour.drive.TrafficCorridor
import com.jellemax.detour.drive.TrafficRound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HERE traffic for the iOS nav screen (#654): the corridor state one nav
 * session carries between ticks, and the key it was built for.
 *
 * Which tiles, how often and the backoff are [TrafficCorridor]'s, one round
 * is [TrafficRound]'s, and what to draw is [trafficOnRoute]'s — this only
 * holds the state between calls, the way the phone's `RetainedMap` does, and
 * picks the dispatcher. The caller owns the clock of the loop
 * ([TrafficRound.TICK_MS]) and the conditions under which it runs at all
 * (navigating, app in the foreground).
 *
 * Also a holder because [TrafficCorridor.State]'s constructor is defaulted,
 * which Swift cannot call. Lives as long as one nav session, unlike the
 * Settings screen's [TrafficKeyHolder].
 */
class TrafficHolder {

    private var corridor = TrafficCorridor.State()
    private var corridorKey: String? = null

    /**
     * One tick: asks HERE whatever is due for the part of [route] ahead of
     * [progress] and returns what to draw, or null with no usable [key].
     *
     * A key different from the last tick's gets a clean corridor — that is
     * how a refused key is left behind. Off the main thread: a flow answer for
     * a busy tile is a sizeable JSON body, and matching it to 20 km of route
     * is a walk over every leg.
     */
    suspend fun tick(key: String, route: RouteResult, progress: NavEngine.Progress?): TrafficOnRoute? =
        withContext(Dispatchers.Default) {
            if (corridorKey != key) {
                corridorKey = key
                corridor = TrafficCorridor.State()
            }
            val client = HereTrafficClient.forKey(key) ?: return@withContext null
            val ahead = routeAhead(route.polyline, progress)
            corridor = TrafficRound.run(
                corridor, client, ahead,
                navigating = true,
                nowMs = ::nowMs,
                spend = Settings::spendTrafficRequests,
            )
            trafficOnRoute(corridor, ahead, nowMs())
        }

    /** [progress] with [traffic]'s delay in the time left, for the ETA — see
     *  [withTrafficDelay]. A member so Swift need not reach for a top-level
     *  extension function. */
    fun withDelay(progress: NavEngine.Progress, traffic: TrafficOnRoute?): NavEngine.Progress =
        progress.withTrafficDelay(traffic)
}

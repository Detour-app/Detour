package com.jellemax.detour.drive

import com.jellemax.detour.data.HereTrafficClient
import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.TrafficKeyStatusWatcher
import com.jellemax.detour.data.nowMs
import com.jellemax.detour.presentation.TrafficKeyPresenter
import com.jellemax.detour.presentation.TrafficOnRoute
import com.jellemax.detour.presentation.routeAhead
import com.jellemax.detour.presentation.trafficOnRoute
import com.jellemax.detour.presentation.withTrafficDelay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The HERE key section's [TrafficKeyPresenter] for iOS Settings (#654).
 *
 * A holder rather than the presenter itself for [CameraPrefetchHolder]'s
 * reason: the presenter's constructor parameters are all defaulted (they are
 * commonTest's seams), and Kotlin/Native exports no defaulted constructor, so
 * Swift could not write `TrafficKeyPresenter()`. Its status is a
 * `StateFlow<TrafficKeyStatus>`, which Swift reads through [status].
 */
class TrafficKeyHolder {

    private val presenter = TrafficKeyPresenter()

    /** The line under the field. Cancel it when the screen goes away. */
    fun status() = TrafficKeyStatusWatcher(presenter.status)

    /** See [TrafficKeyPresenter.test]. */
    suspend fun test(key: String) = presenter.test(key)

    /** See [TrafficKeyPresenter.edited]. */
    fun edited() = presenter.edited()

    /** See [TrafficKeyPresenter.remove]. */
    fun remove() = presenter.remove()
}

/**
 * HERE traffic for the iOS nav screen (#654): the corridor state one nav
 * session carries between ticks, and the key it was built for.
 *
 * Which tiles, how often and the backoff are [TrafficCorridor]'s, one round
 * is [TrafficRound]'s, and what to draw is [trafficOnRoute]'s — this only
 * holds the state between calls, the way the phone's `RetainedMap` does, and
 * picks the dispatcher. The caller owns the clock of the loop and the
 * conditions under which it runs at all (navigating, app in the foreground).
 *
 * Also a holder because [TrafficCorridor.State]'s constructor is defaulted,
 * which Swift cannot call.
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
            corridor = TrafficRound.run(corridor, client, ahead, navigating = true, nowMs = ::nowMs)
            trafficOnRoute(corridor, ahead, nowMs())
        }

    /** [progress] with [traffic]'s delay in the time left, for the ETA — see
     *  [withTrafficDelay]. A member so Swift need not reach for a top-level
     *  extension function. */
    fun withDelay(progress: NavEngine.Progress, traffic: TrafficOnRoute?): NavEngine.Progress =
        progress.withTrafficDelay(traffic)
}

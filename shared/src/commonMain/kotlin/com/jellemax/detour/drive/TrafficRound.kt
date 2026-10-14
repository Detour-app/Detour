package com.jellemax.detour.drive

import com.jellemax.detour.data.HereTrafficClient
import com.jellemax.detour.data.LatLon
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * One round of [TrafficCorridor]'s schedule against a rider's key (#645): the
 * order its class doc spells out, written once so the phone, and later the
 * head unit (#653) and iOS (#654), do not each re-type it.
 *
 * The round's requests go out together and every answer is folded in after
 * the last one lands, in request order. Folding as each one landed would need
 * the state shared between coroutines; folding after keeps [run] a function
 * from one [TrafficCorridor.State] to the next, whatever dispatcher the caller
 * runs it on. The cost is that a fast answer waits for the slow one in its
 * round, which at two to eight requests is nothing a rider sees.
 *
 * No dispatcher here (commonMain has none): the caller picks it.
 */
object TrafficRound {

    /** How often a caller's traffic loop wakes, on every surface. Not how
     *  often HERE is asked — that is [TrafficCorridor]'s TTLs — but how soon
     *  the delay follows the rider down the route, and how soon a reroute's
     *  new road is asked about. */
    const val TICK_MS = 15_000L

    /**
     * Make whatever [TrafficCorridor.due] says is due for [route] and return
     * the state with the answers in. Nothing is asked when it says nothing is
     * due — not navigating, a refused key, a backoff running — so a caller can
     * call this on any cadence without spending the rider's quota.
     *
     * [spend] trims the round to what the rider's monthly budget still allows.
     *
     * [nowMs] is read twice: once to stamp the requests, once for the answers.
     */
    suspend fun run(
        state: TrafficCorridor.State,
        client: HereTrafficClient,
        route: List<LatLon>,
        navigating: Boolean,
        nowMs: () -> Long,
        spend: (wanted: Int, nowMs: Long) -> Int = { wanted, _ -> wanted },
    ): TrafficCorridor.State {
        val startedAt = nowMs()
        // [spend] is the monthly budget ([TrafficBudget]): it counts what it
        // grants, so it is asked only when something is due.
        val due = TrafficCorridor.due(state, route, navigating, startedAt)
            .let { it.take(if (it.isEmpty()) 0 else spend(it.size, startedAt)) }
        if (due.isEmpty()) return state
        val folds = coroutineScope {
            due.map { request -> async { answer(client, request) } }.awaitAll()
        }
        val answeredAt = nowMs()
        return folds.fold(TrafficCorridor.started(state, due, startedAt)) { s, fold -> fold(s, answeredAt) }
    }

    /** Ask [request]'s question, and hand back how to fold the answer in. */
    private suspend fun answer(
        client: HereTrafficClient,
        request: TrafficCorridor.Request,
    ): (TrafficCorridor.State, Long) -> TrafficCorridor.State {
        val tile = request.tile
        return when (request.feed) {
            TrafficCorridor.Feed.FLOW -> {
                val answer = client.flow(tile.box())
                ({ s, at -> TrafficCorridor.flowAnswered(s, tile, answer, at) })
            }
            TrafficCorridor.Feed.INCIDENTS -> {
                val answer = client.incidents(tile.box())
                ({ s, at -> TrafficCorridor.incidentsAnswered(s, tile, answer, at) })
            }
        }
    }
}

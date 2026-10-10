package com.jellemax.detour.drive

import com.jellemax.detour.data.HereTrafficClient
import com.jellemax.detour.data.HereTrafficClient.Fetch
import com.jellemax.detour.data.HereTrafficClient.FlowSegment
import com.jellemax.detour.data.HereTrafficClient.Incident
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * *Which* HERE traffic requests to make and *when*, plus the answers held
 * between them (#643). Same split as [CameraPrefetch]: commonMain has no
 * dispatcher to hand a request to, so the caller owns the I/O and the order is
 *
 * ```
 * val due = TrafficCorridor.due(st, remainingRoute, navigating, now)
 * st = TrafficCorridor.started(st, due, now)        // stamp before awaiting
 * for (r in due) <platform coroutine> {
 *     st = when (r.feed) {
 *         FLOW -> TrafficCorridor.flowAnswered(st, r.tile, client.flow(r.tile.box()), now)
 *         INCIDENTS -> TrafficCorridor.incidentsAnswered(st, r.tile, client.incidents(r.tile.box()), now)
 *     }
 * }
 * ```
 *
 * **The rider pays for every request** — the key is theirs, and HERE bills per
 * transaction past a monthly free allocation — so the budget is the design:
 *
 * - Nothing at all unless navigating ([due] returns nothing). Pausing while the
 *   screen is off is the caller's, since only it knows.
 * - Only along the route ahead: zoom-[ZOOM] tiles (about 39 × 25 km at 50° N)
 *   under the next [LOOKAHEAD_M] of it, at most [MAX_TILES]. Fixed tiles rather
 *   than a box around the rider, so a tile fetched a minute ago is still the
 *   same tile — that is what makes the cache hit.
 * - Each tile's flow at most every [FLOW_TTL_MS], incidents every
 *   [INCIDENTS_TTL_MS] — HERE refreshes them about that often, so asking sooner
 *   buys nothing.
 *
 * That is typically one or two tiles: two to four requests a minute, 120–240 an
 * hour of navigation.
 *
 * **Backoff never loops** (#637). A refused key ([Fetch.BadKey]) stops every
 * request for good; a new key means a new [State], which is how it restarts. A
 * spent quota ([Fetch.Quota]) waits [QUOTA_BACKOFF_MS], doubling to
 * [QUOTA_MAX_BACKOFF_MS]. Anything else ([Fetch.Failed]) backs off like
 * [CameraPrefetch], doubling from [FAILURE_THROTTLE_MS] to [MAX_BACKOFF_MS], and
 * one answer puts the cadence straight back.
 *
 * **No clock**: every `nowMs` is the caller's, so the schedule is testable.
 */
object TrafficCorridor {

    /** Slippy-map zoom of the tiles requests are made by. 10 keeps a tile
     *  well inside HERE's one-degree box limit everywhere. */
    const val ZOOM = 10

    /** How far ahead along the route traffic is asked for. */
    const val LOOKAHEAD_M = 20_000.0

    /** Route points are sampled this often when finding the tiles under it,
     *  so a long straight leg cannot skip a tile it crosses. */
    const val SAMPLE_M = 500.0

    /** Ceiling on tiles per refresh, whatever the route does inside
     *  [LOOKAHEAD_M] (a winding road along a tile edge). */
    const val MAX_TILES = 4

    const val FLOW_TTL_MS = 60_000L
    const val INCIDENTS_TTL_MS = 120_000L

    /** Answers older than this are not shown or fed to the ETA, even if the
     *  refresh failed: five minutes is when a jam may have cleared. */
    const val MAX_AGE_MS = 5 * 60_000L

    /** The floor failures double from: the flow TTL, so the first failure
     *  already waits longer than a working refresh would. */
    const val FAILURE_THROTTLE_MS = FLOW_TTL_MS
    const val MAX_BACKOFF_MS = 5 * 60_000L

    const val QUOTA_BACKOFF_MS = 15 * 60_000L
    const val QUOTA_MAX_BACKOFF_MS = 2 * 60 * 60_000L

    /** Which HERE endpoint a request is for. */
    enum class Feed { FLOW, INCIDENTS }

    /** One zoom-[ZOOM] slippy-map tile. */
    data class Tile(val x: Int, val y: Int) {
        fun box(): HereTrafficClient.Box {
            val n = (1 shl ZOOM).toDouble()
            return HereTrafficClient.Box(
                west = x / n * 360.0 - 180.0,
                south = latOf(y + 1, n),
                east = (x + 1) / n * 360.0 - 180.0,
                north = latOf(y, n),
            )
        }

        private fun latOf(row: Int, n: Double): Double =
            atan(sinh(PI * (1 - 2 * row / n))) * 180.0 / PI

        private fun sinh(v: Double): Double = (exp(v) - exp(-v)) / 2
    }

    data class Request(val tile: Tile, val feed: Feed)

    /** An answer and when it came. */
    data class Held<T>(val value: T, val atMs: Long)

    data class State(
        /** When each request was last *made*, answered or not — what the TTL
         *  is measured from, so a failure is throttled too. */
        internal val attemptedMs: Map<Request, Long> = emptyMap(),
        internal val flow: Map<Tile, Held<List<FlowSegment>>> = emptyMap(),
        internal val incidents: Map<Tile, Held<List<Incident>>> = emptyMap(),
        /** Consecutive failed rounds, and when the last failure came. */
        val failures: Int = 0,
        val lastFailureMs: Long = 0L,
        /** Consecutive refused rounds; nothing is asked before [quotaUntilMs]. */
        val quotaHits: Int = 0,
        val quotaUntilMs: Long = 0L,
        /** HERE refused the key. Final for this [State]. */
        val keyRefused: Boolean = false,
    ) {
        internal fun flowOf(tile: Tile, nowMs: Long): List<FlowSegment> =
            flow[tile]?.takeIf { nowMs - it.atMs <= MAX_AGE_MS }?.value.orEmpty()

        internal fun incidentsOf(tile: Tile, nowMs: Long): List<Incident> =
            incidents[tile]?.takeIf { nowMs - it.atMs <= MAX_AGE_MS }?.value.orEmpty()

        internal fun withFlow(tile: Tile, value: List<FlowSegment>, nowMs: Long) =
            copy(flow = flow + (tile to Held(value, nowMs)))

        internal fun withIncidents(tile: Tile, value: List<Incident>, nowMs: Long) =
            copy(incidents = incidents + (tile to Held(value, nowMs)))
    }

    /** How long to wait after [failures] consecutive [Fetch.Failed] answers. */
    fun retryDelayMs(failures: Int): Long = backoffDelayMs(FAILURE_THROTTLE_MS, MAX_BACKOFF_MS, failures)

    /** How long to wait after the [hits]th consecutive [Fetch.Quota]. */
    fun quotaDelayMs(hits: Int): Long = backoffDelayMs(QUOTA_BACKOFF_MS, QUOTA_MAX_BACKOFF_MS, hits - 1)

    /**
     * The tiles under the first [LOOKAHEAD_M] of [route], in route order,
     * without repeats, at most [MAX_TILES]. [route] is what is left of it, from
     * the rider on.
     */
    fun tilesAhead(route: List<LatLon>): List<Tile> {
        if (route.isEmpty()) return emptyList()
        val tiles = LinkedHashSet<Tile>()
        tiles.add(tileOf(route.first()))
        var travelled = 0.0
        for ((a, b) in route.zipWithNext()) {
            val leg = RoadRoulette.distanceMeters(a, b)
            val steps = (leg / SAMPLE_M).toInt() + 1
            for (i in 1..steps) {
                val t = i.toDouble() / steps
                if (travelled + leg * t > LOOKAHEAD_M) return tiles.take(MAX_TILES)
                tiles.add(tileOf(LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)))
                if (tiles.size >= MAX_TILES) return tiles.toList()
            }
            travelled += leg
        }
        return tiles.toList()
    }

    /** The slippy-map tile [at] falls in. */
    fun tileOf(at: LatLon): Tile {
        val n = 1 shl ZOOM
        val latRad = at.lat * PI / 180.0
        val x = floor((at.lon + 180.0) / 360.0 * n).toInt()
        val y = floor((1 - ln(tan(latRad) + 1 / cos(latRad)) / PI) / 2 * n).toInt()
        return Tile(x.coerceIn(0, n - 1), y.coerceIn(0, n - 1))
    }

    /**
     * The requests to make now: none unless [navigating], none while backed
     * off, and otherwise each feed of each tile ahead whose TTL has run out.
     * **The requests are the caller's**, and so is any in-flight guard.
     */
    fun due(state: State, route: List<LatLon>, navigating: Boolean, nowMs: Long): List<Request> {
        if (!navigating || state.keyRefused || nowMs < state.quotaUntilMs) return emptyList()
        if (state.failures > 0 && nowMs - state.lastFailureMs < retryDelayMs(state.failures)) return emptyList()
        return tilesAhead(route).flatMap { tile ->
            Feed.entries.map { Request(tile, it) }
        }.filter { r ->
            val last = state.attemptedMs[r] ?: return@filter true
            nowMs - last >= ttlMs(r.feed)
        }
    }

    /**
     * Stamp [requests] as made. Called *before* awaiting any of them.
     *
     * Also where the tiles left behind are dropped: an answer past
     * [MAX_AGE_MS] is never served, and a stamp past the longest TTL never
     * holds a request back, so over a long ride neither is worth keeping.
     */
    fun started(state: State, requests: List<Request>, nowMs: Long): State = state.copy(
        attemptedMs = state.attemptedMs.filterValues { nowMs - it < INCIDENTS_TTL_MS } +
            requests.associateWith { nowMs },
        flow = state.flow.filterValues { nowMs - it.atMs <= MAX_AGE_MS },
        incidents = state.incidents.filterValues { nowMs - it.atMs <= MAX_AGE_MS },
    )

    /** Fold a flow answer for [tile] in. A failure keeps whatever was held for
     *  the tile: recent traffic beats none, until [MAX_AGE_MS] retires it. */
    fun flowAnswered(state: State, tile: Tile, answer: Fetch<List<FlowSegment>>, nowMs: Long): State =
        if (answer is Fetch.Ok) {
            succeeded(state).withFlow(tile, answer.value, nowMs)
        } else {
            failed(state, answer, nowMs)
        }

    /** [flowAnswered], for incidents. */
    fun incidentsAnswered(state: State, tile: Tile, answer: Fetch<List<Incident>>, nowMs: Long): State =
        if (answer is Fetch.Ok) {
            succeeded(state).withIncidents(tile, answer.value, nowMs)
        } else {
            failed(state, answer, nowMs)
        }

    private fun succeeded(state: State): State = state.copy(failures = 0, quotaHits = 0)

    /**
     * A round is several requests made together, and one outage fails them
     * all. So a failure that lands while the backoff it would extend is still
     * running is the same outage, not another one: counting per answer would
     * turn one 429 round of four tiles into the two-hour ceiling at once.
     */
    private fun failed(state: State, answer: Fetch<*>, nowMs: Long): State = when (answer) {
        is Fetch.Ok -> state
        Fetch.BadKey -> state.copy(keyRefused = true)
        Fetch.Quota -> if (nowMs < state.quotaUntilMs) {
            state
        } else {
            val hits = state.quotaHits + 1
            state.copy(quotaHits = hits, quotaUntilMs = nowMs + quotaDelayMs(hits))
        }
        Fetch.Failed -> if (state.failures > 0 && nowMs - state.lastFailureMs < retryDelayMs(state.failures)) {
            state
        } else {
            state.copy(failures = state.failures + 1, lastFailureMs = nowMs)
        }
    }

    /** Flow held for the tiles under [route], fresh enough to use. */
    fun flowAlong(state: State, route: List<LatLon>, nowMs: Long): List<FlowSegment> =
        tilesAhead(route).flatMap { state.flowOf(it, nowMs) }

    /** Incidents held for the tiles under [route], fresh enough to show. One
     *  incident can straddle two tiles and come back from both; it is listed
     *  once. */
    fun incidentsAlong(state: State, route: List<LatLon>, nowMs: Long): List<Incident> =
        tilesAhead(route).flatMap { state.incidentsOf(it, nowMs) }.distinctBy { if (it.id.isEmpty()) it else it.id }

    private fun ttlMs(feed: Feed): Long = when (feed) {
        Feed.FLOW -> FLOW_TTL_MS
        Feed.INCIDENTS -> INCIDENTS_TTL_MS
    }
}

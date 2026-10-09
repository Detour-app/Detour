package com.jellemax.detour.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.IOException

/** The longest a whole spin may take, on every surface: a round trip's
 *  rolls, timed re-roll and fallback together ([LoopSpin]), or the three
 *  candidate rolls ([pickThreeCandidates]). In shared so iOS gets it too —
 *  see `docs/refactor/mapscreen/15-divergence-register.md` entry 9, where a
 *  candidate spin used to hang forever on iOS while Android bailed out after
 *  30 s. 20 s is the owner's call on #507: a slow backend took ~45 s to fail,
 *  longer than a rider will watch a spinner.
 *
 *  It is no shorter than a single roll's own HTTP read timeout, so the cap,
 *  not the request, is often what ends a stalled roll. Both spins therefore
 *  keep every roll that has already landed and return those when the cap
 *  fires; only a spin with nothing in hand fails with
 *  [spinTimeoutMessage]'s sentence. */
internal const val SPIN_TIMEOUT_MS = 20_000L

/** Runs [block] under the spin's one time cap; running out of time returns
 *  [onTimeout]'s answer instead. [onTimeout] is called only once the cap
 *  fires, so it can use what the spin learned on the way — a loop already in
 *  hand, or a server error to word a [SpinFailure] with — and throws that
 *  [SpinFailure] when there is nothing to return, so every caller, Swift
 *  included, gets a sentence instead of a raw `TimeoutCancellationException`. */
internal suspend fun <T> withSpinTimeout(
    onTimeout: () -> T,
    timeoutMs: Long = SPIN_TIMEOUT_MS,
    block: suspend CoroutineScope.() -> T,
): T {
    // withTimeoutOrNull, not withTimeout + catch: only this cap's own timer
    // comes back as null. A caller's enclosing timeout cancels the spin with
    // the same TimeoutCancellationException type, and must propagate as a
    // cancellation rather than be reworded as this spin running out of time.
    val finished = withTimeoutOrNull(timeoutMs) { Result.success(block()) }
        ?: return onTimeout()
    return finished.getOrThrow()
}

/** How long a road pick's name lookup may hold up its roll: a pick without a
 *  name still shows (as its coordinates), a spin left waiting on a slow
 *  geocoder does not show at all. */
private const val REVERSE_GEOCODE_TIMEOUT_MS = 6_000L

/** A spin's own dead end, worded for the rider ("No roads found within
 *  radius"), as opposed to a network or parser failure on the way. An
 *  [IOException] so every catch that moves on to the next server or sector
 *  still does; its own type so `spinFailureText` shows it verbatim instead of
 *  flattening it to "check your connection" (#487). */
class SpinFailure(message: String) : IOException(message)

/** One spin result awaiting a pick; [route] is null when the routing server
 *  couldn't be reached — the card then shows straight-line distance only.
 *  [twistiness] is [Curviness.forecastScore] of [route], worked out once when
 *  the candidate is rolled rather than on every recomposition of the card
 *  that shows it; null wherever the forecast is. */
data class RouteCandidate(
    val destination: LatLon,
    val name: String?,
    val route: RouteResult?,
    val straightLineMeters: Double,
    val twistiness: Double? = null,
)

/** The three candidates a spin offers, rolled concurrently — each is an
 *  independent draw plus its own routing request, so running them in sequence
 *  would take three times as long for no better result.
 *
 *  One roll failing is normal (a draw can land somewhere with no road, or its
 *  route request can time out) and must not sink the spin; only every roll
 *  failing does, and then the first real failure is what gets reported rather
 *  than a generic message. A cancellation is never a failed roll — it means
 *  the spin was called off, so it propagates instead of being counted.
 *  Running past [SPIN_TIMEOUT_MS] returns the candidates that had already
 *  landed — one stalled roll must not cost the rider the two that came back
 *  — and throws [SpinFailure] with [spinTimeoutMessage]'s sentence only when
 *  none had.
 *
 *  `@Throws(Exception::class)`: called directly from iosApp/Detour as
 *  `SpinPickerKt.pickThreeCandidates` — see [SyncClient.sync]'s doc for why
 *  `Exception` and not just `IOException`. [pickCandidate] below is not
 *  annotated: nothing outside this module calls it, only this function does,
 *  through `runCatching`. */
@Throws(Exception::class)
suspend fun pickThreeCandidates(
    config: ServerConfig,
    loc: LatLon,
    radiusMeters: Double,
    minRadiusMeters: Double,
    mode: TravelMode,
    poiKind: PoiKind,
    bearing: Double?,
    explored: ExploredArea,
): List<RouteCandidate> = pickThreeCandidates(serverUsable = config.usable) {
    pickCandidate(config, loc, radiusMeters, minRadiusMeters, mode, poiKind, bearing, explored)
}

/** [pickThreeCandidates] with its roll passed in, so the time cap and the
 *  keep-what-landed rule run in `commonTest` against fakes. Each candidate is
 *  recorded the moment its roll lands (the rolls run in parallel — on
 *  `Dispatchers.IO` on Android, hence the lock), and read only after the cap
 *  has cancelled and joined every roll. */
internal suspend fun pickThreeCandidates(
    serverUsable: Boolean,
    timeoutMs: Long = SPIN_TIMEOUT_MS,
    roll: suspend () -> RouteCandidate,
): List<RouteCandidate> {
    val landed = mutableListOf<RouteCandidate>()
    val landedLock = Mutex()
    return withSpinTimeout(
        onTimeout = {
            landed.toList().ifEmpty {
                throw SpinFailure(
                    spinTimeoutMessage(serverError = null, roundTrip = false, serverUsable = serverUsable),
                )
            }
        },
        timeoutMs = timeoutMs,
    ) {
        coroutineScope {
            val rolls = (1..3).map {
                async {
                    runCatching { roll() }.onSuccess { landedLock.withLock { landed += it } }
                }
            }.awaitAll()
            collectRolls(rolls)
        }
    }
}

/** The composition rule for a spin's three rolls, pulled out of
 *  [pickThreeCandidates] so it is testable without the network I/O
 *  [pickCandidate] does: a cancelled roll always propagates rather than
 *  being counted as a failure (see the class doc above), and only when every
 *  roll failed does the caller see an error — the first real one, not a
 *  generic message. */
internal fun collectRolls(rolls: List<Result<RouteCandidate>>): List<RouteCandidate> {
    rolls.forEach { roll ->
        val e = roll.exceptionOrNull()
        if (e is CancellationException) throw e
    }
    val found = rolls.mapNotNull { it.getOrNull() }
    if (found.isEmpty()) {
        throw rolls.firstNotNullOfOrNull { it.exceptionOrNull() }
            ?: SpinFailure("Failed to find a destination")
    }
    return found
}

/** Picks one destination candidate and eagerly routes to it, so the card list
 *  can show real road distance/ETA instead of a straight line. */
suspend fun pickCandidate(
    config: ServerConfig,
    loc: LatLon,
    radiusMeters: Double,
    minRadiusMeters: Double,
    mode: TravelMode,
    poiKind: PoiKind,
    bearing: Double?,
    explored: ExploredArea,
): RouteCandidate {
    val (dest, name) = if (poiKind != PoiKind.ROAD) {
        val poi = PoiRoulette.randomPoi(loc, radiusMeters, poiKind, bearing, explored, minRadiusMeters)
        poi.location to poi.name
    } else {
        // Own server snaps a random point to a road reachable in this mode's
        // profile; Overpass fallback below.
        val server = if (config.usable) {
            try {
                RoutingClient.randomRoadDestination(
                    config, loc, radiusMeters, bearing, explored, mode.ghProfile, minRadiusMeters)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } else null
        val d = server ?: RoadRoulette.randomRoadPoint(
            loc, radiusMeters, mode.highwayRegex, bearing, explored, minRadiusMeters)
        d to null
    }
    // A road pick has only coordinates (a POI always carries a name); look
    // its name up alongside the route request rather than after it, so the spin
    // waits for whichever is slower, not both (#503).
    val (route, label) = coroutineScope {
        val lookup = async { name ?: withTimeoutOrNull(REVERSE_GEOCODE_TIMEOUT_MS) { Geocoder.reverse(dest) } }
        val route = try {
            RoutingClient.route(config, loc, dest, mode.ghProfile, Settings.routePreferences())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        route to lookup.await()
    }
    return RouteCandidate(
        destination = dest,
        name = label,
        route = route,
        straightLineMeters = RoadRoulette.distanceMeters(loc, dest),
        twistiness = route?.let { Curviness.forecastScore(it) },
    )
}

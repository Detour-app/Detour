package com.jellemax.detour.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import okio.IOException

/** The phone's own bound, moved here so iOS gets it too — see
 *  `docs/refactor/mapscreen/15-divergence-register.md` entry 9: a spin
 *  against a wedged routing server used to hang forever on iOS while Android
 *  bailed out after 30s with a specific message. */
private const val SPIN_TIMEOUT_MS = 30_000L

/** One spin result awaiting a pick; [route] is null when the routing server
 *  couldn't be reached — the card then shows straight-line distance only. */
data class RouteCandidate(
    val destination: LatLon,
    val name: String?,
    val route: RouteResult?,
    val straightLineMeters: Double,
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
): List<RouteCandidate> = withTimeout(SPIN_TIMEOUT_MS) {
    coroutineScope {
        val rolls = (1..3).map {
            async {
                runCatching {
                    pickCandidate(
                        config, loc, radiusMeters, minRadiusMeters, mode, poiKind, bearing, explored)
                }
            }
        }.awaitAll()
        collectRolls(rolls)
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
            ?: IOException("Failed to find a destination")
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
    val route = try {
        RoutingClient.route(config, loc, dest, mode.ghProfile,
            Settings.avoidHighways.value, Settings.avoidSmallRoads.value)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    return RouteCandidate(
        destination = dest,
        name = name,
        route = route,
        straightLineMeters = RoadRoulette.distanceMeters(loc, dest),
    )
}

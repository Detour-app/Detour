package com.jellemax.detour.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.audio.NavVoice
import com.jellemax.detour.ble.BleNavServer
import com.jellemax.detour.ble.Minimap
import com.jellemax.detour.ble.MinimapKind
import com.jellemax.detour.ble.MinimapLine
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.NavAnnouncer
import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.RouteResult
import com.jellemax.detour.data.RoutingClient
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.map.NavPolicy
import com.jellemax.detour.map.RouteTileCache
import com.jellemax.detour.map.fetchNavRoute
import com.jellemax.detour.presentation.rerouteFailureText
import com.jellemax.detour.tracking.Fix
import com.jellemax.detour.tracking.TripTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the rider at [pos] is along [route]. A line through planned stops or
 * one that ends where it starts can ride a road twice, so on one the rider is
 * placed forward only, and a stop passed drops off every later reroute (#736,
 * #921); how far off the line they are
 * stays the global answer. The ride goes to [SpinResultHolder] every fix, so
 * a rotation carries on from it rather than from metre zero.
 */
private fun progressOn(s: MapScreenState, route: RouteResult, pos: LatLon): NavEngine.Progress? {
    val global = NavEngine.progress(route, pos) ?: return null
    val along = s.followPlanned(route.polyline, pos)
    SpinResultHolder.keepRide(s.viaRide)
    if (along == null) return global
    return NavEngine.progressAlong(route, along, global.offRouteMeters) ?: global
}

/**
 * Following the route: progress, arrival, reroute — and the external display,
 * whose two halves are here because the same characteristic carries both.
 *
 * The decision is not made here. Arrival and reroute are [NavPolicy]'s call,
 * shared with `car/NavScreen.kt`, so the two surfaces cannot word one maneuver
 * differently; the announcer's wording is [NavAnnouncer]'s, shared the same way.
 * What is here is the I/O those answers imply: push the display, speak, and
 * fetch a fresh route. The driven fraction is not drawn from here — the marker
 * loop in MapCamera.kt owns that seam, off its own eased position.
 *
 * The reroute request runs on the screen scope rather than in this effect,
 * which is keyed on `liveFix` — inline, the next GPS fix would cancel the
 * request that the previous one asked for.
 */
@Composable
internal fun MapNavigationSession(
    s: MapScreenState,
    scope: CoroutineScope,
    announcer: NavAnnouncer,
    announceAloud: (String) -> Unit,
    onArrive: () -> Unit,
    retained: RetainedMap,
) {
    // Derived here rather than drilled in: each was an alias for something the
    // holder or a Settings flow already owns (state-holders.md §14.1).
    val context = LocalContext.current
    val darkTheme = isAppDarkTheme(Settings.theme.collectAsStateWithLifecycle().value)
    // Hoisted: a CompositionLocal is only readable in a @Composable, and the
    // effect below needs it. One instance for the process, so holding it across
    // the effect's life cannot go stale (compose-state-hazards §2).
    val clock = LocalDriveClock.current
    val liveFix by TripTrackingService.lastFix.collectAsStateWithLifecycle()
    val mode by Settings.tripMode.collectAsStateWithLifecycle()
    val serverConfig = remember { RoutingServer.load() }
    // Both are called from effects keyed on liveFix, which restarts every
    // second; captured directly, each would freeze on its first composition.
    val announce by rememberUpdatedState(announceAloud)
    val arrive by rememberUpdatedState(onArrive)
    PlannedRouteTileCache(s, darkTheme)
    // Current speed for the external display when there's no route up —
    // BleNavServer.send() below covers the navigating case on the same
    // characteristic, so this only fires the other half of the time.
    LaunchedEffect(s.navigating, liveFix) {
        if (s.navigating) return@LaunchedEffect
        val fix = liveFix ?: return@LaunchedEffect
        BleNavServer.sendStats(
            context,
            currentSpeedKmh = fix.speedMps * 3.6,
            speedLimitKmh = retained.ambientSpeedLimitKmh,
        )
    }

    MapMinimapPush(liveFix, s, retained)

    // Follow the route while navigating: progress, arrival, reroute.
    LaunchedEffect(s.navigating, liveFix, s.route) {
        if (!s.navigating) {
            // A new session starts with no off-route run behind it.
            s.offRoute = null
            s.offRouteShown = false
            s.stoppedSinceMs = null
            s.rerouteAttempts = s.rerouteAttempts.endEpisode()
            return@LaunchedEffect
        }
        val fix = liveFix ?: return@LaunchedEffect
        val r = s.route ?: return@LaunchedEffect
        val pos = LatLon(fix.lat, fix.lon)
        val progress = progressOn(s, r, pos) ?: return@LaunchedEffect
        // The drive's clock, as in car/NavScreen.kt: the cooldown NavPolicy
        // applies is per drive, not per wall second.
        val now = clock.nowMs()
        // Keyed on the fix, so this effect restarting for a new s.route on the
        // same fix does not count it twice. Written with navProgress, so the
        // bar's "Off route" is the same confirmation decide() reroutes on.
        s.offRoute = NavPolicy.trackOffRoute(
            s.offRoute, progress.offRouteMeters, fix.accuracyMeters.toDouble(), fix.elapsedRealtimeMs, now)
        s.offRouteShown = NavPolicy.offRouteConfirmed(s.offRoute, now)
        s.stoppedSinceMs = NavPolicy.trackStopped(s.stoppedSinceMs, fix.speedMps, now)
        s.rerouteAttempts = s.rerouteAttempts.afterFix(s.offRoute)
        s.navProgress = progress
        // No setDrivenFraction here: the marker's frame loop (MapCamera.kt) fades
        // the road behind you off its own eased position. A per-fix write would
        // fight that, dragging the seam back to the raw fix once a second under a
        // marker that has already moved on.
        BleNavServer.send(context, progress, currentSpeedKmh = fix.speedMps * 3.6)

        // Same policy the head unit and iOS read, so the three surfaces cannot
        // word one maneuver three ways.
        announcer.onProgress(progress, fix.speedMps)?.let { announce(it) }

        // Arrival and reroute are NavPolicy's call, shared with car/NavScreen.kt.
        val dest = s.destination
        when (NavPolicy.decide(
            progress = progress,
            destination = toDestination(dest, pos, s.stoppedSinceMs),
            stopsAhead = s.vias.isNotEmpty(),
            offRoute = s.offRoute,
            rerouting = s.rerouting,
            attempts = s.rerouteAttempts,
            nowMs = now,
        )) {
            // Point-to-point only; loops end back at the start on their own.
            NavPolicy.Decision.Arrived -> {
                arrive()
                return@LaunchedEffect
            }
            // Off route → fresh route to the destination, through any planned
            // stops not yet passed (#736). Launched on the screen scope so the
            // next GPS fix doesn't cancel the request; loops keep their drawn
            // line (rerouting a loop would change the whole trip).
            NavPolicy.Decision.Reroute -> {
                val target = dest ?: return@LaunchedEffect // Reroute implies a destination
                s.rerouting = true
                // Spoken once per off-route episode: offline, every retry
                // saying it was the bug (#1010). The bar says why instead.
                if (s.rerouteAttempts.announce) announce(announcer.rerouting())
                s.rerouteAttempts = s.rerouteAttempts.started(now)
                // Continue in the direction of travel: keeps the fresh line from
                // opening with a U-turn, and the penalty scales up with speed so
                // it never asks for one on a 70/90 road. Skipped while parked or
                // before GPS has a course.
                val heading = NavPolicy.headingHint(fix.bearingDeg, fix.speedMps)
                scope.launch {
                    try {
                        val fresh = fetchNavRoute(serverConfig, pos, target, mode, heading, s.vias)
                        s.route = fresh
                        s.rerouteAttempts = s.rerouteAttempts.succeeded()
                        // #737: the new line may leave the corridor cached at the start,
                        // and this is the one moment there is signal to extend it.
                        RouteTileCache.prefetch(context, fresh.polyline, darkTheme)
                        // Instruction indices belong to the old polyline; start
                        // the new line's prompts from scratch.
                        announcer.routeChanged()
                    } catch (e: Exception) {
                        // inline: ambient nav state — the route bar already says
                        // "Off route"; a snackbar mid-ride has no moment to announce.
                        // Stay on the old line; retried after a cooldown that
                        // grows per failure (NavPolicy.rerouteCooldownMs).
                        s.rerouteAttempts = s.rerouteAttempts.failed(rerouteFailureText(e))
                    } finally {
                        s.rerouting = false
                    }
                }
            }
            NavPolicy.Decision.Continue -> {}
        }
    }
}

/** Where the rider at [pos] is relative to [dest] for [NavPolicy.decide]'s
 *  parked arrival (#1011); null while there is no destination (a loop). */
private fun toDestination(dest: LatLon?, pos: LatLon, stoppedSinceMs: Long?): NavPolicy.ToDestination? =
    dest?.let { NavPolicy.ToDestination(RoadRoulette.distanceMeters(pos, it), stoppedSinceMs) }

/**
 * #737: a route on the map is the moment to cache its corridor, while the rider
 * is still deciding and has signal, not only once Start is pressed. Start
 * (MapScreen) and reroute ([MapNavigationSession]) fetch their own, so this only
 * covers the planned line: a picked spin, a loop, a saved route awaiting Go.
 */
@Composable
private fun PlannedRouteTileCache(s: MapScreenState, darkTheme: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(s.route) {
        val line = s.route?.polyline ?: return@LaunchedEffect
        if (!s.navigating) RouteTileCache.prefetch(context, line, darkTheme)
    }
}

/**
 * The external display's minimap (#1070), one frame per [fix], navigating or not. Takes the
 * map screen's own fix rather than collecting [TripTrackingService.lastFix] again. The route
 * and the nearby roads are read per fix, not keyed: both holders' fields are snapshot state.
 */
@Composable
internal fun MapMinimapPush(fix: Fix?, s: MapScreenState, retained: RetainedMap) {
    val context = LocalContext.current
    // Survives a stop: a standstill fix's bearing is noise.
    var heading by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(fix) {
        if (fix == null || !Settings.externalDisplayEnabled.value) return@LaunchedEffect
        if (fix.speedMps > MINIMAP_HEADING_MIN_MPS) fix.bearingDeg?.let { heading = it.toDouble() }
        // ponytail: roads are the speed-limit ways already fetched for the sign, so a road
        // without a maxspeed tag isn't drawn; a dedicated roads fetch if that shows.
        val lines = retained.limitState.ways.map {
            MinimapLine(if (it.kmh >= MINIMAP_MAJOR_KMH) MinimapKind.MAJOR else MinimapKind.MINOR, it.points)
        } + listOfNotNull(s.route?.let { MinimapLine(MinimapKind.ROUTE, it.polyline) })
        val facing = heading
        val frame = withContext(Dispatchers.Default) {
            Minimap.encode(LatLon(fix.lat, fix.lon), facing, Minimap.radiusFor(fix.speedMps), lines)
        }
        BleNavServer.sendMap(context, frame)
    }
}

/** Below this a fix's bearing is noise, so the minimap keeps the last good heading. */
private const val MINIMAP_HEADING_MIN_MPS = 1.5
/** A posted limit at or above this draws the road as major on the minimap. */
private const val MINIMAP_MAJOR_KMH = 70.0

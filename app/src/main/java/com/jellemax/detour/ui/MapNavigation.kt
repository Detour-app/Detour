package com.jellemax.detour.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.audio.NavVoice
import com.jellemax.detour.ble.BleNavServer
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.NavAnnouncer
import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.data.RoutingClient
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.map.NavPolicy
import com.jellemax.detour.map.fetchNavRoute
import com.jellemax.detour.tracking.Fix
import com.jellemax.detour.tracking.ReplayClock
import com.jellemax.detour.tracking.TripTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
) {
    // Derived here rather than drilled in: each was an alias for something the
    // holder or a Settings flow already owns (state-holders.md §14.1).
    val context = LocalContext.current
    val liveFix by TripTrackingService.lastFix.collectAsStateWithLifecycle()
    val mode by Settings.tripMode.collectAsStateWithLifecycle()
    val serverConfig = remember { RoutingServer.load() }
    // Both are called from effects keyed on liveFix, which restarts every
    // second; captured directly, each would freeze on its first composition.
    val announce by rememberUpdatedState(announceAloud)
    val arrive by rememberUpdatedState(onArrive)
    // Current speed for the external display when there's no route up —
    // BleNavServer.send() below covers the navigating case on the same
    // characteristic, so this only fires the other half of the time.
    LaunchedEffect(s.navigating, liveFix) {
        if (s.navigating) return@LaunchedEffect
        val fix = liveFix ?: return@LaunchedEffect
        BleNavServer.sendStats(context, currentSpeedKmh = fix.speedMps * 3.6)
    }

    // Follow the route while navigating: progress, arrival, reroute.
    LaunchedEffect(s.navigating, liveFix, s.route) {
        if (!s.navigating) return@LaunchedEffect
        val fix = liveFix ?: return@LaunchedEffect
        val r = s.route ?: return@LaunchedEffect
        val pos = LatLon(fix.lat, fix.lon)
        val progress = NavEngine.progress(r, pos) ?: return@LaunchedEffect
        s.navProgress = progress
        // No setDrivenFraction here: the marker's frame loop (MapCamera.kt) fades
        // the road behind you off its own eased position. A per-fix write would
        // fight that, dragging the seam back to the raw fix once a second under a
        // marker that has already moved on.
        BleNavServer.send(context, progress, currentSpeedKmh = fix.speedMps * 3.6)

        // Same policy the head unit and iOS read, so the three surfaces cannot
        // word one maneuver three ways.
        announcer.onProgress(progress.nextInstruction, progress.distanceToTurnMeters)
            ?.let { announce(it) }

        // Arrival and reroute are NavPolicy's call, shared with car/NavScreen.kt.
        val dest = s.destination
        // The drive's clock, as in car/NavScreen.kt: the cooldown NavPolicy
        // applies is per drive, not per wall second.
        val now = ReplayClock.nowMs()
        when (NavPolicy.decide(
            progress = progress,
            hasDestination = dest != null,
            rerouting = s.rerouting,
            lastRerouteMs = s.lastRerouteMs,
            nowMs = now,
        )) {
            // Point-to-point only; loops end back at the start on their own.
            NavPolicy.Decision.Arrived -> {
                arrive()
                return@LaunchedEffect
            }
            // Off route → fresh route to the destination. Launched on the screen
            // scope so the next GPS fix doesn't cancel the request; loops keep
            // their drawn line (rerouting a loop would change the whole trip).
            NavPolicy.Decision.Reroute -> {
                val target = dest ?: return@LaunchedEffect // Reroute implies a destination
                s.rerouting = true
                s.lastRerouteMs = now
                announce(announcer.rerouting())
                scope.launch {
                    try {
                        s.route = fetchNavRoute(serverConfig, pos, target, mode)
                        // Instruction indices belong to the old polyline; start
                        // the new line's prompts from scratch.
                        announcer.routeChanged()
                    } catch (e: Exception) {
                        // stay on the old line; retried after the cooldown
                    } finally {
                        s.rerouting = false
                    }
                }
            }
            NavPolicy.Decision.Continue -> {}
        }
    }
}

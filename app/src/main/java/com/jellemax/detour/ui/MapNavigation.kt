package com.jellemax.detour.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.jellemax.detour.audio.NavVoice
import com.jellemax.detour.ble.BleNavServer
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.NavAnnouncer
import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.data.RoutingClient
import com.jellemax.detour.data.ServerConfig
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.map.NavPolicy
import com.jellemax.detour.map.fetchNavRoute
import com.jellemax.detour.tracking.Fix
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
    context: android.content.Context,
    liveFix: Fix?,
    announcer: NavAnnouncer,
    serverConfig: ServerConfig,
    mode: TravelMode,
    announceAloud: (String) -> Unit,
    onArrived: () -> Unit,
) {
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
            ?.let { announceAloud(it) }

        // Arrival and reroute are NavPolicy's call, shared with car/NavScreen.kt.
        val dest = s.destination
        val now = System.currentTimeMillis()
        when (NavPolicy.decide(
            progress = progress,
            hasDestination = dest != null,
            rerouting = s.rerouting,
            lastRerouteMs = s.lastRerouteMs,
            nowMs = now,
        )) {
            // Point-to-point only; loops end back at the start on their own.
            NavPolicy.Decision.Arrived -> {
                onArrived()
                return@LaunchedEffect
            }
            // Off route → fresh route to the destination. Launched on the screen
            // scope so the next GPS fix doesn't cancel the request; loops keep
            // their drawn line (rerouting a loop would change the whole trip).
            NavPolicy.Decision.Reroute -> {
                val target = dest ?: return@LaunchedEffect // Reroute implies a destination
                s.rerouting = true
                s.lastRerouteMs = now
                announceAloud(announcer.rerouting())
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

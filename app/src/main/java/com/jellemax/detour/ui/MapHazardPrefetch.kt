package com.jellemax.detour.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.SpeedCameras
import com.jellemax.detour.drive.CameraPrefetch
import com.jellemax.detour.drive.SpeedLimitTracker
import com.jellemax.detour.tracking.TripTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * The two Overpass prefetches: the ambient speed-limit sign, and the speed
 * cameras and trajectcontrole sections ahead.
 *
 * The whole policy — the throttle, the local snap, the three-miss clear, the
 * fetch margin — is `SpeedLimitTracker`'s and `CameraPrefetch`'s in
 * `shared/…/drive/`, where it lives with tests and is shared with the head
 * unit. The I/O is here because `commonMain` has no Dispatchers: the machine
 * says a fetch is wanted, and this performs it.
 *
 * Each fetch runs in its own coroutine behind an `isActive` guard rather than
 * inline in the collector. `lastFix` is a conflating StateFlow and its
 * collector is sequential, so awaiting a slow Overpass mirror inline suspended
 * the collector and every fix that landed meanwhile was conflated away — the
 * sign stopped tracking the road for as long as the mirror took. The guard is
 * what now stops two fetches overlapping, which the inline await used to do by
 * accident.
 */
@Composable
internal fun MapHazardPrefetch(
    s: MapScreenState,
    retained: RetainedMap,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
) {
    // Ambient speed-limit sign while just driving (not navigating). The whole
    // policy — the prefetch throttle, the local snap and the three-miss clear —
    // is SpeedLimitTracker's (shared/…/drive/), where it lives with its tests and
    // is shared with the head unit. The I/O below is ours: commonMain has no
    // Dispatchers, so the machine says a fetch is wanted and we perform it.
    LaunchedEffect(s.navigating) {
        // Crossing into or out of navigation invalidates whatever sign we hold;
        // reset() says why, and keeps the prefetched area. Clear it and let the
        // next snap re-establish it, the way the car has since it shipped
        // (car/SpinScreen.kt's onStart).
        //
        // Guarded, because this effect also restarts whenever the composition
        // is recreated — which is every return to the map — and the effect
        // cannot tell that apart from a real crossing. Unguarded, the sign went
        // blank on every trip to the Hub and stayed blank until the next
        // prefetch and snap re-established it.
        if (retained.limitResetForNavigating != s.navigating) {
            retained.limitResetForNavigating = s.navigating
            retained.limitState = SpeedLimitTracker.reset(retained.limitState)
            retained.ambientSpeedLimitKmh = null
        }
        if (s.navigating) return@LaunchedEffect
        TripTrackingService.lastFix.collect { fix ->
            fix ?: return@collect
            // Not just the machine's own floor: returning here is what also keeps
            // a parked phone from prefetching.
            if (fix.speedMps < SpeedLimitTracker.MIN_MPS) return@collect
            val pos = LatLon(fix.lat, fix.lon)
            val now = System.currentTimeMillis()
            if (SpeedLimitTracker.needsWays(retained.limitState, pos, now) &&
                s.speedLimitFetchJob?.isActive != true
            ) {
                // The refresh runs in its own coroutine. lastFix is a StateFlow
                // and this collector is sequential, so awaiting a mirror *here*
                // suspended the collector — and every fix that landed meanwhile
                // was conflated away, so the snap below, the miss counter and
                // the sign all stopped tracking the road for as long as Overpass
                // took. A mirror having a slow ten seconds is normal; a posted
                // limit that stops following the road for ten seconds is not.
                // The isActive guard is what now stops two fetches overlapping,
                // which is the job the inline await used to do by accident.
                // Same fix as car/SpinScreen.kt's updateSpeedLimit.
                retained.limitState = SpeedLimitTracker.fetchStarted(retained.limitState, now)
                s.speedLimitFetchJob = scope.launch {
                    // runCatching because this no longer runs inside the
                    // collector: an exception escaping here would cancel
                    // `scope`, i.e. every coroutine this screen owns, where
                    // inline it only killed this one collector. speedLimitWays
                    // now catches the SerializationException a busy Overpass's
                    // HTML error page produces as well as the IOException — the
                    // hazard SpeedCameras.near documents — so the runCatching is
                    // belt and braces rather than the only guard it used to be.
                    //
                    // getOrNull, not getOrDefault(emptyList()): speedLimitWays
                    // returns null for both of those and an empty list only for
                    // an area with no tagged road. The tracker backs off on the
                    // first and not on the second, and collapsing them here
                    // would give that distinction away.
                    val ways = runCatching {
                        withContext(Dispatchers.IO) { RoadRoulette.speedLimitWays(pos) }
                    }.getOrNull()
                    retained.limitState = SpeedLimitTracker.withWays(retained.limitState, ways, pos)
                }
            }
            retained.limitState = SpeedLimitTracker.onFix(
                state = retained.limitState,
                at = pos,
                headingDeg = fix.bearingDeg?.toDouble(),
                speedMps = fix.speedMps,
            )
            retained.ambientSpeedLimitKmh = retained.limitState.limitKmh
        }
    }

    // Speed cameras + trajectcontrole sections from Overpass (OSM). Prefetched
    // for a wide circle, refreshed only as you near the edge of what you hold,
    // so there's no request per fix. A null result is a network blip: keep the
    // markers we have and let CameraPrefetch's backoff decide when to try again,
    // instead of flickering them off.
    LaunchedEffect(Unit) {
        // The cadence — the margin, the throttle and the backoff after a run of
        // refusals — is CameraPrefetch's (shared/…/drive/), so the head unit
        // keeps the same one. What stays here is the I/O and the two holders it
        // fills.
        //
        // This used to hold `prefetch` in a coroutine-local, on the grounds
        // that "this effect is keyed on Unit and never restarts, so a local has
        // nothing to lose". That was wrong about the restart: Unit keeps it
        // from restarting on a *recomposition*, but leaving the map disposes
        // the composition outright, so every return re-ran it with a fresh
        // State — re-fetching an area already held, and losing the backoff that
        // exists to stop hammering a refusing mirror.
        var prefetch = retained.cameraPrefetch
        var fetchJob: Job? = null
        TripTrackingService.lastFix.collect { fix ->
            fix ?: return@collect
            val pos = LatLon(fix.lat, fix.lon)
            val now = System.currentTimeMillis()
            if (CameraPrefetch.needsFetch(prefetch, pos, now) &&
                fetchJob?.isActive != true
            ) {
                // Own coroutine, isActive guard, runCatching: same reasoning as
                // the ambient limit above, and as car/NavScreen.kt:348-379,
                // which is where this was diagnosed. This collector feeds the
                // section machine, so suspending it also stalled the running
                // average's own fix stream.
                prefetch = CameraPrefetch.fetchStarted(prefetch, now)
                retained.cameraPrefetch = prefetch
                fetchJob = scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) { SpeedCameras.near(pos) }
                    }.getOrNull()
                    prefetch = CameraPrefetch.fetched(prefetch, result, pos)
                    retained.cameraPrefetch = prefetch
                    // Only the markers are ours to fold in; a null result keeps
                    // the ones we hold rather than flickering them off.
                    if (result != null) {
                        retained.speedCameras = result.cameras
                        retained.speedSections = result.sections
                    } else if (
                        prefetch.failures == 1 &&
                        retained.speedCameras.isEmpty() &&
                        retained.speedSections.isEmpty()
                    ) {
                        // "Couldn't load" and "none around here" draw the same
                        // empty map, and that is the one thing the rider cannot
                        // work out for themselves. Said once per run of
                        // failures (CameraPrefetch counts them, and backs off,
                        // so repeating it every retry would be noise) and only
                        // while we hold nothing — markers on screen are their
                        // own answer. Its own coroutine because showSnackbar
                        // suspends until dismissed, and this job is the
                        // in-flight guard above.
                        scope.launch {
                            snackbarHostState.showSnackbar("Couldn't load speed cameras")
                        }
                    }
                }
            }
        }
    }
}

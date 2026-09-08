package com.jellemax.detour.tracking

import android.location.Location
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.tracking.TripTrackingService.LocationMode

/**
 * Where [TripTrackingService]'s fixes come from: "hand me positions at roughly
 * this cadence, and tell me when the OS takes the permission away".
 *
 * A port, so a replay can feed the app without going through the platform at
 * all (#306). The mock-location rig it exists beside pushes fixes into Play
 * Services' fused provider and lets fused deliver them, which costs four things
 * that are all the platform's rather than ours:
 *
 *  - **Fused blends the real position into the mock stream.** Measured on a
 *    CPH2449 over one 20-second replay: 91 mock fixes and 4 real ones, ~4 km
 *    off-route. Four is enough — each breaks the run of fast fixes the start
 *    detector needs, and once a trip is running a fix kilometres away keeps
 *    resetting the moving clock so it can never end (#47).
 *  - **Fused thins the stream** to 5-9 fixes a second whatever the replay
 *    factor, so 5x is the fastest lossless replay and 50x delivers 18 % of a
 *    route.
 *  - **The designation is manual on some devices** — `appops set
 *    android:mock_location` is refused on a CPH2449 by OEM policy.
 *  - **Nothing can ride along with a fix**: fused delivers every mocked
 *    `Location` with `extras = null`.
 *
 * Through this port none of those exist, because the real position is never
 * produced rather than filtered out afterwards.
 *
 * **Both rigs are kept, and are chosen per run.** They answer different
 * questions, which is why [ReplayFixGate] and [ReplayMode] stay: this port
 * exercises the app's own arithmetic at rates the platform cannot deliver, and
 * the mock provider exercises the leg this bypasses — provider selection, the
 * accuracy gate, `elapsedRealtimeNanos` freshness, and fused's own blending and
 * thinning. Two of the defects behind #306 lived in exactly that leg, and only
 * the platform rig can see them.
 *
 * Two implementations plus a test fake, which is what earns an interface under
 * `docs/guidelines/multiplatform.md` §5.2. [DriveClock] is the sibling port from
 * #307, and [LocationSources] chooses between the implementations the same way
 * [DriveClocks] does.
 */
internal interface LocationSource {

    /**
     * (Re)request updates matching [mode], if that differs from the active
     * request. Returns whether a new request actually went out, so the caller
     * knows whether anything — the notification, in practice — needs refreshing
     * along with it.
     */
    fun ensureFor(mode: LocationMode): Boolean

    /** Drop the active request entirely; the service is stopping. */
    fun stop()

    /**
     * One-shot "where is the rider now", for a caller that wants a position
     * rather than a subscription — the map centring itself on open, most of all.
     *
     * On the port this has to come from the source rather than from the platform,
     * and that is not tidiness. `MapScreen.fetchLocation` asked fused directly for
     * a *fresh* fix and then moved the camera to it. Under the mock-provider rig
     * that agreed with the replay, because the mock reaches fused; through the
     * port it does not, so the camera was yanked to wherever the phone physically
     * is, mid-replay.
     */
    suspend fun currentLatLon(): LatLon?

    /**
     * Last position this source knows about, freshest first, for arming the park
     * geofence. Null until the very first fix. [fallback] is the service's own
     * last raw [Location] — kept there, since it backs more than this one call —
     * for a moment before the first fix lands.
     *
     * Implemented here rather than per adapter: it reads the fix stream's own
     * published state, which is the same whichever source filled it, so an
     * override would be a second copy of one expression.
     */
    fun lastKnownLatLon(fallback: Location?): Pair<Double, Double>? =
        TripTrackingService.lastFix.value?.let { it.lat to it.lon }
            ?: fallback?.let { it.latitude to it.longitude }
}

/**
 * What a [LocationSource] hands back: one delivery, which may carry several
 * fixes.
 *
 * A batch rather than a fix at a time, because the difference is load-bearing.
 * `IDLE` requests are batched, so a burst arrives in one callback describing
 * minutes of driving, and the service re-evaluates its mode and its dormancy
 * **once per burst** rather than once per fix — see the call site.
 */
internal fun interface LocationBatchListener {
    fun onFixes(locations: List<Location>)
}

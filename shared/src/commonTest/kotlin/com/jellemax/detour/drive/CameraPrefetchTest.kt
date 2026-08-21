package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.SpeedCameras
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The camera/section prefetch cadence, and the backoff that is the reason this
 * file exists.
 *
 * The margin and throttle halves are **characterisation**: transcribed from the
 * two inline copies this replaced — `ui/MapScreen.kt`'s literals `1000.0` and
 * `15_000`, and `car/NavScreen.kt`'s `CAMERA_FETCH_MARGIN_M` /
 * `CAMERA_FETCH_THROTTLE_MS` — which agreed on both values. Those tests should
 * not change.
 *
 * The backoff half is **new behaviour**, and [aRunOfRefusalsCostsSingleFiguresOfRequestsNotForty]
 * is its acceptance criterion. Neither copy backed off: a failed fetch leaves
 * the centre where it was, so the distance trigger stays true and the throttle
 * becomes a retry timer. maxke24/Detour#22 counted roughly 143 Overpass requests
 * out of one 17 km replay, of which 7 could have succeeded, and being refused
 * takes the markers, the sections *and* the ambient speed-limit sign down for
 * hours — which is why that issue still cannot be finished from this IP.
 *
 * No Android APIs, no clock, no file access: runs on JVM and Kotlin/Native both.
 */
class CameraPrefetchTest {

    /** Epoch millis. Realistic, because the machine subtracts timestamps and a
     *  zero base would hide a stamp that never happened. */
    private val t0 = 1_700_000_000_000L

    private val here = LatLon(50.85, 4.35)

    /** [meters] from [here] along compass [bearingDeg]. `offset` projects flat
     *  and `distanceMeters` is a haversine, so what comes back measures
     *  0.998876x this — the fixtures below sit ~100 m clear of the boundary on
     *  both sides so that difference cannot decide a test. */
    private fun at(meters: Double, bearingDeg: Double = 90.0) =
        RoadRoulette.offset(here, meters, bearingDeg * PI / 180.0)

    /** An area held around [here], fetched at [t0] and nothing refused since. */
    private fun holding() = CameraPrefetch.State(center = here, lastFetchMs = t0, failures = 0)

    private val empty = SpeedCameras.Result(emptyList(), emptyList())

    // ---- the cadence, unchanged from the two copies this replaced ----------

    /** False inside `PREFETCH_RADIUS_M - FETCH_MARGIN_M` (4000.0 - 1000.0) of
     *  what we hold, true outside it. */
    @Test
    fun needsFetchOnlyOnceYouNearTheEdgeOfWhatYouHold() {
        val held = holding().copy(lastFetchMs = 0L)
        assertFalse(CameraPrefetch.needsFetch(held, at(2_900.0), t0))
        assertTrue(CameraPrefetch.needsFetch(held, at(3_100.0), t0))
    }

    /** False inside [CameraPrefetch.FETCH_THROTTLE_MS] of the last attempt
     *  however far you have moved. The boundary, stated: exactly the throttle
     *  does **not** fetch, because the test is `>`. */
    @Test
    fun theThrottleHoldsHoweverFarYouHaveMoved() {
        val held = holding()
        val faraway = at(9_000.0)
        assertFalse(CameraPrefetch.needsFetch(held, faraway, t0 + 1))
        assertFalse(
            CameraPrefetch.needsFetch(held, faraway, t0 + CameraPrefetch.FETCH_THROTTLE_MS),
        )
        assertTrue(
            CameraPrefetch.needsFetch(held, faraway, t0 + CameraPrefetch.FETCH_THROTTLE_MS + 1),
        )
    }

    /** True on a virgin state: a null centre must mean "no area held", not
     *  "distance zero". Both copies spelled that `?: Double.MAX_VALUE`. */
    @Test
    fun aVirginStateNeedsAFetchWhereverItIs() {
        assertTrue(CameraPrefetch.needsFetch(CameraPrefetch.State(), here, t0))
    }

    /** The stamp goes on the *attempt*, not the completion, so a failing mirror
     *  is throttled like a succeeding one. Nothing else moves. */
    @Test
    fun theThrottleIsStampedOnAttemptNotOnCompletion() {
        val virgin = CameraPrefetch.State()
        val started = CameraPrefetch.fetchStarted(virgin, t0)
        assertEquals(virgin.copy(lastFetchMs = t0), started)
        assertFalse(CameraPrefetch.needsFetch(started, at(9_000.0), t0 + 5_000L))
    }

    // ---- what an answer does ----------------------------------------------

    /** A refusal must not move the centre: moving it would claim we hold an area
     *  we never received. It costs one failure, and nothing else. */
    @Test
    fun aRefusalMovesNeitherTheCentreNorAnythingButTheFailureCount() {
        val held = holding()
        val after = CameraPrefetch.fetched(held, null, at(2_000.0))
        assertEquals(held.copy(failures = 1), after)
    }

    /** An empty answer is a success — an area with no cameras is a fact about
     *  the area, not a blip — so it moves the centre and clears the backoff.
     *  Getting this backwards would back off hardest exactly where the query is
     *  cheapest and the answer is right. */
    @Test
    fun anEmptyAnswerStillMovesTheCentreAndClearsTheBackoff() {
        val moved = at(2_000.0)
        val after = CameraPrefetch.fetched(holding().copy(failures = 4), empty, moved)
        assertEquals(moved, after.center)
        assertEquals(0, after.failures)
        // The attempt stamp is the attempt's, not the completion's.
        assertEquals(t0, after.lastFetchMs)
    }

    // ---- the backoff -------------------------------------------------------

    /** Doubling from the throttle, capped, and back to the throttle on zero.
     *  Spelled out rather than derived, so a change to either constant has to
     *  come and edit the number it changed. */
    @Test
    fun theRetryDelayDoublesPerConsecutiveFailureAndThenStops() {
        assertEquals(15_000L, CameraPrefetch.retryDelayMs(0))
        assertEquals(30_000L, CameraPrefetch.retryDelayMs(1))
        assertEquals(60_000L, CameraPrefetch.retryDelayMs(2))
        assertEquals(120_000L, CameraPrefetch.retryDelayMs(3))
        assertEquals(240_000L, CameraPrefetch.retryDelayMs(4))
        // 15 s << 5 would be 480 s; the cap is what it actually gets.
        assertEquals(CameraPrefetch.MAX_BACKOFF_MS, CameraPrefetch.retryDelayMs(5))
        assertEquals(CameraPrefetch.MAX_BACKOFF_MS, CameraPrefetch.retryDelayMs(50))
        // No negative count can reach here, but a Long shifted by one would be a
        // silent 2x rather than a crash, so the floor is pinned too.
        assertEquals(15_000L, CameraPrefetch.retryDelayMs(-1))
    }

    /** One answer is enough: the very next fetch is back on the plain throttle,
     *  so a tunnel does not cost you the rest of the drive. */
    @Test
    fun oneSuccessPutsTheCadenceStraightBackToNormal() {
        val burned = holding().copy(failures = 5)
        val recovered = CameraPrefetch.fetched(burned, empty, at(2_000.0))
        assertEquals(0, recovered.failures)
        assertEquals(
            CameraPrefetch.FETCH_THROTTLE_MS,
            CameraPrefetch.retryDelayMs(recovered.failures),
        )
    }

    /**
     * The acceptance criterion for the whole file, in the units #22 measured.
     *
     * Ten minutes of 1 Hz fixes driving away from a centre that never advances,
     * with every answer refused — the exact shape of a rate-limited IP, and
     * about what a 17 km motorway replay is. Flat-throttled that is 38 attempts
     * — 15 s compared with `>`, so one every 16 — each one trying *every* mirror
     * in `RoadRoulette.rawQuery`; backed off it is five. The old number is
     * asserted alongside the new one so this test says what it bought, not just
     * that it is small.
     */
    @Test
    fun aRunOfRefusalsCostsSingleFiguresOfRequestsNotForty() {
        val tenMinutes = 600
        var state = CameraPrefetch.State()
        var attempts = 0
        var flatThrottled = 0
        var flatLastFetchMs = 0L
        for (second in 0 until tenMinutes) {
            val now = t0 + second * 1_000L
            // Driving away, so the distance trigger is true on every fix — which
            // it also is when standing still with no area held.
            val pos = at(10_000.0 + second)
            if (CameraPrefetch.needsFetch(state, pos, now)) {
                attempts++
                state = CameraPrefetch.fetchStarted(state, now)
                state = CameraPrefetch.fetched(state, null, pos)
            }
            if (now - flatLastFetchMs > CameraPrefetch.FETCH_THROTTLE_MS) {
                flatThrottled++
                flatLastFetchMs = now
            }
        }
        assertEquals(38, flatThrottled)
        assertEquals(5, attempts)
        // And the centre is still null, so nothing pretended to hold an area.
        assertEquals(null, state.center)
    }
}

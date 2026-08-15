package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Characterises [SpeedLimitTracker] - the ambient speed-limit sign's prefetch
 * throttle, its local snap and its three-miss clear hysteresis - transcribed from
 * `car/SpinScreen.kt`'s `updateSpeedLimit`, which is the better of the two copies
 * (named constants, fetch off the collector: `detour-shared-core` §6), with the
 * phone's `ui/MapScreen.kt` version diffed against it. After stage 0's task 0d the
 * two structures agree as well: the phone's fetch is its own `scope.launch` job
 * behind an `isActive` guard, exactly as the head unit's has always been.
 *
 * Five calls rather than one step function because the fetch cannot come along:
 * commonMain has no `Dispatchers.*`, and an `onFix` taking a fetcher lambda would
 * be commonMain's first dependency-inversion interface. So "should I fetch", "I
 * have started fetching", "here is what came back", "here is a fix" and "I have
 * crossed the navigation boundary" are five calls, and the ordering between the
 * first two is load-bearing - see [theThrottleIsStampedOnAttemptNotOnCompletion].
 *
 * The failure this guards against is a sign that flickers off on one untagged
 * fix, or one that never clears when the limit really ended. Both are only
 * visible while riding.
 *
 * **This file is the only verification this extraction has.** The stage's replay
 * A/B gate for machine 3 was deliberately traded away for completion: both app
 * Overpass mirrors refuse this host and the one healthy public mirror is a
 * Switzerland-only extract, so no Belgian fixture can be replayed. Nothing below
 * is a substitute for having watched the sign on a road - what it does buy is
 * that the transitions are now drivable offline. Coverage that no replay would
 * have reached either way is named on the tests that carry it:
 * [resetClearsTheSignAndTheMissesAndKeepsTheHeldArea] (no replay starts
 * navigation) and [theThrottleIsStampedOnAttemptNotOnCompletion] (a failing
 * mirror is the case, and a recording of one has no sign to compare).
 *
 * No Android APIs and no clock: runs on JVM and Kotlin/Native both.
 */
class SpeedLimitTrackerTest {

    /** Epoch millis, because `needsWays` subtracts timestamps and a zero base
     *  would hide a sign error. */
    private val t0 = 1_700_000_000_000L
    private val here = LatLon(50.85, 4.35)

    /** [meters] from [from] along compass [bearingDeg]. `RoadRoulette.offset`
     *  takes radians and projects at a flat 111 320 m/deg, while `distanceMeters`
     *  is a haversine at r = 6 371 km, i.e. 111 194.9 m/deg - so what
     *  `distanceMeters` measures is **0.998876x** this, one part in 890 *short*
     *  rather than long. Measured, not derived: 900 m of `offset` due east of
     *  [here] reads 898.989 m and 1 100 m reads 1 098.764 m, which is why the
     *  throttle fixtures below sit ~100 m clear of the 1 000 m refetch edge
     *  rather than riding it. */
    private fun at(from: LatLon, meters: Double, bearingDeg: Double) =
        RoadRoulette.offset(from, meters, bearingDeg * PI / 180.0)

    /** A north-south way straight through [here] - `distanceToSegmentMeters`
     *  reads 0.0 - so it aligns with a heading of 0deg and snaps from [here]. */
    private val alongWay = RoadRoulette.SpeedLimitWay(
        kmh = 70.0,
        points = listOf(at(here, 400.0, 180.0), at(here, 400.0, 0.0)),
    )

    /** An east-west way 5.0 m north of [here]: **nearer** than [parallelWay]
     *  below, and 90deg off a heading of 0deg, so it loses the alignment test. */
    private val crossingWay = RoadRoulette.SpeedLimitWay(
        kmh = 30.0,
        points = listOf(
            at(at(here, 5.0, 0.0), 400.0, 270.0),
            at(at(here, 5.0, 0.0), 400.0, 90.0),
        ),
    )

    /** A north-south way 12.0 m east of [here]: **farther** than [crossingWay]
     *  and aligned with a heading of 0deg. Both are inside
     *  `RoadRoulette`'s 25 m snap radius, so which one wins is decided by the
     *  alignment test and nothing else. */
    private val parallelWay = RoadRoulette.SpeedLimitWay(
        kmh = 90.0,
        points = listOf(
            at(at(here, 12.0, 90.0), 400.0, 180.0),
            at(at(here, 12.0, 90.0), 400.0, 0.0),
        ),
    )

    /** Held ways, an established sign, nothing pending. */
    private fun holding() = SpeedLimitTracker.State(
        ways = listOf(alongWay),
        waysCenter = here,
        lastFetchMs = t0,
        misses = 0,
        limitKmh = 70.0,
    )

    /** 5 km east: 4 994 m from [here] and 4 600 m from the nearest fixture way,
     *  so the snap finds nothing inside the 25 m radius - a miss, with the held
     *  set still held. */
    private val offTheWay = at(here, 5_000.0, 90.0)

    private fun fix(state: SpeedLimitTracker.State, at: LatLon, speedMps: Double = 20.0) =
        SpeedLimitTracker.onFix(state, at = at, headingDeg = 0.0, speedMps = speedMps)

    // ---- the snap and the hysteresis --------------------------------------

    @Test
    fun aSuccessfulSnapSetsTheLimitAndZeroesTheMisses() {
        val st = fix(holding().copy(limitKmh = null, misses = 2), here)
        assertEquals(70.0, st.limitKmh!!, 1e-9)
        assertEquals(0, st.misses)
    }

    /**
     * One miss keeps the sign, two keep it, **the third clears it** - and the
     * count is asserted, not just the end state. "A few misses in a row means the
     * limit really ended (or the road isn't tagged), not a one-fix gap."
     *
     * The literal 3 is the thing in this machine most likely to break: it was
     * measured unchanged at 3 across four independent baseline clears
     * (`stop-start` 470->473, `urban-limits` run 2 609->612), and task 0d
     * established it cannot fall below 3 by construction. There is no replay to
     * catch a change to it now, so this test is the only thing that will.
     */
    @Test
    fun theThirdConsecutiveMissClearsTheSign() {
        var st = fix(holding(), offTheWay)
        assertEquals(1, st.misses)
        assertEquals(70.0, st.limitKmh!!, 1e-9)

        st = fix(st, offTheWay)
        assertEquals(2, st.misses)
        assertEquals(70.0, st.limitKmh!!, 1e-9)

        st = fix(st, offTheWay)
        assertEquals(SpeedLimitTracker.MISSES_TO_CLEAR, st.misses)
        assertNull(st.limitKmh)
    }

    /** The counter keeps climbing past the threshold on a long untagged stretch
     *  and the sign stays cleared: both call sites spelled the branch
     *  `else if (++misses >= MISSES_TO_CLEAR)`, so the increment was never
     *  capped. Characterised rather than tidied - a cap would change nothing
     *  observable today, which is exactly why it does not belong in an
     *  extraction. */
    @Test
    fun aFourthMissKeepsTheSignClearedAndTheCounterKeepsGrowing() {
        var st = holding()
        repeat(4) { st = fix(st, offTheWay) }
        assertEquals(4, st.misses)
        assertNull(st.limitKmh)
    }

    @Test
    fun aFreshSnapAfterAClearReEstablishesTheSign() {
        var st = holding()
        repeat(3) { st = fix(st, offTheWay) }
        assertNull(st.limitKmh)

        st = fix(st, here)
        assertEquals(70.0, st.limitKmh!!, 1e-9)
        assertEquals(0, st.misses)
    }

    /**
     * Below [SpeedLimitTracker.MIN_MPS] the fix is skipped entirely and does
     * **not** count as a miss, so a long wait at a light cannot clear the sign.
     * Both copies return before the snap (`MapScreen.kt:816`,
     * `SpinScreen.kt:266`), and this is the single easiest thing to get wrong in
     * the move, because an early `return@collect` becomes an early `return state`
     * and it is invisible in a diff.
     *
     * The boundary, stated: exactly `MIN_MPS` is **not** skipped, because both
     * call sites spell it `if (speedMps < MIN_MPS) return`. Note that
     * [SectionAverageTracker.ARM_MIN_MPS] is the same literal 2.0 on the
     * *opposite* edge (`takeIf { speedMps > … }`). Neither is a typo to tidy.
     */
    @Test
    fun aFixBelowTheSpeedFloorIsSkippedAndIsNotAMiss() {
        val held = holding()
        val stopped = fix(held, offTheWay, speedMps = SpeedLimitTracker.MIN_MPS - 0.01)
        assertEquals(held, stopped)

        val crawling = fix(held, offTheWay, speedMps = SpeedLimitTracker.MIN_MPS)
        assertEquals(1, crawling.misses)
    }

    /**
     * The snap is delegated to `RoadRoulette.snapSpeedLimitKmh`
     * (`RoadRoulette.kt:295`), not reimplemented: a way aligned with the heading
     * wins over a **nearer** crossing one, which is most of why the sign stopped
     * showing the cross street's limit. The fixtures are 12 m and 5 m out
     * respectively, both inside the 25 m snap radius, so alignment is the only
     * thing deciding it.
     *
     * The second half is the half a reader gets wrong: an unaligned way is not
     * rejected outright. `snapSpeedLimitKmh` ends `aligned ?: nearest`, so with
     * no aligned candidate at all the cross street's own limit is what shows.
     * Pinned because it looks like a bug and is the shipped behaviour.
     */
    @Test
    fun theAlignedWayBeatsANearerCrossingOneButAnUnalignedOneStillWinsAlone() {
        val both = fix(holding().copy(ways = listOf(crossingWay, parallelWay)), here)
        assertEquals(90.0, both.limitKmh!!, 1e-9)

        val crossingAlone = fix(holding().copy(ways = listOf(crossingWay)), here)
        assertEquals(30.0, crossingAlone.limitKmh!!, 1e-9)
    }

    /** [SpeedLimitTracker.onFix] never touches the prefetched area or the
     *  throttle - on a hit or on a miss. Three of five fields are the fetch
     *  half's and only [SpeedLimitTracker.withWays] and
     *  [SpeedLimitTracker.fetchStarted] write them. An extraction that dropped
     *  the way set on a miss would look like a flickering sign in the field and
     *  like nothing at all in a diff. */
    @Test
    fun onFixNeverTouchesTheHeldAreaOrTheThrottle() {
        val held = holding()
        val hit = fix(held, here)
        assertSame(held.ways, hit.ways)
        assertEquals(here, hit.waysCenter)
        assertEquals(t0, hit.lastFetchMs)

        val miss = fix(held, offTheWay)
        assertSame(held.ways, miss.ways)
        assertEquals(here, miss.waysCenter)
        assertEquals(t0, miss.lastFetchMs)
    }

    // ---- the prefetch throttle -------------------------------------------

    /** `needsWays` is false inside `SPEED_PREFETCH_RADIUS_M - FETCH_MARGIN_M`
     *  (1500.0 - 500.0, `RoadRoulette.kt:255`) of what we hold, and true outside
     *  it. The comparison is `>`; the fixtures sit ~100 m either side of 1 000 m
     *  because `offset` and `distanceMeters` disagree by 0.998876x - 900 m reads
     *  898.989 m and 1 100 m reads 1 098.764 m. */
    @Test
    fun needsWaysOnlyOnceYouNearTheEdgeOfWhatYouHold() {
        val held = holding().copy(lastFetchMs = 0L)
        assertFalse(SpeedLimitTracker.needsWays(held, at(here, 900.0, 90.0), t0))
        assertTrue(SpeedLimitTracker.needsWays(held, at(here, 1_100.0, 90.0), t0))
    }

    /** False inside [SpeedLimitTracker.FETCH_THROTTLE_MS] of the last attempt
     *  however far you have moved. The boundary, stated: exactly the throttle does
     *  **not** fetch, because the test is `>`. */
    @Test
    fun theThrottleHoldsHoweverFarYouHaveMoved() {
        val held = holding()
        val faraway = at(here, 5_000.0, 90.0)
        assertFalse(SpeedLimitTracker.needsWays(held, faraway, t0 + 1))
        assertFalse(
            SpeedLimitTracker.needsWays(held, faraway, t0 + SpeedLimitTracker.FETCH_THROTTLE_MS),
        )
        assertTrue(
            SpeedLimitTracker.needsWays(held, faraway, t0 + SpeedLimitTracker.FETCH_THROTTLE_MS + 1),
        )
    }

    /** True on a virgin state: a null `waysCenter` must mean "no area held", not
     *  "distance zero". Both copies spell that `?: Double.MAX_VALUE`
     *  (`MapScreen.kt:818-819`, `SpinScreen.kt:267-268`). `nowMs` is epoch millis
     *  at every call site, so the zero `lastFetchMs` is never inside the
     *  throttle - the same shape as stage 2's
     *  `theFirstRerouteOfASessionIsNotBlocked`. */
    @Test
    fun aVirginStateNeedsWaysWhereverItIs() {
        assertTrue(SpeedLimitTracker.needsWays(SpeedLimitTracker.State(), here, t0))
    }

    /**
     * The stamp goes on the *attempt*, not the completion. Both copies stamp
     * before awaiting the network (`MapScreen.kt:835`, `SpinScreen.kt:276`) so a
     * failing mirror is throttled like a succeeding one - the car says so at
     * `:274-275`. Folding the stamp into `needsWays` would make a query function
     * mutate; folding it into `withWays` would stamp on completion and turn a
     * 30-second timeout into a 30-second-plus-ten gap.
     *
     * No replay can reach this: the fixture that would show it is a recording
     * against a failing mirror, which has no sign to compare against in the
     * first place. Unit-tested only, and now the only coverage at all.
     */
    @Test
    fun theThrottleIsStampedOnAttemptNotOnCompletion() {
        val virgin = SpeedLimitTracker.State()
        val started = SpeedLimitTracker.fetchStarted(virgin, t0)
        assertEquals(t0, started.lastFetchMs)
        // Nothing else moved: the stamp is not a place to sneak a clear into.
        assertEquals(virgin.copy(lastFetchMs = t0), started)
        // And a failed fetch is throttled exactly like a successful one.
        assertFalse(SpeedLimitTracker.needsWays(started, at(here, 9_000.0, 90.0), t0 + 5_000L))
    }

    /** An empty result is a network blip: keep what we hold rather than
     *  flickering the sign off, and do **not** move the centre - moving it would
     *  claim we hold an area we do not. */
    @Test
    fun anEmptyFetchResultIsANoOpOnBothWaysAndCentre() {
        val held = holding()
        val after = SpeedLimitTracker.withWays(held, emptyList(), at(here, 2_000.0, 90.0))
        assertSame(held.ways, after.ways)
        assertEquals(here, after.waysCenter)
        assertEquals(held, after)
    }

    /** A non-empty result replaces both, and touches neither the sign nor the
     *  miss counter nor the throttle stamp: the fetch completing is not a fix,
     *  and re-snapping here would publish a limit for a position the vehicle has
     *  already left. */
    @Test
    fun aNonEmptyFetchResultReplacesBothWaysAndCentre() {
        val moved = at(here, 2_000.0, 90.0)
        val held = holding().copy(misses = 2)
        val after = SpeedLimitTracker.withWays(held, listOf(crossingWay), moved)
        assertEquals(listOf(crossingWay), after.ways)
        assertEquals(moved, after.waysCenter)
        assertEquals(70.0, after.limitKmh!!, 1e-9)
        assertEquals(2, after.misses)
        assertEquals(t0, after.lastFetchMs)
    }

    // ---- crossing the navigation boundary ---------------------------------

    /**
     * `reset` clears the sign and the miss counter and **keeps the held area** -
     * `ways`, `waysCenter` and `lastFetchMs`. Two of five fields cleared, three
     * kept. Crossing into or out of navigation makes the derived *sign* stale, not
     * the prefetched geometry, and re-clearing `lastFetchMs` would let a
     * navigation toggle punch through the throttle.
     *
     * `bac833a` reset exactly these two on the phone, and this test is what stops
     * the extraction resetting five. No replay starts navigation, so this is the
     * only coverage this branch has or will have from the current fixtures.
     */
    @Test
    fun resetClearsTheSignAndTheMissesAndKeepsTheHeldArea() {
        val st = SpeedLimitTracker.reset(holding().copy(misses = 2))
        assertNull(st.limitKmh)
        assertEquals(0, st.misses)
        assertEquals(listOf(alongWay), st.ways)
        assertEquals(here, st.waysCenter)
        assertEquals(t0, st.lastFetchMs)
    }
}

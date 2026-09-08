package com.jellemax.detour.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TripEndDetector] is the auto-stop machine pulled out of [TripTrackingService]
 * (#307): the "still moving" clock, the grace period after an IN_VEHICLE exit,
 * and the stationary fallback behind both.
 *
 * It could not be tested before the extraction — it was three `var`s inside an
 * Android `Service`, and this repo has no Robolectric and no `androidTest`
 * source set. The point of the clock being a parameter is exactly this: a dwell
 * is arithmetic over timestamps, so with the clock handed in it is checkable
 * against literals, on no device at all.
 *
 * What is *not* here, and is worth naming rather than implying: the trip's
 * recorded duration. That is `TripSession.end`, which needs a `Context` to
 * save through, so it stays out of reach of plain JUnit. What backs it instead
 * is [ScaledClockTest] for the reading's arithmetic plus the fact that
 * `TripSession` now takes the same [DriveClock] this does — the two halves of
 * the claim, neither of them a device test.
 */
class TripEndDetectorTest {

    /** The drive's clock under the test's control. `advance` is in *drive*
     *  milliseconds, which is the only unit the detector deals in. */
    private class FakeDriveClock(private var t: Long = 1_000_000L) : DriveClock {
        override fun nowMs(): Long = t
        override fun fixTimeMs(providerTimeMs: Long): Long = providerTimeMs
        override fun predictionNowMs(fixElapsedMs: Long): Long = fixElapsedMs
        override fun driveElapsedMs(): Long = t
        fun advance(ms: Long): Long {
            t += ms
            return t
        }
        fun now(): Long = t
    }

    private val stationaryEnd = TripTrackingService.STATIONARY_END_MS   // 5 min
    private val exitGrace = TripTrackingService.EXIT_GRACE_MS           // 2 min

    @Test fun `an auto-started trip ends once it has been still for the stationary window`() {
        val clock = FakeDriveClock()
        val d = TripEndDetector(clock)
        d.onTripBegan()

        // Just short of the window: still running.
        assertFalse(d.shouldEnd(speed = 0.0, autoStarted = true, now = clock.advance(stationaryEnd)))
        // One millisecond past it — the gate is a strict `>`.
        assertTrue(d.shouldEnd(speed = 0.0, autoStarted = true, now = clock.advance(1)))
    }

    @Test fun `movement keeps resetting the stationary window`() {
        val clock = FakeDriveClock()
        val d = TripEndDetector(clock)
        d.onTripBegan()

        // Above the 2.0 m/s moving gate every four minutes, for half an hour.
        repeat(8) {
            assertFalse(d.shouldEnd(speed = 3.0, autoStarted = true, now = clock.advance(4 * 60_000L)))
        }
        // And the window still has to run in full from the last moving fix.
        assertFalse(d.shouldEnd(speed = 0.0, autoStarted = true, now = clock.advance(stationaryEnd)))
        assertTrue(d.shouldEnd(speed = 0.0, autoStarted = true, now = clock.advance(1)))
    }

    @Test fun `exactly the moving gate does not count as moving`() {
        val clock = FakeDriveClock()
        val d = TripEndDetector(clock)
        d.onTripBegan()

        // `speed > 2.0`, so 2.0 itself is stationary and the window keeps running.
        assertFalse(d.shouldEnd(speed = 2.0, autoStarted = true, now = clock.advance(stationaryEnd)))
        assertTrue(d.shouldEnd(speed = 2.0, autoStarted = true, now = clock.advance(1)))
    }

    @Test fun `a manually started trip is never ended by the stationary fallback`() {
        val clock = FakeDriveClock()
        val d = TripEndDetector(clock)
        d.onTripBegan()

        // A rider who started tracking by hand is entitled to park for an hour.
        repeat(12) {
            assertFalse(d.shouldEnd(speed = 0.0, autoStarted = false, now = clock.advance(5 * 60_000L)))
        }
    }

    @Test fun `leaving the vehicle ends the trip once the grace period lapses`() {
        val clock = FakeDriveClock()
        val d = TripEndDetector(clock)
        d.onTripBegan()
        d.shouldEnd(speed = 20.0, autoStarted = true, now = clock.advance(60_000L))

        clock.advance(1_000L)
        d.onVehicleExit()

        assertFalse(d.shouldEnd(speed = 0.0, autoStarted = true, now = clock.advance(exitGrace)))
        assertTrue(d.shouldEnd(speed = 0.0, autoStarted = true, now = clock.advance(1)))
    }

    @Test fun `driving off again cancels a pending exit`() {
        val clock = FakeDriveClock()
        val d = TripEndDetector(clock)
        d.onTripBegan()
        d.onVehicleExit()

        // A fuel stop: back above the resumed-speed gate before the grace lapses.
        assertFalse(d.shouldEnd(speed = 6.0, autoStarted = true, now = clock.advance(30_000L)))
        // The exit is gone, so the grace period can no longer end anything.
        assertFalse(d.shouldEnd(speed = 6.0, autoStarted = true, now = clock.advance(exitGrace * 2)))
    }

    @Test fun `IN_VEHICLE again cancels a pending exit`() {
        val clock = FakeDriveClock()
        val d = TripEndDetector(clock)
        d.onTripBegan()
        d.onVehicleExit()
        clock.advance(30_000L)
        d.onVehicleEnter()

        // Slow, but no exit is pending any more, so only the stationary
        // fallback is left — and it has not run yet.
        assertFalse(d.shouldEnd(speed = 0.0, autoStarted = true, now = clock.advance(exitGrace + 1)))
    }

    @Test fun `crawling below the resumed gate does not cancel the exit`() {
        val clock = FakeDriveClock()
        val d = TripEndDetector(clock)
        d.onTripBegan()
        d.onVehicleExit()

        // 4.0 m/s is over the 2.0 moving gate but under the 5.0 resumed gate —
        // wheeling a bike across a forecourt. The exit stands, and the grace
        // period still ends the trip.
        assertFalse(d.shouldEnd(speed = 4.0, autoStarted = true, now = clock.advance(exitGrace)))
        assertTrue(d.shouldEnd(speed = 4.0, autoStarted = true, now = clock.advance(1)))
    }

    @Test fun `a new trip clears the exit left pending by the last one`() {
        val clock = FakeDriveClock()
        val d = TripEndDetector(clock)
        d.onTripBegan()
        d.onVehicleExit()
        d.onTripEnded()

        clock.advance(60 * 60_000L)   // an hour parked between drives
        d.onTripBegan()

        // Neither the stale exit nor the hour counts against the new trip.
        assertFalse(d.shouldEnd(speed = 0.0, autoStarted = true, now = clock.advance(stationaryEnd)))
        assertTrue(d.shouldEnd(speed = 0.0, autoStarted = true, now = clock.advance(1)))
    }

    @Test fun `the dwell is measured in drive time, so a compressed replay is unchanged`() {
        // The whole reason the clock is a parameter. At 5x the rig delivers a
        // route's fixes five times as fast; the clock advances five drive
        // milliseconds per wall millisecond, so the window still takes the
        // drive's five minutes — 60 s of wall time, not 300.
        val clock = FakeDriveClock()
        val d = TripEndDetector(clock)
        d.onTripBegan()

        val wallMsPerStep = 1_000L
        val scale = 5
        var wallElapsed = 0L
        while (wallElapsed < 60_000L) {
            wallElapsed += wallMsPerStep
            assertFalse(d.shouldEnd(speed = 0.0, autoStarted = true, now = clock.advance(wallMsPerStep * scale)))
        }
        assertTrue(d.shouldEnd(speed = 0.0, autoStarted = true, now = clock.advance(1)))
        // 60 s of wall time bought exactly the 5 minutes of drive time the
        // window is specified in.
        assertTrue(clock.now() > 0)
    }
}

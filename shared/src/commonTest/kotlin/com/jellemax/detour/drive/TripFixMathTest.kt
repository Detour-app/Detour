package com.jellemax.detour.drive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Characterises [TripFixMath] — the four per-fix decisions `onTripLocation`
 * makes on every GPS fix of a recorded trip. None of them were reachable from
 * a test while they were inline in an Android [android.app.Service]; each has a
 * failure mode that only shows up in the field (a tunnel, a parking garage, a
 * BT dropout), which is exactly the kind that needs pinning here.
 *
 * The thresholds below are this test's *own* copies of the service's values,
 * deliberately re-declared rather than defaulted inside [TripFixMath]: a
 * default in production code that happens to equal the app's constant is how
 * two thresholds silently drift apart.
 */
class TripFixMathTest {

    private val t0 = 1_700_000_000_000L

    // Mirrors the literals in onTripLocation's distance gate.
    private val maxAccuracyM = 50f
    private val minGapMs = 1L
    private val maxGapMs = 15_000L

    // ---- distanceHopMeters -------------------------------------------------

    @Test
    fun anAccurateAndRecentFixAccumulatesItsHop() {
        val hop = TripFixMath.distanceHopMeters(
            rawHopMeters = 24.0,
            lastFixMs = t0,
            fixMs = t0 + 1_000,
            accuracyM = 8f,
            maxAccuracyM = maxAccuracyM,
            minGapMs = minGapMs,
            maxGapMs = maxGapMs,
        )
        assertEquals(24.0, hop)
    }

    @Test
    fun aStaleButPerfectlyAccurateFixContributesNothing() {
        // The whole reason the gate is accuracy AND recency. Rider enters a
        // tunnel at t0, re-acquires 5 minutes and 4.2 km later with a textbook
        // 4 m fix. An accuracy-only gate banks the entire 4.2 km in one hop and
        // attributes it to whatever road class the re-acquire snaps to.
        val hop = TripFixMath.distanceHopMeters(
            rawHopMeters = 4_200.0,
            lastFixMs = t0,
            fixMs = t0 + 300_000,
            accuracyM = 4f,
            maxAccuracyM = maxAccuracyM,
            minGapMs = minGapMs,
            maxGapMs = maxGapMs,
        )
        assertEquals(0.0, hop)
    }

    @Test
    fun aLooseFixContributesNothingEvenWhenItIsRecent() {
        val hop = TripFixMath.distanceHopMeters(
            rawHopMeters = 90.0,
            lastFixMs = t0,
            fixMs = t0 + 1_000,
            accuracyM = 80f,
            maxAccuracyM = maxAccuracyM,
            minGapMs = minGapMs,
            maxGapMs = maxGapMs,
        )
        assertEquals(0.0, hop)
    }

    @Test
    fun theFirstFixOfATripHasNoAnchorSoThereIsNoHop() {
        val hop = TripFixMath.distanceHopMeters(
            rawHopMeters = 1_000.0, // whatever a caller passes is irrelevant with no anchor
            lastFixMs = null,
            fixMs = t0,
            accuracyM = 4f,
            maxAccuracyM = maxAccuracyM,
            minGapMs = minGapMs,
            maxGapMs = maxGapMs,
        )
        assertEquals(0.0, hop)
    }

    @Test
    fun aRedeliveredFixWithAFrozenClockContributesNothing() {
        // GPS staleness: the same fix is handed back, so its timestamp hasn't
        // moved. Gap 0 is below the window's lower bound, so no hop — and no
        // double-counting of a hop already banked.
        val hop = TripFixMath.distanceHopMeters(
            rawHopMeters = 12.0,
            lastFixMs = t0,
            fixMs = t0,
            accuracyM = 4f,
            maxAccuracyM = maxAccuracyM,
            minGapMs = minGapMs,
            maxGapMs = maxGapMs,
        )
        assertEquals(0.0, hop)
    }

    @Test
    fun theGateBoundsAreInclusiveExactlyWhereTheServicesRangeIs() {
        fun hopAt(gapMs: Long, accuracyM: Float) = TripFixMath.distanceHopMeters(
            rawHopMeters = 7.0,
            lastFixMs = t0,
            fixMs = t0 + gapMs,
            accuracyM = accuracyM,
            maxAccuracyM = maxAccuracyM,
            minGapMs = minGapMs,
            maxGapMs = maxGapMs,
        )
        assertEquals(7.0, hopAt(1L, 4f), "1 ms is the first accepted gap")
        assertEquals(7.0, hopAt(15_000L, 4f), "15 s is the last accepted gap")
        assertEquals(0.0, hopAt(15_001L, 4f), "one ms past the window is out")
        assertEquals(7.0, hopAt(1_000L, 50f), "accuracy is a <= gate")
        assertEquals(0.0, hopAt(1_000L, 50.1f), "a hair looser is out")
    }

    @Test
    fun aFixWithNoUsableAccuracyBanksNothing() {
        // Every comparison with NaN is false, so a `> maxAccuracyM` gate would
        // wave this through. A synthetic provider (this repo's own mock-location
        // harness is one) can hand us an unset accuracy; banking its hop puts a
        // silent error into persisted trip distance.
        assertEquals(
            0.0,
            TripFixMath.distanceHopMeters(
                rawHopMeters = 42.0,
                lastFixMs = t0,
                fixMs = t0 + 1_000L,
                accuracyM = Float.NaN,
                maxAccuracyM = maxAccuracyM,
                minGapMs = minGapMs,
                maxGapMs = maxGapMs,
            ),
            "a NaN accuracy must be rejected, not trusted",
        )
    }

    // ---- speedIsReal -------------------------------------------------------

    @Test
    fun aFixCarryingItsOwnSpeedIsReal() {
        assertTrue(
            TripFixMath.speedIsReal(
                fixHasSpeed = true,
                boardHasSpeed = false,
                modeTracksGForce = false,
                obdHasSpeed = false,
            )
        )
    }

    @Test
    fun aFabricatedZeroWithNoCorroboratingSourceIsNotReal() {
        // speedOf() hands back 0.0 as a sentinel for a coarse/no-speed fix. If
        // that reads as real, a tunnel gap becomes a false hard brake, a false
        // stop, and a bogus "suddenly under the limit" transition.
        assertFalse(
            TripFixMath.speedIsReal(
                fixHasSpeed = false,
                boardHasSpeed = false,
                modeTracksGForce = true, // even on a car/moto trip
                obdHasSpeed = false,
            )
        )
    }

    @Test
    fun boardTelemetrySpeedRescuesAFixWithNoGpsSpeedInAnyMode() {
        // The board arm is not gated on tracksGForce in the service.
        assertTrue(
            TripFixMath.speedIsReal(
                fixHasSpeed = false,
                boardHasSpeed = true,
                modeTracksGForce = false,
                obdHasSpeed = false,
            )
        )
    }

    @Test
    fun obdSpeedOnlyCountsOnAModeThatTracksGForce() {
        assertTrue(
            TripFixMath.speedIsReal(
                fixHasSpeed = false,
                boardHasSpeed = false,
                modeTracksGForce = true,
                obdHasSpeed = true,
            )
        )
        // An adapter still connected while the rider walks must not make a
        // pedestrian fix's fabricated zero look measured.
        assertFalse(
            TripFixMath.speedIsReal(
                fixHasSpeed = false,
                boardHasSpeed = false,
                modeTracksGForce = false,
                obdHasSpeed = true,
            )
        )
    }

    // ---- recordedFixMs -----------------------------------------------------

    @Test
    fun anObdDrivenSpeedIsStampedWithTheAdaptersOwnArrivalClock() {
        // PID 0D arrives at ~1 Hz with its own jitter; the detectors' Δt has to
        // see that jitter rather than a flattened nominal second.
        assertEquals(
            t0 + 1_140,
            TripFixMath.recordedFixMs(
                obdDroveSpeed = true,
                obdReceivedAtMs = t0 + 1_140,
                locationTimeMs = t0 + 1_000,
            )
        )
    }

    @Test
    fun aGpsDrivenSpeedKeepsTheGpsClock() {
        assertEquals(
            t0 + 1_000,
            TripFixMath.recordedFixMs(
                obdDroveSpeed = false,
                obdReceivedAtMs = t0 + 1_140, // a snapshot exists, it just didn't win
                locationTimeMs = t0 + 1_000,
            )
        )
    }

    @Test
    fun withNoObdSnapshotAtAllTheGpsClockIsUsed() {
        assertEquals(
            t0 + 1_000,
            TripFixMath.recordedFixMs(
                obdDroveSpeed = true,
                obdReceivedAtMs = null,
                locationTimeMs = t0 + 1_000,
            )
        )
    }

    // ---- roadTypeHopMeters -------------------------------------------------

    @Test
    fun roadTypeAttributionUsesTheSameGatedHopTheDistanceAccumulatorBanked() {
        val previous = 12_000.0
        val hop = TripFixMath.distanceHopMeters(
            rawHopMeters = 24.0,
            lastFixMs = t0,
            fixMs = t0 + 1_000,
            accuracyM = 8f,
            maxAccuracyM = maxAccuracyM,
            minGapMs = minGapMs,
            maxGapMs = maxGapMs,
        )
        assertEquals(24.0, TripFixMath.roadTypeHopMeters(previous + hop, previous))
    }

    @Test
    fun aPostTunnelReacquireAttributesNoDistanceToItsRoadClass() {
        // The reason roadTypeHop is derived from the accumulated distance
        // rather than from its own `lastFixLocation` anchor: an accuracy-only
        // anchor would hand RoadTypeTracker 4.2 km of tunnel as motorway (or
        // whatever the re-acquire fix happens to snap to).
        val previous = 12_000.0
        val hop = TripFixMath.distanceHopMeters(
            rawHopMeters = 4_200.0,
            lastFixMs = t0,
            fixMs = t0 + 300_000,
            accuracyM = 4f,
            maxAccuracyM = maxAccuracyM,
            minGapMs = minGapMs,
            maxGapMs = maxGapMs,
        )
        assertEquals(0.0, TripFixMath.roadTypeHopMeters(previous + hop, previous))
    }
}

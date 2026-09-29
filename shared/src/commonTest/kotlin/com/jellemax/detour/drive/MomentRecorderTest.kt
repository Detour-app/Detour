package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RidingEvent
import com.jellemax.detour.data.RidingEventKind
import com.jellemax.detour.data.TripStop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Characterises [MomentRecorder] — where a trip's peaks, hard events and stops
 *  get pinned (#444). The stored trace is decimated to 25 m, so these pins are
 *  the only record of where the top speed or a hard brake happened; the rule
 *  under test throughout is that the pins agree with the numbers the existing
 *  detectors already count. */
class MomentRecorderTest {

    private val t0 = 1_700_000_000_000L
    private val a = LatLon(50.80, 3.20)
    private val b = LatLon(50.81, 3.21)
    private val c = LatLon(50.82, 3.22)

    @Test
    fun theTopSpeedIsPinnedWhereItWasReachedNotWhereItWasLastMatched() {
        var s = MomentRecorder.State()
        s = MomentRecorder.onSpeed(s, a, t0, 20.0)
        s = MomentRecorder.onSpeed(s, b, t0 + 1000, 35.0)
        // Equal to the peak, later: the first place it was reached keeps the pin.
        s = MomentRecorder.onSpeed(s, c, t0 + 2000, 35.0)
        assertEquals(b, s.moments.topSpeed?.at)
        assertEquals(t0 + 1000, s.moments.topSpeed?.timeMs)
    }

    @Test
    fun aStandingTripHasNoTopSpeedPinRatherThanOneAtZero() {
        val s = MomentRecorder.onSpeed(MomentRecorder.State(), a, t0, 0.0)
        assertNull(s.moments.topSpeed)
    }

    @Test
    fun theDeepestLeanEitherWayWinsAndKeepsItsSign() {
        var s = MomentRecorder.State()
        s = MomentRecorder.onLean(s, a, t0, 30.0)
        s = MomentRecorder.onLean(s, b, t0 + 1000, -42.0)
        s = MomentRecorder.onLean(s, c, t0 + 2000, 41.0)
        // Negative is leaning left — the detail screen shows which way.
        assertEquals(-42.0, s.moments.maxLean!!.value, absoluteTolerance = 1e-9)
        assertEquals(b, s.moments.maxLean!!.at)
    }

    @Test
    fun aCornerIsPinnedWhereItBeganButRecordsTheDeepestLeanThroughIt() {
        var s = MomentRecorder.State()
        val kind = RidingEventKind.HARD_CORNER_LEAN
        s = MomentRecorder.onCorner(s, cornering = true, newEvent = true, RidingEvent(kind, a, t0, 40.5))
        s = MomentRecorder.onCorner(s, cornering = true, newEvent = false, RidingEvent(kind, b, t0 + 500, 48.0))
        s = MomentRecorder.onCorner(s, cornering = true, newEvent = false, RidingEvent(kind, c, t0 + 900, 44.0))
        val corner = s.moments.events.single()
        assertEquals(a, corner.at)
        assertEquals(t0, corner.timeMs)
        assertEquals(48.0, corner.magnitude, absoluteTolerance = 1e-9)
    }

    @Test
    fun aCornerStopsDeepeningOnceTheLatchDrops() {
        var s = MomentRecorder.State()
        val kind = RidingEventKind.HARD_CORNER_LEAN
        s = MomentRecorder.onCorner(s, cornering = true, newEvent = true, RidingEvent(kind, a, t0, 41.0))
        s = MomentRecorder.onCorner(s, cornering = false, newEvent = false, RidingEvent(kind, b, t0 + 500, 10.0))
        // Leaned hard again but without a new edge — e.g. a sample the service's
        // speed gate let through mid-latch. Must not rewrite the finished corner.
        s = MomentRecorder.onCorner(s, cornering = true, newEvent = false, RidingEvent(kind, c, t0 + 900, 55.0))
        assertEquals(41.0, s.moments.events.single().magnitude, absoluteTolerance = 1e-9)
    }

    @Test
    fun eventsPastTheCapAreDroppedAndNoOldCornerIsDeepenedInstead() {
        var s = MomentRecorder.State()
        repeat(MomentRecorder.MAX_EVENTS) {
            s = MomentRecorder.onEvent(s, RidingEventKind.HARD_BRAKE, a, t0 + it, -4.0)
        }
        val kind = RidingEventKind.HARD_CORNER_TURN
        s = MomentRecorder.onCorner(s, cornering = true, newEvent = true, RidingEvent(kind, b, t0 + 999, 30.0))
        s = MomentRecorder.onCorner(s, cornering = true, newEvent = false, RidingEvent(kind, b, t0 + 1999, 60.0))
        assertEquals(MomentRecorder.MAX_EVENTS, s.moments.events.size)
        assertEquals(RidingEventKind.HARD_BRAKE, s.moments.events.last().kind)
        assertEquals(-4.0, s.moments.events.last().magnitude, absoluteTolerance = 1e-9)
    }

    @Test
    fun aStopIsPinnedWhereTheVehicleCameToRestOverTheWindowTheDetectorCounted() {
        var stop = StopDetector.State()
        var s = MomentRecorder.State()
        fun fix(at: LatLon, speed: Double, ms: Long) {
            val next = StopDetector.onFix(stop, speed, ms)
            s = MomentRecorder.onStopFix(s, stop, next, at, ms)
            stop = next
        }
        fix(a, 12.0, t0)
        fix(b, 0.0, t0 + 10_000) // comes to rest here
        fix(c, 0.5, t0 + 40_000) // GPS wander while parked — not where it stopped
        fix(a, 12.0, t0 + 70_000) // pulls away
        assertEquals(1, stop.stopCount)
        assertEquals(listOf(TripStop(b, t0 + 10_000, t0 + 70_000)), s.moments.stops)
        // The window is the idle time StopDetector counted, to the millisecond.
        assertEquals(stop.idleMs, s.moments.stops.single().durationMs)
    }

    @Test
    fun aTrafficLightLeavesNoStopAndNoStaleRestingPlaceBehind() {
        var stop = StopDetector.State()
        var s = MomentRecorder.State()
        fun fix(at: LatLon, speed: Double, ms: Long) {
            val next = StopDetector.onFix(stop, speed, ms)
            s = MomentRecorder.onStopFix(s, stop, next, at, ms)
            stop = next
        }
        fix(a, 12.0, t0)
        fix(a, 0.0, t0 + 1_000) // red light at a
        fix(b, 12.0, t0 + 11_000) // green after 10 s, under MIN_STOP_DWELL_MS
        fix(c, 0.0, t0 + 60_000) // a real stop at c
        fix(a, 12.0, t0 + 120_000)
        assertEquals(listOf(TripStop(c, t0 + 60_000, t0 + 120_000)), s.moments.stops)
    }
}

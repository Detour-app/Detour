package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RidingEvent
import com.jellemax.detour.data.RidingEventKind
import com.jellemax.detour.data.TripMoments
import com.jellemax.detour.data.TripPeak
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TripHighlightRulesTest {
    private val at = LatLon(50.0, 4.0)
    private fun event(kind: RidingEventKind, magnitude: Double, timeMs: Long = 0) =
        RidingEvent(kind, at, timeMs, magnitude)
    private fun peak(value: Double) = TripPeak(at, 0, value)

    @Test
    fun anEventReadsVeryOnlyAtOneAndAHalfTimesItsThreshold() {
        val brake = HardEventDetector.HARD_BRAKE_MPS2
        assertEquals(EventStrength.HARD, TripHighlightRules.strength(event(RidingEventKind.HARD_BRAKE, brake * 1.49)))
        assertEquals(EventStrength.VERY_HARD, TripHighlightRules.strength(event(RidingEventKind.HARD_BRAKE, brake * 1.5)))
        val turn = HardEventDetector.HARD_CORNER_DEG_PER_SEC
        assertEquals(EventStrength.SHARP, TripHighlightRules.strength(event(RidingEventKind.HARD_CORNER_TURN, turn * 1.2)))
        assertEquals(EventStrength.VERY_SHARP, TripHighlightRules.strength(event(RidingEventKind.HARD_CORNER_TURN, turn * 2)))
        assertEquals(EventStrength.VERY_HARD, TripHighlightRules.strength(event(RidingEventKind.HARD_ACCEL, HardEventDetector.HARD_ACCEL_MPS2 * 2)))
    }

    @Test
    fun aLeanCornerStaysANumber() {
        assertEquals(EventStrength.LEAN, TripHighlightRules.strength(event(RidingEventKind.HARD_CORNER_LEAN, -48.0)))
    }

    @Test
    fun picksTakeTheDeepestCornerAndTheHardestBrake() {
        val moments = TripMoments(
            topSpeed = peak(40.0),
            events = listOf(
                event(RidingEventKind.HARD_CORNER_LEAN, -35.0, 1),
                event(RidingEventKind.HARD_CORNER_LEAN, 52.0, 2),
                event(RidingEventKind.HARD_CORNER_TURN, 30.0, 3),
                event(RidingEventKind.HARD_BRAKE, -4.0, 4),
                event(RidingEventKind.HARD_BRAKE, -6.5, 5),
                event(RidingEventKind.HARD_ACCEL, 3.5, 6),
            ),
        )
        val picks = TripHighlightRules.picks(moments, tracksLean = true)
        assertEquals(2L, picks.corner?.timeMs)
        assertEquals(5L, picks.brake?.timeMs)
        assertEquals(40.0, picks.topSpeed?.value)
    }

    @Test
    fun leanNeedsALeanTrackingModeAndANonZeroPeak() {
        val moments = TripMoments(maxLean = peak(31.0))
        assertEquals(31.0, TripHighlightRules.picks(moments, tracksLean = true).lean?.value)
        assertNull(TripHighlightRules.picks(moments, tracksLean = false).lean)
        assertNull(TripHighlightRules.picks(TripMoments(maxLean = peak(0.0)), tracksLean = true).lean)
    }

    @Test
    fun anEmptyTripPicksNothing() {
        val picks = TripHighlightRules.picks(TripMoments(), tracksLean = true)
        assertEquals(TripHighlightPicks(null, null, null, null), picks)
    }
}

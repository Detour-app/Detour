package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LoopDurationTest {

    private fun loop(minutes: Double?) = RouteResult(
        polyline = emptyList(),
        waypoints = emptyList(),
        distanceMeters = null,
        timeMs = minutes?.let { (it * 60_000).toLong() },
    )

    @Test
    fun halfAnHourGuessesTwentyFiveKilometres() {
        assertEquals(25_000.0, LoopDuration.guessMeters(30f), 1e-6)
    }

    @Test
    fun aLoopWithinFifteenPercentFitsAndOneOutsideDoesNot() {
        assertTrue(LoopDuration.fits(loop(34.0), 30f))
        assertTrue(LoopDuration.fits(loop(26.0), 30f))
        assertFalse(LoopDuration.fits(loop(35.0), 30f))
        assertFalse(LoopDuration.fits(loop(null), 30f))
    }

    @Test
    fun loopsThatCameBackTooLongShrinkTheNextRequestByTheMedianTime() {
        // Asked for 25 km, got 40/60/45 min back: median 45 → scale 30/45.
        val next = LoopDuration.rescaledMeters(
            30f, 25_000.0, listOf(loop(40.0), loop(60.0), loop(45.0)),
        )
        assertEquals(25_000.0 * 30 / 45, next!!, 1e-6)
    }

    @Test
    fun anEvenNumberOfTimesUsesTheMeanOfTheMiddleTwo() {
        val next = LoopDuration.rescaledMeters(30f, 10_000.0, listOf(loop(20.0), loop(40.0)))
        assertEquals(10_000.0, next!!, 1e-6)
    }

    @Test
    fun oneRescaleIsClampedSoAWildEstimateCannotSwingTheRequest() {
        assertEquals(
            25_000.0 * 2.5,
            LoopDuration.rescaledMeters(30f, 25_000.0, listOf(loop(1.0)))!!, 1e-6,
        )
        assertEquals(
            25_000.0 * 0.4,
            LoopDuration.rescaledMeters(30f, 25_000.0, listOf(loop(600.0)))!!, 1e-6,
        )
    }

    @Test
    fun noReportedTimeMeansNothingToCalibrateAgainst() {
        assertNull(LoopDuration.rescaledMeters(30f, 25_000.0, listOf(loop(null))))
        assertNull(LoopDuration.rescaledMeters(30f, 25_000.0, emptyList()))
    }

    @Test
    fun pickTakesTheCurviestOfTheLoopsThatFit() {
        val straightFit = loop(30.0) to 0.1
        val twistyFit = loop(28.0) to 0.3
        val twistierTooLong = loop(50.0) to 0.9
        assertSame(twistyFit, LoopDuration.pick(listOf(straightFit, twistyFit, twistierTooLong), 30f))
    }

    @Test
    fun withNothingFittingPickTakesTheClosestTimeNotTheCurviest() {
        val close = loop(38.0) to 0.1
        val far = loop(60.0) to 0.9
        val unknown = loop(null) to 1.0
        assertSame(close, LoopDuration.pick(listOf(far, unknown, close), 30f))
    }

    @Test
    fun pickOfNothingIsNull() {
        assertNull(LoopDuration.pick(emptyList(), 30f))
    }
}

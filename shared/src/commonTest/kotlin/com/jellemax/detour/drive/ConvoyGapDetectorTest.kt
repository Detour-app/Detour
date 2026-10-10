package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.drive.ConvoyGapDetector.Event
import com.jellemax.detour.drive.ConvoyGapDetector.Fix
import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Characterises [ConvoyGapDetector]: each alert fires once per episode, a
 *  whole convoy stopping together raises nothing, and repeated or late frames
 *  change nothing. */
class ConvoyGapDetectorTest {

    private val t0 = 1_700_000_000_000L
    private val lead = RiderId("lead")
    private val tail = RiderId("tail")

    /** A straight 21 km route due east along 50°N. */
    private val route = listOf(LatLon(50.0, 4.0), LatLon(50.0, 4.3))
    private val metersPerDegLon = 111_320.0 * cos(50.0 * PI / 180.0)

    private fun fix(rider: RiderId, alongM: Double, kmh: Double?, tsMs: Long) =
        Fix(rider, LatLon(50.0, 4.0 + alongM / metersPerDegLon), kmh, tsMs)

    /** Where one simulated rider is along [route]. */
    private class Rider(val id: RiderId, var alongM: Double)

    /**
     * Steps both riders once a second for [speeds].size seconds, each second
     * moving them at the pair of speeds given, and returns every event raised.
     */
    private fun ride(
        start: Pair<Double, Double>,
        speeds: List<Pair<Double, Double>>,
        resend: Boolean = false,
    ): List<Pair<Long, Event>> {
        val front = Rider(lead, start.first)
        val back = Rider(tail, start.second)
        var state = ConvoyGapDetector.State()
        val events = mutableListOf<Pair<Long, Event>>()
        var previous = emptyList<Fix>()
        speeds.forEachIndexed { second, (leadKmh, tailKmh) ->
            val now = t0 + second * 1_000L
            front.alongM += leadKmh / 3.6
            back.alongM += tailKmh / 3.6
            val fresh = listOf(fix(lead, front.alongM, leadKmh, now), fix(tail, back.alongM, tailKmh, now))
            val fixes = if (resend) previous + fresh + fresh else fresh
            val step = ConvoyGapDetector.step(state, fixes, lead, route, now)
            state = step.state
            step.events.forEach { events += now to it }
            previous = fresh
        }
        return events
    }

    @Test
    fun fallingBehindFiresOnceAndClearsOnRejoin() {
        val speeds = List(30) { 50.0 to 50.0 } + // 100 m back, together
            List(120) { 50.0 to 30.0 } + //         drops back 667 m
            List(120) { 50.0 to 70.0 } //          closes back up to 100 m
        val events = ride(start = 100.0 to 0.0, speeds = speeds).map { it.second }
        assertEquals(listOf(Event.FellBehind(tail), Event.Rejoined(tail)), events)
    }

    @Test
    fun fallingBehindFiresAtTheDistanceThreshold() {
        val speeds = List(10) { 50.0 to 50.0 } + List(120) { 50.0 to 30.0 }
        val (at, event) = ride(start = 100.0 to 0.0, speeds = speeds).single()
        assertEquals(Event.FellBehind(tail), event)
        // 100 m plus 20/3.6 m/s of separation reaches 500 m 72 s into the slow
        // stretch (second 81); a second either side is rounding in the snap.
        assertEquals((t0 + 81_000L).toDouble(), at.toDouble(), absoluteTolerance = 1_000.0)
    }

    @Test
    fun aShortGapRiddenSlowlyIsBehindOnTime() {
        // 200 m at 10 km/h is 72 s back: under the distance threshold, over the time one.
        val speeds = List(200) { 10.0 to 10.0 }
        val events = ride(start = 200.0 to 0.0, speeds = speeds)
        assertEquals(listOf(Event.FellBehind(tail)), events.map { it.second })
    }

    @Test
    fun aWholeConvoyStoppedTogetherRaisesNothing() {
        // A red light: both stop for two minutes 50 m apart, then ride on.
        val speeds = List(30) { 50.0 to 50.0 } + List(120) { 0.0 to 0.0 } + List(60) { 50.0 to 50.0 }
        val events = ride(start = 50.0 to 0.0, speeds = speeds)
        assertEquals(emptyList(), events, "a light the whole convoy waited at must not alert anyone")
    }

    @Test
    fun aRiderStoppedWhileTheLeadMovesIsStoppedAfterTwentySeconds() {
        val speeds = List(10) { 50.0 to 50.0 } + List(30) { 50.0 to 0.0 }
        val events = ride(start = 50.0 to 0.0, speeds = speeds)
        val stopped = events.filter { it.second is Event.Stopped }
        assertEquals(1, stopped.size)
        // First stopped fix at second 10; twenty seconds later is second 30.
        assertEquals(t0 + 30 * 1_000L, stopped.single().first)
    }

    @Test
    fun theDwellOnlyCountsOnceTheLeadMoves() {
        // Both wait at a light; the lead pulls away at second 40, the tail stays put.
        val speeds = List(10) { 50.0 to 50.0 } + List(30) { 0.0 to 0.0 } + List(30) { 50.0 to 0.0 }
        val stopped = ride(start = 50.0 to 0.0, speeds = speeds).filter { it.second is Event.Stopped }
        assertEquals(listOf(t0 + 60 * 1_000L), stopped.map { it.first })
    }

    @Test
    fun signalLostFiresAtThirtySecondsOnceAndClearsWhenAFrameArrives() {
        var state = ConvoyGapDetector.State()
        val events = mutableListOf<Pair<Long, Event>>()
        fun at(second: Int, tailToo: Boolean) {
            val now = t0 + second * 1_000L
            val fixes = listOfNotNull(
                fix(lead, 1_000.0 + second, 3.6, now),
                if (tailToo) fix(tail, 990.0 + second, 3.6, now) else null,
            )
            val step = ConvoyGapDetector.step(state, fixes, lead, route, now)
            state = step.state
            step.events.forEach { events += now to it }
        }
        at(0, tailToo = true)
        for (second in 1..45) at(second, tailToo = false)
        assertEquals(listOf<Pair<Long, Event>>(t0 + 30_000L to Event.SignalLost(tail)), events)
        at(46, tailToo = true)
        assertEquals(Event.Rejoined(tail), events.last().second)
        assertEquals(2, events.size)
    }

    @Test
    fun repeatedAndLateFramesDoNotDuplicateEvents() {
        val speeds = List(30) { 50.0 to 50.0 } +
            List(120) { 50.0 to 30.0 } +
            List(60) { 50.0 to 0.0 } +
            List(200) { 50.0 to 90.0 }
        val clean = ride(start = 100.0 to 0.0, speeds = speeds)
        val noisy = ride(start = 100.0 to 0.0, speeds = speeds, resend = true)
        assertTrue(clean.isNotEmpty())
        assertEquals(clean, noisy, "a frame delivered twice, or after a newer one, must not raise an alert again")
    }

    @Test
    fun theLeadIsNeverBehindOrStopped() {
        // The tail overtakes and pulls 1 km ahead; then the lead stops for a minute.
        val speeds = List(120) { 50.0 to 90.0 } + List(60) { 0.0 to 50.0 }
        val events = ride(start = 0.0 to 0.0, speeds = speeds)
        assertEquals(emptyList<Pair<Long, Event>>(), events.filter { it.second.rider == lead })
    }
}

package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Characterises the ladder both surfaces already implemented, including the one
 * boundary they disagreed on until convergence 3 made iOS inclusive. Written
 * before either surface was repointed, so a repoint that changes behaviour
 * fails here rather than in the field. That fixed ladder is now the
 * speed-less overload iOS still calls; the timed ladder and ", then" the
 * Android surfaces use (#1012) are tested at the end.
 */
class NavAnnouncerTest {

    private fun turn(text: String = "Turn right", startIndex: Int = 0) = NavInstruction(
        text = text, distanceMeters = 0.0, sign = 2, startIndex = startIndex, endIndex = startIndex + 1,
    )

    @Test
    fun firstPromptIgnoresTheLadder() {
        val a = NavAnnouncer()
        // 3 km out is phase 0. Being told nothing for the next 3 km after
        // pressing Start is indistinguishable from voice being broken.
        assertEquals("In 3 kilometers, Turn right", a.onProgress(turn(), 3000.0))
    }

    @Test
    fun eachPhaseFiresOnceAndOnlyUpward() {
        val a = NavAnnouncer()
        a.onProgress(turn(), 3000.0)            // burns the start prompt
        assertNull(a.onProgress(turn(), 900.0))
        assertEquals("In 800 meters, Turn right", a.onProgress(turn(), 800.0))
        assertNull(a.onProgress(turn(), 700.0))
        assertEquals("In 300 meters, Turn right", a.onProgress(turn(), 300.0))
        assertNull(a.onProgress(turn(), 100.0))
        assertEquals("Turn right", a.onProgress(turn(), 80.0))
        assertNull(a.onProgress(turn(), 10.0))
    }

    @Test
    fun boundariesAreInclusive() {
        // The case iOS got wrong with `..<`: exactly on a threshold is inside
        // it. Register entry 12.
        for ((distance, expected) in listOf(
            800.0 to "In 800 meters, Turn right",
            300.0 to "In 300 meters, Turn right",
            80.0 to "Turn right",
        )) {
            val a = NavAnnouncer()
            a.onProgress(turn(), 3000.0)
            assertEquals(expected, a.onProgress(turn(), distance), "at $distance m")
        }
    }

    @Test
    fun justOutsideTheFarBoundIsSilent() {
        val a = NavAnnouncer()
        a.onProgress(turn(), 3000.0)
        assertNull(a.onProgress(turn(), 800.001))
    }

    @Test
    fun aNewInstructionRearmsTheLatch() {
        val a = NavAnnouncer()
        a.onProgress(turn(), 3000.0)
        assertEquals("Turn right", a.onProgress(turn(startIndex = 0), 80.0))
        // Next step of the route: the phase latch is per instruction, so a
        // fresh startIndex hears all three prompts again.
        assertEquals(
            "In 800 meters, Turn left",
            a.onProgress(turn("Turn left", startIndex = 7), 800.0),
        )
    }

    @Test
    fun routeChangedRearmsTheStartPromptToo() {
        val a = NavAnnouncer()
        a.onProgress(turn(), 3000.0)
        a.onProgress(turn(), 80.0)
        a.routeChanged()
        // Same instruction, same distance, and it speaks: indices belong to the
        // old polyline, so a reroute starts the prompts from scratch.
        assertEquals("Turn right", a.onProgress(turn(), 80.0))
    }

    @Test
    fun noInstructionIsSilent() {
        assertNull(NavAnnouncer().onProgress(null, 100.0))
    }

    @Test
    fun blankInstructionTextBecomesContinue() {
        assertEquals("Continue", NavAnnouncer().onProgress(turn(text = ""), 50.0))
    }

    @Test
    fun reroutingWording() {
        assertEquals("Rerouting", NavAnnouncer().rerouting())
    }

    @Test
    fun spokenDistanceBuckets() {
        // Characterising, not improving: 1500 m reading "2 kilometers" is the
        // shipped rounding on both surfaces, and register entry 19's distance
        // quantisation is out of scope here.
        assertEquals("2 kilometers", spokenDistance(1500.0))
        assertEquals("3 kilometers", spokenDistance(2600.0))
        assertEquals("1 kilometer", spokenDistance(1499.0))
        assertEquals("1 kilometer", spokenDistance(950.0))
        assertEquals("900 meters", spokenDistance(949.0))
        assertEquals("100 meters", spokenDistance(100.0))
        assertEquals("100 meters", spokenDistance(99.0))
        assertEquals("80 meters", spokenDistance(84.0))
        assertEquals("0 meters", spokenDistance(4.0))
    }

    // ---- timed ladder and ", then" (#1012) --------------------------------

    private fun progress(
        distance: Double,
        instruction: NavInstruction? = turn(),
        following: NavInstruction? = null,
        followingAt: Double? = null,
    ) = NavEngine.Progress(
        offRouteMeters = 0.0,
        snappedAt = LatLon(0.0, 0.0),
        segmentBearingDeg = null,
        nextInstruction = instruction,
        distanceToTurnMeters = distance,
        remainingMeters = 10_000.0,
        routeMeters = 20_000.0,
        remainingTimeMs = null,
        speedLimitKmh = null,
        nextNextInstruction = following,
        distanceToNextNextMeters = followingAt,
    )

    private fun assertLadder(expected: Ladder, actual: Ladder, at: String) {
        assertEquals(expected.farMeters, actual.farMeters, 1e-9, "far at $at")
        assertEquals(expected.nearMeters, actual.nearMeters, 1e-9, "near at $at")
        assertEquals(expected.nowMeters, actual.nowMeters, 1e-9, "now at $at")
    }

    @Test
    fun timedLadderFollowsSpeedBetweenItsFloorsAndCeiling() {
        // Standing still: the floors, nothing added for speech.
        assertLadder(Ladder(400.0, 150.0, 30.0), timedLadder(0.0), "0 m/s")
        // 120 km/h ≈ 33.3 m/s: 25/10/3 s of travel plus 1.5 s of lead. The
        // last prompt now lands ~4.5 s before the exit, not 2.4 s.
        val v = 120.0 / 3.6
        assertLadder(Ladder(26.5 * v, 11.5 * v, 4.5 * v), timedLadder(v), "120 km/h")
        // 300 km/h: the far prompt stops at 2 km (plus lead).
        val fast = 300.0 / 3.6
        assertEquals(2000.0 + 1.5 * fast, timedLadder(fast).farMeters, 1e-9)
    }

    @Test
    fun townSpeedNoLongerWarnsAMinuteAndAHalfAhead() {
        val a = NavAnnouncer()
        val v = 30.0 / 3.6
        a.onProgress(progress(3000.0), v) // burns the start prompt
        // 800 m used to fire here: 96 s out at 30 km/h.
        assertNull(a.onProgress(progress(800.0), v))
        assertEquals("In 400 meters, Turn right", a.onProgress(progress(400.0), v))
        assertEquals("In 200 meters, Turn right", a.onProgress(progress(160.0), v))
        assertNull(a.onProgress(progress(60.0), v))
        assertEquals("Turn right", a.onProgress(progress(40.0), v))
        assertNull(a.onProgress(progress(5.0), v))
    }

    @Test
    fun motorwaySpeedWarnsEarlierAndCallsTheExitInTime() {
        val a = NavAnnouncer()
        val v = 120.0 / 3.6
        a.onProgress(progress(5000.0), v)
        assertEquals("In 900 meters, Turn right", a.onProgress(progress(880.0), v))
        assertEquals("In 400 meters, Turn right", a.onProgress(progress(380.0), v))
        // 80 m would be 2.4 s before the turn; the now prompt fires at ~150 m.
        assertEquals("Turn right", a.onProgress(progress(140.0), v))
    }

    @Test
    fun aCloseFollowingTurnIsChainedOnNearAndNowOnly() {
        val a = NavAnnouncer()
        val v = 30.0 / 3.6
        val left = turn("Turn left onto Kerkstraat", startIndex = 5)
        a.onProgress(progress(3000.0, following = left, followingAt = 3100.0), v)
        // Far prompt: just this turn — the next one would be forgotten by then.
        assertEquals(
            "In 400 meters, Turn right",
            a.onProgress(progress(400.0, following = left, followingAt = 500.0), v),
        )
        assertEquals(
            "In 200 meters, Turn right, then turn left onto Kerkstraat",
            a.onProgress(progress(160.0, following = left, followingAt = 260.0), v),
        )
        assertEquals(
            "Turn right, then turn left onto Kerkstraat",
            a.onProgress(progress(40.0, following = left, followingAt = 140.0), v),
        )
    }

    @Test
    fun aDistantFollowingTurnIsNotChained() {
        val a = NavAnnouncer()
        val v = 30.0 / 3.6
        val left = turn("Turn left", startIndex = 5)
        a.onProgress(progress(3000.0), v)
        // 151 m apart at 30 km/h: past both the 150 m floor and 8 s (67 m).
        assertEquals("Turn right", a.onProgress(progress(40.0, following = left, followingAt = 191.0), v))
    }

    @Test
    fun theChainingGapScalesWithSpeed() {
        val a = NavAnnouncer()
        val v = 120.0 / 3.6 // 8 s ≈ 267 m
        val left = turn("Keep left", startIndex = 5)
        a.onProgress(progress(5000.0), v)
        assertEquals(
            "Turn right, then keep left",
            a.onProgress(progress(140.0, following = left, followingAt = 400.0), v),
        )
    }

    @Test
    fun anUnusableSpeedCountsAsStandingStill() {
        for (bad in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val a = NavAnnouncer()
            a.onProgress(progress(3000.0), bad)
            assertNull(a.onProgress(progress(401.0), bad), "speed $bad")
            assertEquals("In 400 meters, Turn right", a.onProgress(progress(400.0), bad), "speed $bad")
        }
    }

    @Test
    fun noInstructionIsSilentOnTheTimedPathToo() {
        assertNull(NavAnnouncer().onProgress(progress(100.0, instruction = null), 10.0))
    }
}

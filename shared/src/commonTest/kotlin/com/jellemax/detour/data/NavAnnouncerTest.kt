package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Characterises the ladder both surfaces already implemented, including the one
 * boundary they disagreed on until convergence 3 made iOS inclusive. Written
 * before either surface was repointed, so a repoint that changes behaviour
 * fails here rather than in the field.
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
}

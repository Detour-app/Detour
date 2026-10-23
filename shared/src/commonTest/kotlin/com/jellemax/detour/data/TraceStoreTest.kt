package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [TraceStore.withUnsynced]: the /sync write-back keeping lines the upload never carried (#693).
 *  [TraceStore.withoutWindow]: a deleted trip taking its trace with it (#779). */
class TraceStoreTest {

    @Test
    fun aLineFlushedDuringTheRoundTripSurvivesTheWriteBack() {
        val uploaded = listOf("[a]", "[b]")
        // The recording trip flushed "[c]" after the payload was read.
        val current = uploaded + "[c]"

        val kept = TraceStore.withUnsynced(server = listOf("[a]", "[b]", "[x]"), uploaded.toSet(), current)

        assertEquals(listOf("[a]", "[b]", "[x]", "[c]"), kept, "the mid-sync segment was wiped and never reached the server")
    }

    @Test
    fun aLineTheServerDroppedIsNotRestoredFromTheUpload() {
        // Uploaded and not returned: the server's answer stands, as before.
        val kept = TraceStore.withUnsynced(server = listOf("[b]"), setOf("[a]", "[b]"), listOf("[a]", "[b]"))

        assertEquals(listOf("[b]"), kept)
    }

    @Test
    fun aLineTheServerAlreadyReturnedIsNotDuplicated() {
        val kept = TraceStore.withUnsynced(server = listOf("[a]", "[c]"), setOf("[a]"), listOf("[a]", "[c]"))

        assertEquals(listOf("[a]", "[c]"), kept)
    }

    @Test
    fun blankLinesAreDroppedFromBothSides() {
        val kept = TraceStore.withUnsynced(server = listOf("[a]", " "), emptySet(), listOf("", "[c]"))

        assertEquals(listOf("[a]", "[c]"), kept)
    }

    private fun point(lat: Double, timeMs: Long) = TraceStore.TracePoint(LatLon(lat, 4.0), timeMs, 50.0, null)

    private fun line(vararg times: Long) = TraceStore.encodeLine(times.map { point(50.0 + it / 1_000_000.0, it) })

    @Test
    fun aLineStraddlingTheTripIsSplitAroundIt() {
        // One buffer flush: idle before, the deleted trip, the next trip after.
        val kept = TraceStore.withoutWindow(listOf(line(1, 2, 10, 11, 12, 20, 21)), fromMs = 10, toMs = 12)

        assertEquals(listOf(line(1, 2), line(20, 21)), kept)
    }

    @Test
    fun aLineOutsideTheTripIsKeptByteForByte() {
        // Re-encoding would round differently from whatever wrote it, and sync
        // matches lines as strings: a changed line would upload as new.
        val untouched = "[[50.1,4.1,5,12.34,null],[50.2,4.2,6,13.0,null]]"

        assertEquals(listOf(untouched), TraceStore.withoutWindow(listOf(untouched), fromMs = 10, toMs = 12))
    }

    @Test
    fun aLineInsideTheTripAndALoneSurvivingPointAreDropped() {
        val kept = TraceStore.withoutWindow(listOf(line(10, 11), line(9, 10, 11)), fromMs = 10, toMs = 12)

        assertEquals(emptyList(), kept, "a one-point line is not a polyline")
    }

    @Test
    fun untimedPointsAndUndecodableLinesAreKept() {
        // Written before points carried a time: nothing places them in the trip.
        val legacy = "[[50.1,4.1],[50.2,4.2]]"
        val broken = "not json"

        assertEquals(listOf(legacy, broken), TraceStore.withoutWindow(listOf(legacy, broken), fromMs = 0, toMs = Long.MAX_VALUE))
    }

    @Test
    fun deletingATripDropsTheCoverageItEarned() {
        // ~2.2 km square, so a few dozen 250 m cells.
        val town = Municipality(1, "Town", listOf(listOf(LatLon(50.0, 4.0), LatLon(50.0, 4.03), LatLon(50.02, 4.03), LatLon(50.02, 4.0))))
        val crossing = (0..30).map { TraceStore.TracePoint(LatLon(50.01, 4.0 + it * 0.001), 100L + it, 50.0, null) }
        val lines = listOf(TraceStore.encodeLine(crossing))

        fun percent(lines: List<String>) =
            Coverage.entriesOf(TraceStore.parseLines(lines).flatten(), listOf(town)).single().percent

        val before = percent(lines)
        val after = percent(TraceStore.withoutWindow(lines, fromMs = 100, toMs = 115))

        assertTrue(after > 0.0 && after < before, "coverage went $before -> $after")
    }
}

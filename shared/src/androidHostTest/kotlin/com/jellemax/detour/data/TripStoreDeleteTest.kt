package com.jellemax.detour.data

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonArray
import okio.Path
import okio.Path.Companion.toPath

/**
 * [TripStore.delete] over real files: what it records for the server and what it
 * cuts from the trace must both honour the span cap (#877). Android-only because
 * [filesDirForTest] is what gives this target a files dir at all.
 */
class TripStoreDeleteTest {

    private lateinit var root: Path

    @BeforeTest
    fun startInAnEmptyFilesDir() {
        root = Files.createTempDirectory("trip-store-delete").toString().toPath()
        filesDirForTest = root
        AccountFiles.migrate(fileSystem, root)
        fileSystem.createDirectories(accountDir())
    }

    @AfterTest
    fun putTheGlobalsBack() {
        fileSystem.deleteRecursively(root)
        filesDirForTest = null
        AccountFiles.resetMigratedForTest()
        TraceStore.reset()
    }

    private val hour = 60L * 60 * 1000
    private val start = 1_700_000_000_000L

    private fun givenRide(endTimeMs: Long) {
        val trip = Trip(
            startTimeMs = start,
            endTimeMs = endTimeMs,
            distanceMeters = 1200.0,
            topSpeedMps = 30.0,
            destinationLat = null,
            destinationLon = null,
            mode = TravelMode.CAR,
        )
        accountFile("trips.json").writeText(buildJsonArray { add(TripStore.encode(trip)) }.toString())
    }

    private fun givenTrace(vararg lines: String) {
        accountFile("traces.jsonl").writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun line(vararg times: Long) = TraceStore.encodeLine(
        times.map { TraceStore.TracePoint(LatLon(50.0, 4.0), it, 50.0, null) },
    )

    @Test
    fun deletingARideCutsItsTraceAndRecordsItsWindow() {
        val end = start + hour
        val inside = line(start + 1, start + 2)
        val later = line(end + 1, end + 2)
        givenRide(end)
        givenTrace(inside, later)

        TripStore.delete(start)

        assertEquals(listOf(later), TraceStore.rawLines())
        assertEquals(mapOf(start to end), TripStore.deletedWindows())
        assertTrue(start in TripStore.deletedStartTimes())
        assertTrue(TripStore.load().isEmpty())
    }

    @Test
    fun deletingARideWithAnImplausibleSpanLeavesLaterRidesInTheTrace() {
        // A clock correction mid-ride made this one look years long.
        val end = start + 1000 * 24 * hour
        val later = line(start + 48 * hour + 1, start + 48 * hour + 2)
        givenRide(end)
        givenTrace(later)

        TripStore.delete(start)

        assertEquals(listOf(later), TraceStore.rawLines(), "a later ride's trace was cut by the bad span")
        assertFalse(start in TripStore.deletedWindows(), "the bad span would go to the server on every sync")
        assertTrue(start in TripStore.deletedStartTimes(), "the ride must still count as deleted")
        assertTrue(TripStore.load().isEmpty())
    }
}

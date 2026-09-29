package com.jellemax.detour.data

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogbookTest {

    private val utc = TimeZone.UTC

    /** 2026-09-27 was a Sunday. */
    private fun at(day: Int, hour: Int, month: Int = 9) =
        LocalDateTime(2026, month, day, hour, 0).toInstant(utc).toEpochMilliseconds()

    private fun trip(
        startMs: Long,
        mode: TravelMode = TravelMode.MOTO,
        km: Double = 50.0,
        twist: Double = 0.0,
    ) = Trip(
        startTimeMs = startMs, endTimeMs = startMs + 3_600_000, distanceMeters = km * 1000,
        topSpeedMps = 30.0, destinationLat = null, destinationLon = null, mode = mode,
        drivingStats = DrivingStats(twistinessScore = twist),
    )

    private fun place(name: String, new: Boolean = false) = PlaceVisit(name, new)

    @Test
    fun aRideIsTitledByDayTimeAndPlacesNewFirst() {
        assertEquals(
            "Sunday afternoon ride through Ronse and Gent",
            Logbook.autoTitle(trip(at(27, 14)), listOf(place("Gent"), place("Ronse", new = true), place("Aalst")), utc),
        )
        assertEquals("Sunday morning ride", Logbook.autoTitle(trip(at(27, 9)), emptyList(), utc))
    }

    @Test
    fun aCarTripLeadsWithTheTimeOfDayThenAToB() {
        val car = trip(at(27, 8), TravelMode.CAR)
        assertEquals(
            "Morning drive from Gent to Aalst",
            Logbook.autoTitle(car, listOf(place("Gent"), place("Merelbeke"), place("Aalst")), utc),
        )
        assertEquals("Evening drive around Gent", Logbook.autoTitle(trip(at(27, 19), TravelMode.CAR), listOf(place("Gent")), utc))
        assertEquals("Sunday morning drive", Logbook.autoTitle(car, emptyList(), utc))
    }

    @Test
    fun onlyAHighlightOrANewTownMakesARideStandOut() {
        fun ride(chip: String?, places: List<PlaceVisit>) = LogbookItem.Ride(trip(at(27, 8)), "t", false, chip, places)
        assertTrue(ride("Longest yet", emptyList()).standout)
        assertTrue(ride(null, listOf(place("Ronse", new = true))).standout)
        assertFalse(ride(null, listOf(place("Gent"), place("Aalst"))).standout)
        // One familiar town is the commute case: no stamps.
        assertFalse(ride(null, listOf(place("Gent"))).stampsWorthShowing)
        assertTrue(ride(null, listOf(place("Gent"), place("Aalst"))).stampsWorthShowing)
        assertTrue(ride(null, listOf(place("Ronse", new = true))).stampsWorthShowing)
    }

    @Test
    fun theMonthMapLeavesOutARideFarFromTheRest() {
        fun line(lat: Double, lon: Double) = listOf(LatLon(lat, lon), LatLon(lat + 0.01, lon + 0.01), LatLon(lat + 0.02, lon))
        val coast = listOf(line(51.20, 2.90), line(51.21, 2.95), line(51.19, 3.10), line(51.22, 2.92))
        val luxembourg = line(49.61, 6.13)
        assertEquals(coast, Logbook.mapFocus(coast + listOf(luxembourg)))
        // Too few lines to call any of them an outlier.
        assertEquals(listOf(coast[0], luxembourg), Logbook.mapFocus(listOf(coast[0], luxembourg)))
    }

    @Test
    fun weeksCountFromMondayAndSurviveADstSwitch() {
        val sunday = at(27, 14) // 2026-09-27
        assertEquals(0, Logbook.weeksAgo(at(21, 9), sunday, utc)) // Monday of the same week
        assertEquals(1, Logbook.weeksAgo(at(20, 23), sunday, utc)) // the Sunday before
        val brussels = TimeZone.of("Europe/Brussels")
        // 2026-03-29 is the spring-forward Sunday: the week before it is 167 h.
        val after = LocalDateTime(2026, 3, 31, 12, 0).toInstant(brussels).toEpochMilliseconds()
        val before = LocalDateTime(2026, 3, 24, 12, 0).toInstant(brussels).toEpochMilliseconds()
        assertEquals(1, Logbook.weeksAgo(before, after, brussels))
    }

    @Test
    fun chipsTakeTheFirstMatchInOrder() {
        val t1 = trip(at(1, 10), km = 40.0)
        val t2 = trip(at(2, 10), km = 30.0)
        val t3 = trip(at(3, 10), km = 30.0)
        val t4 = trip(at(4, 10), km = 30.0, twist = 0.2)
        val t5 = trip(at(5, 10), km = 90.0, twist = 0.1)
        val t6 = trip(at(6, 10), km = 10.0)
        val newestFirst = listOf(t6, t5, t4, t3, t2, t1)
        val chips = Logbook.chips(newestFirst, mapOf(t6.startTimeMs to listOf(place("Ronse", new = true))), utc)
        assertEquals("First moto trip", chips[t1.startTimeMs])
        assertNull(chips[t2.startTimeMs]) // too few earlier rides for "longest"
        assertEquals("Twistiest this month", chips[t4.startTimeMs])
        assertEquals("Longest yet", chips[t5.startTimeMs])
        assertEquals("1 new place", chips[t6.startTimeMs])
    }

    @Test
    fun aPlaceIsNewOnlyOnTheEarliestTripThatEnteredIt() {
        val gent = Municipality(1, "Gent", listOf(listOf(LatLon(0.0, 0.0), LatLon(0.0, 1.0), LatLon(1.0, 1.0))))
        val aalst = Municipality(2, "Aalst", listOf(listOf(LatLon(0.0, 0.0), LatLon(0.0, 1.0), LatLon(1.0, 1.0))))
        val older = trip(at(1, 10))
        val newer = trip(at(2, 10))
        // Passed newest-first, as the store holds them.
        val places = Logbook.placesByTrip(listOf(newer to listOf(gent, aalst), older to listOf(gent)))
        assertEquals(listOf(place("Gent", new = true)), places[older.startTimeMs])
        assertEquals(listOf(place("Gent"), place("Aalst", new = true)), places[newer.startTimeMs])
    }

    @Test
    fun buildGroupsMonthsNewestFirstAndKeepsMilestonesAmongFilteredRides() {
        val car = trip(at(20, 10, month = 8), TravelMode.CAR)
        val moto = trip(at(3, 10))
        val olderMoto = trip(at(2, 10, month = 7))
        val badge = LogbookItem.Milestone("First hundred", at(4, 10))
        val months = Logbook.build(listOf(moto, car, olderMoto), LogbookFilter.MOTO, emptyMap(), emptyMap(), listOf(badge), utc)
        assertEquals(listOf(9, 7), months.map { it.month })
        assertEquals(listOf(badge, months[0].rides.single()), months[0].items)
        assertEquals(listOf(moto), months[0].rides.map { it.trip })
    }

    @Test
    fun aMonthWithNoRidesUnderTheFilterIsDropped() {
        val car = trip(at(20, 10, month = 8), TravelMode.CAR)
        val badge = LogbookItem.Milestone("First hundred", at(21, 10, month = 8))
        assertTrue(Logbook.build(listOf(car), LogbookFilter.MOTO, emptyMap(), emptyMap(), listOf(badge), utc).isEmpty())
    }

    @Test
    fun anEditedTitleWinsOverTheGeneratedOne() {
        val t = trip(at(27, 14))
        val ride = Logbook.build(listOf(t), LogbookFilter.ALL, emptyMap(), mapOf(t.startTimeMs to "Coffee run"), emptyList(), utc)
            .single().rides.single()
        assertEquals("Coffee run", ride.title)
        assertTrue(ride.titleEdited)
    }

    @Test
    fun titlesRoundTripThroughTheirFileFormat() {
        val titles = mapOf(1L to "Say \"hi\"", 2L to "Plain")
        assertEquals(titles, TripTitleStore.decode(TripTitleStore.encode(titles)))
    }
}

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

    private fun on(year: Int, month: Int, day: Int, hour: Int = 10) =
        LocalDateTime(year, month, day, hour, 0).toInstant(utc).toEpochMilliseconds()

    @Test
    fun theWeekStreakCrossesNewYearAndStopsAtAGapWeek() {
        val wednesday = on(2027, 1, 6) // 2027-01-04 is a Monday
        val rides = listOf(
            on(2027, 1, 5), // this week
            on(2026, 12, 30), // last week, which spans New Year
            on(2026, 12, 22),
            // nothing in the week of 14 Dec
            on(2026, 12, 10),
        )
        assertEquals(3, Logbook.weekStreak(rides, wednesday, utc))
    }

    @Test
    fun theWeekStreakSurvivesARideFreeStartToTheWeek() {
        val mondayMorning = on(2027, 1, 4, hour = 8)
        assertEquals(2, Logbook.weekStreak(listOf(on(2026, 12, 30), on(2026, 12, 22)), mondayMorning, utc))
        // Neither this week nor last: the streak is over.
        assertEquals(0, Logbook.weekStreak(listOf(on(2026, 12, 22)), mondayMorning, utc))
        assertEquals(0, Logbook.weekStreak(emptyList(), mondayMorning, utc))
    }

    @Test
    fun theYearStripCountsThisYearsRidesUnderTheFilter() {
        val a = trip(on(2027, 1, 5), km = 60.0)
        val b = trip(on(2027, 1, 2), km = 40.0)
        val car = trip(on(2027, 1, 3), TravelMode.CAR)
        val lastYear = trip(on(2026, 12, 30))
        val places = mapOf(
            a.startTimeMs to listOf(place("Gent"), place("Ronse", new = true)),
            b.startTimeMs to listOf(place("Gent")),
            car.startTimeMs to listOf(place("Aalst")),
            lastYear.startTimeMs to listOf(place("Oudenaarde")),
        )
        val trips = listOf(a, car, b, lastYear)
        assertEquals(
            LogbookYear(year = 2027, meters = 100_000.0, towns = 2, rides = 2, weekStreak = 2),
            Logbook.year(trips, LogbookFilter.MOTO, places, on(2027, 1, 6), utc),
        )
        assertEquals(3, Logbook.year(trips, LogbookFilter.ALL, places, on(2027, 1, 6), utc).towns)
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
        val titles = mapOf(
            1L to TripTitleStore.Entry("Say \"hi\"", 10L),
            2L to TripTitleStore.Entry("", 20L),
        )
        assertEquals(titles, TripTitleStore.decode(TripTitleStore.encode(titles)))
    }

    private fun title(text: String, at: Long) = TripTitleStore.Entry(text, at)

    @Test
    fun aNewerServerTitleReplacesAnOlderLocalOne() {
        val merged = TripTitleStore.merge(mapOf(1L to title("Sunday ride", 4)), mapOf(1L to title("Coast road", 5)))
        assertEquals(title("Coast road", 5), merged[1L])
    }

    @Test
    fun anOlderServerTitleDoesNotRevertANewerLocalRename() {
        // A rename made while the sync was in flight: the response still holds the old title.
        val merged = TripTitleStore.merge(mapOf(1L to title("Coast road", 6)), mapOf(1L to title("Sunday ride", 5)))
        assertEquals(title("Coast road", 6), merged[1L])
    }

    @Test
    fun aClearedTitleOnTheServerStaysCleared() {
        val merged = TripTitleStore.merge(mapOf(1L to title("Coast road", 5)), mapOf(1L to title("", 6)))
        assertEquals(title("", 6), merged[1L])
    }

    @Test
    fun aTieGoesToTheServerSoTwoUnstampedTitlesConverge() {
        // Both devices renamed before titles carried a stamp: both read as edited at 0.
        val merged = TripTitleStore.merge(mapOf(1L to title("Mine", 0)), mapOf(1L to title("Theirs", 0)))
        assertEquals(title("Theirs", 0), merged[1L])
    }

    @Test
    fun titlesOnlyOneSideHoldsAreKept() {
        val merged = TripTitleStore.merge(mapOf(1L to title("Local", 5)), mapOf(2L to title("Remote", 5)))
        assertEquals(setOf(1L, 2L), merged.keys)
    }

    @Test
    fun titlesRoundTripThroughTheSyncWireForm() {
        val titles = mapOf(1L to title("Coast road", 5), 2L to title("", 6))
        assertEquals(titles, TripTitleStore.fromServer(TripTitleStore.toUpload(titles)))
    }

    @Test
    fun aMonthIsComparedToTheFurthestCityItReaches() {
        assertEquals("Gent to Paris, with 30 km left over", Logbook.distanceComparison(320_000.0))
        assertEquals("Gent to London, with 80 km left over", Logbook.distanceComparison(410_000.0))
        assertEquals("Gent to Paris", Logbook.distanceComparison(290_000.0))
        // Rounded to the km before comparing: 289.6 km reaches Paris.
        assertEquals("Gent to Paris", Logbook.distanceComparison(289_600.0))
        assertEquals("Gent to Brugge", Logbook.distanceComparison(50_000.0))
        assertNull(Logbook.distanceComparison(49_000.0))
        assertEquals("Gent to Nordkapp, with 2000 km left over", Logbook.distanceComparison(5_000_000.0))
    }

    @Test
    fun aMonthWrappedPicksEachHighlightFromItsOwnRide() {
        val sunday = trip(at(27, 14), twist = 0.4).copy(maxLeanAngleDeg = 38.0)
        val earlySunday = trip(at(20, 6), km = 120.0, twist = 0.7).copy(maxLeanAngleDeg = 31.0)
        // The car records a deeper "lean" (the phone sliding in its cradle)
        // that must not win.
        val car = trip(at(23, 8), TravelMode.CAR).copy(maxLeanAngleDeg = 50.0)
        val places = mapOf(
            sunday.startTimeMs to listOf(place("Gent"), place("Ronse", new = true)),
            earlySunday.startTimeMs to listOf(place("Gent", new = true), place("Ronse")),
        )
        val month = Logbook.build(listOf(sunday, car, earlySunday), LogbookFilter.ALL, places, emptyMap(), emptyList(), utc).single()
        val w = Logbook.wrapped(month, utc)
        assertEquals(2026 to 9, w.year to w.month)
        assertEquals(3, w.rides)
        assertEquals(220_000.0, w.meters)
        assertEquals(earlySunday, w.twistiest?.trip)
        assertEquals(earlySunday, w.earliestStart?.trip)
        assertEquals(sunday, w.deepestLean?.trip)
        assertEquals(7, w.favouriteWeekday) // Sunday
        assertEquals(2, w.favouriteWeekdayRides)
        // Oldest ride first, so Gent (20th) comes before Ronse (27th).
        assertEquals(listOf("Gent", "Ronse"), w.newTowns)
    }

    @Test
    fun earliestStartIsByTimeOfDayNotDate() {
        val month = Logbook.build(
            listOf(trip(at(1, 9)), trip(at(28, 7))), LogbookFilter.ALL, emptyMap(), emptyMap(), emptyList(), utc,
        ).single()
        assertEquals(at(28, 7), Logbook.wrapped(month, utc).earliestStart?.trip?.startTimeMs)
    }

    @Test
    fun aMonthWrappedLeavesOutWhatNoRideRecorded() {
        // Three rides on three weekdays, nothing twisty, car only.
        val trips = listOf(at(21, 8), at(22, 8), at(23, 8)).map { trip(it, TravelMode.CAR) }
        val w = Logbook.wrapped(Logbook.build(trips, LogbookFilter.ALL, emptyMap(), emptyMap(), emptyList(), utc).single(), utc)
        assertNull(w.twistiest)
        assertNull(w.deepestLean)
        assertNull(w.favouriteWeekday)
        assertEquals(0, w.favouriteWeekdayRides)
        assertTrue(w.newTowns.isEmpty())
    }

    @Test
    fun aFavouriteWeekdayTieGoesToTheMoreDistance() {
        // 2026-09-21 and 09-28 are Mondays; 09-22 and 09-29 Tuesdays.
        val trips = listOf(
            trip(at(21, 8), km = 10.0), trip(at(28, 8), km = 10.0),
            trip(at(22, 8), km = 80.0), trip(at(29, 8), km = 80.0),
        )
        val w = Logbook.wrapped(Logbook.build(trips, LogbookFilter.ALL, emptyMap(), emptyMap(), emptyList(), utc).single(), utc)
        assertEquals(2, w.favouriteWeekday) // Tuesday
    }
}

package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the bytes of the per-account files that can't be rebuilt — trips,
 * traces, saved places, routes, tombstones, mode edits, badges — plus the
 * enum names and badge ids those files store as strings.
 *
 * A round-trip test can't catch a renamed key: encode and decode change
 * together and still agree, while every file already on a phone (and on the
 * sync server) reads that field as its default. Every parser here is lenient
 * (`optDouble`, `TravelMode.of`), so that failure is silent — a zero
 * distance, a moto trip turned into a car trip — not a crash.
 *
 * Rules, for whoever is here because a test failed:
 * - The `V1` fixtures are bytes real installs hold. Never edit one.
 * - Adding a field: update [trip]/[route]/… and add a new `V2` fixture for the
 *   writer test; keep `V1` in the read test, decoding to the old values.
 * - If a `V1` fixture can no longer be read the way it was, that's a breaking
 *   data-format change: it needs a migration and a major `versionName` bump
 *   (CLAUDE.md "Versioning").
 */
class PersistedFormatTest {

    // --- trips.json ----------------------------------------------------------

    private val trip = Trip(
        startTimeMs = 1_726_000_000_000L,
        endTimeMs = 1_726_003_600_000L,
        distanceMeters = 84250.5,
        topSpeedMps = 38.9,
        maxLeanAngleDeg = 41.2,
        maxGForce = 0.87,
        destinationLat = 50.8503,
        destinationLon = 4.3517,
        mode = TravelMode.MOTO,
        drivingStats = DrivingStats(
            hardBrakeCount = 3, hardAccelCount = 2, hardCornerCount = 5,
            secondsOverLimit = 120, pctOverLimit = 4.5,
            roadTypeMeters = mapOf(
                HighwayClass.MOTORWAY to 40000.0,
                HighwayClass.ARTERIAL to 30000.5,
                HighwayClass.LOCAL to 14250.0,
            ),
            twistinessScore = 0.63, stopCount = 4, idleMs = 180_000L,
            obd2SpeedPct = 91.5, maxRpm = 8200.0, maxThrottlePct = 97.5,
            pctWideOpenThrottle = 6.5, avgRpm = 4100.0,
            fuelMilliliters = 5200L, fuelSampledMeters = 80_000L, fuelEstimated = true,
        ),
    )
    private val tripNoDestination = trip.copy(
        startTimeMs = 1_726_100_000_000L, endTimeMs = 1_726_101_800_000L, destinationLat = null, destinationLon = null,
        mode = TravelMode.CAR, drivingStats = DrivingStats(),
    )

    private val tripsV1 = """
        [{"startTimeMs":1726000000000,"endTimeMs":1726003600000,"distanceMeters":84250.5,
          "topSpeedMps":38.9,"maxLeanAngleDeg":41.2,"maxGForce":0.87,
          "destinationLat":50.8503,"destinationLon":4.3517,"mode":"MOTO",
          "drivingStats":{"hardBrakeCount":3,"hardAccelCount":2,"hardCornerCount":5,
            "secondsOverLimit":120,"pctOverLimit":4.5,
            "roadTypeMeters":{"MOTORWAY":40000.0,"ARTERIAL":30000.5,"LOCAL":14250.0},
            "twistinessScore":0.63,"stopCount":4,"idleMs":180000,"obd2SpeedPct":91.5,
            "maxRpm":8200.0,"maxThrottlePct":97.5,"pctWideOpenThrottle":6.5,"avgRpm":4100.0,
            "fuelMilliliters":5200,"fuelSampledMeters":80000,"fuelEstimated":true}},
         {"startTimeMs":1726100000000,"endTimeMs":1726101800000,"distanceMeters":84250.5,
          "topSpeedMps":38.9,"maxLeanAngleDeg":41.2,"maxGForce":0.87,
          "destinationLat":null,"destinationLon":null,"mode":"CAR",
          "drivingStats":{"hardBrakeCount":0,"hardAccelCount":0,"hardCornerCount":0,
            "secondsOverLimit":0,"pctOverLimit":0.0,"roadTypeMeters":{},
            "twistinessScore":0.0,"stopCount":0,"idleMs":0,"obd2SpeedPct":0.0,
            "maxRpm":0.0,"maxThrottlePct":0.0,"pctWideOpenThrottle":0.0,"avgRpm":0.0,
            "fuelMilliliters":0,"fuelSampledMeters":0,"fuelEstimated":false}}]
    """

    @Test
    fun tripsV1Reads() {
        assertEquals(listOf(trip, tripNoDestination), TripStore.decodeAll(tripsV1))
    }

    @Test
    fun tripsWriterMatchesV1() {
        val written = listOf(trip, tripNoDestination).map { TripStore.encode(it) }
        assertEquals(jsonArrayOf(tripsV1).toList(), written)
    }

    // --- traces.jsonl (one line per recorded segment) ------------------------

    private val trace = listOf(
        TraceStore.TracePoint(LatLon(50.8503, 4.3517), 1_726_000_000_000L, 42.5, 12.3),
        TraceStore.TracePoint(LatLon(50.851, 4.352), 1_726_000_001_000L, 43.0, null),
    )
    private val traceLineV1 =
        """[[50.8503,4.3517,1726000000000,42.5,12.3],[50.851,4.352,1726000001000,43.0,null]]"""

    @Test
    fun traceLineV1Reads() {
        assertEquals(trace, TraceStore.parsePoints(traceLineV1))
        assertEquals(listOf(trace.map { it.at }), TraceStore.parseLines(listOf(traceLineV1)))
    }

    @Test
    fun traceWriterMatchesV1() {
        assertEquals(jsonArrayOf(traceLineV1), jsonArrayOf(TraceStore.encodeLine(trace)))
    }

    // --- saved_places.json ---------------------------------------------------

    private val places = listOf(
        SavedPlace(1L, "Home", LatLon(50.85, 4.35), SavedPlaceKind.HOME),
        SavedPlace(2L, "Track day", LatLon(50.44, 5.97), SavedPlaceKind.FAVOURITE),
    )
    private val placesV1 = """
        [{"id":1,"name":"Home","lat":50.85,"lon":4.35,"kind":"HOME"},
         {"id":2,"name":"Track day","lat":50.44,"lon":5.97,"kind":"FAVOURITE"}]
    """

    @Test
    fun savedPlacesV1Reads() {
        assertEquals(places to false, decodeSavedPlaces(placesV1))
    }

    @Test
    fun savedPlacesWriterMatchesV1() {
        assertEquals(jsonArrayOf(placesV1), jsonArrayOf(encodeSavedPlaces(places)))
    }

    // --- routes.json (also the sync payload and the JSON export) -------------

    private val route = SavedRoute(
        id = 7L,
        name = "Ardennes loop",
        createdMs = 1_726_000_000_000L,
        mode = TravelMode.MOTO,
        stops = listOf(RouteStop(LatLon(50.85, 4.35), "Start"), RouteStop(LatLon(50.25, 5.7))),
        polyline = listOf(LatLon(50.85, 4.35), LatLon(50.5, 5.0), LatLon(50.25, 5.7)),
        distanceMeters = 152300.0,
        timeMs = 7_200_000L,
        sharedBy = "rider2",
    )
    private val routeV1 = """
        {"id":7,"name":"Ardennes loop","createdMs":1726000000000,"mode":"MOTO",
         "stops":[{"lat":50.85,"lon":4.35,"name":"Start"},{"lat":50.25,"lon":5.7}],
         "polyline":[50.85,4.35,50.5,5.0,50.25,5.7],
         "distanceMeters":152300.0,"timeMs":7200000,"sharedBy":"rider2"}
    """

    @Test
    fun routeV1Reads() {
        assertEquals(route, routeFromJson(jsonObjectOf(routeV1)))
    }

    @Test
    fun routeWriterMatchesV1() {
        assertEquals(jsonObjectOf(routeV1), route.toJson())
    }

    // --- deleted_trips.json / edited_modes.json -------------------------------
    // Both ride along with sync: tombstones that stop reading resurrect every
    // deleted trip on the next merge, and a lost override reverts a mode edit.

    private val tombstonesV1 = """[1726000000000,1726100000000]"""
    private val modeOverridesV1 = """{"1726000000000":"MOTO","1726100000000":"CAR"}"""

    @Test
    fun tombstonesV1ReadsAndWrites() {
        val ids = setOf(1_726_000_000_000L, 1_726_100_000_000L)
        assertEquals(ids, TripStore.decodeTombstones(tombstonesV1))
        assertEquals(jsonArrayOf(tombstonesV1), jsonArrayOf(TripStore.encodeTombstones(ids)))
    }

    @Test
    fun modeOverridesV1ReadsAndWrites() {
        val overrides = mapOf(1_726_000_000_000L to "MOTO", 1_726_100_000_000L to "CAR")
        assertEquals(overrides, TripStore.decodeModeOverrides(modeOverridesV1))
        assertEquals(jsonObjectOf(modeOverridesV1), jsonObjectOf(TripStore.encodeModeOverrides(overrides)))
    }

    // --- badges.json ---------------------------------------------------------

    private val earnedV1 = """{"dist_100000":1726000000000,"speed_130":1726100000000}"""

    @Test
    fun earnedBadgesV1ReadsAndWrites() {
        val earned = mapOf("dist_100000" to 1_726_000_000_000L, "speed_130" to 1_726_100_000_000L)
        assertEquals(earned, BadgeStore.decodeEarned(earnedV1))
        assertEquals(jsonObjectOf(earnedV1), jsonObjectOf(BadgeStore.encodeEarned(earned)))
    }

    // --- names stored as strings ---------------------------------------------

    /** Every enum constant name that has been written to a file. Renaming one
     *  doesn't fail to parse: `TravelMode.of` falls back to CAR, an unknown
     *  place kind to NONE, an unknown road class is dropped. */
    @Test
    fun storedEnumNamesStillExist() {
        assertStillPresent(listOf("MOTO", "CAR"), TravelMode.entries.map { it.name })
        assertStillPresent(listOf("MOTORWAY", "ARTERIAL", "LOCAL"), HighwayClass.entries.map { it.name })
        assertStillPresent(listOf("HOME", "WORK", "FAVOURITE", "NONE"), SavedPlaceKind.entries.map { it.name })
    }

    /** badges.json maps these ids to the time they were earned. An id is built
     *  from its threshold, so retuning a threshold silently un-earns it. */
    @Test
    fun earnedBadgeIdsStillExist() {
        assertStillPresent(
            listOf(
                "dist_100000", "dist_500000", "dist_1000000", "dist_5000000", "dist_10000000", "dist_25000000",
                "speed_100", "speed_130", "speed_160", "speed_200", "speed_250",
                "ride_100000", "ride_250000", "ride_500000",
                "muni_3", "muni_10", "muni_25", "muni_50",
                "cover_10", "cover_25", "cover_50", "cover_100",
            ),
            BadgeStore.ALL.map { it.id },
        )
    }

    private fun assertStillPresent(stored: List<String>, current: List<String>) {
        val missing = stored - current.toSet()
        assertTrue(missing.isEmpty(), "Stored in users' files but no longer defined: $missing")
    }
}

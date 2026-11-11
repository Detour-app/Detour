package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The per-trip upload shape and the fog-sharing choice — pure, no network or files. */
class SyncClientTest {

    @Test
    fun aTripStoredBeforeEditStampsExistedUploadsAsNeverEdited() {
        // Without the field the server takes the copy as an older client's and
        // lets it overwrite another device's edit (#486).
        val stored = jsonObjectOf("""{"startTimeMs":1000,"topSpeedMps":10.0,"mode":"CAR"}""")

        val uploaded = SyncClient.tripForUpload(stored)

        assertEquals(0L, uploaded.optLong("editedAtMs", -1L))
    }

    @Test
    fun anEditStampIsUploadedAsStored() {
        val stored = jsonObjectOf("""{"startTimeMs":1000,"topSpeedMps":10.0,"editedAtMs":5000}""")

        val uploaded = SyncClient.tripForUpload(stored)

        assertEquals(5000L, uploaded.optLong("editedAtMs"))
    }

    @Test
    fun theRiddenSpinSurvivesTheSyncRoundTrip() {
        // The server keeps a trip document opaque (SyncTests'
        // Unknown_trip_fields_survive_the_round_trip), so what comes back is
        // what was uploaded; the link has to survive upload and re-read (#590).
        val trip = Trip(
            startTimeMs = 1000L, endTimeMs = 2000L, distanceMeters = 1.0, topSpeedMps = 10.0,
            destinationLat = null, destinationLon = null, spinRouteId = 1_726_100_000_000L,
        )

        val uploaded = SyncClient.tripForUpload(TripStore.encode(trip))

        assertEquals(1_726_100_000_000L, TripStore.decodeTrip(uploaded).spinRouteId)
    }

    @Test
    fun aDeviceThatNeverToggledFogSharingLeavesItOutOfTheUpload() {
        // A fresh install defaults to off; sending that turned sharing off for
        // an account another device had turned on (#689).
        assertNull(SyncClient.shareFogForUpload(pending = false, value = false))
        assertNull(SyncClient.shareFogForUpload(pending = false, value = true))
    }

    @Test
    fun aToggleNotYetOnTheServerIsUploaded() {
        assertEquals(false, SyncClient.shareFogForUpload(pending = true, value = false))
        assertEquals(true, SyncClient.shareFogForUpload(pending = true, value = true))
    }

    @Test
    fun theServerValueReplacesALocalCopyNobodyTouchedDuringTheRoundTrip() {
        // Phone A turned sharing off; phone B still holds true and must take
        // the account's value rather than keep (and later resend) its own.
        assertEquals(false, SyncClient.shareFogFromServer(server = false, editsAtUpload = 0, editsNow = 0))
        assertEquals(true, SyncClient.shareFogFromServer(server = true, editsAtUpload = 3, editsNow = 3))
    }

    @Test
    fun aToggleDuringTheRoundTripIsNotOverwrittenByTheResponse() {
        assertNull(SyncClient.shareFogFromServer(server = true, editsAtUpload = 0, editsNow = 1))
    }

    @Test
    fun aResponseWithoutShareFogKeepsTheLocalValue() {
        assertNull(SyncClient.shareFogFromServer(server = null, editsAtUpload = 0, editsNow = 0))
    }

    // --- deletedTripWindows: a deleted ride with no server row (#877) -------

    @Test
    fun aDeletedRidesWindowUploadsInTheShapeTheServerReads() {
        val uploaded = SyncClient.deletedTripWindows(mapOf(1000L to 2000L))

        assertEquals(jsonArrayOf("""[{"startTimeMs":1000,"endTimeMs":2000}]"""), uploaded)
    }

    // --- deletedTripStartTimes: rides deleted on another device (#778) ------

    @Test
    fun aRideDeletedOnAnotherDeviceIsDroppedFromThisOnesMerge() {
        // Device B still holds 1000 and uploaded it; the server kept A's
        // tombstone, so the answer names it instead of returning it.
        val merged = jsonObjectOf(
            """{"trips":[{"startTimeMs":1000},{"startTimeMs":2000}],"deletedTripStartTimes":[1000]}""",
        )

        val tombstones = SyncClient.deletedTripStarts(merged)
        val kept = TripStore.mergeServerTrips(merged.optArray("trips")!!, tombstones, mutableMapOf(), emptyMap())

        assertEquals(setOf(1000L), tombstones)
        assertEquals(listOf(2000L), kept.objects().map { it.optLong("startTimeMs") })
    }

    @Test
    fun aResponseWithoutTombstonesAddsNone() {
        // An older server omits the field; nothing is deleted on its account.
        assertEquals(emptySet(), SyncClient.deletedTripStarts(jsonObjectOf("""{"trips":[]}""")))
    }

    @Test
    fun anUnreadableTombstoneIsIgnored() {
        val merged = jsonObjectOf("""{"deletedTripStartTimes":[0,"x",3000]}""")

        assertEquals(setOf(3000L), SyncClient.deletedTripStarts(merged))
    }

    // --- deletedSavedRouteIds: routes deleted elsewhere (#910) ---------------

    @Test
    fun routeTombstonesAreReadFromTheResponse() {
        val merged = jsonObjectOf("""{"savedRoutes":[],"deletedSavedRouteIds":[26,0,"x",27]}""")

        assertEquals(setOf(26L, 27L), SyncClient.deletedRouteIds(merged))
    }

    @Test
    fun aResponseWithoutRouteTombstonesAddsNone() {
        // A server that predates #909 omits the field.
        assertEquals(emptySet(), SyncClient.deletedRouteIds(jsonObjectOf("""{"savedRoutes":[]}""")))
    }
}

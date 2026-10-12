package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** The per-trip upload shape — pure JSON, no network or files. */
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
}

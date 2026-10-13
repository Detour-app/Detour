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
}

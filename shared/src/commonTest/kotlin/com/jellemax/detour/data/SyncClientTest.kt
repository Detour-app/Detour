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
}

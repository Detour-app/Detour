package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tombstone half of [SavedPlaces] (#695): a place removed on this device
 * stays removed when the server's union still holds it. Tested through the
 * pure helpers [SavedPlaces.replaceFromServer] and [SavedPlaces.remove] call,
 * since the store's file I/O needs a platform Context.
 */
class SavedPlaceTombstoneTest {

    private fun place(id: Long, name: String) = SavedPlace(id, name, LatLon(50.85, 5.69))

    @Test fun aRemovedPlaceIsDroppedFromTheServerUnion() {
        val server = listOf(place(1, "Home"), place(2, "Work"))
        assertEquals(listOf(2L), withoutDeleted(server, setOf(1L)).map { it.id })
    }

    @Test fun noTombstonesKeepsTheServerUnionAsIs() {
        val server = listOf(place(1, "Home"), place(2, "Work"))
        assertEquals(server, withoutDeleted(server, emptySet()))
    }

    @Test fun deletedIdsRoundTripThroughTheFile() {
        val ids = setOf(1_700_000_000_000L, 42L)
        assertEquals(ids, decodeDeletedIds(encodeDeletedIds(ids)))
    }

    @Test fun anEmptyTombstoneFileDecodesToNoIds() {
        assertEquals(emptySet(), decodeDeletedIds("[]"))
    }
}

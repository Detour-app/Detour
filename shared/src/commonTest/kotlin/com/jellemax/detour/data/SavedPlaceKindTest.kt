package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The kind rules on [SavedPlace], tested through the store's pure halves —
 * [withKind] (the singleton invariant), [encodeSavedPlaces]/[decodeSavedPlaces]
 * (the round trip) and the migration off name matching. The store itself does
 * file I/O through the ambient `appFilesDir()`, which needs a platform Context
 * (see [SavedPlacesLoadOrderTest]); these functions carry the logic that a
 * regression would actually break, and take no file.
 */
class SavedPlaceKindTest {

    private fun place(id: Long, name: String, kind: SavedPlaceKind = SavedPlaceKind.NONE) =
        SavedPlace(id, name, LatLon(50.85 + id, 5.69), kind)

    // --- the singleton invariant (withKind) --------------------------------

    @Test fun markingASecondHomeDemotesTheFirst() {
        val places = listOf(place(1, "Old home", SavedPlaceKind.HOME), place(2, "New home"))
        val after = withKind(places, id = 2, kind = SavedPlaceKind.HOME)
        assertEquals(listOf(2L), after.filter { it.kind == SavedPlaceKind.HOME }.map { it.id })
    }

    @Test fun markingASecondWorkDemotesTheFirst() {
        val places = listOf(place(1, "Old work", SavedPlaceKind.WORK), place(2, "New work"))
        val after = withKind(places, id = 2, kind = SavedPlaceKind.WORK)
        assertEquals(listOf(2L), after.filter { it.kind == SavedPlaceKind.WORK }.map { it.id })
    }

    @Test fun anyNumberOfFavouritesCoexist() {
        var places = listOf(place(1, "A"), place(2, "B"), place(3, "C"))
        places = withKind(places, 1, SavedPlaceKind.FAVOURITE)
        places = withKind(places, 2, SavedPlaceKind.FAVOURITE)
        places = withKind(places, 3, SavedPlaceKind.FAVOURITE)
        assertEquals(3, places.count { it.kind == SavedPlaceKind.FAVOURITE })
    }

    @Test fun markingHomeLeavesWorkAlone() {
        val places = listOf(place(1, "Work", SavedPlaceKind.WORK), place(2, "Home"))
        val after = withKind(places, 2, SavedPlaceKind.HOME)
        assertEquals(SavedPlaceKind.WORK, after.single { it.id == 1L }.kind)
    }

    // --- the round trip (encode / decode) ----------------------------------

    @Test fun kindSurvivesTheEncodeDecodeRoundTrip() {
        val places = listOf(
            place(1, "Casa", SavedPlaceKind.HOME),
            place(2, "Office", SavedPlaceKind.WORK),
            place(3, "Gym", SavedPlaceKind.FAVOURITE),
            place(4, "Aunt", SavedPlaceKind.NONE),
        )
        val (decoded, migrated) = decodeSavedPlaces(encodeSavedPlaces(places))
        assertTrue(!migrated, "a payload we just wrote already carries kinds")
        assertEquals(
            places.associate { it.name to it.kind },
            decoded.associate { it.name to it.kind },
        )
    }

    @Test fun aRenamedHomeKeepsItsKindAcrossTheRoundTrip() {
        // The name no longer decides the kind, so a home renamed away from
        // "Home" must not lose it on the next load.
        val home = place(1, "Home", SavedPlaceKind.HOME)
        val renamed = home.copy(name = "Huis")
        val (decoded, _) = decodeSavedPlaces(encodeSavedPlaces(listOf(renamed)))
        assertEquals(SavedPlaceKind.HOME, decoded.single().kind)
    }

    // --- migration off name matching ---------------------------------------

    @Test fun aPreKindPayloadPromotesHomeAndWorkByNameOnce() {
        val legacy = """
            [{"id":1,"name":"home","lat":51.0,"lon":4.0},
             {"id":2,"name":"WORK","lat":51.1,"lon":4.1},
             {"id":3,"name":"Gym","lat":51.2,"lon":4.2}]
        """.trimIndent()
        val (decoded, migrated) = decodeSavedPlaces(legacy)
        assertTrue(migrated, "a payload with no kind field needs persisting")
        assertEquals(SavedPlaceKind.HOME, decoded.single { it.id == 1L }.kind)
        assertEquals(SavedPlaceKind.WORK, decoded.single { it.id == 2L }.kind)
        assertEquals(SavedPlaceKind.NONE, decoded.single { it.id == 3L }.kind)
    }

    @Test fun twoLegacyHomesPromoteToExactlyOneHome() {
        val legacy = """
            [{"id":1,"name":"Home","lat":51.0,"lon":4.0},
             {"id":2,"name":"home","lat":51.1,"lon":4.1}]
        """.trimIndent()
        val (decoded, _) = decodeSavedPlaces(legacy)
        assertEquals(1, decoded.count { it.kind == SavedPlaceKind.HOME })
    }

    // --- forward compatibility ---------------------------------------------

    @Test fun anUnknownKindValueReadsAsNoneNotAsTheNameMatch() {
        // A newer client's kind we don't know about must not fall back to the
        // name — the field is authoritative once present.
        val payload = """[{"id":1,"name":"home","lat":51.0,"lon":4.0,"kind":"SECRET"}]"""
        val (decoded, migrated) = decodeSavedPlaces(payload)
        assertTrue(!migrated, "the kind field is present, so nothing needs promoting")
        assertEquals(SavedPlaceKind.NONE, decoded.single().kind)
    }

    @Test fun anUnknownExtraFieldNeverDropsThePlace() {
        // An old client reading a new client's payload ignores fields it does
        // not know, rather than failing the parse.
        val payload = """[{"id":1,"name":"Gym","lat":51.0,"lon":4.0,"kind":"FAVOURITE","colour":"red"}]"""
        val (decoded, _) = decodeSavedPlaces(payload)
        assertEquals(1, decoded.size)
        assertEquals(SavedPlaceKind.FAVOURITE, decoded.single().kind)
    }
}

package com.jellemax.detour.presentation

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.SavedPlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure mapping from stored places to the Saved places list. The app stores a
 * name and a coordinate and nothing else, so the coordinate IS the subtitle —
 * there is no address to show (see the plan's deferred table).
 */
class PlacesStateTest {

    private fun place(id: Long, name: String, lat: Double, lon: Double) =
        SavedPlace(id = id, name = name, location = LatLon(lat, lon))

    private val home = place(1L, "Home", 50.851368, 5.690972)
    private val work = place(2L, "Work", 50.842100, 5.706400)

    @Test fun everyStoredPlaceBecomesARow() {
        assertEquals(2, placesStateFrom(listOf(home, work)).size)
    }

    @Test fun theSubtitleIsTheCoordinateToFiveDecimals() {
        val row = placesStateFrom(listOf(home)).single()
        assertEquals("50.85137, 5.69097", row.subtitle)
    }

    @Test fun southernAndWesternCoordinatesKeepTheirSign() {
        val row = placesStateFrom(listOf(place(3L, "Ushuaia", -54.80191, -68.30295))).single()
        assertEquals("-54.80191, -68.30295", row.subtitle)
    }

    @Test fun aRowCarriesItsPlaceIdSoTheScreenCanRenameOrRemoveIt() {
        assertEquals(1L, placesStateFrom(listOf(home)).single().id)
    }

    @Test fun rowOrderFollowsTheStoreRatherThanBeingResortedHere() {
        // SavedPlaces already sorts by lowercased name; re-sorting would be a
        // second source of truth that could disagree with the map's chips.
        val rows = placesStateFrom(listOf(work, home))
        assertEquals(listOf("Work", "Home"), rows.map { it.name })
    }

    @Test fun anEmptyStoreMapsToNoRows() {
        // loaded now lives only on PlacesPresenter's own PlacesState (see
        // its KDoc) — this mapper has no opinion on it, just the row list.
        assertTrue(placesStateFrom(emptyList()).isEmpty())
    }
}

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

    @Test fun theCoordinatePairKeepsAPeriodDecimalWhateverTheSeparatorSetting() {
        // The subtitle is a COMMA-SEPARATED PAIR, so a comma decimal would
        // render "50,85137, 5,69097" — ambiguous to read and wrong to copy or
        // share. Counting characters catches a different separator being
        // hardcoded here.
        val row = placesStateFrom(listOf(home)).single()
        assertEquals(1, row.subtitle.count { it == ',' })
        assertEquals(2, row.subtitle.count { it == '.' })
        // ...and each half has to survive being read back as a number, which is
        // what "stays readable" means for anything downstream of a copy.
        val halves = row.subtitle.split(", ")
        assertEquals(2, halves.size)
        assertEquals(50.85137, halves[0].toDouble())
        assertEquals(5.69097, halves[1].toDouble())
    }

    @Test fun noCoordinateRenderingRouteCanBeHandedASeparatorAtAll() {
        // Character counting alone would stay green if someone gave either
        // function a `sep: Char = '.'` parameter and had the app pass the
        // rider's setting in — the test would simply keep taking the default.
        //
        // Calling through a function reference closes that: a reference is
        // invoked with no default arguments applied, so growing a second
        // parameter — defaulted or not — makes these two lines stop compiling
        // rather than quietly keep passing. Coordinates take no separator by
        // any route, which is the actual rule.
        val mapper = ::placesStateFrom
        val pair = ::formatCoordinatePair
        assertEquals("50.85137, 5.69097", mapper(listOf(home)).single().subtitle)
        assertEquals("50.85137, 5.69097", pair(50.851368, 5.690972))
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

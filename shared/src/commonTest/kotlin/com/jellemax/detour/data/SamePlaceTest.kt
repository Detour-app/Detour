package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Two results are one place only if they share a primary name **and** sit close together;
 *  two branches of a chain in one city share a label and must both survive (#1004). */
class SamePlaceTest {

    // ~0.009° of latitude is ~1 km; ~0.001° is ~111 m.
    private val gentA = GeocodeResult("Colruyt, Gent, België", LatLon(51.050, 3.720))
    private val gentB = GeocodeResult("Colruyt, Gent, België", LatLon(51.059, 3.720))

    @Test fun sameLabelFarApartIsTwoPlaces() {
        assertFalse(gentA.isSamePlaceAs(gentB))
    }

    @Test fun sameLabelCloseTogetherIsOnePlace() {
        assertTrue(gentA.isSamePlaceAs(gentA.copy(location = LatLon(51.051, 3.720))))
    }

    @Test fun onlyThePrimaryNameHasToMatch() {
        val kortrijk = GeocodeResult("Kortrijk, België", LatLon(50.828, 3.265))
        assertTrue(kortrijk.isSamePlaceAs(GeocodeResult("Kortrijk, West-Vlaanderen, België", LatLon(50.829, 3.265))))
    }

    @Test fun differentNamesAtOneSpotAreTwoPlaces() {
        assertFalse(gentA.isSamePlaceAs(GeocodeResult("Aldi, Gent, België", gentA.location)))
    }

    @Test fun pickingABranchKeepsTheOtherBranchInRecents() {
        assertEquals(listOf(gentB, gentA), RecentSearchStore.withPicked(listOf(gentA), gentB))
    }

    @Test fun pickingTheSamePlaceAgainMovesItToTheFrontOnce() {
        val other = GeocodeResult("Gravensteen, Gent, België", LatLon(51.057, 3.721))
        val again = gentA.copy(location = LatLon(51.0505, 3.720))
        assertEquals(listOf(again, other), RecentSearchStore.withPicked(listOf(other, gentA), again))
    }

    @Test fun recentsAreCapped() {
        val many = (0 until 20).map { GeocodeResult("Place $it", LatLon(50.0 + it, 4.0)) }
        assertEquals(8, RecentSearchStore.withPicked(many, gentA).size)
    }
}

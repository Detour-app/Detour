package com.jellemax.detour.presentation

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.SavedPlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [homeShortcutPlaces]: which of a rider's saved places reach the home sheet's
 * shortcut chips, and in what order. The rule caps the row at three so the
 * Spin and Save-pin chips beside them stay on screen, which is the part a
 * regression here would take away.
 */
class HomeShortcutPlacesTest {

    private fun place(id: Long, name: String) =
        SavedPlace(id = id, name = name, location = LatLon(50.85 + id, 5.69))

    private val home = place(1L, "Home")
    private val work = place(2L, "Work")
    private val gym = place(3L, "Gym")
    private val mum = place(4L, "Mum")
    private val pass = place(5L, "Stelvio")

    @Test fun noSavedPlacesGivesNoChips() {
        assertEquals(emptyList(), homeShortcutPlaces(emptyList(), seed = 0))
    }

    @Test fun homeAloneIsTheWholeRow() {
        assertEquals(listOf(home), homeShortcutPlaces(listOf(home), seed = 7))
    }

    @Test fun homeAndWorkComeFirstWhateverOrderTheStoreHoldsThem() {
        val chips = homeShortcutPlaces(listOf(gym, work, mum, home), seed = 0)
        assertEquals(listOf("Home", "Work"), chips.take(2).map { it.name })
    }

    @Test fun theThirdChipIsOneOfTheRestAndIsTheSameForTheSameSeed() {
        val places = listOf(home, work, gym, mum, pass)
        val chips = homeShortcutPlaces(places, seed = 4)
        assertEquals(3, chips.size)
        assertTrue(chips[2] in listOf(gym, mum, pass), "third chip was ${chips[2].name}")
        // Same seed, same pick: the row must not flicker while it is on screen,
        // so the caller holds one seed for as long as it wants the pick to hold.
        assertEquals(chips, homeShortcutPlaces(places, seed = 4))
    }

    @Test fun anotherSeedCanReachAnotherPlace() {
        val places = listOf(home, work, gym, mum, pass)
        val picked = (0..2).map { homeShortcutPlaces(places, seed = it)[2] }.toSet()
        assertEquals(setOf(gym, mum, pass), picked)
    }

    @Test fun aNegativeSeedStillPicksAPlace() {
        // Callers roll a plain Random.nextInt(), which is negative half the
        // time; a bare `%` here would index out of bounds on those.
        val chips = homeShortcutPlaces(listOf(gym, mum), seed = -7)
        assertEquals(1, chips.size)
        assertTrue(chips[0] in listOf(gym, mum))
    }

    @Test fun withNoHomeOrWorkTheRowIsOneRandomPlace() {
        val chips = homeShortcutPlaces(listOf(gym, mum, pass), seed = 1)
        assertEquals(1, chips.size)
        assertTrue(chips[0] in listOf(gym, mum, pass))
    }

    @Test fun aSecondPlaceNamedHomeIsNeverTheThirdChip() {
        // Two shortcuts of the same name are indistinguishable in the row, so
        // the duplicate must not come back as the "other" place.
        val otherHome = place(6L, "home")
        assertEquals(listOf(home, work), homeShortcutPlaces(listOf(home, otherHome, work), seed = 3))
    }
}

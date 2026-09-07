package com.jellemax.detour.presentation

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.SavedPlace
import com.jellemax.detour.data.SavedPlaceKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [homeShortcutPlaces]: which of a rider's saved places reach the home sheet's
 * shortcut chips, and in what order — Home, then Work, then the favourites the
 * rider marked, with no randomness. #268 replaced #204's random third chip with
 * this rule; the sheet caps the row by scrolling (see `ShortcutChipRow`), not
 * by a count here, so this function returns every favourite.
 */
class HomeShortcutPlacesTest {

    private fun place(id: Long, name: String, kind: SavedPlaceKind = SavedPlaceKind.NONE) =
        SavedPlace(id = id, name = name, location = LatLon(50.85 + id, 5.69), kind = kind)

    private val home = place(1L, "Huis", SavedPlaceKind.HOME)
    private val work = place(2L, "Kantoor", SavedPlaceKind.WORK)
    private val gymFav = place(3L, "Gym", SavedPlaceKind.FAVOURITE)
    private val mumFav = place(4L, "Mum", SavedPlaceKind.FAVOURITE)
    private val plain = place(5L, "Stelvio")

    @Test fun noSavedPlacesGivesNoChips() {
        assertEquals(emptyList(), homeShortcutPlaces(emptyList()))
    }

    @Test fun homeComesFirstThenWorkThenFavourites() {
        val row = homeShortcutPlaces(listOf(gymFav, work, mumFav, home))
        assertEquals(listOf(home, work, gymFav, mumFav), row)
    }

    @Test fun theRowIsIdenticalOnEveryVisitOfAnUnchangedStore() {
        val places = listOf(home, work, gymFav, mumFav, plain)
        assertEquals(homeShortcutPlaces(places), homeShortcutPlaces(places))
    }

    @Test fun aPlainPlaceNeverAppears() {
        val row = homeShortcutPlaces(listOf(home, work, gymFav, plain))
        assertEquals(listOf(home, work, gymFav), row)
    }

    @Test fun favouritesKeepTheStoreOrder() {
        // The store hands them in sorted order; the row must not resort them.
        val a = place(6L, "Aachen", SavedPlaceKind.FAVOURITE)
        val z = place(7L, "Zurich", SavedPlaceKind.FAVOURITE)
        assertEquals(listOf(a, z), homeShortcutPlaces(listOf(a, z)))
    }

    @Test fun everyFavouriteIsReturnedNotJustOne() {
        // The dice showed one of the "others"; the row now carries them all,
        // and the sheet scrolls to reach the ones past the edge.
        val favs = (10L..15L).map { place(it, "Fav $it", SavedPlaceKind.FAVOURITE) }
        assertEquals(favs, homeShortcutPlaces(favs))
    }

    @Test fun withNoHomeWorkOrFavouritesTheRowIsEmpty() {
        // No fallback to an arbitrary place — a plain place stays off the row.
        assertEquals(emptyList(), homeShortcutPlaces(listOf(plain, place(8L, "Cafe"))))
    }

    @Test fun onlyTheFirstHomeAndWorkLeadEvenIfTheStoreHoldsMore() {
        // The store keeps at most one of each, but the selection must not offer
        // a stray second HOME/WORK either.
        val otherHome = place(9L, "Cabin", SavedPlaceKind.HOME)
        val row = homeShortcutPlaces(listOf(home, otherHome, work))
        assertEquals(listOf(home, work), row)
    }
}

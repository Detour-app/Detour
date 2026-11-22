package com.jellemax.detour.presentation

import com.jellemax.detour.data.GeocodeResult
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.SavedPlace
import com.jellemax.detour.data.SavedPlaceKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `runBlocking` with the real [SearchSession.DEBOUNCE_MS], as in [TrafficKeyPresenterTest]:
 * commonTest has no kotlinx-coroutines-test, so each lookup here costs one real debounce.
 */
class SearchSessionTest {

    private val gent = LatLon(51.05, 3.72)
    private val home = GeocodeResult("Kerkstraat, Gent, België", gent)
    private val shop = GeocodeResult("Colruyt, Gent, België", LatLon(51.06, 3.73))

    private class Lookup(val query: String, val near: LatLon?)

    private val lookups = mutableListOf<Lookup>()

    private fun session(answer: suspend (String) -> List<GeocodeResult>) =
        SearchSession(search = { q, near -> lookups += Lookup(q, near); answer(q) })

    private suspend fun SearchSession.awaitState(predicate: (SearchState) -> Boolean): SearchState =
        withTimeout(5_000) { state.first(predicate) }

    @Test
    fun aQueryBelowTheFloorListsMatchingRecentsAndAsksNobody() = runBlocking {
        val s = session { emptyList() }
        s.setRecents(listOf(home, shop))
        val queries = MutableStateFlow("")
        val job = launch { s.run(queries) }

        assertEquals(listOf(home, shop), s.awaitState { it.results.size == 2 }.results)
        queries.value = "c"
        assertEquals(listOf(shop), s.awaitState { it.query == "c" }.results)
        delay(SearchSession.DEBOUNCE_MS * 2)
        assertTrue(lookups.isEmpty())
        job.cancelAndJoin()
    }

    @Test
    fun liveHitsFollowMatchingRecentsWithoutRepeatingThem() = runBlocking {
        val otherBranch = GeocodeResult("Colruyt, Gent, België", LatLon(51.10, 3.80))
        val s = session { listOf(shop.copy(location = LatLon(51.0601, 3.7301)), otherBranch) }
        s.setRecents(listOf(home, shop))
        val queries = MutableStateFlow("colruyt")
        val job = launch { s.run(queries) }

        val done = s.awaitState { it.query == "colruyt" && !it.searching && it.results.size > 1 }
        assertEquals(listOf(shop, otherBranch), done.results)
        assertFalse(done.noResults)
        assertNull(done.failure)
        job.cancelAndJoin()
    }

    @Test
    fun anEmptyQueryOffersSavedPlacesThenRecentsNotAlreadyAmongThem() = runBlocking {
        val homePlace = SavedPlace(1, "Home", LatLon(51.0, 3.7), SavedPlaceKind.HOME)
        val shopPlace = SavedPlace(2, "Colruyt", shop.location)
        val s = session { emptyList() }
        s.setRecents(listOf(home, GeocodeResult("Colruyt", LatLon(51.0601, 3.7301))))
        s.setSavedPlaces(listOf(homePlace, shopPlace))
        val job = launch { s.run(MutableStateFlow("")) }

        val expected = listOf(GeocodeResult("Home", homePlace.location), GeocodeResult("Colruyt", shop.location), home)
        val done = s.awaitState { it.saved.size == 2 && it.recents.size == 2 }
        assertEquals(expected, done.results)
        assertEquals(expected, done.suggestions)
        job.cancelAndJoin()
    }

    @Test
    fun aMatchingSavedPlaceComesFirstAndALiveHitForItIsNotRepeated() = runBlocking {
        val saved = GeocodeResult("Home", LatLon(51.0, 3.7))
        val homeStreet = GeocodeResult("Homestraat, Gent, België", gent)
        val s = session { listOf(saved.copy(location = LatLon(51.0001, 3.7001)), homeStreet) }
        s.setRecents(listOf(home, shop))
        s.setSavedPlaces(listOf(SavedPlace(1, saved.name, saved.location), SavedPlace(2, "Work", gent)))
        val queries = MutableStateFlow("")
        val job = launch { s.run(queries) }

        s.awaitState { it.saved.size == 2 }
        queries.value = "h"
        assertEquals(listOf(saved), s.awaitState { it.query == "h" }.results)
        queries.value = "home"
        val done = s.awaitState { it.query == "home" && !it.searching && it.results.size > 1 }
        assertEquals(listOf(saved, homeStreet), done.results)
        job.cancelAndJoin()
    }

    @Test
    fun typingWithinTheDebounceAsksOnceForTheLastQuery() = runBlocking {
        val s = session { listOf(home) }
        val queries = MutableStateFlow("")
        val job = launch { s.run(queries) }

        for (q in listOf("ke", "ker", "kerk")) {
            queries.value = q
            delay(SearchSession.DEBOUNCE_MS / 4)
        }
        s.awaitState { it.query == "kerk" && it.results.isNotEmpty() }
        assertEquals(listOf("kerk"), lookups.map { it.query })
        job.cancelAndJoin()
    }

    @Test
    fun aSupersededLookupIsNeitherAFailureNorEmpty() = runBlocking {
        val first = CompletableDeferred<List<GeocodeResult>>()
        val s = session { q -> if (q == "ke") first.await() else listOf(home) }
        val queries = MutableStateFlow("ke")
        val job = launch { s.run(queries) }

        s.awaitState { it.searching }
        queries.value = "kerk"
        // The cancelled lookup drops its spinner at once, and says nothing while the next one waits.
        val pending = s.awaitState { it.query == "kerk" }
        assertFalse(pending.searching)
        assertNull(pending.failure)
        assertFalse(pending.noResults)
        assertEquals(listOf(home), s.awaitState { it.results.isNotEmpty() }.results)
        job.cancelAndJoin()
    }

    @Test
    fun aFailedLookupIsAFailureAndAnEmptyOneIsNoResults() = runBlocking {
        val down = IOException("unreachable")
        val s = session { q -> if (q == "down") throw down else emptyList() }
        val queries = MutableStateFlow("down")
        val job = launch { s.run(queries) }

        val failed = s.awaitState { it.failure != null }
        assertSame(down, failed.failure)
        assertFalse(failed.noResults)
        assertFalse(failed.searching)

        queries.value = "nowhere"
        val empty = s.awaitState { it.query == "nowhere" && it.noResults }
        assertNull(empty.failure)
        job.cancelAndJoin()
    }

    @Test
    fun biasIsTheAnchorElseTheLastAccurateFix() {
        val s = SearchSession(search = { _, _ -> emptyList() })
        assertNull(s.bias())

        s.onFix(gent, accuracyMeters = 12f)
        assertEquals(gent, s.bias())
        // A wide fix does not replace a good one.
        s.onFix(LatLon(50.0, 4.0), accuracyMeters = SearchSession.MAX_BIAS_ACCURACY_METERS + 1)
        assertEquals(gent, s.bias())

        val lastStop = LatLon(50.85, 4.35)
        s.setAnchor(lastStop)
        assertEquals(lastStop, s.bias())
        s.setAnchor(null)
        assertEquals(gent, s.bias())
    }

    @Test
    fun theLookupCarriesTheBias() = runBlocking {
        val s = session { emptyList() }
        s.onFix(gent, accuracyMeters = 5f)
        val job = launch { s.run(MutableStateFlow("kerk")) }

        s.awaitState { it.noResults }
        assertEquals(gent, lookups.single().near)
        job.cancelAndJoin()
    }
}

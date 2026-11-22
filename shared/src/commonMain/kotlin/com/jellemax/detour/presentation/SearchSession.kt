package com.jellemax.detour.presentation

import com.jellemax.detour.data.GeocodeResult
import com.jellemax.detour.data.Geocoder
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.SavedPlace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * What a place-search box shows for the query it last saw.
 *
 * A failed lookup and an empty one are different answers (§5.4): [failure] is set when the
 * geocoder could not be asked or did not answer, [noResults] when it answered with nothing.
 * Neither is set while a lookup is pending or was superseded by the next keystroke.
 *
 * @property query the trimmed query these results answer.
 * @property results matching saved places first, then matching recents, then live hits, each
 *   one not already listed (#1007).
 * @property recents every recent the session was given, so a row can tell it is one.
 * @property saved every saved place the session was given, as results, so a row can tell it is one.
 * @property searching a lookup is in flight (after the debounce, not during it).
 */
data class SearchState(
    val query: String = "",
    val results: List<GeocodeResult> = emptyList(),
    val recents: List<GeocodeResult> = emptyList(),
    val saved: List<GeocodeResult> = emptyList(),
    val searching: Boolean = false,
    val failure: Throwable? = null,
    val noResults: Boolean = false,
) {
    /**
     * What an empty box offers: saved places, then recents not already among them. Derived from
     * [saved] and [recents] rather than read from [results], which still holds the last query's
     * hits for the moment between a cleared box and the session answering it.
     */
    val suggestions: List<GeocodeResult> get() = mergeSearchResults(saved, recents)
}

/**
 * One place search, the same on every surface (#1005): the map's search island, Android Auto,
 * the route editor and saved places used to carry their own copy, each with its own minimum
 * length, debounce and location bias. This owns all of it — the [MIN_QUERY_LENGTH] floor, the
 * [DEBOUNCE_MS] wait, cancelling a lookup the next keystroke made stale (#1003), failed vs
 * empty, the saved-then-recents-then-live merge (#1004, #1007), and the bias.
 *
 * **Bias.** Without a point to bias toward, Photon ranks by global importance and the famous
 * street abroad beats the one around the corner. The session biases toward the [setAnchor]
 * point if one is set (the route editor's last stop), else the rider's last fix that was
 * accurate to [MAX_BIAS_ACCURACY_METERS]. That fix is held sticky: a wide fix under a bridge
 * does not throw the ranking away, it just doesn't replace a good one.
 *
 * Screen-lifetime (§4.2) and with no coroutine of its own (§4.1): the surface calls [run] in
 * its own scope with its query stream, feeds [onFix] / [setRecents] / [setAnchor], and draws
 * [state]. [search] is the commonTest seam, and where a surface puts the lookup on its I/O
 * dispatcher, which commonMain cannot name.
 */
class SearchSession(
    private val search: suspend (query: String, near: LatLon?) -> List<GeocodeResult> = Geocoder::search,
) {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private val recents = MutableStateFlow<List<GeocodeResult>>(emptyList())
    private val saved = MutableStateFlow<List<GeocodeResult>>(emptyList())
    private val lastGoodFix = MutableStateFlow<LatLon?>(null)
    private val anchor = MutableStateFlow<LatLon?>(null)

    /** The rider's recent picks, shown for an empty query and merged above live hits. */
    fun setRecents(list: List<GeocodeResult>) {
        recents.value = list
    }

    /**
     * The rider's saved places (Home, Work…), matched on name and listed above recents, so typing
     * "home" finds the place already saved instead of asking the geocoder (#1007).
     */
    fun setSavedPlaces(list: List<SavedPlace>) {
        saved.value = list.map { GeocodeResult(it.name, it.location) }
    }

    /** A position fix. Kept as the bias only if it is accurate to [MAX_BIAS_ACCURACY_METERS]. */
    fun onFix(at: LatLon, accuracyMeters: Float) {
        if (accuracyMeters <= MAX_BIAS_ACCURACY_METERS) lastGoodFix.value = at
    }

    /** A point that outranks the rider's position as the bias (the route's last stop); null clears it. */
    fun setAnchor(at: LatLon?) {
        anchor.value = at
    }

    /** Where the next lookup is biased toward; read when it fires, not when it was typed. */
    internal fun bias(): LatLon? = anchor.value ?: lastGoodFix.value

    /**
     * Answers each query from [queries] until the caller's scope ends. A new query, new recents
     * or new saved places cancel whatever the previous one was still doing.
     */
    suspend fun run(queries: Flow<String>) {
        combine(queries.map { it.trim() }.distinctUntilChanged(), recents, saved) { q, r, s -> Triple(q, r, s) }
            .collectLatest { (q, r, s) -> answer(q, r, s) }
    }

    private suspend fun answer(query: String, recentList: List<GeocodeResult>, savedList: List<GeocodeResult>) {
        fun List<GeocodeResult>.matching() =
            if (query.isEmpty()) this else filter { it.name.contains(query, ignoreCase = true) }
        val local = mergeSearchResults(savedList.matching(), recentList.matching())
        _state.update { SearchState(query = query, results = local, recents = recentList, saved = savedList) }
        if (query.length < MIN_QUERY_LENGTH) return
        delay(DEBOUNCE_MS)
        _state.update { it.copy(searching = true) }
        try {
            val merged = mergeSearchResults(local, search(query, bias()))
            _state.update { it.copy(results = merged, searching = false, noResults = merged.isEmpty()) }
        } catch (e: CancellationException) {
            // Superseded by the next query (#1003): not a failure, and the next answer
            // replaces this state whole, spinner included.
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            _state.update { it.copy(searching = false, failure = e) }
        }
    }

    companion object {
        /** Shorter queries list matching recents only; one letter is not worth a round trip. */
        const val MIN_QUERY_LENGTH = 2

        /** How long typing has to pause before the lookup fires. */
        const val DEBOUNCE_MS = 300L

        /** A fix wider than this is fine for centring a map, and wrong for ranking street names. */
        const val MAX_BIAS_ACCURACY_METERS = 100f
    }
}

/** [first] as given, then each of [hits] that is not the same place as one already listed (#1004). */
internal fun mergeSearchResults(
    first: List<GeocodeResult>,
    hits: List<GeocodeResult>,
): List<GeocodeResult> {
    val merged = ArrayList(first)
    for (hit in hits) if (merged.none { it.isSamePlaceAs(hit) }) merged.add(hit)
    return merged
}

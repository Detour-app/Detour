package com.jellemax.detour.presentation

import com.jellemax.detour.data.SavedPlaces
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the Saved places screen's initial-load state.
 *
 * [SavedPlaces] already publishes its own `StateFlow`, and add/rename/remove
 * each write straight through it before returning — so the screen collects
 * [SavedPlaces.places] directly for the row list instead of going through
 * [state], which only ever reflects the one snapshot [refresh] took. [state]
 * exists to answer a narrower question — has the initial disk read happened —
 * so the screen can hold the empty state until it has, instead of flashing
 * "no places yet" for one frame on cold start.
 *
 * This is the general shape for any screen backed by a *mutable* store: it
 * collects the store directly and calls the pure mapper ([placesStateFrom])
 * on the render path, because a cached snapshot in `state` would go stale the
 * moment a mutation fires. A screen backed by an *immutable-per-open* store
 * instead — [BadgesPresenter], [CoveragePresenter]: computed once per open,
 * with nothing that mutates underneath it — has no such staleness problem and
 * renders straight from `presenter.state`.
 *
 * [refresh] is declared `suspend` but never actually suspends:
 * [SavedPlaces.ensureLoaded] is non-suspend and blocks on disk. commonMain has
 * no dispatcher to hop off, so the off-main-thread guarantee rests entirely on
 * the CALLER wrapping this in `withContext(Dispatchers.IO)`. Called on the
 * main dispatcher, this janks the UI while the saved-places file is read.
 */
class PlacesPresenter {
    private val _state = MutableStateFlow(PlacesState())
    val state: StateFlow<PlacesState> = _state

    suspend fun refresh() {
        SavedPlaces.ensureLoaded()
        _state.value = PlacesState(loaded = true)
    }
}

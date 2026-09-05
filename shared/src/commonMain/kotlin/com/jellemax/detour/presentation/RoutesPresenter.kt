package com.jellemax.detour.presentation

import com.jellemax.detour.data.RouteStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the Routes screen's initial-load state. File import/export, sharing
 * and the Maps hand-off stay in the Compose layer — Context/Uri/Intent work
 * that cannot exist in commonMain.
 *
 * [RouteStore] already publishes its own `StateFlow`, and rename/remove/save
 * each write straight through it before returning — so the screen collects
 * [RouteStore.routes] directly for the card list instead of going through
 * [state], which only ever reflects the one snapshot [refresh] took. [state]
 * exists to answer a narrower question — has the initial disk read happened —
 * so the screen can hold the loading spinner until it has, instead of
 * flashing an empty list for one frame on cold start.
 *
 * This is the general shape for any screen backed by a *mutable* store: it
 * collects the store directly and calls the pure mapper ([routesStateFrom])
 * on the render path, because a cached snapshot in `state` would go stale the
 * moment a mutation fires. A screen backed by an *immutable-per-open* store
 * instead — [BadgesPresenter], [CoveragePresenter]: computed once per open,
 * with nothing that mutates underneath it — has no such staleness problem and
 * renders straight from `presenter.state`.
 *
 * [refresh] is declared `suspend` but never actually suspends:
 * [RouteStore.ensureLoaded] is non-suspending and blocks on disk. commonMain
 * has no dispatcher to hop off, so the off-main-thread guarantee rests
 * entirely on the CALLER wrapping this in `withContext(Dispatchers.IO)`.
 * Called on the main dispatcher, this janks the UI for a full routes-file read.
 */
class RoutesPresenter {
    private val _state = MutableStateFlow(RoutesState())
    val state: StateFlow<RoutesState> = _state

    suspend fun refresh() {
        RouteStore.ensureLoaded()
        _state.value = RoutesState(loaded = true)
    }
}

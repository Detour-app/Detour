package com.jellemax.detour.presentation

import com.jellemax.detour.data.RouteStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the Routes screen's list state. File import/export, sharing and the
 * Maps hand-off stay in the Compose layer — Context/Uri/Intent work that
 * cannot exist in commonMain.
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
        _state.value = routesStateFrom(RouteStore.routes.value)
    }
}

package com.jellemax.detour.presentation

import com.jellemax.detour.data.BadgeStore
import com.jellemax.detour.data.Coverage
import com.jellemax.detour.data.MunicipalityStore
import com.jellemax.detour.data.RiderTotals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the Badges screen's state. Created per Badges destination entry.
 *
 * [refresh] is declared `suspend` but never actually suspends: every store call
 * inside it — [RiderTotals.refreshIfStale], [Coverage.compute], [BadgeStore.stats],
 * [BadgeStore.refresh], [MunicipalityStore.load] — is non-suspending and blocks on
 * disk. commonMain has no dispatcher to hop off, so the off-main-thread guarantee
 * rests entirely on the CALLER wrapping this in `withContext(Dispatchers.IO)`.
 * Called on the main dispatcher, this janks the UI for a full Coverage walk.
 *
 * Coverage.compute() is called here and again by the coverage screen; that is
 * deliberate and free, because Coverage caches its own result (see its doc).
 */
class BadgesPresenter {
    private val _state = MutableStateFlow(BadgesState())
    val state: StateFlow<BadgesState> = _state

    suspend fun refresh() {
        val coverage = Coverage.compute()
        val stats = BadgeStore.stats(coverage)
        val scored = BadgeStore.refresh(stats)
        _state.value = badgesStateFrom(
            states = scored.states,
            municipalitiesVisited = stats.municipalitiesVisited,
            municipalitiesTotal = MunicipalityStore.load().size,
        )
        // Last, not first: RiderTotals.current() (inside BadgeStore.stats() above)
        // already returns a STALE record as-is, and STALE is arithmetically
        // identical to a fresh fold — the running total is exact incremental
        // arithmetic, not an estimate the TTL is correcting. Calling
        // refreshIfStale() first buys no correctness, only a synchronous
        // TripStore.load() + full fold blocking the first paint. Calling it
        // here, after the state is already on screen, keeps that fold off the
        // rider's critical path while still settling any genuinely INVALID
        // record before the next refresh. Mirrors YouPresenter.refresh().
        RiderTotals.refreshIfStale()
    }
}

package com.jellemax.detour.presentation

import com.jellemax.detour.data.Account
import com.jellemax.detour.data.BadgeStore
import com.jellemax.detour.data.Coverage
import com.jellemax.detour.data.RouteStore
import com.jellemax.detour.data.SavedPlaces
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the You screen's state. Created per Hub destination entry.
 *
 * [refresh] is suspend and does the heavy Coverage walk itself; the caller runs it
 * on a background dispatcher (commonMain has none). It reads the file-backed
 * singletons and hands their outputs to the pure [youStateFrom]; the mapping, not
 * the reads, is what carries the unit tests.
 */
class YouPresenter {
    private val _state = MutableStateFlow(YouState())
    val state: StateFlow<YouState> = _state

    suspend fun refresh() {
        RouteStore.ensureLoaded()
        val coverage = Coverage.compute()
        val stats = BadgeStore.stats(coverage)
        val refreshed = BadgeStore.refresh(stats)
        _state.value = youStateFrom(
            username = Account.username.value,
            signedIn = Account.signedIn,
            totalDistanceMeters = stats.totalDistanceMeters,
            tripCount = stats.tripCount,
            placesCount = SavedPlaces.places.value.size,
            badgesEarned = refreshed.states.count { it.earned },
            badgesTotal = refreshed.states.size,
        )
    }
}

package com.jellemax.detour.notif

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Trip a tapped trip-ended notification wants the app to open, consumed once
 * by AppRoot. Sibling of [PendingCircleOpen] - see its doc for why this kind of
 * holder stays in app/ rather than shared/.
 *
 * Keyed by the trip's start time: [com.jellemax.detour.data.Trip] has no id,
 * and startTimeMs is the identity
 * [com.jellemax.detour.data.TripStore.updateMode] and
 * [com.jellemax.detour.data.TripStore.delete] already key on.
 */
object PendingTripOpen {

    const val EXTRA_OPEN_TRIP_START_MS = "open_trip_start_ms"

    private val _startTimeMs = MutableStateFlow<Long?>(null)
    val startTimeMs: StateFlow<Long?> = _startTimeMs

    /** Reads a tapped notification's target trip, if any - call from
     *  MainActivity's onCreate (its intent) and onNewIntent, same as the
     *  circle and password-reset links. */
    fun take(intent: Intent?) {
        val start = intent?.getLongExtra(EXTRA_OPEN_TRIP_START_MS, -1L)?.takeIf { it > 0 } ?: return
        _startTimeMs.value = start
    }

    fun clear() {
        _startTimeMs.value = null
    }
}

package com.jellemax.detour.notif

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Planned ride a tapped reminder wants the app to open, consumed once by
 * AppRoot. Sibling of [PendingTripOpen] - see its doc for why this kind of
 * holder stays in app/ rather than shared/.
 */
object PendingRideOpen {

    const val EXTRA_OPEN_PLANNED_RIDE_ID = "open_planned_ride_id"

    private val _rideId = MutableStateFlow<String?>(null)
    val rideId: StateFlow<String?> = _rideId

    /** Reads a tapped reminder's ride, if any - call from MainActivity's
     *  onCreate (its intent) and onNewIntent, same as the trip and circle links. */
    fun take(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_OPEN_PLANNED_RIDE_ID)?.takeIf { it.isNotBlank() } ?: return
        _rideId.value = id
    }

    fun clear() {
        _rideId.value = null
    }
}

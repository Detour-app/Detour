package com.jellemax.detour.notif

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A tapped convoy alert (#906) wants the app to open on the map, where the
 * break pills and the regroup banner are, whatever screen it was left on.
 * Consumed once by AppRoot. Sibling of [PendingUpdateOpen], a plain boolean for
 * the same reason; see [PendingTripOpen]'s doc for why it stays in app/.
 */
object PendingMapOpen {

    const val EXTRA_OPEN_MAP = "open_map"

    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open

    /** Reads a tapped notification's request to open the map, if any - call
     *  from MainActivity's onCreate (its intent) and onNewIntent. */
    fun take(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_MAP, false) == true) _open.value = true
    }

    fun clear() {
        _open.value = false
    }
}

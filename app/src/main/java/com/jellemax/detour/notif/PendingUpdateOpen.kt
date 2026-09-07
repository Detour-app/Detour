package com.jellemax.detour.notif

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A tapped update notification wants the app to open on the Settings row that
 * owns the update flow, consumed once by AppRoot. Sibling of [PendingTripOpen]
 * and [PendingCircleOpen] - see [PendingTripOpen]'s doc for why this kind of
 * holder stays in app/ rather than shared/.
 *
 * A plain boolean rather than a payload, unlike its siblings: there is no id to
 * carry, only "open the update row" versus "don't". Task 8's foreground
 * download-service notification is the second caller of
 * [EXTRA_OPEN_UPDATE_SETTINGS], which is why the constant lives here rather
 * than next to [com.jellemax.detour.update.UpdateNotification]'s one call site.
 */
object PendingUpdateOpen {

    const val EXTRA_OPEN_UPDATE_SETTINGS = "open_update_settings"

    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open

    /** Reads a tapped notification's request to open Settings, if any - call
     *  from MainActivity's onCreate (its intent) and onNewIntent, same as the
     *  trip and circle links. */
    fun take(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_UPDATE_SETTINGS, false) == true) _open.value = true
    }

    fun clear() {
        _open.value = false
    }
}

package com.jellemax.detour.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State of a sign-in that is out in the browser.
 *
 * The activity starts it and the redirect comes back to the activity, but the
 * screen that has to report the outcome is the one with the button on it — and
 * the two are not in the same composition, because the trip goes through another
 * app. This is the small piece of shared state between them, in memory only:
 * a sign-in that does not survive the process is a sign-in to start again.
 */
object PendingSignIn {

    private val _busy = MutableStateFlow(false)
    /** True from the redirect landing until the tokens are stored or refused. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * The handle a sign-in just completed as, until someone reports it.
     *
     * A one-shot in the same shape as [com.jellemax.detour.notif.PendingTripOpen]:
     * set once, read by whichever screen is composed, cleared by that reader so
     * a later recomposition does not announce a week-old sign-in a second time.
     *
     * Success needed saying out loud as much as failure did. The only thing that
     * marked it was the avatar in the top corner turning from a question mark
     * into a letter — which nobody watching the middle of the screen sees, and
     * which looks identical to a sign-in that did nothing at all.
     */
    private val _signedInAs = MutableStateFlow<String?>(null)
    val signedInAs: StateFlow<String?> = _signedInAs.asStateFlow()

    fun begin() {
        _busy.value = true
        _error.value = null
        _signedInAs.value = null
    }

    /** [username] may be blank: the realm is not obliged to put a
     *  `preferred_username` in the token, and the reader says so differently. */
    fun succeed(username: String) {
        _busy.value = false
        _error.value = null
        _signedInAs.value = username
    }

    /** Called by whoever reported the success, so it is reported once. */
    fun clearSignedIn() {
        _signedInAs.value = null
    }

    fun fail(message: String) {
        _busy.value = false
        _error.value = message
    }

    fun clear() {
        _error.value = null
    }
}

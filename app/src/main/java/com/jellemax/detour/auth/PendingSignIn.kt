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

    fun begin() {
        _busy.value = true
        _error.value = null
    }

    fun succeed() {
        _busy.value = false
        _error.value = null
    }

    fun fail(message: String) {
        _busy.value = false
        _error.value = message
    }

    fun clear() {
        _error.value = null
    }
}

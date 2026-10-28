package com.jellemax.detour.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The rider's crash-report consent (#811). Opt-in, per the owner's answer on
 * #811: nothing is sent on its own until [sendCrashReports] is on.
 *
 * These would be `Settings` keys, but Settings.kt sits at the §8 1,000-line
 * limit, so they get a bag of their own. That is also the right scope:
 * consent belongs to the phone, not to an account, and no prefs bag is in
 * the backup allowlists.
 *
 * Read lazily rather than in `Settings.init()`, so the first read after the
 * platform context exists is what loads it.
 */
object CrashConsent {

    private const val PREFS = "crash_reports"
    private const val KEY_SEND = "send_crash_reports"
    private const val KEY_NOTICE_SHOWN = "crash_notice_shown"

    private val bag by lazy { prefs(PREFS) }

    private val _sendCrashReports by lazy { MutableStateFlow(bag.bool(KEY_SEND, false)) }
    private val _crashNoticeShown by lazy { MutableStateFlow(bag.bool(KEY_NOTICE_SHOWN, false)) }

    /** Send a crash quietly on the next start. Off until the rider turns it on. */
    val sendCrashReports: StateFlow<Boolean> get() = _sendCrashReports

    /** Whether the one-time "Help fix crashes?" notice (#824) has been shown. */
    val crashNoticeShown: StateFlow<Boolean> get() = _crashNoticeShown

    fun setSendCrashReports(on: Boolean) {
        bag.put(KEY_SEND, on)
        _sendCrashReports.value = on
    }

    fun markCrashNoticeShown() {
        bag.put(KEY_NOTICE_SHOWN, true)
        _crashNoticeShown.value = true
    }
}

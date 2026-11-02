package com.jellemax.detour.convoy

import android.content.Intent
import com.jellemax.detour.data.ConvoyInviteLink
import com.jellemax.detour.data.parseConvoyInviteLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A convoy invite link Android handed the app (#764), consumed once by AppRoot, which gives it to
 * [com.jellemax.detour.data.ConvoyJoinStore] and opens Friends, where the join confirm screen is.
 * Sibling of [com.jellemax.detour.notif.PendingRideOpen]; app-only for the same reason, it reads
 * an [Intent].
 *
 * Holds the parsed link, never the URI or the intent: the token is a credential to the convoy, so
 * it stays in memory as it does in the store, and nothing here logs it.
 */
object PendingConvoyInvite {

    private val _link = MutableStateFlow<ConvoyInviteLink?>(null)
    val link: StateFlow<ConvoyInviteLink?> = _link

    /**
     * Reads a tapped invite link, if [intent] is one. Call from MainActivity's onCreate for a
     * fresh launch only, and from onNewIntent: an activity recreated by a rotation, or relaunched
     * from Recents, gets the same intent again, and taking it twice would reopen a link the rider
     * already dismissed and spend one of its preview attempts.
     */
    fun take(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        _link.value = intent.dataString?.let(::parseConvoyInviteLink) ?: return
    }

    fun clear() {
        _link.value = null
    }
}

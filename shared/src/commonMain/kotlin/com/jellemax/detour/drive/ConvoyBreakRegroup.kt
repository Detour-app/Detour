package com.jellemax.detour.drive

import com.jellemax.detour.data.RiderId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A convoy's break requests and the host's meeting point (#632), as one
 * device sees them.
 *
 * Owned by [ConvoyRelay], which is the only caller: same lifetime as the
 * convoy it joined, one writer. It lives in its own file because it changes
 * with the break-and-regroup feature, not with the socket loop
 * (boundaries.md §8.2, test 2) - and `ConvoyRelay.kt` is already past the §8
 * size limit.
 */
internal class ConvoyBreakRegroup {

    /** Every rider's latest request, one per rider - a second request from
     *  the same rider replaces the first. Nothing here expires them on a
     *  clock; how long one stays on screen is the caller's call
     *  ([BreakRequest.receivedAtMs] is there for it). A rider's `left` does
     *  not remove theirs either: `left` is a dropped socket as often as a
     *  departure, and a rider who asked for a break and then rode into a
     *  dead zone still wants one. */
    private val _breakRequests = MutableStateFlow<Map<RiderId, BreakRequest>>(emptyMap())
    val breakRequests: StateFlow<Map<RiderId, BreakRequest>> = _breakRequests.asStateFlow()

    private val _regroupPin = MutableStateFlow<RegroupPin?>(null)
    val regroupPin: StateFlow<RegroupPin?> = _regroupPin.asStateFlow()

    fun record(request: BreakRequest) {
        _breakRequests.update { it + (request.riderId to request) }
    }

    /** A pin from the relay, this device's own pin as the host, or null for
     *  a clear. */
    fun showPin(pin: RegroupPin?) {
        _regroupPin.value = pin
    }

    /**
     * What a reconnect drops: the pin, but not the requests. The relay
     * remembers the pin and replays it right after `joined`, so dropping it
     * here is what keeps a pin the host moved or cleared during the gap from
     * surviving it. The relay keeps no requests to replay, and a rider's
     * request is no less true for this device having dropped off for a few
     * seconds.
     */
    fun forgetPinUntilRejoined() {
        _regroupPin.value = null
    }

    /** Leaving the convoy, switching convoys, or a session change. */
    fun clear() {
        _breakRequests.value = emptyMap()
        _regroupPin.value = null
    }
}

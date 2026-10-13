package com.jellemax.detour.presentation

import com.jellemax.detour.data.ConvoysStore
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.nowMs
import com.jellemax.detour.drive.ConvoyGapDetector
import com.jellemax.detour.drive.ConvoyGapFeed
import com.jellemax.detour.drive.FriendPosition

/**
 * Convoy gap alerts for iOS (#656): the [ConvoyGapFeed.State] one joined,
 * connected convoy carries between ticks.
 *
 * Who rides, who leads, what counts as behind and what is said is
 * [convoyGapTick]'s, the same call the phone's `ConvoyGapAlerts` makes — this
 * only holds the state between calls. A holder because the state's
 * constructor is defaulted, which Swift cannot call, and because the own fix
 * goes in as plain values: [ConvoyGapDetector.Fix] holds a [RiderId], which
 * Swift cannot build. The caller owns the timer ([ConvoyGapFeed.TICK_MS]) and
 * when it runs at all (a convoy joined, the socket up), and calls [reset]
 * whenever either changes.
 */
class ConvoyGapHolder {

    private var state = ConvoyGapFeed.State()

    /** Forgets every rider: a new convoy, or a socket that dropped and came
     *  back, starts from nothing. */
    fun reset() {
        state = ConvoyGapFeed.State()
    }

    /**
     * Judges [convoyId] once, as [ConvoysStore] and the signed-in rider know
     * it right now — the reads the phone makes too. [peers] are the relay's
     * latest frames and [ownFix] this device's last fix, null before the
     * first; [ownSpeedKmh] and [ownFixMs] go with it. [route] is this device's
     * active navigation route, empty when not navigating. Stamped on the wall
     * clock, as on the phone: peers' frames arrive in real time.
     */
    fun tick(
        convoyId: String,
        ownFix: LatLon?,
        ownSpeedKmh: Double,
        ownFixMs: Long,
        peers: List<FriendPosition>,
        route: List<LatLon>,
    ): ConvoyGapTick {
        val selfId = Settings.authRiderId.value
        val fix = ownFix?.let { ConvoyGapDetector.Fix(selfId, it, ownSpeedKmh, ownFixMs) }
        val convoy = ConvoysStore.state.value.convoys.firstOrNull { it.id == convoyId }
        val step = convoyGapTick(state, convoy, ConvoyGapSelf(selfId, fix), peers, route, nowMs())
        state = step.state
        return step
    }
}

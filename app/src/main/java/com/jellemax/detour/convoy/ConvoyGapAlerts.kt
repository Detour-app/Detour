package com.jellemax.detour.convoy

import com.jellemax.detour.data.Account
import com.jellemax.detour.data.ConvoysStore
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.drive.ConvoyGapDetector
import com.jellemax.detour.drive.ConvoyGapFeed
import com.jellemax.detour.drive.FriendPosition
import com.jellemax.detour.presentation.ConvoyGapLine
import com.jellemax.detour.presentation.ConvoyGapSelf
import com.jellemax.detour.presentation.convoyGapTick
import com.jellemax.detour.tracking.TripTrackingService
import com.jellemax.detour.ui.SpinResultHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Convoy gap alerts on the phone (#647): steps [ConvoyGapFeed] for the joined
 * convoy and publishes the banner's lines and the sentences to speak.
 *
 * Samples on a timer rather than collecting: peers, this device's fix, the
 * roster and the route each change on their own clock, and only the timer
 * notices a rider who has gone quiet. Runs for the life of the process on
 * [ConvoyLiveClient]'s scope, started from there, so a rider falling behind
 * while the map is not on screen is still judged — and judged against frames
 * stamped when they arrived, not when somebody next looked.
 *
 * Who rides and who leads is [ConvoyGapFeed.Roster.of]'s call; a convoy whose
 * host is unknown gets no alerts. The route is this
 * device's own while it navigates: the host's isn't on the wire, and without
 * one nobody can be behind, though stopped and lost signal still work.
 */
object ConvoyGapAlerts {

    private val _lines = MutableStateFlow<List<ConvoyGapLine>>(emptyList())

    /** One line per rider with an alert; empty with no convoy or none. */
    val lines: StateFlow<List<ConvoyGapLine>> = _lines.asStateFlow()

    private val _spoken = MutableSharedFlow<String>(extraBufferCapacity = SPOKEN_BUFFER)

    /** A sentence per new alert. Not replayed: a late subscriber missed it,
     *  and the banner still shows what holds. */
    val spoken: SharedFlow<String> = _spoken.asSharedFlow()

    private const val SPOKEN_BUFFER = 8

    /** Judges whichever convoy [convoyId] names while [connected], restarting
     *  from nothing on every change of convoy and every reconnect. The relay
     *  keeps [convoyId] across a dropped socket, and with no frames arriving
     *  every other rider would read as a lost signal; the drop is this device's,
     *  not theirs. Called once, by [ConvoyLiveClient]. */
    internal fun watch(
        scope: CoroutineScope,
        convoyId: StateFlow<String?>,
        connected: StateFlow<Boolean>,
        peers: StateFlow<Map<RiderId, FriendPosition>>,
    ) {
        scope.launch {
            combine(convoyId, connected) { id, live -> id.takeIf { live } }.distinctUntilChanged().collectLatest { id ->
                _lines.value = emptyList()
                if (id == null) return@collectLatest
                var state = ConvoyGapFeed.State()
                while (true) {
                    state = tick(state, id, peers.value)
                    delay(ConvoyGapFeed.TICK_MS)
                }
            }
        }
    }

    private fun tick(
        state: ConvoyGapFeed.State,
        convoyId: String,
        peers: Map<RiderId, FriendPosition>,
    ): ConvoyGapFeed.State {
        val selfId = Account.riderId.value
        val own = TripTrackingService.lastFix.value?.let {
            ConvoyGapDetector.Fix(selfId, LatLon(it.lat, it.lon), it.speedMps * 3.6, it.timeMs)
        }
        val nav = SpinResultHolder.state.value
        val route = nav.route?.polyline?.takeIf { nav.navigating }.orEmpty()
        // Wall clock, not the drive clock: peers' frames arrive in real time
        // whatever factor a replay on this device runs at.
        val step = convoyGapTick(
            state,
            ConvoysStore.state.value.convoys.firstOrNull { it.id == convoyId },
            ConvoyGapSelf(selfId, own), peers.values, route, System.currentTimeMillis(),
        )
        _lines.value = step.lines
        step.utterance?.let { _spoken.tryEmit(it) }
        return step.state
    }
}

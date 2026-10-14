package com.jellemax.detour.drive

import com.jellemax.detour.data.Group
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RiderId

/**
 * What a client has to do to [ConvoyGapDetector] before its answers mean
 * anything (#647): the detector wants timestamps on one clock and a fixed
 * convoy, and a relay client has neither.
 *
 * **Receive-stamped.** A peer's `tsMs` is the sender's own clock, which can sit
 * minutes away from this device's, and the detector compares it against
 * [step]'s `nowMs` for signal loss. So each frame is stamped with `nowMs` the
 * first time it is seen here, and a frame already seen is not handed on again —
 * restamping a repeat would make a rider who went quiet look live. The sender's
 * stamp is still what orders frames: one at or behind the last seen is dropped.
 *
 * **Members only.** A frame from a rider not in [Roster.members] is ignored, and a
 * rider who left is dropped from the detector's state, since a leaver looks
 * exactly like a lost signal from here (see [ConvoyGapDetector]'s class doc).
 *
 * **Not about yourself.** This device's own fix goes in like any other, since
 * it can be the rider ahead someone fell behind, or the lead. Events about
 * [Roster.selfId] are dropped: you know you stopped.
 *
 * Phone, car and iOS step this the same way, every [TICK_MS]; the clock is the
 * caller's.
 */
object ConvoyGapFeed {

    /** How often a surface steps this. Signal loss is the one alert no frame
     *  announces, so something has to look on a timer; a second is well
     *  inside its 30 s threshold. */
    const val TICK_MS = 1_000L

    /** The detector's state plus, per rider, the sender's stamp on the last
     *  frame seen. */
    data class State(
        val detector: ConvoyGapDetector.State = ConvoyGapDetector.State(),
        val lastSenderTsMs: Map<RiderId, Long> = emptyMap(),
    )

    /** Who is in the convoy, as this device knows it. [hostId] is the lead. */
    data class Roster(val members: Set<RiderId>, val hostId: RiderId, val selfId: RiderId) {
        companion object {
            /**
             * The roster of [convoy] seen from [selfId]: its accepted members, led by
             * its host ([Group.ownerId]). Null while the host is unknown — a server
             * that predates the field — since self-as-lead would call a tail rider
             * stopped whenever the front waits for them.
             */
            fun of(convoy: Group, selfId: RiderId): Roster? {
                val hostId = convoy.ownerId ?: return null
                val members = convoy.members.filter { it.status == "accepted" }.map { it.id }.toSet()
                return Roster(members, hostId, selfId)
            }
        }
    }

    data class Step(val state: State, val events: List<ConvoyGapDetector.Event>)

    /**
     * [frames] are the newest position known per rider, this device's own
     * included, with `tsMs` on the sender's clock. [route] is the one gaps are
     * measured along; empty means nobody can fall behind.
     */
    fun step(
        state: State,
        frames: List<ConvoyGapDetector.Fix>,
        roster: Roster,
        route: List<LatLon>,
        nowMs: Long,
    ): Step {
        val members = roster.members
        val seen = state.lastSenderTsMs.filterKeys { it in members }
        val fresh = frames.filter { it.riderId in members && it.tsMs > (seen[it.riderId] ?: Long.MIN_VALUE) }
        val detectorState = state.detector.copy(riders = state.detector.riders.filterKeys { it in members })
        val stamped = fresh.map { it.copy(tsMs = nowMs) }
        val step = ConvoyGapDetector.step(detectorState, stamped, roster.hostId, route, nowMs)
        return Step(
            state = State(step.state, seen + fresh.associate { it.riderId to it.tsMs }),
            events = step.events.filter { it.rider != roster.selfId },
        )
    }
}

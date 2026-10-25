package com.jellemax.detour.presentation

import com.jellemax.detour.data.Group
import com.jellemax.detour.data.GroupMember
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.handleFor
import com.jellemax.detour.drive.ConvoyGapDetector
import com.jellemax.detour.drive.ConvoyGapFeed
import com.jellemax.detour.drive.FriendPosition

/** Which alert a convoy gap banner line shows. One per rider: a lost signal
 *  freezes the rider's other alerts, and a stop says more than the gap it opens. */
enum class ConvoyGapKind { SIGNAL_LOST, STOPPED, BEHIND }

/** One rider's line in the convoy gap banner (#647). Worded by the surface:
 *  [kind] picks the string, [name] fills it. */
data class ConvoyGapLine(val rider: RiderId, val name: String, val kind: ConvoyGapKind)

/** Every rider [riders] has an alert for, bar [selfId], in the order they
 *  appear in [members] so the banner doesn't reshuffle as alerts come and go. */
fun convoyGapLinesFrom(
    riders: Map<RiderId, ConvoyGapDetector.Rider>,
    members: List<GroupMember>,
    selfId: RiderId,
): List<ConvoyGapLine> =
    members.mapNotNull { member ->
        val rider = riders[member.id]?.takeIf { member.id != selfId } ?: return@mapNotNull null
        val kind = when {
            rider.signalLost -> ConvoyGapKind.SIGNAL_LOST
            rider.stopped -> ConvoyGapKind.STOPPED
            rider.behind -> ConvoyGapKind.BEHIND
            else -> return@mapNotNull null
        }
        ConvoyGapLine(member.id, members.handleFor(member.id), kind)
    }

/**
 * The English sentence for [kind] about [name]: what is spoken on every
 * surface, and the banner line on iOS, which has no translations. The phone's
 * banner words it from its own string resources instead, so Dutch reads Dutch.
 */
fun convoyGapSentence(kind: ConvoyGapKind, name: String): String = when (kind) {
    ConvoyGapKind.BEHIND -> "$name fell behind"
    ConvoyGapKind.STOPPED -> "$name stopped"
    ConvoyGapKind.SIGNAL_LOST -> "Lost $name's signal"
}

/**
 * The sentence spoken for [event], or null for [ConvoyGapDetector.Event.Rejoined],
 * which only clears the banner. English, like every other spoken prompt: the
 * voice is chosen for GraphHopper's English instructions (`NavVoice`), and a
 * Dutch sentence read by it is worse than an English one.
 */
fun convoyGapSpokenLine(event: ConvoyGapDetector.Event, members: List<GroupMember>): String? {
    val kind = when (event) {
        is ConvoyGapDetector.Event.FellBehind -> ConvoyGapKind.BEHIND
        is ConvoyGapDetector.Event.Stopped -> ConvoyGapKind.STOPPED
        is ConvoyGapDetector.Event.SignalLost -> ConvoyGapKind.SIGNAL_LOST
        is ConvoyGapDetector.Event.Rejoined -> return null
    }
    return convoyGapSentence(kind, members.handleFor(event.rider))
}

/** This device in a convoy: its rider [id] and, once it has one, its own [fix]. */
data class ConvoyGapSelf(val id: RiderId, val fix: ConvoyGapDetector.Fix?)

/** One judged moment of a convoy: the feed's next [state], the banner [lines]
 *  and the one sentence to speak, or null when nothing new happened. */
data class ConvoyGapTick(
    val state: ConvoyGapFeed.State,
    val lines: List<ConvoyGapLine>,
    val utterance: String?,
)

/**
 * Judges [convoy] once, for every surface that shows gap alerts (#647, #655,
 * #656). [peers] are the relay's latest frames, [self] this device (its fix
 * is null before the first one), [route] the one gaps are measured
 * along (empty when this device is not navigating) and [nowMs] the wall clock
 * the frames were stamped against on arrival.
 *
 * A convoy that is unknown, or whose host is unknown, gets no lines and
 * leaves [state] alone. Two riders alerted in the same tick are joined into
 * one utterance: `NavVoice` flushes its queue on every speak, so the second
 * sentence would otherwise cut the first.
 */
fun convoyGapTick(
    state: ConvoyGapFeed.State,
    convoy: Group?,
    self: ConvoyGapSelf,
    peers: Collection<FriendPosition>,
    route: List<LatLon>,
    nowMs: Long,
): ConvoyGapTick {
    val roster = convoy?.let { ConvoyGapFeed.Roster.of(it, self.id) }
    if (convoy == null || roster == null) return ConvoyGapTick(state, emptyList(), null)
    val frames = peers.map { ConvoyGapDetector.Fix(it.riderId, LatLon(it.lat, it.lon), it.speedKmh, it.tsMs) } +
        listOfNotNull(self.fix)
    val step = ConvoyGapFeed.step(state, frames, roster, route, nowMs)
    val utterance = step.events.mapNotNull { convoyGapSpokenLine(it, convoy.members) }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(". ")
    return ConvoyGapTick(step.state, convoyGapLinesFrom(step.state.detector.riders, convoy.members, self.id), utterance)
}

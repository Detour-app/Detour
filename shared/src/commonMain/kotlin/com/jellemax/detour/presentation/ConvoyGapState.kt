package com.jellemax.detour.presentation

import com.jellemax.detour.data.GroupMember
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.handleFor
import com.jellemax.detour.drive.ConvoyGapDetector

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
 * The sentence spoken for [event], or null for [ConvoyGapDetector.Event.Rejoined],
 * which only clears the banner. English, like every other spoken prompt: the
 * voice is chosen for GraphHopper's English instructions (`NavVoice`), and a
 * Dutch sentence read by it is worse than an English one.
 */
fun convoyGapSpokenLine(event: ConvoyGapDetector.Event, members: List<GroupMember>): String? {
    val name = members.handleFor(event.rider)
    return when (event) {
        is ConvoyGapDetector.Event.FellBehind -> "$name fell behind"
        is ConvoyGapDetector.Event.Stopped -> "$name stopped"
        is ConvoyGapDetector.Event.SignalLost -> "Lost $name's signal"
        is ConvoyGapDetector.Event.Rejoined -> null
    }
}

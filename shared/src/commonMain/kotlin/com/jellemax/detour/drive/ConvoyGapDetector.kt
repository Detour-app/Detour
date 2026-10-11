package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.NavEngine
import com.jellemax.detour.data.RiderId
import kotlin.math.max

/**
 * Convoy gap alerts (#631, #646): tells the convoy when a rider has fallen
 * behind, stopped while the lead rides on, or gone quiet. Clock-free: every
 * timestamp is a parameter, so phone, car and iOS step the same rule and
 * `commonTest` can drive it with literals.
 *
 * The caller hands [step] each rider's newest fix it knows about, the lead's
 * included, on whatever cadence it likes. Repeats and late frames are dropped
 * per rider by timestamp, so passing the whole latest-position map every tick
 * is fine. Each alert fires once per episode as an [Event]; [Event.Rejoined]
 * closes a rider's episode once none of their alerts holds any more.
 *
 * **Behind** is measured to the next rider ahead along the lead's route, not
 * to the lead: in a long convoy the tail is legitimately far from the front,
 * and the rider who opened the gap is the one to name. A rider is behind when
 * that gap is [BEHIND_METERS] or more, or when the rider ahead passed this
 * rider's position [BEHIND_MS] or more of *moving* time ago. Moving time, not
 * wall time, because a convoy waiting at a red light would otherwise watch the
 * gap to the rider ahead grow by the length of the light and alert everyone
 * as it pulls away. Clearing needs the gap back under [REJOIN_FRACTION] of
 * both thresholds, so a rider riding right at 500 m doesn't flap.
 *
 * **Stopped** needs the rider under [STOPPED_KMH] for [STOPPED_MS] *while the
 * lead moves*: the dwell only starts counting once both are true, so a whole
 * convoy at a light raises nothing.
 *
 * **Signal lost** is [SIGNAL_LOST_MS] without a frame. While it holds the
 * rider's other alerts are frozen; their last fix says nothing new.
 *
 * Thresholds are the owner's (#631) and deliberately constants, not settings.
 * A rider who leaves the convoy looks exactly like one who lost signal from
 * here, so the caller drops them from [State.riders] when membership changes.
 */
object ConvoyGapDetector {
    const val BEHIND_METERS = 500.0
    const val BEHIND_MS = 60_000L
    const val STOPPED_KMH = 2.0
    const val STOPPED_MS = 20_000L
    const val SIGNAL_LOST_MS = 30_000L

    /** A behind rider is back once both gaps are under this share of their
     *  threshold (400 m and 48 s). */
    const val REJOIN_FRACTION = 0.8

    /** How much moving time of route progress each rider keeps: twice
     *  [BEHIND_MS], so a time gap at the threshold always has a sample on
     *  each side to interpolate between. */
    private const val HISTORY_MOVING_MS = 2 * BEHIND_MS

    /** One rider's position as this rule needs it. [speedKmh] null means the
     *  sender didn't report one; it is then derived from the previous fix. */
    data class Fix(val riderId: RiderId, val at: LatLon, val speedKmh: Double?, val tsMs: Long)

    sealed interface Event {
        val rider: RiderId

        data class FellBehind(override val rider: RiderId) : Event
        data class Stopped(override val rider: RiderId) : Event
        data class SignalLost(override val rider: RiderId) : Event

        /** Every alert [rider] had is cleared. */
        data class Rejoined(override val rider: RiderId) : Event
    }

    /** Where a rider was along the route at [tsMs], and how much time they
     *  had spent moving by then. */
    data class Sample(val tsMs: Long, val alongM: Double, val movingMs: Long)

    data class Rider(
        val last: Fix,
        /** Moving and stopped are both null until a speed is known. */
        val movingSince: Long? = null,
        val stoppedSince: Long? = null,
        /** Running total of time spent at or above [STOPPED_KMH]. */
        val movingMs: Long = 0,
        /** Oldest first; empty without a route. A stop collapses into one
         *  sample, so this stays bounded by moving time, not wall time. */
        val history: List<Sample> = emptyList(),
        val behind: Boolean = false,
        val stopped: Boolean = false,
        val signalLost: Boolean = false,
    ) {
        val alerting: Boolean get() = behind || stopped || signalLost
    }

    data class State(
        val riders: Map<RiderId, Rider> = emptyMap(),
        /** The route [Rider.history] was measured along; a different one
         *  restarts every history from the rider's last fix. */
        val route: List<LatLon> = emptyList(),
    )

    data class Step(val state: State, val events: List<Event>)

    fun step(state: State, fixes: List<Fix>, leadId: RiderId, route: List<LatLon>, nowMs: Long): Step {
        // A new route restarts each history at the rider's last fix rather than
        // emptying it: a rider whose next frame is still on its way must stay
        // someone the others can be behind.
        var riders = if (route == state.route) state.riders
            else state.riders.mapValues { (_, rider) ->
                rider.copy(history = listOfNotNull(sampleOn(route, rider.last, rider.movingMs)))
            }
        for (fix in fixes.sortedBy { it.tsMs }) {
            val prev = riders[fix.riderId]
            if (prev != null && fix.tsMs <= prev.last.tsMs) continue
            riders = riders + (fix.riderId to ingest(prev, fix, route))
        }

        val live = riders.filterValues { nowMs - it.last.tsMs < SIGNAL_LOST_MS }
        val leadMovingSince = live[leadId]?.movingSince
        val events = mutableListOf<Event>()
        val next = riders.mapValues { (id, rider) ->
            val updated = evaluate(rider, id == leadId, riders.values, leadMovingSince, nowMs)
            eventsBetween(id, rider, updated, events)
            updated
        }
        return Step(State(next, route), events)
    }

    private fun ingest(prev: Rider?, fix: Fix, route: List<LatLon>): Rider {
        // A first fix starts from nothing: no speed to compare, no time moved.
        val base = prev ?: Rider(last = fix)
        val speedKmh = fix.speedKmh ?: derivedKmh(base.last, fix)
        val moving = speedKmh != null && speedKmh >= STOPPED_KMH
        val stopped = speedKmh != null && speedKmh < STOPPED_KMH
        val movingMs = base.movingMs + if (moving) fix.tsMs - base.last.tsMs else 0L
        val history = sampleOn(route, fix, movingMs)?.let { trimmed(base.history, it) } ?: emptyList()
        return base.copy(
            last = fix,
            movingSince = if (moving) base.movingSince ?: fix.tsMs else null,
            stoppedSince = if (stopped) base.stoppedSince ?: fix.tsMs else null,
            movingMs = movingMs,
            history = history,
        )
    }

    private fun sampleOn(route: List<LatLon>, fix: Fix, movingMs: Long): Sample? =
        if (route.size < 2) null else Sample(fix.tsMs, NavEngine.advance(route, fix.at, null).meters, movingMs)

    private fun derivedKmh(from: Fix, to: Fix): Double? {
        val dtMs = to.tsMs - from.tsMs
        if (dtMs <= 0) return null
        return NavEngine.lengthMeters(listOf(from.at, to.at)) / dtMs * 3_600.0
    }

    /** Appends [sample], replacing the last one instead when no moving time
     *  passed between them, then drops what is older than the window — but
     *  keeps the newest sample beyond it, as the far side to interpolate to. */
    private fun trimmed(history: List<Sample>, sample: Sample): List<Sample> {
        val grown = if (history.lastOrNull()?.movingMs == sample.movingMs) history.dropLast(1) + sample
            else history + sample
        val cut = grown.indexOfLast { sample.movingMs - it.movingMs > HISTORY_MOVING_MS }
        return if (cut > 0) grown.drop(cut) else grown
    }

    private fun evaluate(
        rider: Rider,
        isLead: Boolean,
        all: Collection<Rider>,
        leadMovingSince: Long?,
        nowMs: Long,
    ): Rider {
        if (nowMs - rider.last.tsMs >= SIGNAL_LOST_MS) return rider.copy(signalLost = true)
        if (isLead) return rider.copy(behind = false, stopped = false, signalLost = false)
        val stoppedSince = rider.stoppedSince
        val stopped = when {
            stoppedSince == null -> false
            rider.stopped -> true
            leadMovingSince == null -> false
            else -> nowMs - max(stoppedSince, leadMovingSince) >= STOPPED_MS
        }
        return rider.copy(behind = isBehind(rider, all, nowMs), stopped = stopped, signalLost = false)
    }

    private fun isBehind(rider: Rider, all: Collection<Rider>, nowMs: Long): Boolean {
        // No history means no route: nothing to be behind along.
        val here = rider.history.lastOrNull() ?: return false
        // The nearest rider ahead among everyone, silent ones included. Picking
        // from the live riders only would let the next rider back stand in as
        // the anchor, or no anchor at all clear an alert that is still true.
        val ahead = all
            .mapNotNull { other -> other.history.lastOrNull()?.let { other to it.alongM } }
            .filter { (_, along) -> along > here.alongM }
            .minByOrNull { (_, along) -> along }
            ?.first
            ?: return false
        // A silent anchor says nothing about the gap: keep what was decided.
        if (nowMs - ahead.last.tsMs >= SIGNAL_LOST_MS) return rider.behind
        val gapM = ahead.history.last().alongM - here.alongM
        val scale = if (rider.behind) REJOIN_FRACTION else 1.0
        if (gapM >= BEHIND_METERS * scale) return true
        val gapMs = movingMsSincePassing(ahead.history, here.alongM, BEHIND_MS * scale)
            ?: return rider.behind
        return gapMs >= BEHIND_MS * scale
    }

    /**
     * Moving time [history]'s rider has spent since they were at [alongM], or
     * null when that can't be told against [thresholdMs]. When [alongM] is
     * older than the history, the whole history's moving time is a lower
     * bound: decisive once it reaches [thresholdMs], undecidable before — as
     * right after a route change, when the history is one sample long.
     */
    private fun movingMsSincePassing(history: List<Sample>, alongM: Double, thresholdMs: Double): Double? {
        val latest = history.last()
        val i = history.indexOfLast { it.alongM <= alongM }
        if (i == -1) {
            val spanMs = (latest.movingMs - history.first().movingMs).toDouble()
            return if (spanMs >= thresholdMs) spanMs else null
        }
        if (i == history.lastIndex) return 0.0
        // Every sample after i is past alongM, so b is strictly ahead of a.
        val a = history[i]
        val b = history[i + 1]
        val t = (alongM - a.alongM) / (b.alongM - a.alongM)
        return latest.movingMs - (a.movingMs + t * (b.movingMs - a.movingMs))
    }

    private fun eventsBetween(id: RiderId, before: Rider, after: Rider, into: MutableList<Event>) {
        if (after.signalLost && !before.signalLost) into += Event.SignalLost(id)
        if (after.behind && !before.behind) into += Event.FellBehind(id)
        if (after.stopped && !before.stopped) into += Event.Stopped(id)
        if (before.alerting && !after.alerting) into += Event.Rejoined(id)
    }
}

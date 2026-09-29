package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RidingEvent
import com.jellemax.detour.data.RidingEventKind
import com.jellemax.detour.data.TripMoments
import com.jellemax.detour.data.TripPeak
import com.jellemax.detour.data.TripStop
import kotlin.math.abs

/**
 * Folds a running trip's peaks, hard events and stops into a [TripMoments],
 * each pinned to the position it happened at (#444). The detectors that decide
 * *whether* something happened stay where they are ([HardEventDetector],
 * [StopDetector]); this only records *where* and *how hard*, from their
 * outputs, so the saved list agrees with the counts in `DrivingStats`.
 *
 * Clock-free and position-free: every timestamp and position is a parameter.
 */
object MomentRecorder {
    /** Per trip. A long ride on a hard-braking day stays far under this; the
     *  cap bounds what one pathological trip can add to trips.json, which the
     *  sync payload carries whole. Events past it are counted, not pinned. */
    const val MAX_EVENTS = 200

    /** Per trip, for the same reason as [MAX_EVENTS]. */
    const val MAX_STOPS = 100

    data class State(
        val moments: TripMoments = TripMoments(),
        /** Where the vehicle came to rest, while [StopDetector] holds a
         *  candidate stop open; null otherwise. */
        val stopAt: LatLon? = null,
        /** Index in [TripMoments.events] of the corner still being taken, so
         *  its magnitude can deepen until the detector's latch drops. */
        val openCorner: Int? = null,
    )

    fun onSpeed(state: State, at: LatLon, timeMs: Long, speedMps: Double): State {
        val peak = state.moments.topSpeed
        if (peak != null && speedMps <= peak.value) return state
        if (peak == null && speedMps <= 0.0) return state
        return state.withMoments { it.copy(topSpeed = TripPeak(at, timeMs, speedMps)) }
    }

    /** [leanDeg] signed; the peak is the deepest either way. */
    fun onLean(state: State, at: LatLon, timeMs: Long, leanDeg: Double): State {
        val peak = state.moments.maxLean
        if (abs(leanDeg) <= abs(peak?.value ?: 0.0)) return state
        return state.withMoments { it.copy(maxLean = TripPeak(at, timeMs, leanDeg)) }
    }

    fun onG(state: State, at: LatLon, timeMs: Long, g: Double): State {
        if (g <= (state.moments.maxG?.value ?: 0.0)) return state
        return state.withMoments { it.copy(maxG = TripPeak(at, timeMs, g)) }
    }

    /** A hard brake or acceleration — one call per event the detector counted. */
    fun onEvent(state: State, kind: RidingEventKind, at: LatLon, timeMs: Long, magnitude: Double): State {
        val events = state.moments.events
        if (events.size >= MAX_EVENTS) return state
        return state.withMoments { it.copy(events = events + RidingEvent(kind, at, timeMs, magnitude)) }
    }

    /**
     * One sample of a corner detector: [cornering] and [newEvent] are its
     * latch and its edge, exactly as [HardEventDetector.onLeanSample] or
     * [HardEventDetector.onHeadingFix] returned them, and [sample] is this
     * sample as an event. A new corner is pinned where it began; while the
     * latch holds, its magnitude deepens to the hardest sample through it, so
     * a corner entered at the threshold and leaned further records how far it
     * went, not where it crossed the line.
     */
    fun onCorner(state: State, cornering: Boolean, newEvent: Boolean, sample: RidingEvent): State {
        if (newEvent) {
            val next = onEvent(state, sample.kind, sample.at, sample.timeMs, sample.magnitude)
            // Past the cap nothing was added, so there is no corner to deepen.
            return next.copy(openCorner = if (next === state) null else next.moments.events.lastIndex)
        }
        if (!cornering) return if (state.openCorner == null) state else state.copy(openCorner = null)
        val i = state.openCorner ?: return state
        val open = state.moments.events[i]
        if (open.kind != sample.kind || abs(sample.magnitude) <= abs(open.magnitude)) return state
        return state.withMoments { m ->
            m.copy(events = m.events.toMutableList().also { it[i] = open.copy(magnitude = sample.magnitude) })
        }
    }

    /**
     * Follows [StopDetector] across one fix: [before] and [after] are its state
     * either side of [StopDetector.onFix]. A stop is pinned where the vehicle
     * came to rest ([at] on the fix that opened the candidate), over the same
     * window the detector counted into its idle time.
     */
    fun onStopFix(
        state: State,
        before: StopDetector.State,
        after: StopDetector.State,
        at: LatLon,
        fixMs: Long,
    ): State {
        val openedAt = before.candidateSince
        if (after.stopCount > before.stopCount && openedAt != null) {
            val stops = state.moments.stops
            val next = state.copy(stopAt = null)
            if (stops.size >= MAX_STOPS) return next
            return next.withMoments { it.copy(stops = stops + TripStop(state.stopAt ?: at, openedAt, fixMs)) }
        }
        return when {
            after.candidateSince == null -> if (state.stopAt == null) state else state.copy(stopAt = null)
            openedAt == null -> state.copy(stopAt = at)
            else -> state
        }
    }

    private inline fun State.withMoments(f: (TripMoments) -> TripMoments) = copy(moments = f(moments))
}

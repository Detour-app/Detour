package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoadRoulette
import com.jellemax.detour.data.SpeedCameras

/**
 * Average speed through a trajectcontrole. Enter at one end heading for the
 * other, then integrate GPS distance over elapsed time until we pass that far
 * end (or overshoot / time out). The average is what the section actually
 * measures, so it's the number worth seeing while inside one.
 *
 * A step function over an immutable [State]: the caller holds one, hands it and
 * a fix in, and replaces it with what comes back. No clock of its own - the
 * caller passes [onFix]'s `nowMs`, because a machine that is path-dependent over
 * timestamps and reads the clock itself has no reproducible test. (`nowMs()` in
 * `data/Angles.kt` is `internal` to `:shared` and so is not callable from
 * `app/`; the Android call sites use `System.currentTimeMillis()` as they always
 * have.)
 *
 * **No `StateFlow` here, deliberately.** A machine that owns a flow owns a
 * subscription and a scope. Whichever per-surface holder wants one wraps this;
 * [Reading] being one type is what makes that one iOS `FlowWatcher` subclass
 * rather than two.
 */
object SectionAverageTracker {

    // How close to a section's device node counts as passing it, for entering and
    // leaving a trajectcontrole average-speed measurement.
    const val SECTION_GATE_METERS = 60.0

    // How far off your heading the far end of a section may lie and still count as
    // driving into it. Wide, because a long section can curve away — it only has to
    // separate "the other end is ahead of me" from "behind me, I'm on my way out".
    const val SECTION_WEDGE_DEG = 75.0

    /** Below this the bearing is noise, so a stopped phone can't heading-test its
     *  way into a section. */
    const val ARM_MIN_MPS = 2.0

    /** Nothing is published until this much has accumulated - one fix at
     *  motorway speed - or the first average would be a division by a
     *  rounding error. */
    const val MIN_ACC_METERS_FOR_AVERAGE = 20.0

    /** Keeps the gate we entered through from counting as the exit on the fix
     *  right after entering. */
    const val MIN_ACC_METERS_BEFORE_EXIT = 150.0

    /** Overshoot bound: `spanMeters * OVERSHOOT_FACTOR + OVERSHOOT_SLACK_METERS`.
     *  Missing the exit gantry entirely - a lane change past it, a lost fix -
     *  must not leave an average on screen for the rest of the drive. */
    const val OVERSHOOT_FACTOR = 1.4
    const val OVERSHOOT_SLACK_METERS = 400.0

    /** Last resort: half an hour in one section is a stop, not a transit. */
    const val TIMEOUT_MS = 30 * 60_000L

    /** The average and the limit it is judged against, as one value, so the two
     *  cannot disagree across a recomposition and so exporting them costs one
     *  iOS watcher subclass rather than two. */
    data class Reading(val averageKmh: Double?, val limitKmh: Double?)

    data class State(
        val active: SpeedCameras.Section? = null,
        val exitGate: List<LatLon> = emptyList(),
        val entryMs: Long = 0L,
        val accMeters: Double = 0.0,
        val last: LatLon? = null,
        val reading: Reading = Reading(null, null),
    )

    /**
     * One GPS fix. Returns the next state; [State.reading] is what a readout
     * shows, both halves null when not inside a section.
     *
     * [sections] is whatever the caller's Overpass prefetch currently holds -
     * this machine never fetches. [headingDeg] and [speedMps] are only read
     * while unarmed, which is where the bearing test lives.
     */
    fun onFix(
        state: State,
        sections: List<SpeedCameras.Section>,
        at: LatLon,
        headingDeg: Double?,
        speedMps: Double,
        nowMs: Long,
    ): State {
        val current = state.active ?: return arm(state, sections, at, headingDeg, speedMps, nowMs)
        return advance(state, current, at, nowMs)
    }

    private fun arm(
        state: State,
        sections: List<SpeedCameras.Section>,
        at: LatLon,
        headingDeg: Double?,
        speedMps: Double,
        nowMs: Long,
    ): State {
        val heading = headingDeg?.takeIf { speedMps > ARM_MIN_MPS } ?: return state
        // Nearest match, not the first: the two directions of one
        // trajectcontrole are separate relations sharing a location, and
        // a short section can sit inside a longer one.
        val entered = sections
            .mapNotNull { s -> sectionExitGate(s, at, heading)?.let { s to it } }
            .minByOrNull { (s, _) ->
                (s.endA + s.endB).minOf { RoadRoulette.distanceMeters(at, it) }
            } ?: return state
        return state.copy(
            active = entered.first,
            exitGate = entered.second,
            entryMs = nowMs,
            accMeters = 0.0,
            last = at,
            reading = Reading(null, entered.first.maxspeedKmh),
        )
    }

    private fun advance(
        state: State,
        current: SpeedCameras.Section,
        at: LatLon,
        nowMs: Long,
    ): State {
        val accMeters = state.accMeters +
            (state.last?.let { RoadRoulette.distanceMeters(it, at) } ?: 0.0)
        val elapsedHours = (nowMs - state.entryMs) / 3_600_000.0
        val reading =
            if (elapsedHours > 0 && accMeters > MIN_ACC_METERS_FOR_AVERAGE) {
                state.reading.copy(averageKmh = (accMeters / 1000.0) / elapsedHours)
            } else {
                state.reading
            }
        // Only the end we drove in towards ends the measurement. The
        // 150 m floor keeps the gate we entered through from counting as
        // the exit on the fix right after entering.
        val reachedEnd = accMeters > MIN_ACC_METERS_BEFORE_EXIT &&
            state.exitGate.any { RoadRoulette.distanceMeters(at, it) < SECTION_GATE_METERS }
        val overshot = accMeters > current.spanMeters * OVERSHOOT_FACTOR + OVERSHOOT_SLACK_METERS
        val timedOut = nowMs - state.entryMs > TIMEOUT_MS
        return if (reachedEnd || overshot || timedOut) {
            // accMeters is carried, not zeroed: the inline version did not zero
            // it either, and arming overwrites it.
            state.copy(
                active = null,
                exitGate = emptyList(),
                accMeters = accMeters,
                last = null,
                reading = Reading(null, null),
            )
        } else {
            state.copy(accMeters = accMeters, last = at, reading = reading)
        }
    }

    /**
     * The far end of [section], if this fix is entering it: within the gate of one
     * end and heading towards the other. Null otherwise.
     *
     * The heading test is what makes the gate mean "driving the section". Passing a
     * device node says nothing on its own — you pass one on the way *out* too, and
     * on every side street that crosses one — and matching on that alone used to
     * start a measurement as you left a section, which is what put an average on
     * screen after the trajectcontrole instead of during it.
     */
    internal fun sectionExitGate(
        section: SpeedCameras.Section,
        pos: LatLon,
        headingDeg: Double,
    ): List<LatLon>? {
        fun atGate(end: List<LatLon>) =
            end.any { RoadRoulette.distanceMeters(pos, it) < SECTION_GATE_METERS }
        fun ahead(end: List<LatLon>) =
            end.any { RoadRoulette.withinWedge(pos, it, headingDeg, SECTION_WEDGE_DEG) }
        return when {
            atGate(section.endA) && ahead(section.endB) -> section.endB
            atGate(section.endB) && ahead(section.endA) -> section.endA
            else -> null
        }
    }
}

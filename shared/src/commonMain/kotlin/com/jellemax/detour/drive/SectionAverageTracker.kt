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

    /** Which of the three clauses in [advance] ended a measurement. Reported
     *  rather than recomputed: a caller that wants to say *why* a readout
     *  vanished would otherwise have to re-derive all three from [State], and a
     *  second copy of that arithmetic is a second thing that can disagree. */
    enum class ExitReason { REACHED_END, OVERSHOT, TIMED_OUT }

    /**
     * What this fix did, for a caller that logs. Nothing here is needed to
     * render a readout — [State.reading] is that — so [onFix] stays the API for
     * callers that only draw.
     */
    sealed interface Outcome {
        /** Still measuring, or still nothing to measure. */
        data object Silent : Outcome

        /**
         * A measurement started. [exitGateMeters] is how far the far end is from
         * [at], and [candidates] how many held sections this fix could have
         * entered — the pair that says whether a *short* section sharing this
         * gantry was picked over the long one the rider is driving.
         */
        data class Armed(
            val section: SpeedCameras.Section,
            val at: LatLon,
            val exitGateMeters: Double,
            val candidates: Int,
        ) : Outcome

        /**
         * A measurement ended. [nearestExitGateMeters] is the distance that
         * decided `REACHED_END`, and the number that convicts the other two
         * reasons when it is large: maxke24/Detour#22 is a clear with no gate
         * within 3.5 km of it.
         */
        data class Cleared(
            val reason: ExitReason,
            val section: SpeedCameras.Section,
            val at: LatLon,
            val accMeters: Double,
            val elapsedMs: Long,
            val nearestExitGateMeters: Double,
        ) : Outcome
    }

    /** The next state and what this fix did. */
    data class Step(val state: State, val outcome: Outcome)

    /**
     * One GPS fix. Returns the next state; [State.reading] is what a readout
     * shows, both halves null when not inside a section.
     *
     * [sections] is whatever the caller's Overpass prefetch currently holds -
     * this machine never fetches. [headingDeg] and [speedMps] are only read
     * while unarmed, which is where the bearing test lives.
     *
     * Thin over [step], which is the same fix with the transition reported. Kept
     * as the default because most callers only draw the reading, and because
     * every existing caller and test was written against this shape.
     */
    fun onFix(
        state: State,
        sections: List<SpeedCameras.Section>,
        at: LatLon,
        headingDeg: Double?,
        speedMps: Double,
        nowMs: Long,
    ): State = step(state, sections, at, headingDeg, speedMps, nowMs).state

    /**
     * [onFix], plus why the readout appeared or vanished.
     *
     * Exists because nothing could say why before it: a run that lost its
     * average mid-section left no record of which section was armed, how far
     * its far end was, or which clause fired — so maxke24/Detour#22 was argued
     * from screenshots for a month. The transition is one step of one fix, so
     * reporting it costs a return value rather than any state.
     */
    fun step(
        state: State,
        sections: List<SpeedCameras.Section>,
        at: LatLon,
        headingDeg: Double?,
        speedMps: Double,
        nowMs: Long,
    ): Step {
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
    ): Step {
        val heading = headingDeg?.takeIf { speedMps > ARM_MIN_MPS }
            ?: return Step(state, Outcome.Silent)
        // Nearest match, not the first: the two directions of one
        // trajectcontrole are separate relations sharing a location, and
        // a short section can sit inside a longer one.
        val candidates = sections.mapNotNull { s -> sectionExitGate(s, at, heading)?.let { s to it } }
        val entered = candidates
            .minByOrNull { (s, _) ->
                (s.endA + s.endB).minOf { RoadRoulette.distanceMeters(at, it) }
            } ?: return Step(state, Outcome.Silent)
        return Step(
            state.copy(
                active = entered.first,
                exitGate = entered.second,
                entryMs = nowMs,
                accMeters = 0.0,
                last = at,
                reading = Reading(null, entered.first.maxspeedKmh),
            ),
            Outcome.Armed(
                section = entered.first,
                at = at,
                exitGateMeters = entered.second.minOf { RoadRoulette.distanceMeters(at, it) },
                candidates = candidates.size,
            ),
        )
    }

    private fun advance(
        state: State,
        current: SpeedCameras.Section,
        at: LatLon,
        nowMs: Long,
    ): Step {
        val accMeters = state.accMeters +
            (state.last?.let { RoadRoulette.distanceMeters(it, at) } ?: 0.0)
        val elapsedMs = nowMs - state.entryMs
        val elapsedHours = elapsedMs / 3_600_000.0
        val reading =
            if (elapsedHours > 0 && accMeters > MIN_ACC_METERS_FOR_AVERAGE) {
                state.reading.copy(averageKmh = (accMeters / 1000.0) / elapsedHours)
            } else {
                state.reading
            }
        // An empty gate reads as unreachably far, which is what `any {}` did.
        val nearestExit = state.exitGate
            .minOfOrNull { RoadRoulette.distanceMeters(at, it) } ?: Double.MAX_VALUE
        val reason = exitReason(accMeters, current.spanMeters, nearestExit, elapsedMs)
            ?: return Step(state.copy(accMeters = accMeters, last = at, reading = reading), Outcome.Silent)
        return Step(
            // accMeters is carried, not zeroed: the inline version did not zero
            // it either, and arming overwrites it.
            state.copy(
                active = null,
                exitGate = emptyList(),
                accMeters = accMeters,
                last = null,
                reading = Reading(null, null),
            ),
            Outcome.Cleared(
                reason = reason,
                section = current,
                at = at,
                accMeters = accMeters,
                elapsedMs = elapsedMs,
                nearestExitGateMeters = nearestExit,
            ),
        )
    }

    /**
     * Which clause ends the measurement, or null to keep measuring. Order is the
     * order the three used to sit in one `if`, so a fix that satisfies more than
     * one reports the same reason it always exited by.
     *
     * Only the end we drove in towards ends it, and the 150 m floor keeps the
     * gate we entered through from counting as the exit on the fix right after
     * entering.
     */
    private fun exitReason(
        accMeters: Double,
        spanMeters: Double,
        nearestExitMeters: Double,
        elapsedMs: Long,
    ): ExitReason? = when {
        accMeters > MIN_ACC_METERS_BEFORE_EXIT && nearestExitMeters < SECTION_GATE_METERS ->
            ExitReason.REACHED_END
        accMeters > spanMeters * OVERSHOOT_FACTOR + OVERSHOOT_SLACK_METERS -> ExitReason.OVERSHOT
        elapsedMs > TIMEOUT_MS -> ExitReason.TIMED_OUT
        else -> null
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

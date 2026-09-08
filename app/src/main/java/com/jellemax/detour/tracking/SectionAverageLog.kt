package com.jellemax.detour.tracking

import android.util.Log
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.SpeedCameras
import com.jellemax.detour.drive.SectionAverageTracker
import com.jellemax.detour.presentation.formatFixed

/**
 * The one-line record of a trajectcontrole measurement starting and ending.
 *
 * **Why this exists.** Nothing logged it before, so a run that lost its average
 * mid-section left no trace of which section had been armed, how far its far end
 * was, or which of the three exit clauses fired. maxke24/Detour#22 was argued
 * from screenshots and arithmetic for a month, and the one input it needed — what
 * `speedSections` actually held — was never measured. The events in
 * `tools/mocklocation/baseline/…-events.tsv` were recovered from *pixels*: a
 * framebuffer capture at 0.87 s per frame, diffed for the chip's colour. This
 * makes the same measurement a `logcat | grep`, on any route, at any replay
 * speed, and reports the reason instead of leaving it to be inferred.
 *
 * **One formatter, two surfaces.** The phone (`ui/MapHazardAlerts.kt`) and the
 * head unit (`car/NavScreen.kt`) run the same machine on the same fix stream, so
 * a defect seen on one is worth grepping for on the other — which only works
 * while both lines are byte-identical. Hence here rather than at either call
 * site. It stays in `app/` and not in `:shared` because it is a diagnostic
 * string and `:shared` has no logger; `SectionAverageTracker.step` reports the
 * transition, this one says it.
 *
 * Shaped like [com.jellemax.detour.perf.PerfLog]: a pure formatting object in
 * `app/`, separate from anything that writes, because this half is where the
 * answers can be wrong.
 *
 * `AVG-ON` and `AVG-CLEARED` are deliberately the two markers the baseline TSVs
 * already use, so a new run and the recorded corpus answer to one grep.
 */
internal object SectionAverageLog {

    const val TAG = "DetourSection"

    /**
     * Say what this fix did, if it did anything.
     *
     * [SectionAverageTracker.Outcome.Silent] is not logged: it is nearly every
     * fix, and a line each would be 9 825 of them for one replay of a 150 km
     * route — volume rather than a record. `Log.i`, not `d`: two lines per
     * section is not debug chatter, and a release build that drops them cannot
     * answer why a rider's readout vanished.
     */
    fun log(outcome: SectionAverageTracker.Outcome) {
        when (outcome) {
            is SectionAverageTracker.Outcome.Silent -> {}
            is SectionAverageTracker.Outcome.Armed -> Log.i(TAG, armed(outcome))
            is SectionAverageTracker.Outcome.Cleared -> Log.i(TAG, cleared(outcome))
        }
    }

    private fun armed(o: SectionAverageTracker.Outcome.Armed) = buildString {
        append("AVG-ON at=").append(pos(o.at))
        append(" span=").append(meters(o.section.spanMeters))
        append(" exitGate=").append(meters(o.exitGateMeters))
        append(" limit=").append(o.section.maxspeedKmh?.let { formatFixed(it, 0) } ?: "none")
        // The pair that says a short section sharing this gantry was preferred to
        // the long one the rider is driving: more than one candidate, and an
        // exitGate much shorter than the span above.
        append(" candidates=").append(o.candidates)
        append(" ends=").append(ends(o.section))
    }

    private fun cleared(o: SectionAverageTracker.Outcome.Cleared) = buildString {
        append("AVG-CLEARED reason=").append(o.reason.name)
        append(" at=").append(pos(o.at))
        append(" acc=").append(meters(o.accMeters))
        append(" span=").append(meters(o.section.spanMeters))
        // REACHED_END is only defensible while this is under
        // SECTION_GATE_METERS. #22's clear had no gate node within 3 529 m,
        // which is what made its reported exit impossible, so this is the first
        // field to read on any early clear.
        append(" nearestGate=").append(meters(o.nearestExitGateMeters))
        append(" elapsed=").append(formatFixed(o.elapsedMs / MS_PER_SECOND, 1)).append('s')
        append(" avg=").append(average(o))
        append(" ends=").append(ends(o.section))
    }

    /** The section's end clusters, so the relation is identifiable from the log
     *  alone — with no Overpass query, which is exactly what was unavailable
     *  while #22 was being investigated. */
    private fun ends(section: SpeedCameras.Section) =
        (section.endA + section.endB).joinToString("|") { pos(it) }

    /** Recomputed rather than read off the state: the clearing step nulls the
     *  reading, so the last average is gone by the time this is called. */
    private fun average(o: SectionAverageTracker.Outcome.Cleared): String {
        val hours = o.elapsedMs / MS_PER_HOUR
        if (hours <= 0.0) return "none"
        return formatFixed((o.accMeters / METERS_PER_KM) / hours, 1)
    }

    /**
     * Coordinates as `lat,lon`, five decimals, no space.
     *
     * Deliberately **not** [com.jellemax.detour.presentation.formatCoordinatePair],
     * which is the right formatter for anything a rider reads and the wrong one
     * here: it separates the pair with `", "`, and a space inside a field breaks
     * a line whose fields are space-separated. What matters about it is reused —
     * [formatFixed] is locale-independent by construction, so this cannot become
     * the `"%.5f, %.5f".format(...)` under a comma locale that
     * `formatCoordinatePair`'s KDoc exists to warn about. This device is one.
     */
    private fun pos(p: LatLon) = "${formatFixed(p.lat, 5)},${formatFixed(p.lon, 5)}"

    private fun meters(v: Double) = formatFixed(v, 1) + "m"

    private const val MS_PER_HOUR = 3_600_000.0
    private const val MS_PER_SECOND = 1_000.0
    private const val METERS_PER_KM = 1_000.0
}

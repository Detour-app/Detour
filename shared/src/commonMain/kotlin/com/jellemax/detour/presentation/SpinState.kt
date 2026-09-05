package com.jellemax.detour.presentation

import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.TravelMode

/**
 * Compass names for the eight 45°-wide bearing buckets a spin's direction
 * picker offers, starting from north. Ported verbatim from
 * `com.jellemax.detour.ui.DIRECTION_NAMES` (`app/.../ui/SpinCards.kt`).
 */
internal val DIRECTION_NAMES = listOf(
    "North", "North-east", "East", "South-east",
    "South", "South-west", "West", "North-west",
)

/** One candidate row in the spin result sheet: a rolled destination's name
 *  plus its already-formatted distance and, when the route reported one, its
 *  duration. [durationText] is null exactly when `CandidatesCard`'s duration
 *  chip today is absent - a route with no `timeMs` (straight-line-only draw). */
data class SpinCandidateRow(
    val name: String,
    val distanceText: String,
    val durationText: String?,
)

/**
 * The spin dock and result sheet's display state: what `ModeCell`
 * (`SpinDock.kt`) and `CandidatesCard` (`CandidatesCard.kt`) render today,
 * pulled out of their inline string-building so it is testable without
 * Compose. Deliberately does not merge [modeLabel] and [directionText] into
 * one string the way `ModeCell` does (`"${mode.label} · $directionText"`) -
 * that join is layout, left to whichever composable renders this next.
 */
data class SpinState(
    val modeLabel: String,
    val radiusText: String,
    val directionText: String,
    val candidates: List<SpinCandidateRow>,
)

/**
 * `ModeCell`'s inline `"${if (mode.maxKm <= 10f) "%.1f".format(radiusKm) else
 * radiusKm.toInt()} km"`, ported as-is: below a mode's own `maxKm` cutoff a
 * radius reads to one decimal place (via [formatFixed], since `commonMain`
 * has no `String.format`); at or above it, a whole number - truncated by
 * `toInt()`, not rounded, exactly as the original does.
 *
 * `internal` rather than folded into [spinStateFrom] so a test can drive the
 * decimal branch directly: no [TravelMode] entry today sets `maxKm` at or
 * under 10 (CAR's is 100, MOTO's 400), so that branch is unreachable through
 * [spinStateFrom] with real modes - almost certainly a vestige of a removed
 * on-foot/bike mode that once used it.
 */
internal fun radiusText(maxKm: Float, radiusKm: Float): String =
    "${if (maxKm <= 10f) formatFixed(radiusKm.toDouble(), 1) else radiusKm.toInt().toString()} km"

/**
 * `ModeCell`'s inline `directionDeg?.let { DIRECTION_NAMES[(it / 45f).toInt()] }
 * ?: "any direction"`, ported unchanged - bug included. The picker that
 * drives `directionDeg` in the app only ever sets it to null or one of the
 * eight bucket centres (0, 45, ..., 315), for which this always resolves.
 * Nothing in the type stops a caller from passing anything else, though, and
 * outside that range the ported arithmetic misbehaves exactly as it does
 * today:
 *  - 360 divides to bucket index 8, one past the last name, and throws
 *    `IndexOutOfBoundsException` - same for any other exact multiple of 360.
 *  - a negative exact multiple of 45 (-45, -90, ...) truncates to a negative
 *    index and throws the same way.
 *  - a negative bearing that is *not* a multiple of 45 (e.g. -10) truncates
 *    toward zero and silently lands on bucket 0 ("North") instead of
 *    throwing or wrapping - wrong, but not a crash.
 * `internal`, matching [radiusText], so a test can reach it without a full
 * [SpinState] on the way.
 */
internal fun directionText(directionDeg: Float?): String =
    directionDeg?.let { DIRECTION_NAMES[(it / 45f).toInt()] } ?: "any direction"

/**
 * `CandidatesCard`'s per-row `Column` and duration `Surface`, ported as one
 * row: `c.name ?: "Option ${index + 1}"` for the label, the route distance
 * falling back to the straight-line draw with the same "~ straight-line " /
 * "via road " prefix, and `"%.0f min".format(timeMs / 60_000.0)` - present
 * only when the candidate has a routed `timeMs` at all, same as the chip
 * `c.route?.timeMs?.let { ... }` guards today.
 */
internal fun candidateRow(index: Int, candidate: RouteCandidate): SpinCandidateRow {
    val distanceMeters = candidate.route?.distanceMeters ?: candidate.straightLineMeters
    val prefix = if (candidate.route?.distanceMeters == null) "~ straight-line " else "via road "
    return SpinCandidateRow(
        name = candidate.name ?: "Option ${index + 1}",
        distanceText = prefix + formatDistanceKm(distanceMeters),
        durationText = candidate.route?.timeMs?.let { "${formatFixed(it / 60_000.0, 0)} min" },
    )
}

/**
 * Pure map from a spin's mode/radius/direction/candidates to [SpinState] -
 * the mode label, the radius readout, the direction name and the candidate
 * rows the dock and result sheet render today. Does not own `spin()`'s
 * orchestration, the convoy vote plumbing, or anything touching
 * `camAuthority`; those stay in `MapScreen.kt`.
 */
fun spinStateFrom(
    mode: TravelMode,
    radiusKm: Float,
    directionDeg: Float?,
    candidates: List<RouteCandidate>,
): SpinState = SpinState(
    modeLabel = mode.label,
    radiusText = radiusText(mode.maxKm, radiusKm),
    directionText = directionText(directionDeg),
    candidates = candidates.mapIndexed(::candidateRow),
)

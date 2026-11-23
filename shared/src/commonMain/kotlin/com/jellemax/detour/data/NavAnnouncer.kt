package com.jellemax.detour.data

import kotlin.math.roundToInt

/**
 * When a navigation session speaks, and what it says.
 *
 * Written twice before this file existed — `app/…/car/NavScreen.kt` and
 * `iosApp/Detour/NavScreen.swift`, both with the same three thresholds, the
 * same `spokenDistance` and the same three-field latch — and register decision
 * 1 adds the phone as a third consumer. A policy written more than once earns
 * the core (docs/DEVELOPERS.md § "Where code belongs"), and the point of writing it here is that
 * three surfaces can no longer word the same maneuver differently.
 *
 * **Decision and wording here; delivery per platform.** The same split
 * `CircleEvents.notificationText` makes, for the same reason. Nothing in this
 * file knows about `TextToSpeech`, `AVSpeechSynthesizer`, `ToneGenerator` or a
 * `CarToast`; a core that did could not be shared.
 *
 * **No clock.** The latch is path-dependent over the *distance* sequence, not
 * over time — there is no timestamp in either copy this replaces — so there is
 * nothing to inject and nothing to fake. The one nav rule that does need a
 * clock, the reroute cooldown, is [com.jellemax.detour.map.NavPolicy]'s and
 * takes `nowMs` as a parameter. The timed prompts (#1012) still need none:
 * "25 s before the turn" is a distance once the caller passes its speed.
 *
 * **Nothing here is derived from [NavInstruction.sign].** The cue is
 * GraphHopper's own `text`, which is already words. The sign-to-glyph table is
 * four per-platform copies (register entry 4) and deliberately stays that way:
 * rendering a maneuver from its sign code here would make this a fifth copy of
 * that table, in prose.
 *
 * One instance per navigation session, fed in the order the fixes arrive —
 * same contract as [GeofenceEvaluator], and the reason this is a class rather
 * than a free function.
 */
class NavAnnouncer {

    companion object {
        // The fixed ladder, in metres before the turn: one prompt early enough
        // to change lane, one to commit, one at the turn itself. Only the
        // speed-less overload of [onProgress] still uses it (iOS, register
        // entry 40); the Android surfaces pass a speed and get the timed
        // ladder below. Each prompt fires at most once per instruction, and a
        // step that starts closer than a threshold simply skips it.
        //
        // Not constructor parameters: Kotlin/Native drops default argument
        // values, so a defaulted constructor would need a withDefaults()
        // factory for Swift the way GeofenceEvaluator does. Not read from
        // Swift either — see the file's KDoc.
        const val FAR_METERS = 800.0
        const val NEAR_METERS = 300.0
        const val NOW_METERS = 80.0

        // The timed ladder (#1012): each prompt lands a number of seconds
        // before the turn at the current speed, with a floor for town speeds
        // and, for the far prompt, a ceiling so a motorway doesn't announce a
        // turn 5 km out. 800/300/80 m was 96 s of warning at 30 km/h and
        // 2.4 s at 120 km/h.
        const val FAR_SECONDS = 25.0
        const val FAR_MIN_METERS = 400.0
        const val FAR_MAX_METERS = 2000.0
        const val NEAR_SECONDS = 10.0
        const val NEAR_MIN_METERS = 150.0
        const val NOW_SECONDS = 3.0
        const val NOW_MIN_METERS = 30.0

        // Added to every timed threshold: the engine starting to talk, plus
        // up to a second until the fix that crosses a threshold arrives.
        const val SPEECH_LEAD_SECONDS = 1.5

        // A following maneuver this close behind the announced one — in time
        // at the current speed or in distance, whichever is larger — rides
        // along on the near and now prompts as ", then …".
        const val THEN_SECONDS = 8.0
        const val THEN_METERS = 150.0
    }

    // Which instruction is being announced, and how far through its three
    // prompts we are.
    private var stepKey = Int.MIN_VALUE
    private var phase = 0
    private var startAnnounced = false

    /**
     * Re-arms everything: the next call to [onProgress] announces whatever the
     * route asks for, at whatever distance it is.
     *
     * Called at the start of a session, and after a reroute succeeds —
     * instruction indices belong to the old polyline, so the new line's prompts
     * have to start from scratch. Deliberately *not* called by [rerouting]:
     * the car speaks "Rerouting" before the request and re-arms only if the
     * request comes back (`car/NavScreen.kt:261` versus `:275-277`).
     */
    fun routeChanged() {
        stepKey = Int.MIN_VALUE
        phase = 0
        startAnnounced = false
    }

    /** What to say when a reroute is requested. Here rather than at three call
     *  sites for the same reason as [PlaceEvent.notificationText]: it is text a
     *  user hears, and the surfaces must not word it differently. */
    fun rerouting(): String = "Rerouting"

    /**
     * The words for this fix, or null when nothing is due — which is most
     * fixes. Call once per fix, in order.
     *
     * The thresholds follow [speedMps] (see the companion's timed ladder), and
     * a maneuver closely following the next one is added to the near and now
     * prompts as ", then …". A negative or non-finite speed counts as standing
     * still.
     */
    fun onProgress(progress: NavEngine.Progress, speedMps: Double): String? {
        val v = if (speedMps.isFinite()) speedMps.coerceAtLeast(0.0) else 0.0
        val following = progress.nextNextInstruction
        val followingAt = progress.distanceToNextNextMeters
        val then = if (following != null && followingAt != null &&
            followingAt - progress.distanceToTurnMeters <= maxOf(THEN_SECONDS * v, THEN_METERS)
        ) following.text.ifBlank { null } else null
        return announce(progress.nextInstruction, progress.distanceToTurnMeters, timedLadder(v), then)
    }

    /**
     * The fixed 800/300/80 m ladder and no ", then …": what every surface did
     * before #1012. Kept for iOS, which is parked and has no speed wired into
     * this call yet — register entry 40. Kotlin/Native drops default
     * arguments, so this is an overload rather than nullable parameters.
     *
     * [distanceMeters] is [NavEngine.Progress.distanceToTurnMeters].
     * [instruction] is [NavEngine.Progress.nextInstruction]; null means there
     * is no maneuver ahead and nothing to say.
     */
    fun onProgress(instruction: NavInstruction?, distanceMeters: Double): String? =
        announce(instruction, distanceMeters, FIXED_LADDER, then = null)

    private fun announce(
        instruction: NavInstruction?,
        distanceMeters: Double,
        ladder: Ladder,
        then: String?,
    ): String? {
        instruction ?: return null
        if (instruction.startIndex != stepKey) {
            stepKey = instruction.startIndex
            phase = 0
        }
        // Inclusive bounds. iOS used a half-open range until convergence 3 and
        // disagreed with the car at exactly 800, 300 and 80 m; register entry
        // 12 chose the car's.
        val next = when {
            distanceMeters <= ladder.nowMeters -> 3
            distanceMeters <= ladder.nearMeters -> 2
            distanceMeters <= ladder.farMeters -> 1
            else -> 0
        }
        val cue = instruction.text.ifBlank { "Continue" }
        // The first prompt of the drive ignores the thresholds: pressing Start
        // and being told nothing for the next 3 km is indistinguishable from
        // voice being broken.
        if (!startAnnounced) {
            startAnnounced = true
            phase = next
            return prompt(next, distanceMeters, cue, then)
        }
        if (next == 0 || next <= phase) return null
        phase = next
        return prompt(next, distanceMeters, cue, then)
    }

    /** At the turn itself the distance is noise — "turn right" is the whole
     *  message. Further out it is the useful half. The following maneuver is
     *  only worth saying once the rider is committing to this one: on the far
     *  prompt it would be heard and forgotten. */
    private fun prompt(phase: Int, distanceMeters: Double, cue: String, then: String?): String {
        val head = if (phase == 3) cue else "In ${spokenDistance(distanceMeters)}, $cue"
        return if (then != null && phase >= 2) "$head, then ${then.replaceFirstChar { it.lowercaseChar() }}" else head
    }
}

/** Where the three prompts land for one fix, in metres before the turn. */
internal data class Ladder(val farMeters: Double, val nearMeters: Double, val nowMeters: Double)

private val FIXED_LADDER = Ladder(NavAnnouncer.FAR_METERS, NavAnnouncer.NEAR_METERS, NavAnnouncer.NOW_METERS)

/** The timed ladder at [speedMps] (already clamped to ≥ 0). `internal` so
 *  `commonTest` can check the numbers the issue asked for directly. */
internal fun timedLadder(speedMps: Double): Ladder {
    val lead = NavAnnouncer.SPEECH_LEAD_SECONDS * speedMps
    return Ladder(
        farMeters = (NavAnnouncer.FAR_SECONDS * speedMps)
            .coerceIn(NavAnnouncer.FAR_MIN_METERS, NavAnnouncer.FAR_MAX_METERS) + lead,
        nearMeters = maxOf(NavAnnouncer.NEAR_SECONDS * speedMps, NavAnnouncer.NEAR_MIN_METERS) + lead,
        nowMeters = maxOf(NavAnnouncer.NOW_SECONDS * speedMps, NavAnnouncer.NOW_MIN_METERS) + lead,
    )
}

/** Distance as a driver would say it, for the spoken prompts. Not the same
 *  rounding as a banner or a template uses — those quantise for a glance and
 *  keep their own copies (`car/NavScreen.kt`'s `displayMeters`,
 *  `NavScreen.swift`'s `displayDistance`). `internal` so `commonTest` can
 *  assert the buckets directly; no surface needs it. */
internal fun spokenDistance(meters: Double): String = when {
    meters >= 1500.0 -> "${(meters / 1000.0).roundToInt()} kilometers"
    meters >= 950.0 -> "1 kilometer"
    meters >= 100.0 -> "${(meters / 100.0).roundToInt() * 100} meters"
    else -> "${(meters / 10.0).roundToInt() * 10} meters"
}

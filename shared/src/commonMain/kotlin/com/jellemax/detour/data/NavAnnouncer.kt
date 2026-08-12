package com.jellemax.detour.data

import kotlin.math.roundToInt

/**
 * When a navigation session speaks, and what it says.
 *
 * Written twice before this file existed — `app/…/car/NavScreen.kt` and
 * `iosApp/Detour/NavScreen.swift`, both with the same three thresholds, the
 * same `spokenDistance` and the same three-field latch — and register decision
 * 1 adds the phone as a third consumer. A policy written more than once earns
 * the core (`CONTRIBUTING.md:23-32`), and the point of writing it here is that
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
 * takes `nowMs` as a parameter.
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
        // Where the spoken prompts land, in metres before the turn. Three of
        // them: one early enough to change lane on a fast road, one to commit,
        // and one at the turn itself. Each fires at most once per instruction,
        // and a step that starts closer than a threshold simply skips it — in
        // town that usually means only the last two are heard.
        //
        // Not constructor parameters: Kotlin/Native drops default argument
        // values, so a defaulted constructor would need a withDefaults()
        // factory for Swift the way GeofenceEvaluator does. Not read from
        // Swift either — see the file's KDoc.
        const val FAR_METERS = 800.0
        const val NEAR_METERS = 300.0
        const val NOW_METERS = 80.0
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
     * [distanceMeters] is [NavEngine.Progress.distanceToTurnMeters].
     * [instruction] is [NavEngine.Progress.nextInstruction]; null means there
     * is no maneuver ahead and nothing to say.
     */
    fun onProgress(instruction: NavInstruction?, distanceMeters: Double): String? {
        instruction ?: return null
        if (instruction.startIndex != stepKey) {
            stepKey = instruction.startIndex
            phase = 0
        }
        // Inclusive bounds. iOS used a half-open range until convergence 3 and
        // disagreed with the car at exactly 800, 300 and 80 m; register entry
        // 12 chose the car's.
        val next = when {
            distanceMeters <= NOW_METERS -> 3
            distanceMeters <= NEAR_METERS -> 2
            distanceMeters <= FAR_METERS -> 1
            else -> 0
        }
        val cue = instruction.text.ifBlank { "Continue" }
        // The first prompt of the drive ignores the thresholds: pressing Start
        // and being told nothing for the next 3 km is indistinguishable from
        // voice being broken.
        if (!startAnnounced) {
            startAnnounced = true
            phase = next
            return prompt(next, distanceMeters, cue)
        }
        if (next == 0 || next <= phase) return null
        phase = next
        return prompt(next, distanceMeters, cue)
    }

    /** At the turn itself the distance is noise — "turn right" is the whole
     *  message. Further out it is the useful half. */
    private fun prompt(phase: Int, distanceMeters: Double, cue: String): String =
        if (phase == 3) cue else "In ${spokenDistance(distanceMeters)}, $cue"
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

package com.jellemax.detour.data

import kotlinx.coroutines.CancellationException
import kotlin.concurrent.Volatile

/**
 * Circles' second sink on whatever fix a platform's own location collector
 * just produced — the "one collector, two sinks" rule from
 * docs/CIRCLES_AND_CONVOYS.md section 10. [tick] is what
 * `TripTrackingService.circleSyncLoop` (Android) and `CircleSync.loop` (iOS)
 * used to duplicate independently, structurally identical down to their
 * constants: for every circle where this device's own membership has
 * sharing on, post the latest fix ([CircleFixes.postFix]) and run it through
 * that circle's [GeofenceEvaluator], posting any arrive/depart transition
 * ([CircleEvents.record]).
 *
 * Deliberately not a loop itself. Each platform keeps its own `while`/
 * `delay` and its own fix source (a `StateFlow` on Android, `LocationBroadcast`
 * on iOS) — [tick] is called once per pass and its return value is the delay
 * before the next call:
 * ```
 * var interval = CirclePresence.ACTIVE_INTERVAL_MS
 * while (true) {
 *     delay(interval)
 *     interval = CirclePresence.tick(lat, lon, accuracyM, fixTimeMs, fixAgeMs, nowMs)
 * }
 * ```
 * What moved here is the *decision* every pass makes, which was previously
 * duplicated bit for bit; the loop, the fix source and the "do we have a fix
 * at all" guard all stay on the platform. A platform only calls [tick] once
 * it has a fix to share, so — unlike `SyncClient.configured()` and
 * `Account.signedIn`, both checked inside [tick] — the non-null-fix guard
 * has no equivalent here: [tick]'s position parameters are not optional.
 *
 * ### The three clocks
 * [tick] takes three separate time parameters and they must never collapse
 * into fewer:
 * - [fixAgeMs] — **monotonic**, "how old is this reading". `commonMain` has
 *   no monotonic clock (Platform.kt's three-concern ceiling forbids adding
 *   one), so this is computed by the platform
 *   (`SystemClock.elapsedRealtime() - fix.elapsedRealtimeMs` on Android) and
 *   passed in — a device clock that drifts or is corrected mid-drive would
 *   answer "how old is this reading" wrong in whichever direction the
 *   correction went.
 * - [fixTimeMs] — **wall clock**, "when was this fix taken" — the opposite
 *   question — and it is what gets posted to the server as the fix's own
 *   timestamp.
 * - [nowMs] — **wall clock**, for dwell, and deliberately *not* derived from
 *   [fixTimeMs]: a phone standing still stops producing new fixes, so timing
 *   dwell off the fix's own timestamp would freeze the clock at exactly the
 *   moment someone parked, and arrival would never fire — the one thing a
 *   circle is for.
 *
 * ### Session-scoped state
 * [evaluators] is a per-circle [GeofenceEvaluator] map that has to persist
 * across ticks — it holds dwell/inside state between calls, so a fresh
 * evaluator every tick could never accumulate enough dwell time to fire
 * "arrive" at all. That persistence is exactly the shape a previous slice
 * found leaking rider-scoped state across a sign-out five separate times,
 * three only caught by an adversarial review. [sessionChanged] (wired up in
 * [tick] itself, since this object owns no coroutine of its own the way
 * [com.jellemax.detour.drive.ConvoyRelay.run] does to watch from) is
 * [com.jellemax.detour.drive.ConvoyRelay]'s
 * `discardMembershipIfSessionChanged` pattern: an epoch-freshness check at
 * the one entry point, not a watcher — the watcher pattern needs a live
 * scope to launch into, which a plain `suspend fun` called once per pass
 * does not have. A rejoin under the same session (the epoch unchanged) must
 * still keep whatever dwell state a circle already had, which is why this
 * checks the epoch rather than clearing unconditionally on every tick — but
 * a sign-out, 401, or server switch must not leave a departed rider's dwell
 * state for the next signed-in rider to inherit.
 *
 * [currentIntervalMs] is *not* cleared on a session change, on purpose: it
 * is a cadence, not rider data — nothing in it identifies who was signed in
 * — and the next tick that actually gets past the `SyncClient`/`Account`
 * guards recomputes it fresh from that rider's own circle list before it is
 * ever read again. At worst a session change leaves one stale tick's worth
 * of wait time, the same as Android/iOS already tolerate between any two
 * ordinary ticks.
 */
object CirclePresence {

    /** A circle is Life360-style presence, not a live ride feed, so this
     *  deliberately stays on the order of minutes — keeps "last seen"
     *  reading as current without turning a background circle into a
     *  battery cost anyone notices. */
    const val ACTIVE_INTERVAL_MS = 2 * 60_000L

    /** Cadence once a tick finds no circle to share with — the cost a user
     *  who never touches the feature pays, and the delay before joining
     *  their first circle starts working. */
    const val IDLE_INTERVAL_MS = 30 * 60_000L

    /** On Android, in `SLEEP` mode the fused location request runs at
     *  `PRIORITY_PASSIVE`, so a parked phone can go a long time between
     *  fixes. That's fine for a position nobody has moved, but a fix this
     *  old means the phone could be anywhere by now, and must not drive a
     *  geofence decision. [tick] still posts it first — an honest
     *  "last seen" — and only checks this after; see [isFixTrusted]. */
    const val FIX_TRUST_MS = 15 * 60_000L

    /** One evaluator per circle, kept across ticks — see the class doc.
     *  Replaced wholesale rather than mutated in place, the same reason
     *  `MunicipalityStore.misses` is: `commonMain` has no
     *  `ConcurrentHashMap`, so a plain mutable map touched from more than
     *  one dispatcher is unsafe, and swapping an immutable map into a
     *  `@Volatile` field needs no lock on either platform.
     *
     *  `internal` rather than private so a test can seed dwell state here and
     *  read back whether a session change discarded it — the same seam
     *  [com.jellemax.detour.drive.ConvoyRelay.membershipEpoch] is `internal`
     *  for, and for the same reason: the alternative is bumping the real
     *  `Auth.sessionEpoch`, which means writing `Settings`, which this
     *  module's tests deliberately stay isolated from. */
    @Volatile
    internal var evaluators: Map<String, GeofenceEvaluator> = emptyMap()

    /** The interval [tick] returns when nothing this pass recomputes it —
     *  see [planTick]. `@Volatile` for the same cross-dispatcher reason as
     *  [evaluators]: nothing today ticks from more than one dispatcher, but
     *  nothing here would notice if a future platform did. */
    @Volatile
    private var currentIntervalMs: Long = ACTIVE_INTERVAL_MS

    /** The `Auth.sessionEpoch` this object last saw, `null` until the first
     *  tick — see [sessionChanged]. `@Volatile` for the same reason as
     *  [evaluators], and `internal` for the same reason too: a test sets this
     *  directly to stand in for a sign-out that happened between two ticks. */
    @Volatile
    internal var lastSeenEpoch: Int? = null

    /**
     * One pass: post this device's fix to every circle it's sharing into,
     * and run each through that circle's geofence. Returns the interval the
     * caller should wait before calling [tick] again.
     *
     * Never throws for an ordinary failure — offline, a 5xx, one circle
     * mid-removal — those are swallowed and retried next tick, the same as
     * both platforms' own loops did. `@Throws(Exception::class)` is on the
     * signature anyway: without it, a Kotlin/Native suspend function
     * propagates only `CancellationException` across the Swift boundary,
     * and anything else would terminate the process instead of surfacing as
     * a normal Swift `throws`.
     */
    @Throws(Exception::class)
    suspend fun tick(
        lat: Double,
        lon: Double,
        accuracyM: Double,
        fixTimeMs: Long,
        fixAgeMs: Long,
        nowMs: Long,
    ): Long {
        discardEvaluatorsIfSessionChanged()
        if (!SyncClient.configured() || !Account.signedIn) return currentIntervalMs

        val myId = Account.riderId.value
        val circles = try {
            Groups.list("circle").filter { it.status == "accepted" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // offline or server down; retried next tick, interval unchanged
        }

        val plan = planTick(currentIntervalMs, circles, myId)
        currentIntervalMs = plan.intervalMs

        if (circles != null) {
            // Drop bookkeeping for circles we're no longer in, so rejoining
            // a circle under the same id later doesn't inherit stale dwell
            // state. Only on a successful fetch: a failed one must not
            // touch evaluators any more than it touches the interval.
            evaluators = retainJoinedCircles(evaluators, circles.map { it.id }.toSet())
        }

        for (circle in plan.sharing) {
            try {
                CircleFixes.postFix(circle.id, lat, lon, accuracyM, fixTimeMs)
                // Posted before this check, deliberately: a stale position
                // still updates "last seen" but must not drive a geofence
                // decision below.
                if (!isFixTrusted(fixAgeMs)) continue
                val places = CirclePlaces.places(circle.id)
                val evaluator = evaluators[circle.id] ?: GeofenceEvaluator.withDefaults()
                evaluators = evaluators + (circle.id to evaluator)
                for (t in evaluateGeofences(evaluator, lat, lon, nowMs, places)) {
                    CircleEvents.record(circle.id, t.placeId, t.kind, t.tsMs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One circle failing (removed mid-loop, one bad request)
                // must not stop the others from posting this tick.
            }
        }
        return currentIntervalMs
    }

    // --- decisions, extracted so a test can drive them without a network ------

    /** What one [tick] pass resolves to: the interval to wait before the
     *  next call, and which circles to actually post/evaluate this pass. */
    internal data class TickPlan(val intervalMs: Long, val sharing: List<Group>)

    /**
     * The cadence decision, plus the deliberate asymmetry in how it's
     * reached: [circles] is null exactly when this pass's `Groups.list`
     * call failed, and a failure leaves [previousIntervalMs] **unchanged** —
     * an outage is not evidence that nobody is sharing, so it must not be
     * allowed to relax the cadence the way an honest "nobody's sharing"
     * answer does. Only a successful fetch may switch the interval, in
     * either direction: to [IDLE_INTERVAL_MS] when nobody here is sharing,
     * back to [ACTIVE_INTERVAL_MS] the moment somebody is again.
     */
    internal fun planTick(previousIntervalMs: Long, circles: List<Group>?, myId: RiderId): TickPlan {
        if (circles == null) return TickPlan(previousIntervalMs, emptyList())
        val sharing = sharingCircles(circles, myId)
        val interval = if (sharing.isEmpty()) IDLE_INTERVAL_MS else ACTIVE_INTERVAL_MS
        return TickPlan(interval, sharing)
    }

    /** Circles to actually post/evaluate this pass: [circles] filtered to
     *  this device's *own* member row, with sharing on — not just any
     *  member's, and not merely accepted membership, which [circles] here
     *  already is. */
    internal fun sharingCircles(circles: List<Group>, myId: RiderId): List<Group> =
        circles.filter { c -> c.members.find { it.id == myId }?.sharing == true }

    /** [evaluators] with bookkeeping for anything not in [circleIds]
     *  dropped, so a circle rejoined later under the same id starts with a
     *  fresh [GeofenceEvaluator] rather than inheriting stale dwell/inside
     *  state from before it was left. */
    internal fun retainJoinedCircles(
        evaluators: Map<String, GeofenceEvaluator>,
        circleIds: Set<String>,
    ): Map<String, GeofenceEvaluator> = evaluators.filterKeys { it in circleIds }

    /** Whether [fixAgeMs] is fresh enough to drive a geofence decision — see
     *  [FIX_TRUST_MS]'s doc for why a fix can still be this old and why it's
     *  posted regardless of the answer here. */
    internal fun isFixTrusted(fixAgeMs: Long, trustMs: Long = FIX_TRUST_MS): Boolean =
        fixAgeMs <= trustMs

    /** [tick]'s one call into [GeofenceEvaluator.evaluate], pulled out so a
     *  test can pin that dwell is driven by [nowMs] — wall clock, passed
     *  in — and nothing else: not [fixTimeMs], not an ambient clock this
     *  module doesn't have one of anyway. */
    internal fun evaluateGeofences(
        evaluator: GeofenceEvaluator,
        lat: Double,
        lon: Double,
        nowMs: Long,
        places: List<CirclePlace>,
    ): List<GeofenceTransition> = evaluator.evaluate(lat, lon, nowMs, places)

    /** True exactly when `Auth.sessionEpoch` has moved since the last tick
     *  saw it — never on the very first tick ([previousEpoch] `null`, so a
     *  cold start is never treated as a change from some prior session),
     *  and never a mere reconnect (`previousEpoch == currentEpoch`), only an
     *  actual sign-out/401/server-switch in between. Parameterised, rather
     *  than reading `Auth.sessionEpoch` itself, so this is testable without
     *  the real `Auth`/`Settings` singletons — the same seam
     *  `FriendsState.commitIfCurrent` and `CirclesState.commitIfViewing`
     *  already use in this module. */
    internal fun sessionChanged(previousEpoch: Int?, currentEpoch: Int): Boolean =
        previousEpoch != null && previousEpoch != currentEpoch

    /** The impure half of [sessionChanged]: reads the real `Auth.sessionEpoch`,
     *  clears [evaluators] if it moved, and stamps [lastSeenEpoch] either
     *  way. `internal` rather than private so a test can call it with
     *  [lastSeenEpoch] set by hand — the same shortcut
     *  [com.jellemax.detour.drive.ConvoyRelay.clearMembershipForSessionChange]
     *  exists for, since actually moving `Auth.sessionEpoch` means writing
     *  `Settings`. What that still leaves untested is [tick]'s own call to
     *  this, one line up from a network fetch there is no seam for. */
    internal fun discardEvaluatorsIfSessionChanged() {
        val current = Auth.sessionEpoch.value
        if (sessionChanged(lastSeenEpoch, current)) evaluators = emptyMap()
        lastSeenEpoch = current
    }
}

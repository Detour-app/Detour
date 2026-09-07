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

    /** How close a fix has to be to a circle place before Android registers
     *  an OS geofence for it (issue #91) — a place farther than this stays
     *  on the poll-only path. Provisional like [FIX_TRUST_MS]'s neighbours:
     *  big enough that a fence registers and settles before a motorway-speed
     *  approach crosses it, not yet measured against a real drive. */
    const val PROXIMITY_GATE_RADIUS_M = 5_000.0

    /** Half Android's 100-geofence-per-app ceiling — two fences (enter,
     *  exit) are registered per candidate place. */
    const val MAX_GATE_CANDIDATES = 50

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

        val placesByCircle = mutableListOf<Pair<String, List<CirclePlace>>>()
        for (circle in plan.sharing) {
            try {
                CircleFixes.postFix(circle.id, lat, lon, accuracyM, fixTimeMs)
                // Posted before this check, deliberately: a stale position
                // still updates "last seen" but must not drive a geofence
                // decision below.
                if (!isFixTrusted(fixAgeMs)) continue
                val places = CirclePlaces.places(circle.id)
                placesByCircle += circle.id to places
                // Before detecting anything new: does the durable memory still
                // match where the rider actually is? A process death between
                // two ticks can leave a claim standing for a place they have
                // since left, and the gate in `CircleEvents.record` would then
                // swallow their next real arrival (#273). Announced, not just
                // cleared, so the circle stops showing them parked there — the
                // timestamp is `nowMs` because when they left was never
                // observed.
                val drift = reconcilePlaceMemory(
                    Settings.confirmedInsidePlaceIds(), circle.id, places, lat, lon, nowMs,
                )
                for (t in drift.missedDepartures) {
                    CircleEvents.record(circle.id, t.placeId, t.kind, t.tsMs)
                }
                if (drift.staleKeys.isNotEmpty()) {
                    // Only reachable on a successful `places` fetch, which is
                    // the same "an outage proves nothing" rule the gate
                    // candidates below follow: a failed fetch must not be read
                    // as "the place is gone".
                    Settings.setConfirmedInsidePlaceIds(
                        Settings.confirmedInsidePlaceIds() - drift.staleKeys,
                    )
                }
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
        if (shouldUpdateGateCandidates(circles != null, placesByCircle, plan.sharing)) {
            lastGateCandidates = nearbyPlaces(lat, lon, placesByCircle)
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

    /**
     * Whether this pass learned enough about the world to overwrite
     * [lastGateCandidates] — the same "an outage proves nothing" rule
     * [planTick] applies to the interval, extended past the fetch itself.
     *
     * A fetch can succeed and still yield no places for circles we *are*
     * sharing into: every fix untrusted (a parked phone — exactly when the
     * fences matter most) or every per-circle call failing. Writing an empty
     * list then deregisters the fences of a rider who hasn't moved. Genuinely
     * sharing with nobody ([sharing] empty) is the honest empty answer and
     * does write through.
     */
    internal fun shouldUpdateGateCandidates(
        circlesFetched: Boolean,
        placesByCircle: List<Pair<String, List<CirclePlace>>>,
        sharing: List<Group>,
    ): Boolean = circlesFetched && (placesByCircle.isNotEmpty() || sharing.isEmpty())

    /** One place close enough to this fix that Android should register an
     *  OS geofence for it (issue #91) — [distanceM] is carried along so
     *  [nearbyPlaces] can sort nearest-first before truncating to
     *  [MAX_GATE_CANDIDATES]. */
    data class GateCandidate(
        val circleId: String,
        val placeId: Long,
        val lat: Double,
        val lon: Double,
        val radiusM: Double,
        val distanceM: Double,
    )

    /** The proximity-gate decision: every place across [placesByCircle]
     *  within [gateRadiusM] of this fix, nearest first, capped at
     *  [MAX_GATE_CANDIDATES]. Pure arithmetic on data [tick] already has —
     *  no network call of its own, same reasoning as [evaluateGeofences]. */
    internal fun nearbyPlaces(
        lat: Double,
        lon: Double,
        placesByCircle: List<Pair<String, List<CirclePlace>>>,
        gateRadiusM: Double = PROXIMITY_GATE_RADIUS_M,
    ): List<GateCandidate> {
        val here = LatLon(lat, lon)
        return placesByCircle.flatMap { (circleId, places) ->
            places.mapNotNull { p ->
                val d = RoadRoulette.distanceMeters(here, p.place.location)
                if (d <= gateRadiusM) {
                    GateCandidate(circleId, p.place.id, p.place.location.lat, p.place.location.lon, p.radiusM, d)
                } else null
            }
        }.sortedBy { it.distanceM }.take(MAX_GATE_CANDIDATES)
    }

    /** [nearbyPlaces]' result from the most recent successful [tick] pass —
     *  Android reads this right after calling [tick] and feeds it to
     *  `PlaceGeofenceGate.sync`. `@Volatile` and public-read for the same
     *  cross-module reason [evaluators] is internal-for-tests: this one is
     *  read from `app/`, a different Gradle module, so it can't be
     *  `internal`. Left unchanged on a failed circle fetch, same as
     *  [currentIntervalMs] — an outage is not evidence nobody is nearby a
     *  place any more. */
    @Volatile
    var lastGateCandidates: List<GateCandidate> = emptyList()
        internal set
}

/** What one circle's confirmed-inside memory got wrong, per
 *  [reconcilePlaceMemory]. */
internal data class PlaceMemoryDrift(
    val missedDepartures: List<GeofenceTransition>,
    val staleKeys: Set<String>,
)

/**
 * Checks this circle's confirmed-inside claims against where the rider
 * actually is.
 *
 * The memory [CircleEvents.record] keeps is durable, which is what lets it
 * survive the process death that #273's cold-start symptom comes from — and
 * also what lets it drift: force-stopped for hours, the rider leaves, and no
 * depart is ever announced. The gate would then swallow their next real
 * arrival and the circle would show them at that place forever, which is a
 * worse failure than the duplicate the gate removes. So every tick asks the
 * geometry.
 *
 * Geometry, not the evaluator: after that force-stop the evaluator's
 * `inside` flag is false and has nothing to say. The threshold is the exit
 * ring — `radiusM * EXIT_HYSTERESIS_FACTOR` — so this agrees with what a
 * depart means everywhere else rather than inventing a second radius.
 *
 * A claim whose place is gone cannot be checked at all, so it is reported
 * separately: [staleKeys] are dropped without announcing anything, because
 * nobody can see a place that no longer exists.
 */
internal fun reconcilePlaceMemory(
    confirmed: Set<String>,
    circleId: String,
    places: List<CirclePlace>,
    lat: Double,
    lon: Double,
    nowMs: Long,
): PlaceMemoryDrift {
    val here = LatLon(lat, lon)
    val prefix = "$circleId:"
    val missed = mutableListOf<GeofenceTransition>()
    val stale = mutableSetOf<String>()
    for (key in confirmed) {
        if (!key.startsWith(prefix)) continue
        val placeId = key.removePrefix(prefix).toLongOrNull() ?: continue
        val place = places.firstOrNull { it.place.id == placeId }
        if (place == null) {
            stale += key
            continue
        }
        val exitRing = place.radiusM * GeofenceEvaluator.EXIT_HYSTERESIS_FACTOR
        if (RoadRoulette.distanceMeters(here, place.place.location) > exitRing) {
            missed += GeofenceTransition(placeId, GeofenceKind.DEPART, nowMs)
        }
    }
    return PlaceMemoryDrift(missed, stale)
}

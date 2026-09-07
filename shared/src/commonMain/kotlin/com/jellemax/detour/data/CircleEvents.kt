package com.jellemax.detour.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One arrive/depart record, as `GET /circles/{id}/events` returns it —
 *  includes the caller's own arrivals, not just other members' (the design
 *  doc makes that a requirement). [placeName] is
 *  looked up server-side from `circle_places` at read time, not stored with
 *  the event itself — it can be "" if the place was since unshared. */
data class PlaceEvent(
    /** The server's identifier for the stored event, blank for one that arrived
     *  over the live relay — a live frame addresses nothing. */
    val id: String,
    val placeId: Long,
    val placeName: String,
    val riderId: RiderId,
    val kind: String,
    val tsMs: Long,
)

/** [PlaceEvent] plus the groupId a live relay frame carries. A PlaceEvent
 *  alone doesn't know which circle it came from — the HTTP list already
 *  lives under a groupId the caller supplied, but a `place_event` frame can
 *  arrive for any circle the socket has joined, so the parser needs
 *  somewhere to put it. */
data class RelayPlaceEvent(val groupId: String, val event: PlaceEvent)

/**
 * Records and reads circle arrival/departure events. Geofencing itself runs
 * on-device (see [GeofenceEvaluator] below) — this is only the fan-out: the
 * server stores what a transition already decided and relays it to the rest
 * of the circle, over the live relay when it can and over HTTP catch-up
 * otherwise. No push; phase 7 of the design doc (FCM/APNs) is blocked on
 * an Apple Developer account this project doesn't have.
 */
object CircleEvents {

    // @Throws(Exception::class) on [record] and [events] below, both called
    // directly from iosApp/Detour: see the doc on [SyncClient.sync] for why
    // `Exception` and not just `IOException`.
    /**
     * Announces one transition — if it is the first announcement of it.
     *
     * The gate is here rather than at either caller because this is the one
     * funnel both pass through: the OS fence (`PlaceGeofenceReceiver`) and the
     * poll tick (`CirclePresence.tick`) are both armed for the same place with
     * the same dwell and hysteresis, and their detection state is disjoint, so
     * neither can suppress the other. That is #273, and one gate at the funnel
     * is what fixes it — including the cold-start case, where the evaluator's
     * in-memory `inside` flag is gone but [Settings.confirmedInsidePlaceIds]
     * is not.
     *
     * Returns true when it posted. The memory advances **only after the POST
     * lands**: an arrive that failed to record must not arm a depart nobody
     * ever saw the arrive for.
     *
     * That covers a failed POST, not a process death between the POST landing
     * and [Settings.setConfirmedInsidePlaceIds] finishing: the two writes
     * aren't atomic, so a kill in that gap leaves the server holding the
     * arrival while this memory still doesn't. The next real depart is then
     * wrongly suppressed as having no arrive behind it — self-healing on the
     * next real arrive, which is the same shape of gap [decidePlaceEvent]
     * already tolerates.
     *
     * A second trigger reaches that identical gap with no process death at
     * all: `GeofenceEvaluator.evaluate` flips its in-memory `PlaceState.inside`
     * to `true` the moment dwell elapses, *before* `CirclePresence.tick` ever
     * calls this function — so an ordinary failed POST here (offline, a 5xx,
     * a timeout) leaves this memory without the key while the evaluator
     * already believes it arrived. The evaluator can then never re-enter its
     * arrive branch for that place; it only reaches depart next, which lands
     * here with nothing to depart from and is suppressed the same way.
     * [reconcilePlaceMemory] cannot rescue it either — it only walks keys
     * already in the confirmed set, and this one never joined it. That single
     * visit self-heals only after a full leave-and-return cycle.
     *
     * Serialised by [gate], so a fence delivery and a tick cannot both pass
     * the check before either writes. They are separate coroutines in one
     * process — the receiver declares no `android:process` — so the race is
     * narrow and real. It holds across the POST, which serialises event posts;
     * at arrival frequency that costs nothing.
     */
    @Throws(Exception::class)
    suspend fun record(groupId: String, placeId: Long, kind: GeofenceKind, tsMs: Long): Boolean =
        gate.withLock {
            val confirmed = Settings.confirmedInsidePlaceIds()
            when (val decision = decidePlaceEvent(confirmed, groupId, placeId, kind)) {
                is PlaceEventDecision.Suppress -> {
                    Settings.recordSuppressedPlaceEvent(
                        SuppressedPlaceEvent(groupId, placeId, kind, tsMs, decision.reason),
                    )
                    false
                }
                PlaceEventDecision.Post -> {
                    Api.request(
                        "POST", "/circles/$groupId/events",
                        buildJsonObject {
                            put("placeId", placeId)
                            put("kind", if (kind == GeofenceKind.ARRIVE) "arrive" else "depart")
                            put("timestampMs", tsMs)
                        },
                    )
                    val key = placeEventKey(groupId, placeId)
                    Settings.setConfirmedInsidePlaceIds(
                        if (kind == GeofenceKind.ARRIVE) confirmed + key else confirmed - key,
                    )
                    true
                }
            }
        }

    /** Serialises [record]; see its doc for the race this closes. */
    private val gate = Mutex()

    /**
     * Drops [keys] from [Settings.confirmedInsidePlaceIds], under the same
     * [gate] that guards [record].
     *
     * This exists for `CirclePresence.tick`'s reconciliation, which prunes
     * keys whose place has vanished from the circle (a stale claim nobody
     * can check against geometry any more). That prune is a
     * read-modify-write over the same set [record] reads and writes, and a
     * fence delivery or a poll tick both call [record] from their own
     * coroutine — the receiver declares no `android:process`, so they are
     * genuinely concurrent. A prune done as a bare read-modify-write outside
     * [gate] could land between another call's read and its write: it would
     * then overwrite that call's update with a copy of the set from before
     * it ran, silently un-confirming an arrival the server already has on
     * file. The next real departure for that place would wrongly suppress
     * as [SuppressionReason.NO_ARRIVE_TO_DEPART_FROM] — the exact lost
     * update #273's gate was built to close, reopened through a second
     * writer. Routing the prune through [gate] instead closes it the same
     * way [record] already does.
     */
    suspend fun forgetConfirmedInside(keys: Set<String>) {
        gate.withLock {
            Settings.setConfirmedInsidePlaceIds(Settings.confirmedInsidePlaceIds() - keys)
        }
    }

    /** Events newer than [sinceMs] — pass the last-seen event's [PlaceEvent.tsMs]
     *  to poll incrementally. */
    @Throws(Exception::class)
    suspend fun events(groupId: String, sinceMs: Long): List<PlaceEvent> {
        val o = Api.requestJson("GET", "/circles/$groupId/events?since=$sinceMs")
        return o.optArray("events")?.objects().orEmpty().map { placeEventFromJson(it) }
    }

    /** The last event a client has already turned into a notification for
     *  [circleId] — call [events] with this as `sinceMs` after a cold start
     *  or reconnect so nothing already shown gets shown twice, and advance
     *  it with [setLastSeenEventTsMs] once the catch-up is handled. Backed
     *  by [Settings], not a new store — see there for why it's keyed
     *  dynamically instead of a StateFlow. */
    fun lastSeenEventTsMs(circleId: String): Long = Settings.lastSeenEventTsMs(circleId)

    fun setLastSeenEventTsMs(circleId: String, tsMs: Long) = Settings.setLastSeenEventTsMs(circleId, tsMs)
}

/** Extracted from [CircleEvents.events] so JSON parsing is testable without
 *  a network round trip. */
internal fun placeEventFromJson(e: JsonObject): PlaceEvent = PlaceEvent(
    id = e.optString("id"),
    placeId = e.optLong("placeId"),
    placeName = e.optString("placeName"),
    riderId = RiderId(e.optString("riderId")),
    kind = e.optString("kind"),
    tsMs = e.optLong("timestampMs"),
)

/** Parses a `{"type": "place_event", ...}` live relay frame (see the "group
 *  live relay" protocol in docs/CIRCLES_AND_CONVOYS.md) into a [RelayPlaceEvent],
 *  or null when it isn't one — wrong `type`, or a required field missing or
 *  not the type it claims to be. The relay frame carries no `id` (nothing
 *  server-side needs to address one live frame individually the way a
 *  stored row does), so [PlaceEvent.id] is always blank here.
 *
 *  `groupId` is read as text, which accepts both the identifier the API uses
 *  and the integer the legacy relay sends — the live surface is the one part of
 *  the backend not rebuilt yet (see the note in the API's Startup). */
fun placeEventFromRelayFrame(o: JsonObject): RelayPlaceEvent? {
    if (o.optString("type") != "place_event") return null
    val groupId = o.optString("groupId").takeIf { it.isNotEmpty() } ?: return null
    val placeId = (o["placeId"] as? JsonPrimitive)?.longOrNull ?: return null
    val tsMs = (o["ts"] as? JsonPrimitive)?.longOrNull ?: return null
    val riderId = o.optString("user").takeIf { it.isNotEmpty() } ?: return null
    // Lowercased to match the feed's wire vocabulary (docs/CIRCLES_AND_CONVOYS.md
    // §6.3) — an older relay push sent the enum's "Arrive"/"Depart" here.
    val kind = o.optString("kind").lowercase()
    if (kind != "arrive" && kind != "depart") return null
    return RelayPlaceEvent(
        groupId = groupId,
        event = PlaceEvent(
            id = "",
            placeId = placeId,
            placeName = o.optString("placeName"),
            riderId = RiderId(riderId),
            kind = kind,
            tsMs = tsMs,
        ),
    )
}

/** The one wording both Android and iOS use for a place_event notification,
 *  whether it arrived live over the relay or was caught up over HTTP after
 *  being offline — putting it here is the point, so the two apps can never
 *  read the same event differently. Drops "at <place>" rather than
 *  fabricating a name when [PlaceEvent.placeName] is blank (the place was
 *  unshared since the transition happened).
 *
 *  [displayName] is a parameter rather than a field on [PlaceEvent] because
 *  the event itself only names a rider by [PlaceEvent.riderId] now — the
 *  handle to draw is membership data, and the caller already holds it from
 *  the group's member list. */
fun PlaceEvent.notificationText(displayName: String): String {
    val arrived = kind == "arrive"
    if (placeName.isBlank()) return "$displayName " + (if (arrived) "arrived" else "left")
    // "arrived at School" reads naturally; "left at School" doesn't - "left"
    // takes its object directly, unlike "arrived".
    return if (arrived) "$displayName arrived at $placeName" else "$displayName left $placeName"
}

/** Wording for the one notification that stands in for everything a
 *  catch-up sweep capped away. Here rather than in either app for the same
 *  reason as [notificationText]: it is text a user reads, and the two
 *  platforms raising it must not word it differently. */
fun catchUpSummaryText(collapsed: Int): String =
    "+$collapsed more update" + (if (collapsed == 1) "" else "s")

enum class GeofenceKind { ARRIVE, DEPART }

/** One transition [GeofenceEvaluator] just decided. */
data class GeofenceTransition(val placeId: Long, val kind: GeofenceKind, val tsMs: Long)

/** Why an announcement was dropped, for the durable record in [Settings.suppressedPlaceEvents]. */
enum class SuppressionReason { DUPLICATE, NO_ARRIVE_TO_DEPART_FROM }

/** What [decidePlaceEvent] concluded. */
internal sealed interface PlaceEventDecision {
    data object Post : PlaceEventDecision
    data class Suppress(val reason: SuppressionReason) : PlaceEventDecision
}

/** The key [Settings.confirmedInsidePlaceIds] stores, `"$circleId:$placeId"`.
 *  Comma-free by construction — circle ids are server UUIDs — which is what
 *  lets it share [encodePlaceFenceIds]. */
internal fun placeEventKey(circleId: String, placeId: Long): String = "$circleId:$placeId"

/**
 * Whether this transition is the *first* announcement of a real change, given
 * what was already announced for that place.
 *
 * Arrive and depart must alternate: a second arrive with no depart between it
 * and the first is a duplicate whichever detector produced it and however far
 * apart, and a depart with no arrive to depart from never happened as far as
 * this circle is concerned. That is the whole of #273 — two detectors and a
 * cold start were three ways of announcing the same arrival twice, and none of
 * them could see what the other had done.
 *
 * Pure, and over the set rather than over [Settings], because `shared` tests
 * have no `Prefs` backend to init.
 */
internal fun decidePlaceEvent(
    confirmed: Set<String>,
    circleId: String,
    placeId: Long,
    kind: GeofenceKind,
): PlaceEventDecision {
    val inside = placeEventKey(circleId, placeId) in confirmed
    return when {
        kind == GeofenceKind.ARRIVE && inside ->
            PlaceEventDecision.Suppress(SuppressionReason.DUPLICATE)
        kind == GeofenceKind.DEPART && !inside ->
            PlaceEventDecision.Suppress(SuppressionReason.NO_ARRIVE_TO_DEPART_FROM)
        else -> PlaceEventDecision.Post
    }
}

/**
 * Evaluates arrive/depart transitions from a stream of fixes, entirely
 * on-device (docs/CIRCLES_AND_CONVOYS.md section 8) — settling the design
 * doc's open geofencing question this way keeps the position stream that
 * drives it off the server, which is both the cheaper and the more
 * consistent choice.
 *
 * Two guards against a plain radius check flapping right at the boundary:
 * - **Hysteresis.** A member counts as having left only past
 *   `radiusM * exitHysteresisFactor`, not the entry radius itself, so a few
 *   metres of GPS jitter either side of the line can't toggle the state.
 * - **Dwell.** A member has to stay inside the entry radius for
 *   [minDwellMs] before "arrive" fires, so driving past a place's edge
 *   without stopping doesn't count as a visit.
 *
 * The constants below are provisional — the design doc explicitly defers
 * picking real dwell/hysteresis numbers to a GPS trace rather than
 * intuition (section 13). They're a reasonable starting point, not a
 * measured value.
 *
 * Keep one instance per circle (or per active place set) for the life of a
 * geofencing session: it holds per-place dwell/inside state between calls,
 * which is why this is a class and not a free function, and calls must
 * arrive in chronological [tsMs] order for that state to mean anything.
 */
class GeofenceEvaluator(
    private val exitHysteresisFactor: Double = EXIT_HYSTERESIS_FACTOR,
    private val minDwellMs: Long = MIN_DWELL_MS,
) {
    companion object {
        /** The two provisional guard constants, named rather than left as
         *  inline literals so Android's OS-geofence fences (#91) can build
         *  the same hysteresis/dwell values into `GeofencingRequest` instead
         *  of restating them — see `PlaceGeofenceGate`. */
        const val EXIT_HYSTERESIS_FACTOR = 1.3
        const val MIN_DWELL_MS = 60_000L

        /** What Swift constructs. Kotlin/Native's Objective-C export drops
         *  default argument values, so `GeofenceEvaluator()` has nothing to
         *  bind to on that side — without this, iOS would have to restate
         *  the two provisional constants above and they'd drift apart. */
        fun withDefaults() = GeofenceEvaluator()
    }

    private class PlaceState {
        var inside = false
        var candidateSinceMs: Long? = null
    }

    private val state = mutableMapOf<Long, PlaceState>()

    /** Feeds one fix against the circle's current places, returning
     *  whatever transitions it just produced (usually none). */
    fun evaluate(lat: Double, lon: Double, tsMs: Long, places: List<CirclePlace>): List<GeofenceTransition> {
        // Drop bookkeeping for places no longer shared into the circle, so a
        // removed-then-re-added place under the same id can't inherit a
        // stale "inside" flag from before it was removed.
        state.keys.retainAll(places.map { it.place.id }.toSet())

        val transitions = mutableListOf<GeofenceTransition>()
        val here = LatLon(lat, lon)
        for (p in places) {
            val distanceM = RoadRoulette.distanceMeters(here, p.place.location)
            val st = state.getOrPut(p.place.id) { PlaceState() }
            if (st.inside) {
                if (distanceM > p.radiusM * exitHysteresisFactor) {
                    st.inside = false
                    st.candidateSinceMs = null
                    transitions += GeofenceTransition(p.place.id, GeofenceKind.DEPART, tsMs)
                }
            } else if (distanceM <= p.radiusM) {
                val since = st.candidateSinceMs ?: tsMs.also { st.candidateSinceMs = it }
                if (tsMs - since >= minDwellMs) {
                    st.inside = true
                    st.candidateSinceMs = null
                    transitions += GeofenceTransition(p.place.id, GeofenceKind.ARRIVE, tsMs)
                }
            } else {
                st.candidateSinceMs = null
            }
        }
        return transitions
    }
}

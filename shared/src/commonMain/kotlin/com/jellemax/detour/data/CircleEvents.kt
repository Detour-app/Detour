package com.jellemax.detour.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One arrive/depart record, as `GET /circles/{id}/events` returns it —
 *  includes the caller's own arrivals, not just other members' (the design
 *  doc makes that a requirement, see `do_circle_events`). [placeName] is
 *  looked up server-side from `circle_places` at read time, not stored with
 *  the event itself — it can be "" if the place was since unshared. */
data class PlaceEvent(
    /** The server's identifier for the stored event, blank for one that arrived
     *  over the live relay — a live frame addresses nothing. */
    val id: String,
    val placeId: Long,
    val placeName: String,
    val username: String,
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

    suspend fun record(groupId: String, placeId: Long, kind: GeofenceKind, tsMs: Long) {
        Api.request(
            "POST", "/circles/$groupId/events",
            buildJsonObject {
                put("placeId", placeId)
                put("kind", if (kind == GeofenceKind.ARRIVE) "arrive" else "depart")
                put("timestampMs", tsMs)
            },
        )
    }

    /** Events newer than [sinceMs] — pass the last-seen event's [PlaceEvent.tsMs]
     *  to poll incrementally. */
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
    username = e.optString("username"),
    kind = e.optString("kind"),
    tsMs = e.optLong("timestampMs"),
)

/** Parses a `{"type": "place_event", ...}` live relay frame (see the "group
 *  live relay" protocol comment in sync_server.py) into a [RelayPlaceEvent],
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
    val tsMs = (o["tsMs"] as? JsonPrimitive)?.longOrNull ?: return null
    val username = o.optString("user").takeIf { it.isNotEmpty() } ?: return null
    val kind = o.optString("kind")
    if (kind != "arrive" && kind != "depart") return null
    return RelayPlaceEvent(
        groupId = groupId,
        event = PlaceEvent(
            id = "",
            placeId = placeId,
            placeName = o.optString("placeName"),
            username = username,
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
 *  unshared since the transition happened). */
fun PlaceEvent.notificationText(): String {
    val arrived = kind == "arrive"
    if (placeName.isBlank()) return "$username " + (if (arrived) "arrived" else "left")
    // "arrived at School" reads naturally; "left at School" doesn't - "left"
    // takes its object directly, unlike "arrived".
    return if (arrived) "$username arrived at $placeName" else "$username left $placeName"
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
    private val exitHysteresisFactor: Double = 1.3,
    private val minDwellMs: Long = 60_000L,
) {
    companion object {
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

package com.jellemax.detour.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One arrive/depart record, as `GET /circles/{id}/events` returns it —
 *  includes the caller's own arrivals, not just other members' (the design
 *  doc makes that a requirement, see `do_circle_events`). */
data class PlaceEvent(
    val id: Long,
    val placeId: Long,
    val username: String,
    val kind: String,
    val tsMs: Long,
)

/**
 * Records and reads circle arrival/departure events. Geofencing itself runs
 * on-device (see [GeofenceEvaluator] below) — this is only the fan-out: the
 * server stores what a transition already decided and relays it to the rest
 * of the circle. No push; phase 7 of the design doc (FCM/APNs) is blocked on
 * an Apple Developer account this project doesn't have.
 */
object CircleEvents {

    suspend fun record(groupId: Int, placeId: Long, kind: GeofenceKind, tsMs: Long) {
        Api.request(
            "POST", "/circles/$groupId/events",
            buildJsonObject {
                put("placeId", placeId)
                put("kind", if (kind == GeofenceKind.ARRIVE) "arrive" else "depart")
                put("ts", tsMs)
            },
        )
    }

    /** Events newer than [sinceMs] — pass the last-seen event's [PlaceEvent.tsMs]
     *  to poll incrementally. */
    suspend fun events(groupId: Int, sinceMs: Long): List<PlaceEvent> {
        val o = Api.requestJson("GET", "/circles/$groupId/events?since=$sinceMs")
        return o.optArray("events")?.objects().orEmpty().map { placeEventFromJson(it) }
    }
}

/** Extracted from [CircleEvents.events] so JSON parsing is testable without
 *  a network round trip. */
internal fun placeEventFromJson(e: JsonObject): PlaceEvent = PlaceEvent(
    id = e.optLong("id"),
    placeId = e.optLong("placeId"),
    username = e.optString("username"),
    kind = e.optString("kind"),
    tsMs = e.optLong("tsMs"),
)

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

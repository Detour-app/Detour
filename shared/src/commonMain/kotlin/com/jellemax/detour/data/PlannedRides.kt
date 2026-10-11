package com.jellemax.detour.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * An invitee's answer to a planned ride. [PENDING] is "asked, not answered yet" — the state
 * every invitation starts in, and not something a rider can send back
 * ([isAnswer] is false). See BACKEND_SPEC.md §8.1.
 */
enum class RsvpAnswer(val wire: String) {
    PENDING("pending"),
    GOING("going"),
    MAYBE("maybe"),
    DECLINED("declined"),
    ;

    /** True for the three answers a rider may send; false for [PENDING]. */
    val isAnswer: Boolean get() = this != PENDING

    companion object {
        /** Null for anything the server did not document, rather than a guess. */
        fun fromWire(value: String): RsvpAnswer? = entries.firstOrNull { it.wire == value }
    }
}

data class MeetingPoint(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null,
)

data class PlannedRideInvitee(
    val rider: RiderRef,
    val answer: RsvpAnswer,
)

/**
 * A ride planned ahead, as `GET /api/planned-rides` lists it.
 *
 * [routeId] is the owner's own saved-route id: meaningful on the owner's device only, opaque to
 * everyone else (an invitee gets the route through a share, not through this field).
 * [myAnswer] is the caller's own answer, and null when the caller is the owner — the owner is
 * not invited to their own ride and is not in [invitees].
 */
data class PlannedRide(
    val id: String,
    val owner: RiderRef,
    val title: String,
    val startsAtMs: Long,
    val meetingPoint: MeetingPoint,
    val routeId: Long?,
    val description: String?,
    val createdAtMs: Long,
    val myAnswer: RsvpAnswer?,
    val invitees: List<PlannedRideInvitee>,
) {
    /**
     * Whether [me] may start this ride now: the owner, at any time. There is deliberately no
     * window around [startsAtMs] (owner decision on #639/#640) — the start time is what the
     * invitees are told, not a gate. A blank [me] (`/me` has not answered yet, see
     * [Account.riderId]) is nobody, so a blank owner id cannot match it.
     */
    fun canStart(me: RiderId): Boolean = me.value.isNotBlank() && owner.id == me

    /** Whether [me] holds an invitation to answer. The owner never does. */
    fun canAnswer(me: RiderId): Boolean = !canStart(me) && myAnswer != null
}

/** What the owner sets on create and on every edit: an edit replaces every field. */
data class PlannedRideDraft(
    val title: String,
    val startsAtMs: Long,
    val meetingPoint: MeetingPoint,
    val routeId: Long? = null,
    val description: String? = null,
)

/**
 * Client for the planned-rides endpoints (BACKEND_SPEC.md §8.1). Wire calls only; the state a
 * screen binds to is [PlannedRidesStore].
 */
object PlannedRides {

    // @Throws(Exception::class) on every suspend fun below, for the iOS app — see the doc on
    // [SyncClient.sync] for why `Exception` and not just `IOException`.

    @Throws(Exception::class)
    suspend fun list(): List<PlannedRide> = plannedRidesFromJson(Api.requestJson("GET", "/planned-rides"))

    @Throws(Exception::class)
    suspend fun get(id: String): PlannedRide =
        plannedRideFromJson(Api.requestJson("GET", "/planned-rides/$id"))

    @Throws(Exception::class)
    suspend fun create(draft: PlannedRideDraft): PlannedRide =
        plannedRideFromJson(Api.requestJson("POST", "/planned-rides", draft.toJson()))

    /** Owner only; the server answers 403 to an invitee. */
    @Throws(Exception::class)
    suspend fun update(id: String, draft: PlannedRideDraft): PlannedRide =
        plannedRideFromJson(Api.requestJson("PUT", "/planned-rides/$id", draft.toJson()))

    /** Owner only. Removes the ride and every invitation and answer with it. */
    @Throws(Exception::class)
    suspend fun delete(id: String) {
        Api.request("DELETE", "/planned-rides/$id")
    }

    /**
     * Owner only. One rider who may not be invited refuses the whole request (400), so a
     * failure here means nobody new was asked.
     */
    @Throws(Exception::class)
    suspend fun invite(id: String, riderIds: List<RiderId>, circleIds: List<String>): PlannedRide =
        plannedRideFromJson(
            Api.requestJson("POST", "/planned-rides/$id/invitations", inviteBody(riderIds, circleIds))
        )

    /** Returns the answer the server recorded. [answer] must be one a rider may send
     *  ([RsvpAnswer.isAnswer]); the server refuses [RsvpAnswer.PENDING] and the owner. */
    @Throws(Exception::class)
    suspend fun answer(id: String, answer: RsvpAnswer): RsvpAnswer {
        val o = Api.requestJson("PUT", "/planned-rides/$id/rsvp", buildJsonObject { put("answer", answer.wire) })
        return RsvpAnswer.fromWire(o.optString("answer")) ?: answer
    }
}

/** Extracted from [PlannedRides.list] so parsing is testable without a network round trip. */
internal fun plannedRidesFromJson(o: JsonObject): List<PlannedRide> =
    o.optArray("rides")?.objects().orEmpty().map(::plannedRideFromJson)

internal fun plannedRideFromJson(o: JsonObject): PlannedRide {
    val meeting = o.optObject("meetingPoint") ?: JsonObject(emptyMap())
    return PlannedRide(
        id = o.optString("id"),
        owner = riderRefFromJson(o.optObject("owner") ?: JsonObject(emptyMap())),
        title = o.optString("title"),
        startsAtMs = o.optLong("startsAtMs"),
        meetingPoint = MeetingPoint(
            latitude = meeting.optDouble("latitude"),
            longitude = meeting.optDouble("longitude"),
            label = meeting.optString("label").ifEmpty { null },
        ),
        routeId = if (o.has("routeId")) o.optLong("routeId") else null,
        description = o.optString("description").ifEmpty { null },
        createdAtMs = o.optLong("createdAtMs"),
        myAnswer = RsvpAnswer.fromWire(o.optString("myAnswer")),
        // An invitee with an answer this client doesn't know is dropped rather than shown as
        // pending: a wrong "hasn't answered" is worse than a missing row.
        invitees = o.optArray("invitees")?.objects().orEmpty().mapNotNull { i ->
            val answer = RsvpAnswer.fromWire(i.optString("answer")) ?: return@mapNotNull null
            PlannedRideInvitee(riderRefFromJson(i.optObject("rider") ?: return@mapNotNull null), answer)
        },
    )
}

internal fun PlannedRideDraft.toJson(): JsonObject = buildJsonObject {
    put("title", title)
    put("startsAtMs", startsAtMs)
    putJsonObject("meetingPoint") {
        put("latitude", meetingPoint.latitude)
        put("longitude", meetingPoint.longitude)
        meetingPoint.label?.let { put("label", it) }
    }
    routeId?.let { put("routeId", it) }
    description?.let { put("description", it) }
}

internal fun inviteBody(riderIds: List<RiderId>, circleIds: List<String>): JsonObject = buildJsonObject {
    putJsonArray("riderIds") { riderIds.forEach { add(it.value) } }
    putJsonArray("circleIds") { circleIds.forEach { add(it) } }
}

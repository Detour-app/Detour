package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RelayPlaceEvent
import com.jellemax.detour.data.jsonObjectOf
import com.jellemax.detour.data.objects
import com.jellemax.detour.data.optArray
import com.jellemax.detour.data.optDouble
import com.jellemax.detour.data.optInt
import com.jellemax.detour.data.optLong
import com.jellemax.detour.data.optString
import com.jellemax.detour.data.placeEventFromRelayFrame
import com.jellemax.detour.data.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * One convoy rider or circle member's last-known fix, as the relay reports it
 * inside a `positions` frame.
 *
 * [expiresAtMs] is per peer rather than one client-wide constant, because a
 * convoy rider and a circle member now arrive on the same socket at wildly
 * different cadences - seconds against minutes. A single staleness window for
 * both either flickers circle members off the map between their updates or
 * leaves a dropped convoy rider frozen on it. The relay knows which tier a
 * sender is on, so it says (`ttl`), and [RelayProtocol.decode] turns that into
 * this absolute deadline - see its own doc for why that has to happen on
 * arrival rather than off the frame's own timestamp.
 */
data class FriendPosition(
    val username: String,
    val lat: Double,
    val lon: Double,
    val headingDeg: Double?,
    val speedKmh: Double?,
    val tsMs: Long,
    val expiresAtMs: Long,
)

/** One decoded `ptt_audio` frame: [username]'s raw PCM chunk, already
 *  base64-decoded off the wire. */
data class IncomingAudioChunk(val username: String, val pcm: ByteArray)

/** One `spin_offer` candidate, wire shape - see the relay protocol comment
 *  near `_valid_spin_offer` server-side. [distanceM]/[durationS] are whatever
 *  the sharer's own spin already knew; a member receiving them has no route
 *  of its own for these until it commits and asks for one. */
data class SpinCandidate(
    val lat: Double,
    val lon: Double,
    val distanceM: Double?,
    val durationS: Double?,
    val name: String?,
)

/**
 * A convoy's shared spin, either just sent by this device or just received
 * from a peer's.
 *
 * A **one-candidate** offer is not a sheet to vote on, it's the sharer
 * announcing the winner: every device that sees one commits it. That's the
 * whole reason [fromMe] exists - only the device that opened the round
 * decides when it's over and sends that closing offer, so a member whose
 * view of who's still live differs (a peer gone quiet for 20s is pruned from
 * a peer map on one phone and not another) can't resolve the same votes into
 * a different destination. Everyone commits off one frame instead of each
 * tallying their own answer.
 *
 * [fromMe] is never derived from the wire - a `spin_offer` frame itself
 * carries no such flag - so [RelayProtocol.decode] never produces one of
 * these directly; it's for the caller that already knows which side of a
 * round it's on to assemble.
 */
data class GroupSpin(val candidates: List<SpinCandidate>, val fromMe: Boolean)

/**
 * One inbound convoy-relay frame, decoded. Every frame the relay can send
 * that either app acts on has a subtype here; anything else - an `error`
 * frame, a future frame type an older client has never heard of - decodes to
 * `null` from [RelayProtocol.decode] rather than one of these, so a relay
 * newer than the client can't crash it.
 */
sealed class RelayEvent {
    /** The relay accepted this socket's most recent `join`. */
    data object Joined : RelayEvent()

    /** [username] left the group (or was evicted, or its socket dropped) -
     *  gone now, not in [RelayProtocol.FALLBACK_PEER_TTL_MS] when a staleness
     *  sweep would otherwise have caught up. */
    data class Left(val username: String) : RelayEvent()

    /** Every peer the relay had queued for this socket, batched into one
     *  frame rather than one frame per peer: at eight riders that's one
     *  packet a round instead of seven, and the packet count is what a
     *  phone's radio actually pays for. A peer entry with a blank `u`, a NaN
     *  `lat` or `lon` is dropped from [peers] while the rest of the batch is
     *  kept - one bad row must not cost the whole update. */
    data class Positions(val peers: List<FriendPosition>) : RelayEvent()

    /** [username] started transmitting push-to-talk audio. */
    data class PttStart(val username: String) : RelayEvent()

    /** [username] stopped transmitting. */
    data class PttEnd(val username: String) : RelayEvent()

    /** One push-to-talk audio chunk from [chunk]'s sender. */
    data class PttAudio(val chunk: IncomingAudioChunk) : RelayEvent()

    /** A circle arrival/departure notification, for whichever circle this
     *  socket is joined to that produced it - see [RelayPlaceEvent]. */
    data class PlaceEventReceived(val relayEvent: RelayPlaceEvent) : RelayEvent()

    /** A convoy's shared spin - see [GroupSpin] for what a one-candidate vs.
     *  multi-candidate list each mean. Always `fromMe = false` from the wire;
     *  the caller wraps it. */
    data class SpinOffer(val candidates: List<SpinCandidate>) : RelayEvent()

    /** [username] voted for the candidate at [index] (0-2) of whatever
     *  [SpinOffer] is currently on the table. */
    data class SpinVote(val username: String, val index: Int) : RelayEvent()
}

/**
 * The convoy live-relay's wire protocol: pure string-to-object decoding and
 * object-to-string building, nothing else. No socket, no state, no clock -
 * [decode] takes [nowMs] as a parameter rather than reading one itself, so a
 * test can pin the one behaviour that actually matters here without waiting
 * on a wall clock.
 *
 * This one file replaces two: the Kotlin and Swift clients each parsed and
 * built the same sixteen frame types by hand, field names typed out twice.
 * Both are still the authority on the wire format - this is a port, not a
 * redesign - but from here on there is exactly one place a field name can be
 * spelled wrong.
 */
object RelayProtocol {

    /** Used only for a peer whose frame carried no usable `ttl` - an older or
     *  broken relay. Matches what the clients used to hardcode for everyone:
     *  ~10 missed 2s updates, generous enough that a normal gap in GPS fixes
     *  doesn't flicker a marker, short enough that someone who actually
     *  dropped off stops being shown as live. */
    const val FALLBACK_PEER_TTL_MS = 20_000L

    // --- decoding ---------------------------------------------------------

    /** Decodes one inbound frame, or `null` when [text] isn't JSON at all, is
     *  JSON but not an object, names a `type` this client has no event for
     *  (a relay newer than the client, or `error` - callers that want that
     *  one read `type`/`message` off the raw text themselves), or is missing
     *  a field its type requires. [nowMs] anchors [FriendPosition.expiresAtMs]
     *  for a `positions` frame - see that branch below for why. */
    fun decode(text: String, nowMs: Long): RelayEvent? {
        val obj = try {
            jsonObjectOf(text)
        } catch (e: Exception) {
            return null
        }
        return when (obj.optString("type")) {
            "joined" -> RelayEvent.Joined
            "left" -> obj.optString("user").takeIf { it.isNotBlank() }?.let { RelayEvent.Left(it) }
            "positions" -> decodePositions(obj, nowMs)
            "ptt_start" -> obj.optString("user").takeIf { it.isNotBlank() }?.let { RelayEvent.PttStart(it) }
            "ptt_end" -> obj.optString("user").takeIf { it.isNotBlank() }?.let { RelayEvent.PttEnd(it) }
            "ptt_audio" -> decodePttAudio(obj)
            "place_event" -> placeEventFromRelayFrame(obj)?.let { RelayEvent.PlaceEventReceived(it) }
            "spin_offer" -> decodeSpinOffer(obj)
            "spin_vote" -> decodeSpinVote(obj)
            else -> null
        }
    }

    private fun decodePositions(obj: JsonObject, nowMs: Long): RelayEvent.Positions? {
        val arr = obj.optArray("peers") ?: return null
        val peers = arr.objects().mapNotNull { o ->
            val user = o.optString("u")
            val lat = o.optDouble("lat")
            val lon = o.optDouble("lon")
            if (user.isBlank() || lat.isNaN() || lon.isNaN()) return@mapNotNull null
            val ttlSeconds = o.optInt("ttl", 0)
            FriendPosition(
                username = user,
                lat = lat,
                lon = lon,
                headingDeg = o.optDouble("h").takeIf { !it.isNaN() },
                speedKmh = o.optDouble("s").takeIf { !it.isNaN() },
                tsMs = o.optLong("ts"),
                // Anchored to arrival rather than to the fix's own timestamp:
                // that one comes off the sender's clock, and a phone whose
                // clock is minutes out would otherwise vanish immediately or
                // linger forever.
                expiresAtMs = nowMs + if (ttlSeconds > 0) ttlSeconds * 1_000L else FALLBACK_PEER_TTL_MS,
            )
        }
        return RelayEvent.Positions(peers)
    }

    private fun decodePttAudio(obj: JsonObject): RelayEvent.PttAudio? {
        val user = obj.optString("user")
        val chunk = obj.optString("chunk")
        if (user.isBlank() || chunk.isBlank()) return null
        // The server caps/validates chunk length but not that it's valid
        // base64; a malformed chunk decodes to null rather than throwing.
        val pcm = chunk.decodeBase64()?.toByteArray() ?: return null
        return RelayEvent.PttAudio(IncomingAudioChunk(user, pcm))
    }

    private fun decodeSpinOffer(obj: JsonObject): RelayEvent.SpinOffer? {
        val arr = obj.optArray("candidates") ?: return null
        val candidates = arr.objects().mapNotNull { o ->
            val lat = o.optDouble("lat")
            val lon = o.optDouble("lon")
            if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
            SpinCandidate(
                lat = lat,
                lon = lon,
                distanceM = o.optDouble("distanceM").takeIf { !it.isNaN() },
                durationS = o.optDouble("durationS").takeIf { !it.isNaN() },
                name = o.optString("name").takeIf { it.isNotBlank() },
            )
        }
        // A new offer starts a fresh vote, even mid-round - the candidates it
        // names are a different sheet than whatever was being voted on
        // before. An offer with no valid candidates left after filtering is
        // not a round worth starting.
        if (candidates.isEmpty()) return null
        return RelayEvent.SpinOffer(candidates)
    }

    private fun decodeSpinVote(obj: JsonObject): RelayEvent.SpinVote? {
        val user = obj.optString("user")
        val index = obj.optInt("index", -1)
        if (user.isBlank() || index !in 0..2) return null
        return RelayEvent.SpinVote(user, index)
    }

    // --- building -----------------------------------------------------------
    //
    // Every outbound frame but `location` names the convoy (or circle, for a
    // `join`) it belongs to explicitly, because the relay is keyed on the
    // *rider*, not the group: one socket holds several memberships at once,
    // and only these frames are genuinely group-scoped. `location` carries no
    // `groupId` at all - a fix belongs to whoever sent it, and the relay
    // resolves who may see it from that rider's memberships.

    fun buildJoin(groupId: String): String = buildJsonObject {
        put("type", "join")
        put("groupId", groupId)
    }.string()

    fun buildLocation(location: LatLon, headingDeg: Double?, speedKmh: Double, tsMs: Long): String = buildJsonObject {
        put("type", "location")
        put("lat", location.lat)
        put("lon", location.lon)
        // Omitted entirely rather than sent null - a fix with no bearing
        // (parked, or a platform that doesn't report one) says nothing about
        // heading rather than claiming zero.
        if (headingDeg != null) put("headingDeg", headingDeg)
        put("speedKmh", speedKmh)
        put("ts", tsMs)
    }.string()

    fun buildPttStart(groupId: String): String = buildJsonObject {
        put("type", "ptt_start")
        put("groupId", groupId)
    }.string()

    fun buildPttEnd(groupId: String): String = buildJsonObject {
        put("type", "ptt_end")
        put("groupId", groupId)
    }.string()

    fun buildPttAudio(groupId: String, pcm: ByteArray): String = buildJsonObject {
        put("type", "ptt_audio")
        put("chunk", pcm.toByteString().base64())
        put("groupId", groupId)
    }.string()

    fun buildSpinOffer(groupId: String, candidates: List<SpinCandidate>): String = buildJsonObject {
        put("type", "spin_offer")
        putJsonArray("candidates") {
            for (c in candidates) addJsonObject {
                put("lat", c.lat)
                put("lon", c.lon)
                c.distanceM?.let { put("distanceM", it) }
                c.durationS?.let { put("durationS", it) }
                c.name?.let { put("name", it) }
            }
        }
        put("groupId", groupId)
    }.string()

    fun buildSpinVote(groupId: String, index: Int): String = buildJsonObject {
        put("type", "spin_vote")
        put("index", index)
        put("groupId", groupId)
    }.string()
}

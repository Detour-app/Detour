package com.jellemax.detour.drive

import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.jsonObjectOf
import com.jellemax.detour.data.optDouble
import com.jellemax.detour.data.optInt
import com.jellemax.detour.data.optLong
import com.jellemax.detour.data.optString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers RelayProtocol.kt: the convoy live-relay's wire protocol, ported from
 * two independent hand-rolled implementations (Android's `ConvoyLiveClient.kt`
 * and iOS's `ConvoyLiveClient.swift`) that agreed on every frame only by
 * construction, never by a test either could run.
 *
 * What this proves is internal consistency - that [RelayProtocol.decode] and
 * the builders agree with each other and with the field names read out of the
 * two existing clients while porting this. It proves nothing about the actual
 * relay, which is a deployed server neither this test nor anything else here
 * can reach: a field renamed on both the decoder and an assertion together
 * would still pass every test in this file while breaking every real convoy.
 * The two existing clients remain the ground truth for the wire shape.
 */
class RelayProtocolTest {

    // --- fixtures -------------------------------------------------------------

    private fun joinedFrame() = """{"type":"joined"}"""

    private fun leftFrame(user: String = "ada") = """{"type":"left","user":"$user"}"""

    private fun pttStartFrame(user: String = "ada") = """{"type":"ptt_start","user":"$user"}"""

    private fun pttEndFrame(user: String = "ada") = """{"type":"ptt_end","user":"$user"}"""

    private fun pttAudioFrame(user: String = "ada", chunk: String = "Zm8=") =
        """{"type":"ptt_audio","user":"$user","chunk":"$chunk"}"""

    private fun spinVoteFrame(user: String = "ada", index: Int = 1) =
        """{"type":"spin_vote","user":"$user","index":$index}"""

    private fun placeEventFrame(
        groupId: String = "g1",
        placeId: Long = 42L,
        placeName: String = "Home",
        user: String = "ada",
        kind: String = "arrive",
        tsMs: Long = 1_700_000_000_000L,
    ) = """{"type":"place_event","groupId":"$groupId","placeId":$placeId,"placeName":"$placeName",""" +
        """"user":"$user","kind":"$kind","tsMs":$tsMs}"""

    /** One `peers` row - the shape the `positions` frame batches. A `null`
     *  optional field is omitted from the row entirely, not sent as JSON
     *  null - matching what the relay actually does. */
    private fun peerRow(
        u: String = "ada",
        lat: String = "51.0",
        lon: Double = 4.0,
        h: Double? = 90.0,
        s: Double? = 42.0,
        ts: Long = 1_700_000_000_000L,
        ttl: Int? = 10,
    ): String {
        val fields = buildList {
            add(""""u":"$u"""")
            add(""""lat":$lat""")
            add(""""lon":$lon""")
            if (h != null) add(""""h":$h""")
            if (s != null) add(""""s":$s""")
            add(""""ts":$ts""")
            if (ttl != null) add(""""ttl":$ttl""")
        }
        return "{${fields.joinToString(",")}}"
    }

    private fun positionsFrame(vararg rows: String) =
        """{"type":"positions","peers":[${rows.joinToString(",")}]}"""

    private fun spinOfferFrame(vararg candidates: String) =
        """{"type":"spin_offer","candidates":[${candidates.joinToString(",")}]}"""

    private fun candidate(
        lat: Double = 51.0,
        lon: Double = 4.0,
        distanceM: Double? = 1_200.0,
        durationS: Double? = 90.0,
        name: String? = "Scenic loop",
    ): String {
        val fields = buildList {
            add(""""lat":$lat""")
            add(""""lon":$lon""")
            if (distanceM != null) add(""""distanceM":$distanceM""")
            if (durationS != null) add(""""durationS":$durationS""")
            if (name != null) add(""""name":"$name"""")
        }
        return "{${fields.joinToString(",")}}"
    }

    // --- inbound: one test per frame, every field carried through -------------

    @Test
    fun joinedDecodesWithNoFields() {
        assertEquals(RelayEvent.Joined, RelayProtocol.decode(joinedFrame(), nowMs = 0))
    }

    @Test
    fun leftDecodesTheDepartingUsername() {
        val event = RelayProtocol.decode(leftFrame(user = "grace"), nowMs = 0)
        assertEquals(RelayEvent.Left("grace"), event)
    }

    @Test
    fun pttStartDecodesTheTalkingUsername() {
        val event = RelayProtocol.decode(pttStartFrame(user = "linus"), nowMs = 0)
        assertEquals(RelayEvent.PttStart("linus"), event)
    }

    @Test
    fun pttEndDecodesTheUsernameThatStoppedTalking() {
        val event = RelayProtocol.decode(pttEndFrame(user = "linus"), nowMs = 0)
        assertEquals(RelayEvent.PttEnd("linus"), event)
    }

    @Test
    fun pttAudioDecodesTheSenderAndBase64DecodesTheChunk() {
        // "Zm8=" is the RFC 4648 §10 test vector for "fo" - padded, since a
        // 2-byte input always needs one pad character. Using a published
        // vector rather than hand-encoding the bytes proves the decoder reads
        // the standard padded alphabet, not just whatever this file's own
        // encoder happens to produce.
        val event = RelayProtocol.decode(pttAudioFrame(user = "ada", chunk = "Zm8="), nowMs = 0)
        val chunk = (event as RelayEvent.PttAudio).chunk
        assertEquals("ada", chunk.username)
        assertEquals("fo", chunk.pcm.decodeToString())
    }

    @Test
    fun spinVoteDecodesTheVoterAndTheChosenIndex() {
        val event = RelayProtocol.decode(spinVoteFrame(user = "grace", index = 2), nowMs = 0)
        assertEquals(RelayEvent.SpinVote("grace", 2), event)
    }

    @Test
    fun placeEventDecodesThroughTheSharedCircleEventsParser() {
        // Not a second parser for this frame - RelayProtocol.decode hands the
        // whole object to placeEventFromRelayFrame (CircleEvents.kt), which
        // both apps are already required to agree on for the notification
        // text. This test only pins that decode() actually calls through and
        // wraps the result, not the parsing rules themselves.
        val event = RelayProtocol.decode(
            placeEventFrame(groupId = "circle-1", placeId = 7L, placeName = "School", user = "ada", kind = "depart"),
            nowMs = 0,
        )
        val relayEvent = (event as RelayEvent.PlaceEventReceived).relayEvent
        assertEquals("circle-1", relayEvent.groupId)
        assertEquals(7L, relayEvent.event.placeId)
        assertEquals("School", relayEvent.event.placeName)
        assertEquals("ada", relayEvent.event.username)
        assertEquals("depart", relayEvent.event.kind)
    }

    @Test
    fun spinOfferDecodesEveryCandidateField() {
        val frame = spinOfferFrame(
            candidate(lat = 51.1, lon = 4.1, distanceM = 5_000.0, durationS = 600.0, name = "Coast road"),
        )
        val event = RelayProtocol.decode(frame, nowMs = 0)
        val candidates = (event as RelayEvent.SpinOffer).candidates
        assertEquals(1, candidates.size)
        val c = candidates.single()
        assertEquals(51.1, c.lat)
        assertEquals(4.1, c.lon)
        assertEquals(5_000.0, c.distanceM)
        assertEquals(600.0, c.durationS)
        assertEquals("Coast road", c.name)
    }

    @Test
    fun spinOfferCandidateWithNoNameOrEtaDecodesThoseFieldsAsNull() {
        // distanceM/durationS/name are all optional on the wire - a member
        // sharing a spin has no route of its own for these until it commits.
        val frame = spinOfferFrame(candidate(distanceM = null, durationS = null, name = null))
        val c = (RelayProtocol.decode(frame, nowMs = 0) as RelayEvent.SpinOffer).candidates.single()
        assertEquals(null, c.distanceM)
        assertEquals(null, c.durationS)
        assertEquals(null, c.name)
    }

    @Test
    fun positionsDecodesEveryFieldOfEveryPeer() {
        val frame = positionsFrame(
            peerRow(u = "ada", lat = "51.0", lon = 4.0, h = 90.0, s = 42.0, ts = 1_700_000_000_000L, ttl = 10),
        )
        val peers = (RelayProtocol.decode(frame, nowMs = 5_000L) as RelayEvent.Positions).peers
        assertEquals(1, peers.size)
        val p = peers.single()
        assertEquals("ada", p.username)
        assertEquals(51.0, p.lat)
        assertEquals(4.0, p.lon)
        assertEquals(90.0, p.headingDeg)
        assertEquals(42.0, p.speedKmh)
        assertEquals(1_700_000_000_000L, p.tsMs)
        // ttl of 10s -> 10_000ms past the given nowMs.
        assertEquals(5_000L + 10_000L, p.expiresAtMs)
    }

    @Test
    fun positionsWithNoOptionalHeadingOrSpeedDecodesThemAsNull() {
        val frame = positionsFrame(peerRow(h = null, s = null))
        val p = (RelayProtocol.decode(frame, nowMs = 0) as RelayEvent.Positions).peers.single()
        assertEquals(null, p.headingDeg)
        assertEquals(null, p.speedKmh)
    }

    // --- unknown / malformed ----------------------------------------------

    @Test
    fun anUnknownTypeDecodesToNullRatherThanThrowing() {
        // A relay newer than this client must not crash it.
        assertNull(RelayProtocol.decode("""{"type":"some_future_frame","x":1}""", nowMs = 0))
    }

    @Test
    fun textThatIsNotJsonAtAllDecodesToNull() {
        assertNull(RelayProtocol.decode("not json at all", nowMs = 0))
    }

    @Test
    fun jsonThatIsNotAnObjectDecodesToNull() {
        assertNull(RelayProtocol.decode("[1,2,3]", nowMs = 0))
    }

    @Test
    fun anEmptyObjectWithNoTypeDecodesToNull() {
        assertNull(RelayProtocol.decode("{}", nowMs = 0))
    }

    // --- positions: malformed rows -----------------------------------------

    @Test
    fun positionsDropsAPeerWithABlankUAndKeepsTheRest() {
        val frame = positionsFrame(peerRow(u = "  "), peerRow(u = "grace"))
        val peers = (RelayProtocol.decode(frame, nowMs = 0) as RelayEvent.Positions).peers
        assertEquals(listOf("grace"), peers.map { it.username })
    }

    @Test
    fun positionsDropsAPeerWithANaNLatAndKeepsTheRest() {
        // A row whose "lat" simply isn't a number - the shape that produces
        // Double.NaN out of the lenient opt* accessors this codec is built on.
        val frame = positionsFrame(peerRow(u = "ada", lat = "\"not-a-number\""), peerRow(u = "grace"))
        val peers = (RelayProtocol.decode(frame, nowMs = 0) as RelayEvent.Positions).peers
        assertEquals(listOf("grace"), peers.map { it.username })
    }

    @Test
    fun positionsFallsBackToTheDefaultTtlWhenTtlIsMissing() {
        val frame = positionsFrame(peerRow(ttl = null))
        val p = (RelayProtocol.decode(frame, nowMs = 1_000L) as RelayEvent.Positions).peers.single()
        assertEquals(1_000L + RelayProtocol.FALLBACK_PEER_TTL_MS, p.expiresAtMs)
    }

    // --- the two divergences this codec deliberately resolved ------------
    //
    // Both existing clients disagreed here and shared code took Android's
    // stricter side, so iOS gains a guard it did not have. That is a chosen
    // behaviour change rather than a transcription, which is exactly why it
    // needs pinning: without these, a later edit could relax back to iOS's
    // laxer check and every other test in this file would still pass.

    @Test
    fun aWhitespaceOnlyUserIsRefusedOnEveryFrameThatCarriesOne() {
        // Android guarded with isNotBlank(), iOS only with !isEmpty, so a
        // whitespace handle was dropped on one platform and accepted on the
        // other. Refused everywhere now.
        assertNull(RelayProtocol.decode(leftFrame(user = "   "), nowMs = 0))
        assertNull(RelayProtocol.decode(pttStartFrame(user = "   "), nowMs = 0))
        assertNull(RelayProtocol.decode(pttEndFrame(user = "   "), nowMs = 0))
        assertNull(RelayProtocol.decode(pttAudioFrame(user = "   "), nowMs = 0))
        assertNull(RelayProtocol.decode(spinVoteFrame(user = "   "), nowMs = 0))
    }

    @Test
    fun aPttAudioFrameWithAnEmptyChunkIsRefused() {
        // Swift had no chunk guard, and Data(base64Encoded: "") returns valid
        // empty Data rather than nil — so an empty chunk reached the audio
        // player as zero bytes instead of being dropped.
        assertNull(RelayProtocol.decode(pttAudioFrame(chunk = ""), nowMs = 0))
        assertNull(RelayProtocol.decode(pttAudioFrame(chunk = "   "), nowMs = 0))
    }

    // --- degenerate array shapes, which the two clients also split on -----

    @Test
    fun positionsWithNoPeersKeyAtAllIsRefused() {
        assertNull(RelayProtocol.decode("""{"type":"positions"}""", nowMs = 0))
    }

    @Test
    fun positionsWithEveryRowInvalidDecodesToAnEmptyUpdateRatherThanNothing() {
        // Not the same as a missing "peers": the relay did answer, it just had
        // nothing usable to say. A null here would be indistinguishable from a
        // frame the codec did not recognise.
        val frame = positionsFrame(peerRow(u = ""), peerRow(u = "  "))
        val event = RelayProtocol.decode(frame, nowMs = 0)
        assertTrue(event is RelayEvent.Positions, "expected Positions, got $event")
        assertTrue((event as RelayEvent.Positions).peers.isEmpty())
    }

    @Test
    fun spinOfferDropsAnInvalidCandidateAndKeepsTheRest() {
        // The same drop-bad-keep-good rule `positions` follows. Worth its own
        // test because Swift's array cast was all-or-nothing here: one bad
        // element took the whole offer with it, and an offer that loses
        // candidates silently is how two devices resolve one vote to two
        // different destinations.
        val frame = """{"type":"spin_offer","candidates":[""" +
            """{"lat":"nope","lon":4.0},{"lat":51.0,"lon":4.0,"name":"Kept"}]}"""
        val offer = RelayProtocol.decode(frame, nowMs = 0) as RelayEvent.SpinOffer
        assertEquals(listOf("Kept"), offer.candidates.map { it.name })
    }

    // --- the clock-skew defence: expiresAtMs tracks nowMs, never ts -------

    @Test
    fun aPeersExpiryMovesWithNowMsNotWithTheFramesOwnTimestamp() {
        // The reason expiresAtMs is computed rather than read off the wire:
        // `ts` comes off the sender's own clock, which may be minutes out in
        // either direction. If expiry were derived from `ts` instead, a
        // sender with a fast clock would linger long after going quiet and a
        // sender with a slow clock would vanish the instant its frame
        // arrived. Anchoring to the receiver's own nowMs is what makes
        // staleness mean "we stopped hearing from you", not "your clock says
        // something old".
        //
        // ts is pinned far from both nowMs values below, on purpose - if
        // expiresAtMs were (wrongly) derived from ts, it would not move at
        // all when nowMs does, and this assertion would catch it.
        val skewedTs = 0L
        val ttlSeconds = 10
        val frame = positionsFrame(peerRow(ts = skewedTs, ttl = ttlSeconds))

        val atEarlierArrival = (RelayProtocol.decode(frame, nowMs = 1_000L) as RelayEvent.Positions).peers.single()
        val atLaterArrival = (RelayProtocol.decode(frame, nowMs = 9_000L) as RelayEvent.Positions).peers.single()

        assertEquals(1_000L + ttlSeconds * 1_000L, atEarlierArrival.expiresAtMs)
        assertEquals(9_000L + ttlSeconds * 1_000L, atLaterArrival.expiresAtMs)
        // The whole point, stated directly: the two decodes of the identical
        // frame differ only because nowMs differed.
        assertTrue(atLaterArrival.expiresAtMs > atEarlierArrival.expiresAtMs)
    }

    // --- outbound: one test per builder, exact field names ------------------
    //
    // Read back with the same jsonObjectOf/opt* accessors RelayProtocol.kt
    // itself is built on (Json.kt), rather than a second hand-rolled reader -
    // this is the general-purpose JSON reading this codebase already has.

    @Test
    fun buildJoinProducesTypeAndGroupIdOnly() {
        val obj = jsonObjectOf(RelayProtocol.buildJoin("convoy-1"))
        assertEquals(setOf("type", "groupId"), obj.keys)
        assertEquals("join", obj.optString("type"))
        assertEquals("convoy-1", obj.optString("groupId"))
    }

    @Test
    fun buildLocationOmitsHeadingDegWhenNull() {
        val obj = jsonObjectOf(
            RelayProtocol.buildLocation(location = LatLon(51.0, 4.0), headingDeg = null, speedKmh = 42.0, tsMs = 123L),
        )
        // No groupId - location is unscoped, see buildLocation's own doc.
        assertEquals(setOf("type", "lat", "lon", "speedKmh", "ts"), obj.keys)
        assertEquals("location", obj.optString("type"))
        assertEquals(51.0, obj.optDouble("lat"))
        assertEquals(4.0, obj.optDouble("lon"))
        assertEquals(42.0, obj.optDouble("speedKmh"))
        assertEquals(123L, obj.optLong("ts"))
    }

    @Test
    fun buildLocationIncludesHeadingDegWhenPresent() {
        val obj = jsonObjectOf(
            RelayProtocol.buildLocation(location = LatLon(51.0, 4.0), headingDeg = 275.5, speedKmh = 0.0, tsMs = 0L),
        )
        assertEquals(setOf("type", "lat", "lon", "headingDeg", "speedKmh", "ts"), obj.keys)
        assertEquals(275.5, obj.optDouble("headingDeg"))
    }

    @Test
    fun buildPttStartProducesTypeAndGroupIdOnly() {
        val obj = jsonObjectOf(RelayProtocol.buildPttStart("convoy-1"))
        assertEquals(setOf("type", "groupId"), obj.keys)
        assertEquals("ptt_start", obj.optString("type"))
    }

    @Test
    fun buildPttEndProducesTypeAndGroupIdOnly() {
        val obj = jsonObjectOf(RelayProtocol.buildPttEnd("convoy-1"))
        assertEquals(setOf("type", "groupId"), obj.keys)
        assertEquals("ptt_end", obj.optString("type"))
    }

    @Test
    fun buildPttAudioBase64EncodesWithStandardPadding() {
        // "fo" -> "Zm8=" is the same RFC 4648 vector as the decode test above,
        // proving the encoder and decoder agree on the padded alphabet - not
        // just with each other, but with the one published vector both were
        // checked against independently.
        val obj = jsonObjectOf(RelayProtocol.buildPttAudio("convoy-1", "fo".encodeToByteArray()))
        assertEquals(setOf("type", "chunk", "groupId"), obj.keys)
        assertEquals("Zm8=", obj.optString("chunk"))
    }

    @Test
    fun buildSpinVoteProducesTypeIndexAndGroupId() {
        val obj = jsonObjectOf(RelayProtocol.buildSpinVote("convoy-1", 2))
        assertEquals(setOf("type", "index", "groupId"), obj.keys)
        assertEquals(2, obj.optInt("index"))
    }

    @Test
    fun buildSpinOfferRoundTripsThroughDecode() {
        // spin_offer's array shape is awkward for the flat opt* accessors
        // above, so this asserts the builder's output the same way a real
        // caller would consume it: by decoding it straight back.
        val candidates = listOf(
            SpinCandidate(lat = 51.1, lon = 4.1, distanceM = 2_000.0, durationS = 300.0, name = "Loop"),
            SpinCandidate(lat = 51.2, lon = 4.2, distanceM = null, durationS = null, name = null),
        )
        val text = RelayProtocol.buildSpinOffer("convoy-1", candidates)
        val obj = jsonObjectOf(text)
        assertEquals(setOf("type", "candidates", "groupId"), obj.keys)
        assertEquals("convoy-1", obj.optString("groupId"))
        val decoded = (RelayProtocol.decode(text, nowMs = 0) as RelayEvent.SpinOffer).candidates
        assertEquals(candidates, decoded)
    }
}

package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The client half of CIRCLES_AND_CONVOYS.md §4.1: parsing the invite-link wire shapes, mapping a
 * refused join to its reason, and building and parsing the link a host shares.
 */
class ConvoyInvitesTest {

    @Test
    fun aMintedLinkParsesItsTokenAndExpiry() {
        val invite = convoyInviteFromJson(
            jsonObjectOf("""{ "token": "q9X_a-0bC", "expiresAtMs": 1791100800000 }"""),
        )

        assertEquals(ConvoyInvite(token = "q9X_a-0bC", expiresAtMs = 1791100800000), invite)
    }

    @Test
    fun theGuestListParsesEachGuestAndTheLinkExpiry() {
        val guests = convoyGuestsFromJson(
            jsonObjectOf(
                """
                {
                  "inviteExpiresAtMs": 1791100800000,
                  "guests": [
                    { "id": "22222222-2222-2222-2222-222222222222", "username": "max", "joinedAtMs": 1791090000000 },
                    { "id": "33333333-3333-3333-3333-333333333333", "username": "eva", "joinedAtMs": 1791095000000 }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(1791100800000, guests.inviteExpiresAtMs)
        assertEquals(
            listOf(
                ConvoyGuest(RiderRef(RiderId("22222222-2222-2222-2222-222222222222"), "max"), 1791090000000),
                ConvoyGuest(RiderRef(RiderId("33333333-3333-3333-3333-333333333333"), "eva"), 1791095000000),
            ),
            guests.guests,
        )
    }

    @Test
    fun noUsableLinkReadsAsNullExpiry() {
        // The server writes the field as null, not absent, when no link is usable.
        val guests = convoyGuestsFromJson(jsonObjectOf("""{ "inviteExpiresAtMs": null, "guests": [] }"""))

        assertNull(guests.inviteExpiresAtMs)
        assertEquals(emptyList(), guests.guests)
    }

    @Test
    fun eachRefusalStatusMapsToItsOwnReason() {
        val expected = mapOf(
            404 to ConvoyJoinRefusal.UNKNOWN_LINK,
            410 to ConvoyJoinRefusal.EXPIRED,
            403 to ConvoyJoinRefusal.REMOVED,
            400 to ConvoyJoinRefusal.FULL,
            429 to ConvoyJoinRefusal.TOO_MANY_ATTEMPTS,
        )

        for ((status, refusal) in expected) {
            val served = HttpStatusException(status, body = "", message = "HTTP $status")
            val failure = assertIs<ConvoyJoinException>(joinFailure(served))
            assertEquals(refusal, failure.refusal, "status $status")
            assertSame(served, failure.cause)
        }
    }

    @Test
    fun aStatusTheSpecDoesNotNameStaysThePlainServerError() {
        val served = HttpStatusException(502, body = "", message = "HTTP 502")

        assertSame(served, joinFailure(served))
    }

    @Test
    fun aLinkRoundTripsToItsServerAndToken() {
        val link = convoyInviteLink("https://detour.example.org", "q9X_a-0bC")

        assertEquals("https://detour.example.org/convoy-invite#q9X_a-0bC", link)
        assertEquals(ConvoyInviteLink("https://detour.example.org", "q9X_a-0bC"), parseConvoyInviteLink(link))
    }

    @Test
    fun aTrailingSlashOnTheServerDoesNotDoubleUp() {
        val link = convoyInviteLink("https://detour.example.org/", "abc")

        assertEquals("https://detour.example.org/convoy-invite#abc", link)
        assertEquals("https://detour.example.org", parseConvoyInviteLink(link)?.serverUrl)
    }

    @Test
    fun aPastedLinkWithSurroundingWhitespaceStillParses() {
        val parsed = parseConvoyInviteLink("  https://detour.example.org/convoy-invite#abc\n")

        assertEquals(ConvoyInviteLink("https://detour.example.org", "abc"), parsed)
    }

    @Test
    fun anythingButAnInviteLinkParsesToNull() {
        listOf(
            "",
            "abc",
            "https://detour.example.org/convoy-invite",
            "https://detour.example.org/convoy-invite#",
            "https://detour.example.org/shared-routes#abc",
            "https://detour.example.org/convoy-invite#abc def",
            "https://detour.example.org/convoy-invite#abc=",
            "detour.example.org/convoy-invite#abc",
        ).forEach { assertNull(parseConvoyInviteLink(it), it) }
    }
}

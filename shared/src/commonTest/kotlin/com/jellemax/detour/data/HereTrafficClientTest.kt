package com.jellemax.detour.data

import com.jellemax.detour.data.HereTrafficClient.Criticality
import com.jellemax.detour.data.HereTrafficClient.Fetch
import kotlinx.coroutines.runBlocking
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers HereTrafficClient.kt (#643): parsing HERE's flow and incident bodies,
 * mapping a status to the [Fetch] case its backoff depends on, and the rule that
 * the rider's key never leaves the client in anything a caller could log.
 */
class HereTrafficClientTest {

    private val box = HereTrafficClient.Box(west = 13.38, south = 52.52, east = 13.42, north = 52.54)

    private fun client(fetch: suspend (String) -> String) =
        HereTrafficClient(SENTINEL_KEY, fetch = fetch)

    @Test
    fun flowLinksBecomeOneSegmentEachCarryingTheirItemsFlow() {
        val flow = HereTrafficClient.parseFlow(HereTrafficFixtures.FLOW)
        assertEquals(3, flow.size, "two links of the first item, one of the second, none of the shapeless third")
        val (first, second, closed) = flow
        assertEquals(3, first.shape.size)
        assertEquals(LatLon(52.502879993990064, 13.703829981386662), second.shape.first())
        assertEquals(8.333333, first.speedMps, absoluteTolerance = 1e-6)
        assertEquals(13.888889, second.freeFlowMps, absoluteTolerance = 1e-6)
        assertEquals(4.2, first.jamFactor, absoluteTolerance = 1e-9)
        assertFalse(first.closed)
        assertTrue(closed.closed, "traversability closed and jam factor 10 is a closed road")
    }

    @Test
    fun incidentsKeepTheirKindSeverityPlaceAndTimes() {
        val incidents = HereTrafficClient.parseIncidents(HereTrafficFixtures.INCIDENTS)
        assertEquals(2, incidents.size)
        val works = incidents[0]
        assertEquals("3195531507295233286", works.id)
        assertEquals("construction", works.kind)
        assertEquals(Criticality.MAJOR, works.criticality)
        assertEquals(LatLon(51.0301, 6.9842), works.at)
        assertTrue(works.roadClosed)
        assertEquals("Bei Leverkusen - Bauarbeiten auf Abschnitt.", works.description)
        assertEquals(1575414000000L, works.startMs)
        assertEquals(1678661999000L, works.endMs)
    }

    @Test
    fun anIncidentHerePartlyMisspeltIsKeptWithTheGapsLeftEmpty() {
        val crash = HereTrafficClient.parseIncidents(HereTrafficFixtures.INCIDENTS)[1]
        assertEquals(Criticality.UNKNOWN, crash.criticality, "a criticality this client doesn't know must not drop the incident")
        assertEquals("Unfall", crash.description, "summary stands in for a missing description")
        assertNull(crash.startMs, "an unreadable time is unknown, not now")
        assertNull(crash.endMs)
    }

    @Test
    fun anEmptyAnswerIsNoTrafficNotAnError() = runBlocking {
        val answer = client { HereTrafficFixtures.EMPTY }.flow(box)
        assertEquals(Fetch.Ok(emptyList()), answer)
    }

    @Test
    fun aRefusedKeyAndASpentQuotaAreTheirOwnCases() = runBlocking {
        assertEquals(Fetch.BadKey, client { throw HttpStatusException(401, HereTrafficFixtures.UNAUTHORIZED) }.flow(box))
        assertEquals(Fetch.BadKey, client { throw HttpStatusException(403, "") }.incidents(box))
        assertEquals(Fetch.Quota, client { throw HttpStatusException(429, "") }.flow(box))
        assertEquals(Fetch.Failed, client { throw HttpStatusException(400, HereTrafficFixtures.BAD_REQUEST) }.flow(box))
        assertEquals(Fetch.Failed, client { throw HttpStatusException(503, "") }.flow(box))
    }

    @Test
    fun noAnswerAndAnUnreadableAnswerAreBothFailed() = runBlocking {
        assertEquals(Fetch.Failed, client { throw IOException("Can't reach the server") }.flow(box))
        assertEquals(Fetch.Failed, client { "<html>proxy error</html>" }.incidents(box))
    }

    @Test
    fun theKeyGoesOnlyIntoTheApiKeyParameter() {
        val url = client { "" }.url("flow", box)
        assertEquals(
            "https://data.traffic.hereapi.com/v7/flow?in=bbox:13.38,52.52,13.42,52.54" +
                "&locationReferencing=shape&apiKey=$SENTINEL_KEY",
            url,
        )
    }

    @Test
    fun theKeyNeverReachesAnythingACallerCouldLogOrShow() = runBlocking {
        // What each transport actually puts in its text: ktor's timeout quotes
        // the URL, and a proxy can echo the query string back in its body.
        val leaks = listOf<suspend (String) -> String>(
            { url -> throw IOException("Request timeout has expired [url=$url]") },
            { url -> throw HttpStatusException(401, "denied for $url") },
            { url -> throw HttpStatusException(429, "slow down, $url") },
            { url -> throw IllegalStateException("engine broke on $url") },
            { url -> "not json: $url" },
        )
        for (fetch in leaks) {
            val c = client(fetch)
            for (answer in listOf(c.flow(box), c.incidents(box))) {
                assertFalse(SENTINEL_KEY in answer.toString(), "key leaked through $answer")
            }
            assertFalse(SENTINEL_KEY in c.toString(), "key leaked through the client's toString")
        }
    }

    @Test
    fun aBlankKeyMeansNoClientAtAll() {
        assertNull(HereTrafficClient.forKey(""))
        assertNull(HereTrafficClient.forKey("   "), "whitespace pasted into the field is not a key")
        assertIs<HereTrafficClient>(HereTrafficClient.forKey(" abc "))
    }

    private companion object {
        const val SENTINEL_KEY = "SENTINEL-here-key-7f3a"
    }
}

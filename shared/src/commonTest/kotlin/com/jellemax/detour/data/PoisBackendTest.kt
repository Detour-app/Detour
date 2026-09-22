package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [PoiRoulette.parsePoisResponse] (issue #383 backend bbox response, spin's random-POI
 * feature). The wire shape is `PoiDto`/`PoisBboxResponse`
 * (`backend/Detour/Detour.Api/Contracts/PoiContracts.cs`), camelCase JSON.
 * [PoiRoulette.fetchPoisViaBackend] cannot be exercised here (needs a live backend) — this is
 * the pure half that can, same split [RoadsBackendTest] uses.
 */
class PoisBackendTest {

    @Test
    fun aPoiWithANameParses() {
        val body = jsonObjectOf(
            """{"pois":[{"id":"1","kind":"viewpoint","name":"Belvedere","lat":49.6,"lon":6.1}]}""",
        )
        val pois = PoiRoulette.parsePoisResponse(body, PoiKind.VIEWPOINT)
        assertEquals(1, pois.size)
        assertEquals(LatLon(49.6, 6.1), pois[0].location)
        assertEquals("Belvedere", pois[0].name)
    }

    @Test
    fun aPoiWithABlankNameFallsBackToTheKindLabel() {
        val body = jsonObjectOf(
            """{"pois":[{"id":"1","kind":"food","name":"","lat":49.6,"lon":6.1}]}""",
        )
        val pois = PoiRoulette.parsePoisResponse(body, PoiKind.FOOD)
        assertEquals("Food & drink", pois[0].name)
    }

    @Test
    fun anEmptyPoisArrayProducesAnEmptyResult() {
        val body = jsonObjectOf("""{"pois":[]}""")
        assertEquals(emptyList(), PoiRoulette.parsePoisResponse(body, PoiKind.SIGHT))
    }

    @Test
    fun multiplePoisAllParse() {
        val body = jsonObjectOf(
            """{"pois":[
                {"id":"1","kind":"sight","name":"Old Fort","lat":49.6,"lon":6.1},
                {"id":"2","kind":"sight","name":"Castle","lat":49.7,"lon":6.2}
            ]}""",
        )
        val pois = PoiRoulette.parsePoisResponse(body, PoiKind.SIGHT)
        assertEquals(2, pois.size)
    }
}

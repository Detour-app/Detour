package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The no-custom-model loop GET; GraphHopper 400s a round trip on CH (#626). */
class RoundTripQueryTest {

    private val start = LatLon(49.81291, 6.13084)

    @Test fun aLoopRequestDisablesCh() {
        val q = roundTripQuery("https://gh.example", start, 26892.4, 7L, headingDeg = null)
        assertTrue("&ch.disable=true" in q, q)
    }

    @Test fun aLoopRequestCarriesItsShape() {
        val q = roundTripQuery("https://gh.example", start, 26892.4, 7L, headingDeg = 90.6)
        assertEquals(
            "https://gh.example/route?profile=moto&point=49.81291,6.13084" +
                "&algorithm=round_trip&round_trip.distance=26892&round_trip.seed=7" +
                "&ch.disable=true&heading=90" +
                "&points_encoded=false&details=max_speed&details=roundabout",
            q,
        )
    }
}

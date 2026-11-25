package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The spin destination snap keeps off motorways, ramps and tunnels (#1078). */
class SnapQueryTest {

    private val from = LatLon(50.85, 4.35)
    private val to = LatLon(51.08972, 4.36241)

    @Test fun aMotoSnapCarriesItsShape() {
        assertEquals(
            "https://gh.example/route?profile=moto&point=50.85,4.35&point=51.08972,4.36241" +
                "&points_encoded=false&snap_prevention=motorway&snap_prevention=trunk" +
                "&snap_prevention=tunnel&snap_prevention=ferry",
            snapQuery("https://gh.example", from, to, "moto"),
        )
    }

    @Test fun aCarSnapMayLandOnATrunkButNotAMotorway() {
        val q = snapQuery("https://gh.example", from, to, "car")
        assertFalse("snap_prevention=trunk" in q, q)
        assertTrue("&snap_prevention=motorway" in q, q)
        assertTrue("&snap_prevention=tunnel" in q, q)
    }

    /** GraphHopper ignores the plural name on a GET without an error. */
    @Test fun theGetUsesTheSingularParameterName() {
        assertFalse("snap_preventions" in snapQuery("https://gh.example", from, to, "moto"))
    }
}

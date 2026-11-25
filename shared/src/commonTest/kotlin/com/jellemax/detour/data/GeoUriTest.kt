package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** [parseGeoUri]: the `geo:` forms a car "Navigate to X" intent carries. */
class GeoUriTest {

    private fun point(ssp: String) = assertIs<GeoTarget.Point>(parseGeoUri(ssp), ssp)

    @Test
    fun bareCoordinatesAreAPointWithoutALabel() {
        val p = point("51.05,3.72")
        assertEquals(51.05, p.at.lat, absoluteTolerance = 1e-9)
        assertEquals(3.72, p.at.lon, absoluteTolerance = 1e-9)
        assertNull(p.label)
    }

    @Test
    fun textQueryNextToCoordinatesIsTheLabel() {
        assertEquals("Ghent station", point("51.05,3.72?q=Ghent+station").label)
    }

    @Test
    fun coordinatesInTheQueryWinOverTheZeroPath() {
        val p = point("0,0?q=51.05,3.72(Home)")
        assertEquals(51.05, p.at.lat, absoluteTolerance = 1e-9)
        assertEquals("Home", p.label)
    }

    @Test
    fun ourOwnHandOffFormHasNoLabel() {
        // SpinScreen/SearchScreen hand off as geo:lat,lon?q=lat,lon — the q is not a name.
        assertNull(point("51.05,3.72?q=51.05,3.72").label)
    }

    @Test
    fun zeroZeroWithTextIsASearch() {
        assertEquals(GeoTarget.Query("Ghent station"), parseGeoUri("0,0?q=Ghent station"))
    }

    @Test
    fun zeroZeroWithNothingElseIsNothing() {
        assertNull(parseGeoUri("0,0"), "geo:0,0 must not route to the Gulf of Guinea")
        assertNull(parseGeoUri(""))
    }

    @Test
    fun uncertaintyParameterIsIgnored() {
        assertEquals(3.72, point("51.05,3.72;u=35").at.lon, absoluteTolerance = 1e-9)
    }

    @Test
    fun outOfRangeCoordinatesFallBackToTheQuery() {
        assertEquals(GeoTarget.Query("Ghent"), parseGeoUri("123,3.72?q=Ghent"))
    }
}

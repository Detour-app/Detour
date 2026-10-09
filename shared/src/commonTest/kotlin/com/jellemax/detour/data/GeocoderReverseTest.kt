package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Photon `/reverse` answers as a spin pick or a tapped route stop gets named
 *  from them (#503). The response is the same GeoJSON `/api/` returns. */
class GeocoderReverseTest {

    @Test fun aStreetIsNamedStreetCityCountry() {
        val body = """
            {"type":"FeatureCollection","features":[{"type":"Feature",
              "geometry":{"type":"Point","coordinates":[3.70934,51.05738]},
              "properties":{"street":"Kerkstraat","city":"Gent",
                "country":"België / Belgique / Belgien","osm_key":"highway"}}]}
        """.trimIndent()
        assertEquals("Kerkstraat, Gent, België", Geocoder.reverseLabel(body))
    }

    @Test fun aNamedPlaceWinsOverItsStreet() {
        val body = """
            {"features":[{"geometry":{"coordinates":[3.7,51.0]},
              "properties":{"name":"Gravensteen","street":"Sint-Veerleplein",
                "city":"Gent","country":"België"}}]}
        """.trimIndent()
        assertEquals("Gravensteen, Gent, België", Geocoder.reverseLabel(body))
    }

    @Test fun noFeaturesIsNoName() {
        assertNull(Geocoder.reverseLabel("""{"type":"FeatureCollection","features":[]}"""))
    }

    @Test fun aFeatureWithNothingNameableIsNoName() {
        val body = """{"features":[{"geometry":{"coordinates":[3.7,51.0]},"properties":{"osm_key":"natural"}}]}"""
        assertNull(Geocoder.reverseLabel(body))
    }
}

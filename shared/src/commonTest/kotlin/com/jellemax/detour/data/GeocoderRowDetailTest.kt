package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #1006: what a search row says beyond its title. The features are trimmed from a real Photon
 * 2.4 answer to "Namur", where four rows used to read "Namur, België" and nothing else.
 */
class GeocoderRowDetailTest {

    private fun feature(lon: Double, lat: Double, vararg props: Pair<String, String>): String =
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]},"properties":{""" +
            props.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" } + "}}"

    private fun answer(vararg features: String) =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    private val country = "country" to "België / Belgique / Belgien"

    private val namur = answer(
        feature(4.867, 50.465, "osm_key" to "place", "osm_value" to "city", "type" to "city",
            "name" to "Namur", "county" to "Namur", "state" to "Namur", country),
        feature(4.85, 50.30, "osm_key" to "boundary", "osm_value" to "administrative", "type" to "state",
            "name" to "Namur", country),
        feature(4.87, 50.47, "osm_key" to "railway", "osm_value" to "station", "type" to "house",
            "name" to "Namur", "street" to "Place de la Station", "housenumber" to "1",
            "postcode" to "5000", "city" to "Namur", "district" to "Namur", country),
    )

    @Test
    fun sameNamedHitsDifferByKindAndSecondLine() {
        val rows = Geocoder.parse(namur)

        assertEquals(List(3) { "Namur, België" }, rows.map { it.name })
        assertEquals(listOf(PlaceKind.TOWN, PlaceKind.REGION, PlaceKind.STATION), rows.map { it.kind })
        // The city and the province have nothing beyond their name; the station has its address,
        // without the city its title already says on its own and the district that repeats it.
        assertEquals(listOf("", "", "Place de la Station 1, 5000 Namur"), rows.map { it.secondary })
    }

    @Test
    fun threeStreetsOfOneNameDifferByPostcodeAndDistrict() {
        val rows = Geocoder.parse(answer(
            feature(3.72, 51.05, "osm_key" to "highway", "osm_value" to "residential", "type" to "street",
                "name" to "Kerkstraat", "postcode" to "9000", "city" to "Gent", country),
            feature(3.67, 51.07, "osm_key" to "highway", "osm_value" to "residential", "type" to "street",
                "name" to "Kerkstraat", "postcode" to "9030", "city" to "Gent", "district" to "Mariakerke", country),
        ))

        assertEquals(listOf(PlaceKind.STREET, PlaceKind.STREET), rows.map { it.kind })
        assertEquals(listOf("9000 Gent", "Mariakerke, 9030 Gent"), rows.map { it.secondary })
    }

    @Test
    fun anAddressWithoutANameDoesNotRepeatItsStreet() {
        val row = Geocoder.parse(answer(
            feature(3.72, 51.05, "osm_key" to "place", "osm_value" to "house", "type" to "house",
                "street" to "Kerkstraat", "housenumber" to "12", "postcode" to "9000", "city" to "Gent", country),
        )).single()

        assertEquals("Kerkstraat 12, Gent, België", row.name)
        assertEquals(PlaceKind.ADDRESS, row.kind)
        assertEquals("9000 Gent", row.secondary)
    }

    @Test
    fun poiKindsComeFromTheirOsmTags() {
        assertEquals(PlaceKind.FUEL, PlaceKind.of("amenity", "fuel", "house"))
        assertEquals(PlaceKind.PARKING, PlaceKind.of("amenity", "parking", "other"))
        assertEquals(PlaceKind.FOOD, PlaceKind.of("amenity", "cafe", "house"))
        assertEquals(PlaceKind.SHOP, PlaceKind.of("shop", "supermarket", "house"))
        assertEquals(PlaceKind.TOWN, PlaceKind.of("place", "village", "city"))
        assertEquals(PlaceKind.REGION, PlaceKind.of("boundary", "administrative", "district"))
        assertEquals(PlaceKind.PLACE, PlaceKind.of("tourism", "artwork", "house"))
    }

    @Test
    fun onlyLanguagesPhotonIndexesAreSent() {
        assertEquals("en", Geocoder.photonLanguage("en"))
        assertEquals("fr", Geocoder.photonLanguage("fr-BE"))
        assertEquals("de", Geocoder.photonLanguage("DE"))
        // Photon answers HTTP 400 to any other language, Dutch included.
        assertNull(Geocoder.photonLanguage("nl"))
        assertNull(Geocoder.photonLanguage(null))
    }

    @Test
    fun distanceNeedsAPointToMeasureFrom() {
        val row = GeocodeResult("Namur, België", LatLon(50.465, 4.867))
        assertNull(row.distanceFrom(null))
        assertEquals(0.0, row.distanceFrom(row.location))
    }

    @Test
    fun recentsWrittenBeforeTheSecondLineStillLoad() {
        val rows = RecentSearchStore.parse(
            """[{"name":"Kerkstraat, Gent, België","lat":51.05,"lon":3.72},""" +
                """{"name":"Namur, België","lat":50.47,"lon":4.87,""" +
                """"secondary":"Place de la Station 1, 5000 Namur","kind":"STATION"},""" +
                """{"name":"Somewhere","lat":1.0,"lon":2.0,"kind":"SPACEPORT"}]""",
        )

        assertEquals(GeocodeResult("Kerkstraat, Gent, België", LatLon(51.05, 3.72)), rows[0])
        assertEquals(PlaceKind.STATION, rows[1].kind)
        assertEquals("Place de la Station 1, 5000 Namur", rows[1].secondary)
        assertEquals(PlaceKind.PLACE, rows[2].kind)
    }
}

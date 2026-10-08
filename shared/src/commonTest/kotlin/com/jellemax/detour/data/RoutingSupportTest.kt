package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GraphHopper `/info` answers, read for which encoded values the graph has (#586). */
class RoutingSupportTest {

    @Test fun aGraphWithTollReportsIt() {
        val body = """
            {"bbox":[2.5,49.4,7.2,53.6],"profiles":[{"name":"car"},{"name":"moto"}],
             "version":"11.0","encoded_values":{
               "road_class":["OTHER","MOTORWAY"],"toll":["MISSING","NO","HGV","ALL"],
               "surface":["MISSING","PAVED"],"road_environment":["OTHER","ROAD","FERRY"],
               "max_speed":[]}}
        """.trimIndent()
        val values = RoutingSupport.encodedValues(body)!!
        assertTrue(RoutingEncodedValue.TOLL in values)
        assertTrue(RoutingEncodedValue.SURFACE in values)
        assertTrue(RoutingEncodedValue.ROAD_ENVIRONMENT in values)
    }

    @Test fun aGraphBuiltBeforeTollDoesNotReportIt() {
        val body = """{"version":"11.0","encoded_values":{"road_class":[],"surface":[]}}"""
        assertEquals(setOf("road_class", "surface"), RoutingSupport.encodedValues(body))
    }

    @Test fun anInfoWithoutEncodedValuesCannotTell() {
        assertNull(RoutingSupport.encodedValues("""{"version":"11.0"}"""))
    }

    @Test fun anHtmlErrorPageCannotTell() {
        assertNull(RoutingSupport.encodedValues("<html><body>404 Not Found</body></html>"))
    }
}

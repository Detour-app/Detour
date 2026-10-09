package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RoutePreferences.priorityRules] against the custom-model rules the old
 * per-flag `preferenceRules(avoidHighways, avoidSmallRoads)` built, written
 * out as literal JSON so a change to any multiplier or road class shows up
 * here rather than as a different route.
 */
class RoutePreferencesTest {

    private val highwayRules =
        """{"if":"road_class == MOTORWAY || road_class == TRUNK","multiply_by":0.05}"""

    private val smallRoadRules =
        """{"if":"road_class == UNCLASSIFIED || road_class == RESIDENTIAL","multiply_by":0.2},""" +
            """{"if":"road_class == LIVING_STREET || road_class == SERVICE","multiply_by":0.1},""" +
            """{"if":"road_class == TRACK || road_class == PATH","multiply_by":0.02}"""

    @Test
    fun noPreferencesGiveNoRules() {
        assertTrue(RoutePreferences().priorityRules().isEmpty())
    }

    @Test
    fun avoidHighwaysDowngradesMotorwaysAndTrunks() {
        assertEquals(
            "[$highwayRules]",
            RoutePreferences(avoidHighways = true).priorityRules().toString(),
        )
    }

    @Test
    fun avoidSmallRoadsPenalisesTheMinorLayers() {
        assertEquals(
            "[$smallRoadRules]",
            RoutePreferences(avoidSmallRoads = true).priorityRules().toString(),
        )
    }

    @Test
    fun bothPutHighwaysFirst() {
        assertEquals(
            "[$highwayRules,$smallRoadRules]",
            RoutePreferences(avoidHighways = true, avoidSmallRoads = true).priorityRules().toString(),
        )
    }

    @Test
    fun theGraphDependentOptionsAddOneRuleEach() {
        assertEquals(
            """[{"if":"toll != NO","multiply_by":0.05},""" +
                """{"if":"road_environment == FERRY","multiply_by":0.05},""" +
                """{"if":"surface == UNPAVED || surface == GRAVEL || surface == DIRT || surface == SAND",""" +
                """"multiply_by":0.05}]""",
            RoutePreferences(avoidTolls = true, avoidFerries = true, avoidUnpaved = true)
                .priorityRules().toString(),
        )
    }

    private val all = RoutePreferences(
        avoidHighways = true, avoidSmallRoads = true,
        avoidTolls = true, avoidFerries = true, avoidUnpaved = true,
    )

    @Test
    fun anUnknownServerKeepsOnlyTheOptionsEveryGraphHas() {
        val sent = all.supportedBy(null)
        assertEquals(RoutePreferences(avoidHighways = true, avoidSmallRoads = true), sent)
    }

    @Test
    fun aGraphWithoutTollDropsOnlyTolls() {
        val sent = all.supportedBy(setOf("road_class", "surface", "road_environment"))
        assertFalse(sent.avoidTolls)
        assertEquals(all.copy(avoidTolls = false), sent)
    }

    @Test
    fun aGraphWithAllThreeKeepsEverything() {
        assertEquals(all, all.supportedBy(setOf("toll", "surface", "road_environment")))
    }
}

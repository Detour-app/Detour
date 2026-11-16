package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [routeFromJson] to the fixtures web/src/routes.ts is tested against
 * (testdata/web-parity/README.md), so the web planner's copy of the rule and
 * this one cannot drift apart unnoticed.
 *
 * Host-test only because it reads the fixtures off the real filesystem with
 * java.io ([webParityCases]); the rule under test is common.
 */
class WebParityRoutesTest {

    @Test
    fun everyFixtureParsesAsTheWebPlannerParsesIt() {
        for (case in webParityCases("routes")) {
            assertEquals(
                canonical(case.expected),
                canonical(routeParityView(routeFromJson(jsonObjectOf(case.input)))),
                "fixture ${case.name}",
            )
        }
    }
}

package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [MunicipalityStore.parseMunicipalityResponse] (issue #381 backend point-lookup
 * response). The wire shape is `MunicipalityBoundaryDto`/`MunicipalityResponse`
 * (`backend/Detour/Detour.Api/Contracts/MunicipalityContracts.cs`), camelCase JSON.
 * `MunicipalityStore.fetchViaBackend` cannot be exercised here (needs a live backend) — this is
 * the pure half that can, same split [RoadsBackendTest]/[SpeedLimitWaysBackendTest] use.
 */
class MunicipalityBackendTest {

    @Test
    fun aMunicipalityWithARingParses() {
        val body = jsonObjectOf(
            """{"municipality":{"id":1234,"name":"Testville",
                "rings":[[[50.0,4.0],[50.0,4.02],[50.02,4.02]]]}}""",
        )
        val m = MunicipalityStore.parseMunicipalityResponse(body)
        assertEquals(1234L, m?.id)
        assertEquals("Testville", m?.name)
        assertEquals(1, m?.rings?.size)
        assertEquals(listOf(LatLon(50.0, 4.0), LatLon(50.0, 4.02), LatLon(50.02, 4.02)), m?.rings?.get(0))
    }

    @Test
    fun aNullMunicipalityIsARealNotFoundAnswer() {
        // The server answers 200 with {"municipality": null} when no admin_level=8 boundary
        // contains the point — sea, or outside the imported region. MunicipalityStore.fetch
        // treats this the same as fetchViaOverpass's own "not found" null, not as a request
        // failure that should fall through to Overpass too.
        val body = jsonObjectOf("""{"municipality":null}""")
        assertNull(MunicipalityStore.parseMunicipalityResponse(body))
    }

    @Test
    fun aMissingMunicipalityFieldParsesAsNotFound() {
        val body = jsonObjectOf("""{}""")
        assertNull(MunicipalityStore.parseMunicipalityResponse(body))
    }

    @Test
    fun aRingWithFewerThanThreePointsIsDropped() {
        // Same "no shape to ray-cast against" rule MunicipalityBoundary.Create enforces
        // server-side — a two-point ring cannot close into a polygon.
        val body = jsonObjectOf(
            """{"municipality":{"id":1,"name":"Testville","rings":[[[50.0,4.0],[50.0,4.02]]]}}""",
        )
        assertNull(MunicipalityStore.parseMunicipalityResponse(body))
    }

    @Test
    fun multipleRingsAllParse() {
        // Outer plus an enclave's inner ring, same shape MunicipalityBoundaryConfiguration's
        // RingsJson carries for a municipality with a hole.
        val body = jsonObjectOf(
            """{"municipality":{"id":1,"name":"Testville",
                "rings":[
                    [[50.0,4.0],[50.0,4.02],[50.02,4.02],[50.02,4.0]],
                    [[50.008,4.008],[50.008,4.012],[50.012,4.012]]
                ]}}""",
        )
        val m = MunicipalityStore.parseMunicipalityResponse(body)
        assertEquals(2, m?.rings?.size)
    }
}

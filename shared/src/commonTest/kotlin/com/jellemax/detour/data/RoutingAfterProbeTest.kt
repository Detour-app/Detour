package com.jellemax.detour.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #854: once the API has answered a capability probe, its routing announcement
 * decides. A server that announced none resolves to no routing, instead of
 * falling back to the general address or the baked host and sending `/route`
 * to an API with no GraphHopper behind it.
 */
class RoutingAfterProbeTest {

    private val api = "https://kit.example"

    /** A rider who pointed the app at the kit server and typed nothing else. */
    private val general = ServerConfig(url = api, enabled = true)

    @AfterTest
    fun resetBakedDefaults() = BuildDefaults.configure()

    /** What a probe of [api] that announced [announced] leaves as the accepted base. */
    private fun discoveredAfter(announced: String, previous: RoutingServer.AnnouncedServiceState =
        RoutingServer.AnnouncedServiceState()): String =
        RoutingServer.nextAnnouncedServiceState(announced, api, previous).discovered

    @Test
    fun aProbedServerThatAnnouncedNoRoutingResolvesToNone() {
        BuildDefaults.configure(routingUrl = "https://baked-route.example")
        assertEquals(
            ResolvedAddress("", AddressSource.NONE),
            RoutingServer.routingResolved(general, discoveredAfter(""), probed = true),
        )
        // The baked slot is skipped too, also with nothing saved (load() is bakedDefaults()).
        assertEquals("", RoutingServer.routingBase(null, discoveredAfter(""), probed = true))
        assertEquals("", RoutingServer.routingBase(RoutingServer.bakedDefaults(), "", probed = true))
    }

    @Test
    fun aPendingOrDeclinedAnnouncementCountsAsNoneAccepted() {
        val elsewhere = "https://route.elsewhere.example"
        val pending = RoutingServer.nextAnnouncedServiceState(elsewhere, api, RoutingServer.AnnouncedServiceState())
        assertEquals(elsewhere, pending.pending)
        assertEquals("", RoutingServer.routingBase(general, pending.discovered, probed = true))

        val declined = RoutingServer.resolvedAnnouncedServiceState(pending, accept = false)
        assertEquals(elsewhere, declined.declined)
        assertEquals("", RoutingServer.routingBase(general, declined.discovered, probed = true))
    }

    @Test
    fun aTypedRoutingAddressStillWinsOnAProbedServerWithNoRouting() {
        val typed = general.copy(routingUrl = "https://my-route.example")
        assertEquals(
            ResolvedAddress("https://my-route.example", AddressSource.TYPED),
            RoutingServer.routingResolved(typed, discoveredAfter(""), probed = true),
        )
    }

    @Test
    fun anAcceptedAnnouncementIsUsedOnAProbedServer() {
        val announced = "https://kit.example/route-host"
        assertEquals(
            ResolvedAddress(announced, AddressSource.ANNOUNCED),
            RoutingServer.routingResolved(general, discoveredAfter(announced), probed = true),
        )
    }

    @Test
    fun anUnprobedServerKeepsTheGeneralThenBakedFallback() {
        BuildDefaults.configure(routingUrl = "https://baked-route.example")
        assertEquals(
            ResolvedAddress(api, AddressSource.GENERAL),
            RoutingServer.routingResolved(general, "", probed = false),
        )
        assertEquals(
            ResolvedAddress("https://baked-route.example", AddressSource.BAKED),
            RoutingServer.routingResolved(null, "", probed = false),
        )
    }

    @Test
    fun routingIsUsableExactlyWhenTheResolvedBaseIsNotBlank() {
        BuildDefaults.configure(routingUrl = "https://baked-route.example")
        // The case #854 is about: ServerConfig.usable says yes, the probe says no.
        assertTrue(general.usable)
        assertFalse(RoutingServer.routingUsable(general, discoveredAfter(""), probed = true))
        assertFalse(RoutingServer.routingUsable(null, "", probed = true))

        assertTrue(RoutingServer.routingUsable(general.copy(routingUrl = "https://my-route.example"), "", probed = true))
        assertTrue(RoutingServer.routingUsable(general, discoveredAfter("https://kit.example/gh"), probed = true))
        assertTrue(RoutingServer.routingUsable(general, "", probed = false))
        assertTrue(RoutingServer.routingUsable(null, "", probed = false))

        BuildDefaults.configure()
        assertFalse(RoutingServer.routingUsable(null, "", probed = false))
    }
}

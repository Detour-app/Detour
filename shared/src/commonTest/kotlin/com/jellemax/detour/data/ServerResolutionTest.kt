package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers how a request's base address is chosen in RoutingServer.kt.
 *
 * Four clients read four different addresses out of one saved [ServerConfig],
 * and each falls back a different distance: the sync API, the router and the
 * geocoder all drop back to the single general address the app has always had,
 * while the identity provider deliberately does not. Getting that last rule
 * wrong points sign-in at the API host, where the discovery document does not
 * exist and the failure surfaces as "not signed in" with nothing logged.
 */
class ServerResolutionTest {

    /** A rider who filled in every field separately. */
    private fun split() = ServerConfig(
        url = "https://all.example",
        apiUrl = "https://api.example",
        routingUrl = "https://route.example",
        geocoderUrl = "https://search.example",
        idpIssuer = "https://idp.example/realms/detour",
        enabled = true,
    )

    /** What a build with no CI secrets ships: every default blank. */
    private fun noBakedDefaults() = BuildDefaults.configure()

    @Test
    fun aPerServiceOverrideWinsOverTheGeneralAddress() {
        noBakedDefaults()
        val c = split()
        assertEquals("https://api.example", RoutingServer.apiBase(c))
        assertEquals("https://route.example", RoutingServer.routingBase(c))
        assertEquals("https://search.example", RoutingServer.geocoderBase(c))
    }

    @Test
    fun theGeneralAddressServesEveryClientWhenNoOverrideIsSet() {
        // The shape every existing install is in: one URL saved, nothing else.
        // Reproducing it exactly is what makes this change migration-free.
        noBakedDefaults()
        val c = ServerConfig(url = "https://all.example", enabled = true)
        assertEquals("https://all.example", RoutingServer.apiBase(c))
        assertEquals("https://all.example", RoutingServer.routingBase(c))
        assertEquals("https://all.example", RoutingServer.geocoderBase(c))
    }

    @Test
    fun theBakedDefaultAppliesOnlyWhenNothingWasSaved() {
        BuildDefaults.configure(
            routingUrl = "https://baked-route.example",
            apiUrl = "https://baked-api.example",
            geocoderUrl = "https://baked-search.example",
        )
        assertEquals("https://baked-api.example", RoutingServer.apiBase(null))
        assertEquals("https://baked-route.example", RoutingServer.routingBase(null))
        assertEquals("https://baked-search.example", RoutingServer.geocoderBase(null))

        val c = ServerConfig(url = "https://all.example", enabled = true)
        assertEquals("https://all.example", RoutingServer.apiBase(c))
    }

    @Test
    fun theIssuerNeverFallsBackToTheGeneralServerAddress() {
        // A realm URL is never the API base, so a saved server with no issuer
        // must leave sign-in unconfigured rather than aim it at the API host.
        noBakedDefaults()
        val c = ServerConfig(url = "https://all.example", enabled = true)
        assertEquals("", RoutingServer.issuer(c))
    }

    @Test
    fun theIssuerStillPrefersTheSavedValueOverTheBakedOne() {
        BuildDefaults.configure(idpIssuer = "https://baked-idp.example/realms/detour")
        assertEquals(
            "https://idp.example/realms/detour",
            RoutingServer.issuer(split()),
        )
        assertEquals(
            "https://baked-idp.example/realms/detour",
            RoutingServer.issuer(ServerConfig(url = "https://all.example", enabled = true)),
        )
    }

    @Test
    fun trailingSlashesAndSurroundingSpaceAreStrippedSoPathsDoNotDoubleUp() {
        // Every caller appends a path beginning with "/", and Photon's is
        // "/api/?q=" — a base kept as "https://x/" produces "https://x//api/?q=",
        // which Photon answers with a 404 rather than a search result.
        noBakedDefaults()
        val c = ServerConfig(
            url = "  https://all.example/  ",
            idpIssuer = "https://idp.example/realms/detour/",
            enabled = true,
        )
        assertEquals("https://all.example", RoutingServer.apiBase(c))
        assertEquals("https://idp.example/realms/detour", RoutingServer.issuer(c))
    }

    @Test
    fun nothingConfiguredAnywhereResolvesToBlank() {
        noBakedDefaults()
        assertEquals("", RoutingServer.apiBase(null))
        assertEquals("", RoutingServer.routingBase(null))
        assertEquals("", RoutingServer.geocoderBase(null))
        assertEquals("", RoutingServer.issuer(null))
    }
}

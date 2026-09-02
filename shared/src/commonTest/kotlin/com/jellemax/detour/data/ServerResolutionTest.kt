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
        assertEquals("", RoutingServer.issuer(c, discovered = ""))
    }

    @Test
    fun theIssuerStillPrefersTheSavedValueOverTheBakedOne() {
        BuildDefaults.configure(idpIssuer = "https://baked-idp.example/realms/detour")
        assertEquals(
            "https://idp.example/realms/detour",
            RoutingServer.issuer(split(), discovered = ""),
        )
        assertEquals(
            "https://baked-idp.example/realms/detour",
            RoutingServer.issuer(
                ServerConfig(url = "https://all.example", enabled = true),
                discovered = "",
            ),
        )
    }

    @Test
    fun aTypedIssuerBeatsADiscoveredOne() {
        // The rule the deprecation copy promises: the field still wins. A rider
        // who typed an address is overruling the server on purpose, and
        // silently ignoring that is worse than the problem discovery solves.
        noBakedDefaults()
        assertEquals(
            "https://idp.example/realms/detour",
            RoutingServer.issuer(split(), discovered = "https://discovered.example/realms/detour"),
        )
    }

    @Test
    fun aDiscoveredIssuerBeatsTheBakedDefault() {
        // A rider pointing at their own server should sign in to their own
        // realm, not the realm this build happened to be compiled against.
        BuildDefaults.configure(idpIssuer = "https://baked-idp.example/realms/detour")
        val c = ServerConfig(url = "https://all.example", enabled = true)
        assertEquals(
            "https://discovered.example/realms/detour",
            RoutingServer.issuer(c, discovered = "https://discovered.example/realms/detour"),
        )
    }

    @Test
    fun aDiscoveredIssuerIsUsedWhenNothingElseIsConfigured() {
        noBakedDefaults()
        val c = ServerConfig(url = "https://all.example", enabled = true)
        assertEquals(
            "https://discovered.example/realms/detour",
            RoutingServer.issuer(c, discovered = "https://discovered.example/realms/detour"),
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
        assertEquals("https://idp.example/realms/detour", RoutingServer.issuer(c, discovered = ""))
    }

    @Test
    fun nothingConfiguredAnywhereResolvesToBlank() {
        noBakedDefaults()
        assertEquals("", RoutingServer.apiBase(null))
        assertEquals("", RoutingServer.routingBase(null))
        assertEquals("", RoutingServer.geocoderBase(null))
        assertEquals("", RoutingServer.issuer(null, discovered = ""))
    }

    @Test
    fun changingTheServerAddressDiscardsTheDiscoveredIssuer() {
        // The discovered value belongs to the server that stated it. Carried
        // across to a new address it would aim sign-in at the old deployment's
        // realm, which is the failure this whole feature exists to remove.
        noBakedDefaults()
        val before = ServerConfig(url = "https://old.example", enabled = true)
        val after = ServerConfig(url = "https://new.example", enabled = true)
        assertEquals(
            "",
            RoutingServer.issuerAfterSave(
                config = after,
                previous = before,
                discovered = "https://discovered.example/realms/detour",
            ),
        )
    }

    @Test
    fun keepingTheServerAddressKeepsTheDiscoveredIssuer() {
        // The rule this protects is the existing one recorded on
        // Auth.sessionEpoch: a server switch that leaves the effective issuer
        // alone must not drop the session. Editing an unrelated field is that
        // case, and it has to survive.
        noBakedDefaults()
        val before = ServerConfig(url = "https://same.example", enabled = true)
        val after = ServerConfig(
            url = "https://same.example",
            geocoderUrl = "https://search.example",
            enabled = true,
        )
        val discovered = "https://discovered.example/realms/detour"
        assertEquals(
            discovered,
            RoutingServer.issuerAfterSave(after, before, discovered),
        )
        // Same value before and after, so save() finds nothing to clear.
        assertEquals(
            RoutingServer.issuer(before, discovered),
            RoutingServer.issuerAfterSave(after, before, discovered),
        )
    }

    @Test
    fun anUnacceptableStoredIssuerIsNeverTheEffectiveOne() {
        // The vet sits on the read rather than on the composition, because
        // Auth.refresh() reaches the stored value through Auth.endpoint() and
        // RoutingServer.issuer() without passing Capabilities.preferredDiscovered,
        // which runs only at an interactive sign-in.
        noBakedDefaults()
        val poisoned = "http://localhost:8080@evil.example/realms/detour"
        assertEquals("", RoutingServer.vettedIssuer(poisoned))
        assertEquals("", RoutingServer.vettedIssuer(""))
        assertEquals(
            "https://idp.example/realms/detour",
            RoutingServer.vettedIssuer("https://idp.example/realms/detour"),
        )
        // issuer() composes candidates and does not judge them -- that is
        // vettedIssuer's job, and putting the filter in both places would hide
        // which one is the control.
        val c = ServerConfig(url = "https://all.example", enabled = true)
        assertEquals(poisoned, RoutingServer.issuer(c, discovered = poisoned))
    }

    @Test
    fun changingServersWhileAnIssuerWasTypedChangesNothing() {
        // A rider who typed a realm is not affected by a server change: the
        // typed value outranks the discovered one, so the effective issuer is
        // the same before and after and the session survives.
        noBakedDefaults()
        val typed = "https://idp.example/realms/detour"
        val before = ServerConfig(url = "https://old.example", idpIssuer = typed, enabled = true)
        val after = ServerConfig(url = "https://new.example", idpIssuer = typed, enabled = true)
        assertEquals(
            typed,
            RoutingServer.issuerAfterSave(after, before, "https://discovered.example/realms/detour"),
        )
    }
}

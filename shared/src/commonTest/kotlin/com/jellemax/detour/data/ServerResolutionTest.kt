package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        assertEquals("https://route.example", RoutingServer.routingBase(c, discoveredRouting = ""))
        assertEquals("https://search.example", RoutingServer.geocoderBase(c, discoveredGeocoder = ""))
    }

    @Test
    fun theGeneralAddressServesEveryClientWhenNoOverrideIsSet() {
        // The shape every existing install is in: one URL saved, nothing else.
        // Reproducing it exactly is what makes this change migration-free.
        noBakedDefaults()
        val c = ServerConfig(url = "https://all.example", enabled = true)
        assertEquals("https://all.example", RoutingServer.apiBase(c))
        assertEquals("https://all.example", RoutingServer.routingBase(c, discoveredRouting = ""))
        assertEquals("https://all.example", RoutingServer.geocoderBase(c, discoveredGeocoder = ""))
    }

    @Test
    fun theBakedDefaultAppliesOnlyWhenNothingWasSaved() {
        BuildDefaults.configure(
            routingUrl = "https://baked-route.example",
            apiUrl = "https://baked-api.example",
            geocoderUrl = "https://baked-search.example",
        )
        assertEquals("https://baked-api.example", RoutingServer.apiBase(null))
        assertEquals(
            "https://baked-route.example",
            RoutingServer.routingBase(null, discoveredRouting = ""),
        )
        assertEquals(
            "https://baked-search.example",
            RoutingServer.geocoderBase(null, discoveredGeocoder = ""),
        )

        val c = ServerConfig(url = "https://all.example", enabled = true)
        assertEquals("https://all.example", RoutingServer.apiBase(c))
    }

    @Test
    fun aDiscoveredRoutingBaseIsUsedWhenNothingElseIsConfigured() {
        // A rider pointing at their own server should route through the
        // GraphHopper it announces, not this build's baked-in one — the same
        // reasoning [aDiscoveredIssuerBeatsTheBakedDefault] applies to the realm.
        // No custom server at all here — unlike the issuer, [routingBase] falls
        // back to [ServerConfig.url] before the baked default, so a config that
        // set `url` would mask the discovered value behind that fallback rather
        // than testing the slot this covers.
        BuildDefaults.configure(routingUrl = "https://baked-route.example")
        assertEquals(
            "https://discovered-route.example",
            RoutingServer.routingBase(null, discoveredRouting = "https://discovered-route.example"),
        )
    }

    @Test
    fun aTypedRoutingUrlBeatsADiscoveredOne() {
        noBakedDefaults()
        val c = split()
        assertEquals(
            "https://route.example",
            RoutingServer.routingBase(c, discoveredRouting = "https://discovered-route.example"),
        )
    }

    @Test
    fun aDiscoveredGeocoderBaseIsUsedWhenNothingElseIsConfigured() {
        // Same reasoning as [aDiscoveredRoutingBaseIsUsedWhenNothingElseIsConfigured].
        BuildDefaults.configure(geocoderUrl = "https://baked-search.example")
        assertEquals(
            "https://discovered-search.example",
            RoutingServer.geocoderBase(null, discoveredGeocoder = "https://discovered-search.example"),
        )
    }

    /**
     * The regression for #355.
     *
     * An announced address is the server naming *this* service; [ServerConfig.url]
     * is a general "everything is at this host". The specific one wins, or
     * accepting an announcement does nothing for every rider who filled in
     * Server URL — which is the documented way to configure the app, so the
     * consent prompt was unreachable in exactly the case it is shown.
     *
     * A non-blank `url` is the whole point of the fixture: every other discovery
     * test here passes `null` or leaves it blank, which is how one wrong
     * argument order reached a release with a green suite.
     */
    @Test
    fun aDiscoveredRoutingBaseBeatsTheGeneralAddress() {
        noBakedDefaults()
        val c = ServerConfig(url = "https://all.example", enabled = true)
        assertEquals(
            "https://discovered-route.example",
            RoutingServer.routingBase(c, discoveredRouting = "https://discovered-route.example"),
            "an accepted routing announcement must outrank the general server address (#355)",
        )
    }

    /** The same rule for search — untested before #355, which is the other half
     *  of why this escaped: the geocoder had no general-address case at all. */
    @Test
    fun aDiscoveredGeocoderBaseBeatsTheGeneralAddress() {
        noBakedDefaults()
        val c = ServerConfig(url = "https://all.example", enabled = true)
        assertEquals(
            "https://discovered-search.example",
            RoutingServer.geocoderBase(c, discoveredGeocoder = "https://discovered-search.example"),
            "an accepted geocoder announcement must outrank the general server address (#355)",
        )
    }

    /** The general address is still the fallback when nothing was announced —
     *  the one-hostname deployment `docker/prod/docker-compose.proxy.yml` serves,
     *  which #355's fix must not break. */
    @Test
    fun theGeneralAddressStillServesWhenNothingWasAnnounced() {
        noBakedDefaults()
        val c = ServerConfig(url = "https://all.example", enabled = true)
        assertEquals("https://all.example", RoutingServer.routingBase(c, discoveredRouting = ""))
        assertEquals("https://all.example", RoutingServer.geocoderBase(c, discoveredGeocoder = ""))
    }

    /** A declined announcement never reaches `discovered`, so the general
     *  address keeps serving — accepting and declining must stay distinguishable. */
    @Test
    fun aTypedPerServiceAddressStillBeatsAnAnnouncedOne() {
        noBakedDefaults()
        val c = split()
        assertEquals(
            "https://route.example",
            RoutingServer.routingBase(c, discoveredRouting = "https://discovered-route.example"),
        )
        assertEquals(
            "https://search.example",
            RoutingServer.geocoderBase(c, discoveredGeocoder = "https://discovered-search.example"),
        )
    }

    /** No baked default exists for the camera-data endpoint (#303): an install
     *  that has announced nothing resolves to blank rather than to a public
     *  host, which is what tells [SpeedCameras.near] to fall back to Overpass. */
    @Test
    fun camerasBase_prefers_announced_over_general_and_baked() {
        val custom = ServerConfig(url = "https://general.example", enabled = true)
        assertEquals(
            "https://announced.example",
            RoutingServer.camerasBase(custom, discoveredCameras = "https://announced.example"),
        )
    }

    @Test
    fun camerasBase_falls_back_to_general_when_nothing_announced() {
        val custom = ServerConfig(url = "https://general.example", enabled = true)
        assertEquals("https://general.example", RoutingServer.camerasBase(custom, discoveredCameras = ""))
    }

    @Test
    fun camerasBase_resolves_to_blank_when_nothing_is_configured() {
        // Unlike routingBase/geocoderBase, cameras has no BuildDefaults value to
        // fall back to — there is no public camera-data host.
        assertEquals("", RoutingServer.camerasBase(null, discoveredCameras = ""))
    }

    /** [RoutingServer.speedLimitsBase] mirrors [RoutingServer.camerasBase] exactly (issue
     *  #379) — the three tests above, repeated for the speed-limit-way endpoint. */
    @Test
    fun speedLimitsBase_prefers_announced_over_general_and_baked() {
        val custom = ServerConfig(url = "https://general.example", enabled = true)
        assertEquals(
            "https://announced.example",
            RoutingServer.speedLimitsBase(custom, discoveredSpeedLimits = "https://announced.example"),
        )
    }

    @Test
    fun speedLimitsBase_falls_back_to_general_when_nothing_announced() {
        val custom = ServerConfig(url = "https://general.example", enabled = true)
        assertEquals("https://general.example", RoutingServer.speedLimitsBase(custom, discoveredSpeedLimits = ""))
    }

    @Test
    fun speedLimitsBase_resolves_to_blank_when_nothing_is_configured() {
        assertEquals("", RoutingServer.speedLimitsBase(null, discoveredSpeedLimits = ""))
    }

    /** [RoutingServer.roadsBase] mirrors [RoutingServer.camerasBase] exactly (issue #380) — the
     *  three tests above, repeated for the drivable-road endpoint. */
    @Test
    fun roadsBase_prefers_announced_over_general_and_baked() {
        val custom = ServerConfig(url = "https://general.example", enabled = true)
        assertEquals(
            "https://announced.example",
            RoutingServer.roadsBase(custom, discoveredRoads = "https://announced.example"),
        )
    }

    @Test
    fun roadsBase_falls_back_to_general_when_nothing_announced() {
        val custom = ServerConfig(url = "https://general.example", enabled = true)
        assertEquals("https://general.example", RoutingServer.roadsBase(custom, discoveredRoads = ""))
    }

    @Test
    fun roadsBase_resolves_to_blank_when_nothing_is_configured() {
        assertEquals("", RoutingServer.roadsBase(null, discoveredRoads = ""))
    }

    /** [RoutingServer.municipalityBase] mirrors [RoutingServer.camerasBase] exactly (issue
     *  #381) — the three tests above, repeated for the municipality-boundary endpoint. */
    @Test
    fun municipalityBase_prefers_announced_over_general_and_baked() {
        val custom = ServerConfig(url = "https://general.example", enabled = true)
        assertEquals(
            "https://announced.example",
            RoutingServer.municipalityBase(custom, discoveredMunicipality = "https://announced.example"),
        )
    }

    @Test
    fun municipalityBase_falls_back_to_general_when_nothing_announced() {
        val custom = ServerConfig(url = "https://general.example", enabled = true)
        assertEquals(
            "https://general.example",
            RoutingServer.municipalityBase(custom, discoveredMunicipality = ""),
        )
    }

    @Test
    fun municipalityBase_resolves_to_blank_when_nothing_is_configured() {
        assertEquals("", RoutingServer.municipalityBase(null, discoveredMunicipality = ""))
    }

    /** [RoutingServer.poisBase] mirrors [RoutingServer.camerasBase] exactly (issue #383) — the
     *  three tests above, repeated for the point-of-interest endpoint. */
    @Test
    fun poisBase_prefers_announced_over_general_and_baked() {
        val custom = ServerConfig(url = "https://general.example", enabled = true)
        assertEquals(
            "https://announced.example",
            RoutingServer.poisBase(custom, discoveredPois = "https://announced.example"),
        )
    }

    @Test
    fun poisBase_falls_back_to_general_when_nothing_announced() {
        val custom = ServerConfig(url = "https://general.example", enabled = true)
        assertEquals("https://general.example", RoutingServer.poisBase(custom, discoveredPois = ""))
    }

    @Test
    fun poisBase_resolves_to_blank_when_nothing_is_configured() {
        assertEquals("", RoutingServer.poisBase(null, discoveredPois = ""))
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
        assertEquals("", RoutingServer.routingBase(null, discoveredRouting = ""))
        assertEquals("", RoutingServer.geocoderBase(null, discoveredGeocoder = ""))
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
    fun removingTheCustomServerDropsASessionBoundToADiscoveredRealm() {
        // The #106 shape: the rider never typed an issuer, so the realm they
        // signed in against came from the server they are now removing.
        noBakedDefaults()
        assertTrue(
            RoutingServer.clearDropsSession(
                previous = ServerConfig(url = "https://mine.example", enabled = true),
                discovered = "https://mine.example/realms/detour",
            ),
        )
    }

    @Test
    fun removingTheCustomServerDropsASessionBoundToATypedRealm() {
        // The pre-#106 form of the same defect: clearCustom() already dropped a
        // typed idp_issuer without dropping the session it was bound to.
        noBakedDefaults()
        assertTrue(
            RoutingServer.clearDropsSession(
                previous = ServerConfig(
                    url = "https://mine.example",
                    idpIssuer = "https://typed.example/realms/detour",
                    enabled = true,
                ),
                discovered = "",
            ),
        )
    }

    @Test
    fun removingTheCustomServerKeepsASessionTheBakedRealmAlreadyMinted() {
        // The rule recorded on Auth.sessionEpoch: a change that leaves the
        // effective issuer alone must not sign the rider out. Written as a test
        // so the fix cannot be simplified into an unconditional Auth.clear().
        BuildDefaults.configure(idpIssuer = "https://same.example/realms/detour")
        assertFalse(
            RoutingServer.clearDropsSession(
                previous = ServerConfig(url = "https://mine.example", enabled = true),
                discovered = "https://same.example/realms/detour",
            ),
        )
    }

    @Test
    fun removingNothingStillDropsASessionBoundToADiscoveredRealm() {
        // Reachable with no custom server ever saved: ConfigFile.import calls
        // clearCustom() for a blank routingUrl whether or not one exists, and
        // every interactive sign-in probe stores a discovered realm. So a rider
        // on a stock install is signed in against the discovered realm rather
        // than the baked one, and dropping it moves them.
        BuildDefaults.configure(idpIssuer = "https://baked.example/realms/detour")
        assertTrue(
            RoutingServer.clearDropsSession(
                previous = null,
                discovered = "https://discovered.example/realms/detour",
            ),
        )
    }

    @Test
    fun removingNothingDropsNothing() {
        // clearCustom() on an install that never had a custom server is a no-op,
        // and must stay one — the settings screen can reach it in that state.
        // A baked default is configured rather than left blank so both sides of
        // the comparison are a real address, not the empty string twice over,
        // which would pass against an implementation comparing anything to
        // itself.
        BuildDefaults.configure(idpIssuer = "https://baked.example/realms/detour")
        assertFalse(RoutingServer.clearDropsSession(previous = null, discovered = ""))
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

    @Test
    fun eachResolvedAddressNamesTheSlotThatSuppliedIt() {
        // #352: the source tag is what lets a self-hoster tell "the announcement
        // never arrived" from "a typed address outranks it". Each slot is
        // exercised by blanking everything above it.
        BuildDefaults.configure(routingUrl = "https://baked-route.example")
        val announced = "https://announced-route.example"
        assertEquals(
            ResolvedAddress("https://route.example", AddressSource.TYPED),
            RoutingServer.routingResolved(split(), announced),
        )
        assertEquals(
            ResolvedAddress(announced, AddressSource.ANNOUNCED),
            RoutingServer.routingResolved(ServerConfig(url = "https://all.example", enabled = true), announced),
        )
        assertEquals(
            ResolvedAddress("https://all.example", AddressSource.GENERAL),
            RoutingServer.routingResolved(ServerConfig(url = "https://all.example/", enabled = true), ""),
        )
        assertEquals(
            ResolvedAddress("https://baked-route.example", AddressSource.BAKED),
            RoutingServer.routingResolved(null, ""),
        )
        // No baked camera host exists, so nothing configured is a real state
        // the screen has to name rather than render as an empty string.
        assertEquals(
            ResolvedAddress("", AddressSource.NONE),
            RoutingServer.camerasResolved(null, discoveredCameras = ""),
        )
    }

    @Test
    fun anAcceptedAnnouncementBeatsTheBakedRoutingHostWhenNothingWasSaved() {
        // Routing callers pass load(), which is bakedDefaults() on an install
        // with nothing saved. Its routing host must not sit in the typed slot,
        // or the announcement the diagnostics readout shows as winning loses.
        BuildDefaults.configure(routingUrl = "https://baked-route.example")
        val announced = "https://announced-route.example"
        assertEquals(announced, RoutingServer.routingBase(RoutingServer.bakedDefaults(), announced))
        assertEquals(
            "https://baked-route.example",
            RoutingServer.routingBase(RoutingServer.bakedDefaults(), discoveredRouting = ""),
        )
        assertTrue(RoutingServer.bakedDefaults().usable)
    }
}

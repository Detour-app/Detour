package com.jellemax.detour.data

/**
 * One rider's self-hosted addresses.
 *
 * [url] is the original single address, and still means "everything is here":
 * the three service overrides below fall back to it, so an install that only
 * ever filled that one field keeps behaving exactly as it did. The overrides
 * exist because a split deployment cannot be expressed as one address — the
 * sync API answers `/api/trips` and Photon answers `/api/?q=`, so no path
 * routing in front of them separates the two cleanly.
 *
 * [idpIssuer] is the exception that does *not* fall back to [url]: see
 * [RoutingServer.issuer].
 */
data class ServerConfig(
    val url: String = "",
    val apiUrl: String = "",
    val routingUrl: String = "",
    val geocoderUrl: String = "",
    val idpIssuer: String = "",
    val enabled: Boolean = false,
) {
    /** Whether routing can be attempted — which is the only thing every caller
     *  of this ever meant by it. */
    val usable: Boolean get() = enabled && (routingUrl.isNotBlank() || url.isNotBlank())
}

/**
 * Where this install's server addresses come from, and where they are kept.
 *
 * Resolution is the whole subject: a rider's typed address, the realm a
 * server stated on its last probe, and the values baked into the build are
 * three sources for the same field, and every [pick]-based accessor below is
 * one precedence order over them. The requests those addresses are used for
 * live in [RoutingClient].
 *
 * Keeps the name it had before the split, because that is what callers
 * outside `shared/` mean by it: `Api.kt`, `Auth.kt`, `Oidc.kt` and the
 * settings screens all reach `RoutingServer` for configuration.
 */
object RoutingServer {

    // internal, not private: CredentialMigration.migrateOnce() needs the same bag
    // name to migrate this group's plaintext, and a second string constant for the
    // same value would just be a second way to get it wrong.
    internal const val PREFS = "routing_server"

    /**
     * The realm the API server stated on its last successful probe.
     *
     * Not part of [ServerConfig], which stays the rider's own input. This is
     * *not* a cache in front of the probe — an interactive sign-in always asks
     * the server again, so a realm that moved cannot produce a 404 on an
     * authorize URL built from a stale value. What it is for is [Auth.refresh],
     * which runs on a cold start that may have no network and still has to
     * build a token endpoint from something.
     */
    private const val KEY_DISCOVERED_ISSUER = "idp_issuer_discovered"

    /**
     * The feature list the API server stated on its last successful probe.
     *
     * Stored, rather than asked for when needed, because the callers that need
     * it are synchronous and run before anything has been on the network — see
     * [knownServerFeatures].
     */
    private const val KEY_SERVER_FEATURES = "server_features"

    /**
     * Written in front of the stored list so an empty one is distinguishable
     * from a key that was never written. Those two mean opposite things: a
     * server that advertises nothing is an answer, and never having asked is
     * not, and [knownServerFeatures] returns null for the second.
     *
     * A marker rather than a separate "have probed" boolean, because each
     * `put` is its own async commit on Android and two of them can be torn
     * apart by a process death — leaving "probed, no features", which reads as
     * a definite no.
     */
    private const val FEATURES_MARKER = "v1:"

    fun bakedDefaults(): ServerConfig = ServerConfig(
        url = BuildDefaults.routingUrl,
        apiUrl = BuildDefaults.apiUrl,
        routingUrl = BuildDefaults.routingUrl,
        geocoderUrl = BuildDefaults.geocoderUrl,
        idpIssuer = BuildDefaults.idpIssuer,
        enabled = BuildDefaults.routingUrl.isNotBlank(),
    )

    /** Effective config: user's custom server if set, else baked defaults. */
    fun load(): ServerConfig = loadCustom() ?: bakedDefaults()

    /**
     * First non-blank candidate, trimmed and without its trailing slash.
     *
     * Every caller appends a path that already begins with `/`, and Photon's
     * begins `/api/?q=` — a base left as `https://x/` builds `https://x//api/?q=`,
     * which answers 404 rather than a search result.
     */
    private fun pick(vararg candidates: String): String =
        normalisedAddress(candidates.firstOrNull { it.isNotBlank() } ?: "")

    /**
     * The precedence every address in this file resolves by, stated once and
     * nowhere else.
     *
     * | slot | meaning |
     * | --- | --- |
     * | [typed] | what the rider entered for *this* service |
     * | [announced] | what the server stated for *this* service |
     * | [general] | the rider's one address for everything ([ServerConfig.url]) |
     * | [baked] | what this build was compiled against |
     *
     * Specific beats general, which is the rule #355 broke: [general] used to
     * sit ahead of [announced] for routing and search, so a rider who filled in
     * Server URL — the documented way to configure the app — could accept an
     * announcement and have it silently discarded. The consent prompt only
     * appears when the announced host differs from the API host, which is
     * exactly when [general] is the wrong answer, so accepting could never take
     * effect.
     *
     * A service declares which slots it *has*; it never declares an order. That
     * is the point: the defect was one service disagreeing with the other three
     * about the order, which was invisible while each wrote its own argument
     * list. Pass `""` for a slot that does not apply and the reason belongs in a
     * comment at that call site.
     */
    private fun resolve(typed: String, announced: String, general: String, baked: String): String =
        pick(typed, announced, general, baked)

    /** Base of the sync + social API, which serves everything under `/api`. */
    fun apiBase(custom: ServerConfig?): String = resolve(
        typed = custom?.apiUrl.orEmpty(),
        // Nothing announces the API: it is the address the rider points at, and
        // the document that would announce it is the one served from it.
        announced = "",
        general = custom?.url.orEmpty(),
        baked = BuildDefaults.apiUrl,
    )

    /** Base of the GraphHopper instance, which serves `/route`. A rider who
     *  typed a routing address keeps winning; one who only pointed the app at
     *  their own server reaches whatever that server announces, rather than the
     *  general address or this build's baked-in routing host (#355). */
    fun routingBase(custom: ServerConfig?): String = routingBase(custom, discoveredRoutingBase())

    /** `internal` with the discovered value passed in, for the same reason the
     *  [issuer] overload exists: reading it means touching `prefs`, which
     *  reaches a Context that does not exist in a unit test. */
    internal fun routingBase(custom: ServerConfig?, discoveredRouting: String): String = resolve(
        typed = custom?.routingUrl.orEmpty(),
        announced = discoveredRouting,
        general = custom?.url.orEmpty(),
        baked = BuildDefaults.routingUrl,
    )

    /** Base of the Photon instance, which serves `/api/?q=`. Same precedence as
     *  [routingBase]. */
    fun geocoderBase(custom: ServerConfig?): String = geocoderBase(custom, discoveredGeocoderBase())

    /** `internal` counterpart of [routingBase]'s, for the same reason. */
    internal fun geocoderBase(custom: ServerConfig?, discoveredGeocoder: String): String = resolve(
        typed = custom?.geocoderUrl.orEmpty(),
        announced = discoveredGeocoder,
        general = custom?.url.orEmpty(),
        baked = BuildDefaults.geocoderUrl,
    )

    /** Base of the camera-data endpoint, which serves `/api/cameras`. Same precedence as
     *  [routingBase]/[geocoderBase]. Unlike those, there is no [BuildDefaults] baked value — no
     *  public camera-data host exists to fall back to, so an install that announces nothing (or
     *  hasn't been probed yet) resolves to blank, and [SpeedCameras.near] falls back to Overpass. */
    fun camerasBase(custom: ServerConfig?): String = camerasBase(custom, discoveredCamerasBase())

    /** `internal` counterpart of [routingBase]'s, for the same reason. */
    internal fun camerasBase(custom: ServerConfig?, discoveredCameras: String): String = resolve(
        typed = "",
        announced = discoveredCameras,
        general = custom?.url.orEmpty(),
        baked = "",
    )

    /** Base of the speed-limit-way endpoint, which serves `/api/speedlimits`. Same precedence
     *  as [camerasBase], for the same reason: no [BuildDefaults] baked value — no public
     *  speed-limit-way host exists to fall back to — so an install that announces nothing
     *  resolves to blank, and [RoadRoulette.speedLimitWays] falls back to Overpass. */
    fun speedLimitsBase(custom: ServerConfig?): String = speedLimitsBase(custom, discoveredSpeedLimitsBase())

    /** `internal` counterpart of [routingBase]'s, for the same reason. */
    internal fun speedLimitsBase(custom: ServerConfig?, discoveredSpeedLimits: String): String = resolve(
        typed = "",
        announced = discoveredSpeedLimits,
        general = custom?.url.orEmpty(),
        baked = "",
    )

    /** Base of the drivable-road endpoint, which serves `/api/roads`. Same precedence as
     *  [camerasBase]/[speedLimitsBase], for the same reason: no [BuildDefaults] baked value —
     *  no public drivable-road host exists to fall back to — so an install that announces
     *  nothing resolves to blank, and [RoadRoulette.fetchRoads]/`RoadTypeTracker.fetchWays` fall
     *  back to Overpass. */
    fun roadsBase(custom: ServerConfig?): String = roadsBase(custom, discoveredRoadsBase())

    /** `internal` counterpart of [routingBase]'s, for the same reason. */
    internal fun roadsBase(custom: ServerConfig?, discoveredRoads: String): String = resolve(
        typed = "",
        announced = discoveredRoads,
        general = custom?.url.orEmpty(),
        baked = "",
    )

    /**
     * The realm that issues rider tokens.
     *
     * Note what is missing: [ServerConfig.url] is not a candidate. A realm URL
     * is never the API base, and letting it fall through would aim the token
     * exchange at a host with no discovery document — which surfaces as sign-in
     * appearing to work and the app landing back on "not signed in".
     */
    fun issuer(custom: ServerConfig?): String = issuer(custom, discoveredIssuer())

    /**
     * `internal` with the discovered value passed in, for the same reason
     * [Oidc.begin] has an overload taking the issuer: reading it means touching
     * `prefs`, and `prefs` reaches a Context that does not exist in a unit test.
     *
     * [discovered] sits ahead of the baked default on purpose: a rider who
     * pointed at their own server should reach their own realm rather than
     * whichever one this build was compiled against.
     */
    internal fun issuer(custom: ServerConfig?, discovered: String): String = resolve(
        typed = custom?.idpIssuer.orEmpty(),
        announced = discovered,
        // See this function's own KDoc: the general address is never a realm.
        general = "",
        baked = BuildDefaults.idpIssuer,
    )

    /**
     * What is actually on disk, unvetted. Only [discoveredIssuer] and
     * [rememberDiscoveredIssuer] may call this — the first to vet it, the second
     * to evict it. Everything else must read through [discoveredIssuer].
     */
    private fun storedIssuerRaw(): String = prefs(PREFS).string(KEY_DISCOVERED_ISSUER)

    /**
     * The realm the API server last stated, or blank. See [rememberDiscoveredIssuer].
     *
     * Vetted on **read**, not only on write, and that placement is load-bearing
     * rather than belt-and-braces. This is the single read point for the stored
     * issuer, and [Auth.refresh] reaches it through [Auth.endpoint] on a cold
     * start without going anywhere near [Capabilities.preferredDiscovered],
     * which runs only at an interactive sign-in. Vetting at the sign-in read
     * alone would leave a value written by an older, looser build receiving a
     * refresh token on every launch, forever.
     */
    internal fun discoveredIssuer(): String = vettedIssuer(storedIssuerRaw())

    /**
     * The stored issuer if it is still acceptable, blank otherwise.
     *
     * Split from [discoveredIssuer] so the vet itself can be asserted:
     * [discoveredIssuer] reads `prefs`, which no unit test can reach, and this
     * is the line that stops a value written by an older, looser build from
     * being used. Same reason [issuerAfterSave] is extracted from [save].
     */
    internal fun vettedIssuer(stored: String): String =
        stored.takeIf { Capabilities.acceptable(it) } ?: ""

    /**
     * The effective issuer a [save] of [config] would leave behind, given the
     * currently stored [discovered] value and the [previous] config.
     *
     * Extracted from [save] so it can be asserted: the clear itself calls
     * [Auth.clear] behind `prefs` and is unreachable from a unit test, but the
     * comparison that drives it is the part worth protecting.
     *
     * The rule is that a new API address discards the discovered issuer, since
     * it belonged to the server that stated it. Carried across it would aim
     * sign-in at the old deployment's realm.
     */
    internal fun issuerAfterSave(
        config: ServerConfig,
        previous: ServerConfig?,
        discovered: String,
    ): String = issuer(config, if (serverChanged(config, previous)) "" else discovered)

    /**
     * Whether clearing the custom server changes which realm this device signs
     * in to, given the [previous] config and the stored [discovered] issuer.
     *
     * Extracted from [clearCustom] for the same reason [issuerAfterSave] is
     * extracted from [save]: [clearCustom] reaches [Auth.clear] through `prefs`
     * and is unreachable from a unit test, but the comparison that drives it is
     * the part worth protecting.
     *
     * The "after" side takes neither a config nor a discovered value because
     * [clearCustom] drops both — what survives it is the baked default.
     */
    internal fun clearDropsSession(previous: ServerConfig?, discovered: String): Boolean =
        issuer(null, "") != issuer(previous, discovered)

    /**
     * Whether [config] points at a different API host than [previous].
     *
     * Extracted so [issuerAfterSave] and [save] cannot drift apart on it: both
     * need exactly this predicate, one to decide the effective issuer and the
     * other to decide whether to evict the stored one, and a later edit to
     * only one of two inlined copies would leave eviction and session-clearing
     * disagreeing about what "the server changed" means.
     */
    private fun serverChanged(config: ServerConfig, previous: ServerConfig?): Boolean =
        apiBase(config) != apiBase(previous)

    /** The user's own server settings, or null when using built-in defaults. */
    fun loadCustom(): ServerConfig? {
        // Guarded once-per-process, shared with Settings.init() — see migrateOnce().
        CredentialMigration.migrateOnce()
        val p = prefs(PREFS)
        if (!p.bool("saved", false)) return null
        val config = ServerConfig(
            url = p.string("url"),
            apiUrl = p.string("api_url"),
            routingUrl = p.string("routing_url"),
            geocoderUrl = p.string("geocoder_url"),
            idpIssuer = p.string("idp_issuer"),
            enabled = true,
        )
        // Saved-but-empty is the same as never saved. Checked across every
        // address rather than `url` alone: a split deployment may fill only the
        // per-service fields, and testing `url` would discard the whole config.
        val anyAddress = listOf(
            config.url, config.apiUrl, config.routingUrl,
            config.geocoderUrl, config.idpIssuer,
        ).any { it.isNotBlank() }
        return config.takeIf { anyAddress }
    }

    fun save(config: ServerConfig) {
        val previous = loadCustom()
        val discovered = discoveredIssuer()

        // Tokens are minted by one realm and meaningless to another, and a
        // refresh presented to the wrong realm reads as a replay rather than as
        // a mistake. Compared on the *effective* issuer, which is why the
        // discarded discovered value is folded in through [issuerAfterSave]: a
        // rider whose only issuer was discovered, changing servers, is changing
        // realms. A server switch that leaves the effective issuer alone still
        // does not clear — see the note on [Auth.sessionEpoch].
        if (issuerAfterSave(config, previous, discovered) != issuer(previous, discovered)) {
            Auth.clear()
        }

        // Above the config write, not inside it: each put/remove below is its
        // own async commit on Android, so a process death between them must not
        // be able to leave a new API address paired with the old server's
        // realm. The reverse order is safe — old address with no discovered
        // issuer just resolves to typed-or-baked.
        if (serverChanged(config, previous)) {
            prefs(PREFS).remove(KEY_DISCOVERED_ISSUER)
            // Same reason: what the old deployment said it supports says nothing
            // about the new one, and carrying it across would have a client
            // configure itself against a server it is no longer talking to.
            prefs(PREFS).remove(KEY_SERVER_FEATURES)
            // Same reason again: a routing/geocoder base the old deployment
            // announced — accepted, still pending, or declined — belonged to
            // that deployment. Carrying an accepted one across would silently
            // route a rider's destinations through a host the *new* server
            // never announced.
            clearAnnouncedService(ROUTING_KEYS)
            clearAnnouncedService(GEOCODER_KEYS)
            clearAnnouncedService(CAMERAS_KEYS)
            clearAnnouncedService(SPEEDLIMITS_KEYS)
        }

        prefs(PREFS).apply {
            put("saved", true)
            put("url", config.url.trim())
            put("api_url", config.apiUrl.trim())
            put("routing_url", config.routingUrl.trim())
            put("geocoder_url", config.geocoderUrl.trim())
            put("idp_issuer", config.idpIssuer.trim())
        }
    }

    /**
     * Records the realm the API server just stated, dropping the session if
     * that changes which realm this device signs in to.
     *
     * [discovered] must be [Capabilities.acceptable] or blank — blank meaning
     * the probe found nothing usable. An unacceptable, non-blank value is
     * refused as a no-op rather than stored or used to evict: this is the
     * only place that writes [KEY_DISCOVERED_ISSUER], so a caller passing one
     * through anyway is a caller bug, and the safe response to a caller bug is
     * to change nothing rather than to guess which side of it to trust.
     *
     * The clear goes through the same rule [save] applies, and for the same
     * reason: a refresh token presented to a realm that did not mint it reads
     * as a replay. Cheap to call with an unchanged value, which is the common
     * case, since every interactive sign-in probes.
     */
    internal fun rememberDiscoveredIssuer(discovered: String) {
        // Normalised once, up front: what's stored is compared for equality
        // against what's already stored, and the stored value is always
        // normalised (Capabilities.parse does it before this is ever called).
        // Comparing a raw argument against a normalised previous would miss a
        // same-issuer-different-slash write and store a value that no longer
        // matches what normalisedAddress's three call sites agree on.
        val normalised = normalisedAddress(discovered)

        // Refused rather than stored, and deliberately *not* by falling into
        // the blank branch below: evicting here would drop a good stored value
        // in favour of one this build will not use, which is the downgrade
        // [Capabilities.preferredDiscovered] exists to prevent.
        if (normalised.isNotBlank() && !Capabilities.acceptable(normalised)) return

        val previous = discoveredIssuer()
        val custom = loadCustom()

        // A blank argument evicts rather than returning early. Blank means the
        // probe found nothing usable, and that includes the case where what was
        // already stored is no longer acceptable — so returning early here would
        // strand exactly the value the caller just refused. Compared on the raw
        // read, because a value the vetted read already filters to blank still
        // occupies the key and should still go.
        if (normalised.isBlank()) {
            if (storedIssuerRaw().isNotEmpty()) {
                if (issuer(custom, "") != issuer(custom, previous)) Auth.clear()
                prefs(PREFS).remove(KEY_DISCOVERED_ISSUER)
            }
            return
        }

        if (normalised == previous) return
        if (issuer(custom, normalised) != issuer(custom, previous)) Auth.clear()
        prefs(PREFS).put(KEY_DISCOVERED_ISSUER, normalised)
    }

    fun clearCustom() {
        // Read before the wipe: both inputs live in the prefs this is about to
        // clear. And cleared above it, not below, matching the discipline [save]
        // documents for its own eviction — each put/remove is its own async
        // commit on Android, so a process death between them must not be able to
        // leave a cleared config paired with a live session.
        //
        // The rule is [save]'s: tokens are minted by one realm and meaningless to
        // another, and a refresh presented to the wrong realm reads as a replay
        // rather than as a mistake. Dropping the custom server is a realm change
        // whenever what it resolved to differs from the baked default.
        if (clearDropsSession(loadCustom(), discoveredIssuer())) Auth.clear()

        prefs(PREFS).clear()
    }

    /**
     * What the API server last said it supports, or null if it has never been
     * asked (or never answered).
     *
     * The null is the point, and callers must not collapse it into an empty
     * list: "this server cannot send push" and "nobody has asked yet" lead to
     * opposite decisions on Android, where the second must leave the relay
     * running rather than stand it down on an assumption.
     *
     * Synchronous, and deliberately so — [com.jellemax.detour.notif] reads this
     * on a cold start, before any coroutine has reached the network.
     */
    fun knownServerFeatures(): List<String>? = decodeFeatures(prefs(PREFS).string(KEY_SERVER_FEATURES))

    /**
     * Whether the configured server has stated it supports [feature] — the one
     * query every capability-gated call site should share, instead of each one
     * hand-rolling `knownServerFeatures()?.contains(...) == true`.
     *
     * Unknown reads as false, same as [knownServerFeatures]'s null does for its
     * one caller today ([com.jellemax.detour.notif]'s `pushCovers`): a server
     * that has never been asked, or whose last probe failed, is indistinguishable
     * here from one that answered "no". What a caller *does* with that false is
     * a decision for the call site, not for this function — "hide the control",
     * "keep the old behaviour running" and "degrade to an older path" are all
     * valid answers for different features, and which one applies belongs in a
     * comment at the call site, not folded into a shared default.
     *
     * Synchronous for the same reason [knownServerFeatures] is: a caller such as
     * [com.jellemax.detour.notif] reads this on a cold start, before any
     * coroutine has reached the network.
     */
    fun hasFeature(feature: String): Boolean = has(knownServerFeatures(), feature)

    /**
     * The pure half of [hasFeature], split out so the unknown-reads-as-false
     * rule can be asserted without a `Context` — same reason [decodeFeatures] is
     * split from [knownServerFeatures].
     */
    internal fun has(features: List<String>?, feature: String): Boolean = features?.contains(feature) == true

    /**
     * The stored form read back, or null when nothing was ever stored.
     *
     * Split from [knownServerFeatures] so the tri-state can be asserted:
     * [knownServerFeatures] reads `prefs`, which reaches a Context no unit test
     * has. Same reason [vettedIssuer] is split from [discoveredIssuer].
     */
    internal fun decodeFeatures(stored: String): List<String>? {
        if (!stored.startsWith(FEATURES_MARKER)) return null
        return stored.removePrefix(FEATURES_MARKER)
            .split(',')
            .filter { it.isNotBlank() }
    }

    /** The inverse of [decodeFeatures]. Extracted for the same reason. */
    internal fun encodeFeatures(features: List<String>): String =
        FEATURES_MARKER + features.joinToString(",")

    /**
     * Records what the API server just stated.
     *
     * A null [features] — the probe failed, or the body was not a capability
     * document — leaves whatever is stored alone. That is not a cache policy:
     * a rider who is simply offline must not lose an answer their server gave
     * yesterday, because the thing that reads it treats "unknown" as a reason
     * to fall back to the more expensive transport.
     *
     * A document that *parsed* always overwrites, including with an empty list.
     * That is how a deployment which has had its push credentials removed stops
     * being treated as push-capable.
     */
    internal fun rememberServerFeatures(features: List<String>?) {
        if (features == null) return
        prefs(PREFS).put(KEY_SERVER_FEATURES, encodeFeatures(features))
    }

    /**
     * Asks the configured server what it supports and records the answer.
     *
     * Separate from the probe [Oidc.resolveIssuer] makes, which runs only at an
     * interactive sign-in: a rider who signed in months ago would otherwise
     * never learn what their deployment grew since. Cheap enough for every app
     * start, and a failed one changes nothing.
     *
     * `@Throws(Exception::class)`: called from Swift, where an unannotated
     * escaping exception terminates the process rather than arriving as an
     * error — same reason [Oidc.resolveIssuer] carries it. [Capabilities.fetch]
     * already swallows everything, so this is the annotation stating that
     * rather than admitting a hazard.
     */
    @Throws(Exception::class)
    suspend fun probeCapabilities() {
        val custom = loadCustom()
        val api = apiBase(custom)
        val fetched = Capabilities.fetch(api, userAgentHeaders())
        rememberServerFeatures(fetched?.features)
        // Same "null leaves it alone, a parsed document always overwrites" rule
        // as rememberServerFeatures — a failed or unparseable probe must not
        // evict an accepted routing/geocoder base just because the rider is
        // offline right now.
        if (fetched != null) {
            rememberAnnouncedService(ROUTING_KEYS, fetched.routingBaseUrl, api)
            rememberAnnouncedService(GEOCODER_KEYS, fetched.geocoderBaseUrl, api)
            rememberAnnouncedService(CAMERAS_KEYS, fetched.camerasBaseUrl, api)
            rememberAnnouncedService(SPEEDLIMITS_KEYS, fetched.speedLimitsBaseUrl, api)
            rememberAnnouncedService(ROADS_KEYS, fetched.roadsBaseUrl, api)
        }
    }

    /**
     * The one header every request identifies itself with. `internal` rather
     * than private because the capability probe needs it too.
     */
    internal fun userAgentHeaders(): Map<String, String> =
        mapOf("User-Agent" to "Detour/${BuildDefaults.versionName}")

    // --- Routing/geocoder discovery (#177) ------------------------------------
    //
    // The same shape as the issuer's own discovery pair above, extended with a
    // consent step: an issuer is mandatory and the server is trusted for it by
    // construction (the ID-token `iss` check downstream re-verifies it anyway),
    // but a routing/geocoder base is optional and moves rider data — a typed
    // destination, an origin/destination pair — whichever way it points. See
    // `Capabilities.acceptable`'s KDoc and `docs/BACKEND_SPEC.md` §15.5.
    //
    // Three states per service, not one: [AnnouncedServiceKeys.discovered] is
    // what [routingBase]/[geocoderBase] actually use; [pending] is an announced
    // value that differs from the API's own host and awaits the rider's tap;
    // [declined] is the last value the rider said no to, so an unchanged
    // re-announcement does not nag them on every app start.

    private data class AnnouncedServiceKeys(
        val discovered: String,
        val pending: String,
        val declined: String,
    )

    private val ROUTING_KEYS = AnnouncedServiceKeys(
        discovered = "routing_discovered_base",
        pending = "routing_pending_base",
        declined = "routing_declined_base",
    )

    private val GEOCODER_KEYS = AnnouncedServiceKeys(
        discovered = "geocoder_discovered_base",
        pending = "geocoder_pending_base",
        declined = "geocoder_declined_base",
    )

    private val CAMERAS_KEYS = AnnouncedServiceKeys(
        discovered = "cameras_discovered_base",
        pending = "cameras_pending_base",
        declined = "cameras_declined_base",
    )

    private val SPEEDLIMITS_KEYS = AnnouncedServiceKeys(
        discovered = "speedlimits_discovered_base",
        pending = "speedlimits_pending_base",
        declined = "speedlimits_declined_base",
    )

    private val ROADS_KEYS = AnnouncedServiceKeys(
        discovered = "roads_discovered_base",
        pending = "roads_pending_base",
        declined = "roads_declined_base",
    )

    /** What a probe's freshly-announced value, plus the previous stored state,
     *  resolve to — see [AnnouncedServiceKeys] for what each field means. */
    internal data class AnnouncedServiceState(
        val discovered: String = "",
        val pending: String = "",
        val declined: String = "",
    )

    /**
     * Pure — touches no `prefs` — so it can be asserted directly, the same split
     * [vettedIssuer] and [issuerAfterSave] use for the issuer's own discovery.
     *
     * [announced] is [ServerCapabilities.routingBaseUrl] or `.geocoderBaseUrl`
     * from a probe that *parsed* — the caller is what decides a failed probe
     * changes nothing, same as [rememberServerFeatures].
     */
    internal fun nextAnnouncedServiceState(
        announced: String,
        apiBase: String,
        previous: AnnouncedServiceState,
    ): AnnouncedServiceState {
        val normalised = normalisedAddress(announced)
        // Blank means the deployment stopped announcing (or never did). Nothing
        // stale survives it — an accepted value from a server that has since
        // stood the service down must not go on being used silently.
        if (normalised.isBlank()) return AnnouncedServiceState()
        // Refused outright rather than stored anywhere: an unacceptable value
        // (plain HTTP, a malformed authority) is not a pending decision for the
        // rider to make, it is not a value at all.
        if (!Capabilities.acceptable(normalised)) return AnnouncedServiceState()
        // Already in effect: re-announcing the same value on the next probe
        // must not re-litigate a decision already made, and must clear a
        // pending prompt for a value this now supersedes.
        if (normalised == previous.discovered) return previous.copy(pending = "")
        // Same host as the API itself: a rider who already trusts the API host
        // — by having pointed the app at it, or by shipping with it baked in —
        // is trusting this by construction, so no separate prompt is owed for
        // the same host answering a second service.
        if (Capabilities.hostOf(normalised) != null &&
            Capabilities.hostOf(normalised) == Capabilities.hostOf(apiBase)
        ) {
            return AnnouncedServiceState(discovered = normalised)
        }
        // Already declined exactly this value: do not ask again until it
        // changes. Compared against the raw announcement, not a vetted read, so
        // a value that was acceptable when declined and is unchanged now still
        // matches.
        if (normalised == previous.declined) return previous
        // A genuinely new value to ask about. `declined` is dropped rather than
        // carried — it named a different, now-superseded announcement, and
        // keeping it around answers no future comparison (the next declined
        // value, if any, overwrites it when the rider actually says no to
        // *this* one).
        return AnnouncedServiceState(discovered = previous.discovered, pending = normalised)
    }

    private fun readAnnouncedServiceState(keys: AnnouncedServiceKeys): AnnouncedServiceState =
        AnnouncedServiceState(
            discovered = prefs(PREFS).string(keys.discovered),
            pending = prefs(PREFS).string(keys.pending),
            declined = prefs(PREFS).string(keys.declined),
        )

    private fun writeAnnouncedServiceState(keys: AnnouncedServiceKeys, state: AnnouncedServiceState) {
        prefs(PREFS).apply {
            put(keys.discovered, state.discovered)
            put(keys.pending, state.pending)
            put(keys.declined, state.declined)
        }
    }

    private fun clearAnnouncedService(keys: AnnouncedServiceKeys) =
        writeAnnouncedServiceState(keys, AnnouncedServiceState())

    private fun rememberAnnouncedService(keys: AnnouncedServiceKeys, announced: String, apiBase: String) {
        val next = nextAnnouncedServiceState(announced, apiBase, readAnnouncedServiceState(keys))
        writeAnnouncedServiceState(keys, next)
    }

    /** What is actually on disk for [keys], vetted on read the same way
     *  [discoveredIssuer] vets [storedIssuerRaw] — a value written under looser
     *  rules by an older build must not outlive the tightening just because it
     *  is sitting there unread. */
    private fun vettedAnnounced(keys: AnnouncedServiceKeys): String =
        prefs(PREFS).string(keys.discovered).takeIf { Capabilities.acceptable(it) } ?: ""

    /** Base of the announced GraphHopper instance this device has accepted —
     *  auto-accepted because its host matched the API's own, or accepted by the
     *  rider via [acceptRoutingAnnouncement] — or blank. Feeds [routingBase]. */
    internal fun discoveredRoutingBase(): String = vettedAnnounced(ROUTING_KEYS)

    /** Same as [discoveredRoutingBase], for Photon. Feeds [geocoderBase]. */
    internal fun discoveredGeocoderBase(): String = vettedAnnounced(GEOCODER_KEYS)

    /** Same as [discoveredRoutingBase], for the camera-data endpoint. Feeds [camerasBase]. */
    internal fun discoveredCamerasBase(): String = vettedAnnounced(CAMERAS_KEYS)

    /** Same as [discoveredRoutingBase], for the speed-limit-way endpoint. Feeds [speedLimitsBase]. */
    internal fun discoveredSpeedLimitsBase(): String = vettedAnnounced(SPEEDLIMITS_KEYS)

    /** Same as [discoveredRoutingBase], for the drivable-road endpoint. Feeds [roadsBase]. */
    internal fun discoveredRoadsBase(): String = vettedAnnounced(ROADS_KEYS)

    /** An announced routing base awaiting the rider's decision — differs from
     *  the API's own host, and has been neither accepted nor declined for this
     *  exact value — or null. What Settings shows the one-time prompt for. */
    fun pendingRoutingAnnouncement(): String? =
        prefs(PREFS).string(ROUTING_KEYS.pending).takeIf { it.isNotBlank() }

    /** Same as [pendingRoutingAnnouncement], for Photon. */
    fun pendingGeocoderAnnouncement(): String? =
        prefs(PREFS).string(GEOCODER_KEYS.pending).takeIf { it.isNotBlank() }

    fun acceptRoutingAnnouncement() = resolvePendingAnnouncement(ROUTING_KEYS, accept = true)

    fun declineRoutingAnnouncement() = resolvePendingAnnouncement(ROUTING_KEYS, accept = false)

    fun acceptGeocoderAnnouncement() = resolvePendingAnnouncement(GEOCODER_KEYS, accept = true)

    fun declineGeocoderAnnouncement() = resolvePendingAnnouncement(GEOCODER_KEYS, accept = false)

    /**
     * What accepting or declining [previous]'s pending value resolves to.
     *
     * Declining does not touch [AnnouncedServiceState.discovered]: a pending
     * value only ever appears *beside* an existing accepted one when the
     * deployment re-announces a different host before the rider has answered
     * the first prompt (see [nextAnnouncedServiceState]'s last branch), and
     * saying no to the new one must not silently stop using the old one — that
     * would be the exact stale-address failure #177 asks not to reintroduce,
     * just triggered by a decline instead of by a server outage.
     */
    internal fun resolvedAnnouncedServiceState(
        previous: AnnouncedServiceState,
        accept: Boolean,
    ): AnnouncedServiceState = if (accept)
        AnnouncedServiceState(discovered = previous.pending)
    else
        previous.copy(pending = "", declined = previous.pending)

    /** A no-op once nothing is pending, which is the ordinary case: a rider can
     *  reach the Settings row that calls this after the pending value has
     *  already resolved itself (accepted on a previous tap, superseded by a
     *  newer probe) without a stale button doing something unexpected. */
    private fun resolvePendingAnnouncement(keys: AnnouncedServiceKeys, accept: Boolean) {
        val previous = readAnnouncedServiceState(keys)
        if (previous.pending.isBlank()) return
        writeAnnouncedServiceState(keys, resolvedAnnouncedServiceState(previous, accept))
    }
}

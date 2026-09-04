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

    /** Base of the sync + social API, which serves everything under `/api`. */
    fun apiBase(custom: ServerConfig?): String =
        pick(custom?.apiUrl ?: "", custom?.url ?: "", BuildDefaults.apiUrl)

    /** Base of the GraphHopper instance, which serves `/route`. */
    fun routingBase(custom: ServerConfig?): String =
        pick(custom?.routingUrl ?: "", custom?.url ?: "", BuildDefaults.routingUrl)

    /** Base of the Photon instance, which serves `/api/?q=`. */
    fun geocoderBase(custom: ServerConfig?): String =
        pick(custom?.geocoderUrl ?: "", custom?.url ?: "", BuildDefaults.geocoderUrl)

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
     * The precedence order lives here so a test can assert it.
     *
     * [discovered] sits between the typed value and the baked one on purpose. A
     * rider who typed an address is overruling their server deliberately and
     * keeps winning; a rider who pointed at their own server should reach their
     * own realm rather than whichever one this build was compiled against.
     */
    internal fun issuer(custom: ServerConfig?, discovered: String): String =
        pick(custom?.idpIssuer ?: "", discovered, BuildDefaults.idpIssuer)

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
        if (serverChanged(config, previous)) prefs(PREFS).remove(KEY_DISCOVERED_ISSUER)

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
     * The one header every request identifies itself with. `internal` rather
     * than private because the capability probe needs it too.
     */
    internal fun userAgentHeaders(): Map<String, String> =
        mapOf("User-Agent" to "Detour/${BuildDefaults.versionName}")
}

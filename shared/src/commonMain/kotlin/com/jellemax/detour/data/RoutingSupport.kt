package com.jellemax.detour.data

import okio.IOException

/**
 * GraphHopper encoded values a request-time custom-model rule may read, as the
 * names the server's `graph.encoded_values` and its `/info` use. A rule naming
 * one the graph was not built with is an HTTP 400, which [RoutingClient] reads
 * as "no route" — so a client checks [RoutingSupport.encodedValues] before
 * sending one (#586).
 *
 * Plain vals rather than `const`, for the reason [ServerFeature] gives.
 */
@Suppress("MayBeConst") // plain vals on purpose: see above
object RoutingEncodedValue {
    val TOLL = "toll"
    val SURFACE = "surface"
    val ROAD_ENVIRONMENT = "road_environment"
}

/**
 * Which encoded values the rider's GraphHopper graph was actually built with.
 *
 * Asked of the server rather than assumed from `docker/prod`'s config.yml: a
 * stack that pulled the new config but still serves its old graph volume has
 * the old set, and only the server knows which graph it loaded.
 */
object RoutingSupport {

    /**
     * The encoded values in a GraphHopper `/info` body, or null when the body
     * is not one — an HTML error page, or a proxy answering `{}`. Null is "can't
     * tell", distinct from a graph that has none of them; a caller treats both
     * as unsupported.
     */
    internal fun encodedValues(infoBody: String): Set<String>? {
        val o = runCatching { jsonObjectOf(infoBody) }.getOrNull() ?: return null
        return o.optObject("encoded_values")?.keys
    }

    /**
     * [encodedValues] as the configured server reports it, or null when it
     * can't be read: no routing server, a network failure, or an `/info` that
     * isn't reachable — a proxy from before #586 forwards only `/route`.
     */
    @Throws(Exception::class)
    suspend fun encodedValues(config: ServerConfig): Set<String>? {
        val base = RoutingServer.routingBase(config).ifBlank { return null }
        val body = try {
            Http.get(base + "/info", RoutingServer.userAgentHeaders(), readTimeoutMs = 10_000)
        } catch (e: IOException) {
            return null
        }
        return encodedValues(body)
    }

    /**
     * What the configured routing server's graph had when last asked, or null
     * when it never answered — or answered for a routing address this install
     * no longer uses. Synchronous, from storage, because the routing calls
     * that need it ([Settings.routePreferences]) are synchronous too.
     */
    fun known(): Set<String>? = decode(
        prefs(PREFS).string(KEY_ENCODED_VALUES),
        RoutingServer.routingBase(RoutingServer.load()),
    )

    /** Whether [known] includes [value]; unknown reads as false. */
    fun supports(value: String): Boolean = has(known(), value)

    internal fun has(values: Set<String>?, value: String): Boolean = values?.contains(value) == true

    /**
     * Asks the configured routing server and stores the answer for [known].
     * A failed or unparseable probe leaves the last answer alone, the same
     * rule [RoutingServer.probeCapabilities] keeps: a rider who is offline
     * right now must not lose the avoid options their server had yesterday.
     */
    @Throws(Exception::class)
    suspend fun refresh() {
        val config = RoutingServer.load()
        val values = encodedValues(config) ?: return
        prefs(PREFS).put(KEY_ENCODED_VALUES, encode(RoutingServer.routingBase(config), values))
    }

    /**
     * The stored answer is keyed by the routing base it came from, so a
     * rider who points the app at another server reads null rather than the
     * old server's set — without every path that changes an address having to
     * remember to clear this.
     */
    internal fun encode(base: String, values: Set<String>): String =
        base + "\n" + values.sorted().joinToString(",")

    /** The inverse of [encode], or null for nothing stored, a blank [base] or another base. */
    internal fun decode(stored: String, base: String): Set<String>? {
        if (base.isBlank()) return null
        val storedBase = stored.substringBefore('\n', missingDelimiterValue = "")
        if (storedBase != base) return null
        return stored.substringAfter('\n').split(',').filter { it.isNotBlank() }.toSet()
    }

    private const val PREFS = "routing_support"
    private const val KEY_ENCODED_VALUES = "encoded_values"
}

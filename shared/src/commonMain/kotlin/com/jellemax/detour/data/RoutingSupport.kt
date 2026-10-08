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
}

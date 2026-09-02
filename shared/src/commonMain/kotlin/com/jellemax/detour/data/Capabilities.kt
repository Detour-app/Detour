package com.jellemax.detour.data

/**
 * What one deployment says it can do.
 *
 * [schema] and [features] are read even when this client does not recognise
 * their values, because the server's own rule is that unknown is ignored rather
 * than refused — see `docs/BACKEND_SPEC.md` §15.5. That is what lets an app
 * older than the server it is pointed at keep working.
 */
internal data class ServerCapabilities(
    val schema: Int,
    val features: List<String>,
    /** Blank when the server has the endpoint but names no realm. */
    val idpIssuer: String,
)

/**
 * Asking a deployment which realm to sign in against, instead of the rider
 * typing an address their server already knows.
 *
 * The split here is forced and worth stating: [parse], [acceptable] and
 * [preferredDiscovered] are pure and covered by `CapabilitiesTest`, while the
 * fetch that feeds them is not covered at all. `Http`'s client is private with
 * no injection seam and this source set has no `MockEngine` — see the same note
 * on `AuthRetry.kt`. So every decision lives in a function that takes its
 * inputs as arguments, and the I/O is a thin wrapper with no judgement in it.
 */
internal object Capabilities {

    /**
     * Reads a capability document, or null when the body is not one.
     *
     * Null covers two real cases that must not be confused with a document
     * naming no realm: an access gateway's HTML sign-in page, and a proxy
     * answering `{}`. Both would otherwise read as "the server told us the
     * realm is blank", which is a different fact with a different message.
     */
    fun parse(body: String): ServerCapabilities? {
        val o = runCatching { jsonObjectOf(body) }.getOrNull() ?: return null
        // Absent or zero means this is not a capability document. A real one
        // always names its schema, and the server never emits 0.
        val schema = o.optInt("schema")
        if (schema < 1) return null
        val features = (o.optArray("features") ?: JsonArrayEmpty)
            .let { a -> a.indices.map { a.optString(it) } }
        return ServerCapabilities(
            schema = schema,
            features = features,
            // Normalised exactly as RoutingServer.pick normalises a typed
            // address. Without this a discovered issuer and the identical typed
            // one compare unequal, and the ID token's `iss` — which carries no
            // trailing slash — would refuse a sign-in that is correct.
            idpIssuer = o.optObject("idp")?.optString("issuer").orEmpty().trim().trimEnd('/'),
        )
    }

    /**
     * Whether a discovered issuer may be used at all.
     *
     * HTTPS or nothing: this string becomes the page a rider types their
     * password into, and over plain HTTP the realm's signing keys can be
     * swapped in transit — which is the same thing `IdpSettings`'
     * `RequireHttpsMetadata` says on the server side.
     *
     * Loopback is the one carve-out, because OAuth guidance makes it for native
     * clients and because `BuildDefaults.idpIssuer` documents
     * `http://localhost:7580/realms/detour` as the dev value. Matched on the
     * host, not as a substring: `http://localhost.evil.example` is not loopback.
     */
    fun acceptable(issuer: String): Boolean {
        if (issuer.startsWith("https://")) return true
        val host = issuer.removePrefix("http://").substringBefore('/').substringBefore(':')
        return issuer.startsWith("http://") && (host == "localhost" || host == "127.0.0.1")
    }

    /**
     * Which discovered issuer to use: the one just fetched, or the one kept
     * from the last successful probe.
     *
     * The stored value is not a cache in front of the fetch — the fetch always
     * runs at an interactive sign-in. It is what [Auth.refresh] falls back to on
     * a cold start with no network, and what carries a probe that failed. An
     * unacceptable fetched value loses to a good stored one, so a server that
     * starts answering with a plain-HTTP realm cannot downgrade a rider who
     * already had an HTTPS one.
     */
    fun preferredDiscovered(fetched: String, stored: String): String =
        if (fetched.isNotBlank() && acceptable(fetched)) fetched else stored
}

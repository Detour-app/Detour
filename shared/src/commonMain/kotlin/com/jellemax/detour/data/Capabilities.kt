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
     * answering `{}`. The type keeps them distinct — null here, an empty
     * `idpIssuer` there — though no consumer reads that distinction yet.
     */
    fun parse(body: String): ServerCapabilities? {
        val o = runCatching { jsonObjectOf(body) }.getOrNull() ?: return null
        // Absent, zero, negative, or not an integer all read back as optInt's
        // 0 default, and none of them is a capability document. A real one
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
     * password into and the token endpoint the authorization code is sent to,
     * and over plain HTTP the realm's signing keys can be swapped in transit —
     * which is what `IdpSettings.RequireHttpsMetadata` says on the server side.
     *
     * This is the *only* substantive control on a server-supplied issuer. The
     * ID-token `iss` check downstream cannot stand in for it: that compares
     * `iss` against this very value, so a hostile realm that echoes what it
     * advertised passes. Anything this function accepts is trusted from here on.
     */
    fun acceptable(issuer: String): Boolean {
        val scheme = when {
            issuer.startsWith("https://") -> "https://"
            issuer.startsWith("http://") -> "http://"
            // Case-sensitive, so `HTTPS://` is refused. Fail-closed and
            // deliberate: a realm whose issuer is spelled that way already
            // fails the backend's own exact `iss` comparison.
            else -> return false
        }
        val authority = issuer.removePrefix(scheme).substringBefore('/')
        // Userinfo is what turns a host check into a host *prefix* check:
        // `localhost:8080@evil.example` has userinfo `localhost:8080` and host
        // `evil.example`, so truncating at the first colon reads an attacker's
        // credentials as the hostname. No OIDC issuer identifier carries
        // userinfo, so refusing the shape outright is both correct and simpler
        // than parsing it properly.
        if ('@' in authority) return false
        // A prefix check would accept a bare `https://`, which reaches
        // Auth.endpoint() as `https:///protocol/...` and fails as a malformed
        // URL instead of as "no realm advertised".
        if (authority.any { it.isWhitespace() || it.isISOControl() }) return false
        val host = authority.substringBefore(':')
        if (host.isEmpty()) return false
        // Loopback over cleartext is the one carve-out, and the reason is that
        // the traffic never leaves the device, so there is no on-path attacker
        // to defend against. `BuildDefaults.idpIssuer` documents
        // http://localhost:7580/realms/detour as the dev value.
        //
        // Deliberately narrower than the full loopback set: `[::1]`,
        // `127.0.0.2`, `127.1` and the integer-collapsed forms are all refused.
        // That is the safe direction, and widening it needs a reason better
        // than symmetry.
        return scheme == "https://" || host == "localhost" || host == "127.0.0.1"
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
    fun preferredDiscovered(fetched: String, stored: String): String = when {
        fetched.isNotBlank() && acceptable(fetched) -> fetched
        // Vetted again on the way out, not only on the way in. The store is
        // written by one caller today and only ever with a value that passed
        // this check, but nothing on the Auth.refresh() path re-vets it and the
        // store survives until the API address changes — so a value written
        // under looser rules would otherwise outlive the tightening.
        stored.isNotBlank() && acceptable(stored) -> stored
        else -> ""
    }
}

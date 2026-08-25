package com.jellemax.detour.data

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ktor.http.parametersOf
import io.ktor.http.formUrlEncode
import okio.ByteString.Companion.decodeBase64

/**
 * The rider's session, which is now a Keycloak session.
 *
 * The backend no longer has a sign-in endpoint: it validates tokens the
 * identity provider minted and provisions a local account the first time it
 * sees a subject (see the API's CurrentUser). So everything that used to be
 * `/auth/login`, `/auth/register` and `/auth/reset` lives in the realm, and
 * what is left on this side is holding the tokens and keeping the access token
 * fresh.
 *
 * The authorization-code leg is platform code — it needs a browser and a
 * SHA-256 — and lives in `app/auth/Oidc.kt`. Everything after the redirect is
 * plain HTTP and lives here, shared, because refreshing has one rule that must
 * not be got wrong twice.
 */
object Auth {

    /** The realm's own client for native apps: public, PKCE, no secret to leak. */
    const val CLIENT_ID = "detour-app"

    /** Registered on `detour-app` in the realm. Changing one without the other
     *  turns every sign-in into `invalid_redirect_uri`. */
    const val REDIRECT_URI = "detour://auth/callback"

    /** Refresh this far before the access token actually expires, so a request
     *  that takes a moment to reach the server does not arrive expired. */
    private const val REFRESH_SKEW_MS = 60_000L

    /**
     * Serialises refreshes. The realm sets `revokeRefreshToken` with
     * `refreshTokenMaxReuse: 0`, which makes a refresh token single-use: two
     * requests refreshing concurrently would both present the same token, and
     * the second one presenting it is exactly what Keycloak treats as a replay
     * — it kills the whole session. So refreshing is one-at-a-time, and the
     * losers re-read what the winner stored.
     */
    private val refreshLock = Mutex()

    val username: StateFlow<String> = Settings.authUsername

    /** A session exists on this device. Whether it is still live is only
     *  knowable by using it, which is what [bearer] does. */
    val signedIn: Boolean get() = Settings.refreshToken.value.isNotBlank()

    private fun endpoint(name: String): String {
        // Read through RoutingServer rather than BuildDefaults directly: a build
        // published to a store ships no baked issuer, so the rider's own value is
        // the only one there will ever be.
        val issuer = RoutingServer.issuer(RoutingServer.loadCustom())
        if (issuer.isBlank()) throw AuthException("No identity provider configured")
        return "$issuer/protocol/openid-connect/$name"
    }

    /**
     * An access token good for the next [REFRESH_SKEW_MS], refreshing first if
     * the stored one is not.
     */
    suspend fun bearer(): String {
        if (!signedIn) throw AuthException("Sign in to sync")
        if (!expiringSoon()) return Settings.accessToken.value

        refreshLock.withLock {
            // Someone else may have refreshed while this call waited.
            if (!expiringSoon()) return Settings.accessToken.value
            return refresh()
        }
    }

    private fun expiringSoon(): Boolean =
        Settings.accessToken.value.isBlank() ||
            Settings.accessTokenExpiresAtMs.value - REFRESH_SKEW_MS <= nowMs()

    /**
     * Trades the authorization code for tokens. Called once per sign-in, by the
     * platform code that owns the browser leg; [verifier] is the PKCE secret
     * that never left the device, and is what makes an intercepted code useless.
     */
    suspend fun exchangeCode(code: String, verifier: String) {
        val response = try {
            post("token", mapOf(
                "grant_type" to "authorization_code",
                "client_id" to CLIENT_ID,
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
                "code_verifier" to verifier,
            ))
        } catch (e: HttpStatusException) {
            // The body is the only thing that says *why*, and this is the one leg
            // of sign-in with no UI of its own — the browser has already closed by
            // the time it runs. Letting HttpStatusException through surfaces
            // "HTTP 401", which reads identically whether the realm refused the
            // client, refused the code, or was never reached at all.
            throw AuthException(tokenFailureMessage(e.code, e.body))
        }
        store(response)
    }

    /**
     * What a refused token request actually said, in a sentence a rider can act
     * on and a maintainer can search for.
     *
     * Two kinds of body arrive here and they are not treated alike. An OAuth
     * error response is JSON carrying `error` and `error_description`
     * (RFC 6749 §5.2) — the realm describing its own refusal, and safe to repeat
     * back verbatim. Anything else is somebody else's output: an access
     * gateway's sign-in page is the common case, and it is HTML of unbounded
     * length that would otherwise land in a snackbar. So a recognised body is
     * quoted and an unrecognised one is only classified, which is ASVS 5.0.0
     * V16.5.1 read from the client side.
     *
     * Same shape as [Api]'s own `errorMessage`, deliberately not the same
     * function: that one reads RFC 7807 `detail`/`title`, and against an OAuth
     * body it returns null and falls back to "HTTP 401" — which is the bug this
     * exists to remove.
     *
     * `internal` rather than private because the test for it is the point: these
     * strings are the whole diagnosis, so they get asserted rather than eyeballed.
     */
    internal fun tokenFailureMessage(code: Int, body: String): String {
        if (body.isBlank()) return "The identity provider returned HTTP $code with no body"

        val json = runCatching { jsonObjectOf(body) }.getOrNull()
            ?: return "The identity provider returned HTTP $code and the body was " +
                "not JSON — something in front of the realm answered instead of " +
                "the realm itself"

        val error = json.optString("error")
        val description = json.optString("error_description")
        if (description.isNotBlank()) {
            return if (error.isBlank()) description else "$description ($error)"
        }
        if (error.isBlank()) {
            return "The identity provider returned HTTP $code and a body with no " +
                "OAuth error in it"
        }
        return explainOAuthError(error)
    }

    /**
     * The bare error codes worth translating, because each names something
     * specific misconfigured at the realm and none of them says so on its own.
     *
     * `invalid_client` earns its place first: it is the failure that looks most
     * like success. The browser leg completes, the rider authenticates, the
     * redirect lands — and only then is the token call refused, because a
     * confidential client wants a secret this app deliberately does not ship.
     */
    private fun explainOAuthError(error: String): String = when (error) {
        "invalid_grant" ->
            "The authorization code was refused (invalid_grant) — it expired, it " +
                "was already spent, or the redirect URI does not match the one " +
                "registered on $CLIENT_ID"
        "invalid_client" ->
            "The realm did not accept the client (invalid_client) — $CLIENT_ID is " +
                "most likely registered as confidential, and a client that needs " +
                "a secret cannot be driven from an app that ships none"
        "unauthorized_client" ->
            "The client may not use this grant (unauthorized_client) — check that " +
                "the standard flow is enabled on $CLIENT_ID"
        "unsupported_grant_type" ->
            "The realm does not offer the authorization-code grant " +
                "(unsupported_grant_type)"
        "invalid_request" ->
            "The realm rejected the request as malformed (invalid_request)"
        "invalid_scope" ->
            "The realm refused the requested scopes (invalid_scope) — openid, " +
                "profile and email must all be allowed on $CLIENT_ID"
        else -> "The identity provider refused the sign-in ($error)"
    }

    /**
     * Ends the session at the identity provider as well as on this device. A
     * failing revoke must not strand the user signed in on a device they are
     * trying to sign out of, so the local clear happens either way — the same
     * rule the old `/auth/logout` call followed.
     */
    suspend fun signOut() {
        val refreshToken = Settings.refreshToken.value
        try {
            if (refreshToken.isNotBlank()) {
                post("logout", mapOf(
                    "client_id" to CLIENT_ID,
                    "refresh_token" to refreshToken,
                ))
            }
        } catch (e: Exception) {
            // Offline, or the session was already gone. Clearing is what matters.
        }
        clear()
    }

    /** Forgets the session without telling the provider — for the one case where
     *  the provider has already told *us* the session is gone. Clears the handle
     *  as well: it is what the screens read to decide whether anyone is signed
     *  in, and a name left behind is a screen that thinks someone is. */
    fun clear() {
        Settings.setSession("", "", 0L, "")
    }

    private suspend fun refresh(): String {
        val response = try {
            post("token", mapOf(
                "grant_type" to "refresh_token",
                "client_id" to CLIENT_ID,
                "refresh_token" to Settings.refreshToken.value,
            ))
        } catch (e: HttpStatusException) {
            // 400 invalid_grant: expired past the 90-day idle horizon, revoked,
            // or replayed. All three mean this device has no session any more,
            // and holding on to a dead refresh token only produces the same
            // failure on every later request.
            if (e.code == 400 || e.code == 401) {
                clear()
                throw AuthException("Session expired — sign in again")
            }
            // Anything else is the realm or whatever sits in front of it, and the
            // session is still good. Same translation as the sign-in leg gets:
            // rethrowing `e` here surfaced "HTTP 502" and nothing about which of
            // the two answered.
            throw AuthException(tokenFailureMessage(e.code, e.body))
        }
        store(response)
        return Settings.accessToken.value
    }

    private suspend fun post(name: String, form: Map<String, String>): String =
        Http.request(
            method = "POST",
            url = endpoint(name),
            body = parametersOf(form.mapValues { listOf(it.value) }).formUrlEncode(),
            contentType = Http.FORM_URLENCODED,
        )

    private fun store(tokenResponse: String) {
        val o = jsonObjectOf(tokenResponse)
        val access = o.optString("access_token")
        if (access.isBlank()) throw AuthException("The identity provider returned no token")

        Settings.setSession(
            accessToken = access,
            // Absent on a client configured without refresh tokens: keep the one
            // we have rather than silently downgrading the session to 15 minutes.
            refreshToken = o.optString("refresh_token").ifBlank { Settings.refreshToken.value },
            expiresAtMs = nowMs() + o.optLong("expires_in", 0L) * 1000L,
            username = usernameFrom(access).ifBlank { Settings.authUsername.value },
        )
    }

    /**
     * The handle the realm issued, read out of the token rather than asked for
     * over a second request. It is also what the backend provisions the local
     * account under, so the two cannot disagree.
     *
     * Signature verification is deliberately absent: this token came from the
     * provider over TLS and is only being read for a label. The party that has
     * to verify it is the API, which does.
     */
    internal fun usernameFrom(accessToken: String): String {
        val payload = accessToken.split(".").getOrNull(1) ?: return ""
        val json = payload.decodeBase64()?.utf8() ?: return ""
        return runCatching { jsonObjectOf(json).optString("preferred_username") }.getOrDefault("")
    }
}

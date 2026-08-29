package com.jellemax.detour.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * The browser leg — opening the realm's page, and drawing the CSPRNG bytes a
 * fresh verifier needs — is the only part that stays platform code, in
 * `app/auth/AuthBrowser.kt` and `iosApp/Detour/SignIn.swift`. The rest of the
 * authorization-code-with-PKCE flow, SHA-256 included, is shared in `Oidc.kt`
 * in this same module. Everything after the redirect — exchanging the code,
 * keeping the access token fresh — is plain HTTP and lives here, because
 * refreshing has one rule that must not be got wrong twice.
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

    /**
     * Bumped every time a session starts as well as every time [clear] ends
     * one — [store] bumps it when [establishesSession][store] is true, which
     * is [exchangeCode]'s call and not [refresh]'s (see [store]'s own doc for
     * why a routine access-token refresh must not count). [FriendsStore.reload],
     * its two siblings, [FriendsStore.refreshOwn] and [FriendFog.refresh] each
     * capture this at the start of an action and check it again before
     * committing a result that took a round trip to produce — if it has moved
     * on, the session that started the action is not the one this store holds
     * any more, whether because it signed out, was 401'd, switched identity
     * providers, signed back in as the same rider, or — the reason the
     * establish side of this exists — a different rider signed in while the
     * action was still running. (A server switch that keeps the same issuer
     * does *not* bump this — [RoutingServer.save] only calls [clear] on an
     * issuer change, see its own doc — but that is still the same rider, so
     * it is not a case this guard needs to catch.)
     *
     * That last case is what a bump on [clear] alone cannot catch: without it,
     * one epoch value spans "rider A signed out" through the whole of rider
     * B's session, so a write started in the gap between them (e.g.
     * [FriendsStore.refreshOwn], which has no [signedIn] guard of its own —
     * see its doc) reads as current for the whole of B's session once it
     * begins, not just for the instant it was actually valid in.
     *
     * It is also why this is a counter and not [username]: two round trips
     * for the same handle are not guaranteed to land in request order, so
     * equality on the username alone would miss a sign-out-then-sign-in-as-
     * yourself, and would not catch a blank-to-blank transition through no
     * session at all either.
     */
    private val _sessionEpoch = MutableStateFlow(0)
    internal val sessionEpoch: StateFlow<Int> = _sessionEpoch.asStateFlow()

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
     *
     * `@Throws` because Swift calls this now: `ConvoyLiveClient.swift` hands
     * it to `ConvoyRelay.run` as the bearer supplier. Without the annotation a
     * Kotlin/Native `suspend` function propagates only `CancellationException`
     * and every other exception terminates the process — so an expired refresh
     * token, a realm that refuses, or an unconfigured issuer would kill the app
     * rather than surface as `lastError`, and a Swift `try?` cannot catch it
     * because the abort happens on this side of the bridge. See [SyncClient]'s
     * own note for the rule, and `docs/IOS_PORT.md` for why the earlier sweep
     * missed this one: it annotated what Swift called *at the time*, and
     * nothing called `bearer` from Swift until the relay did.
     */
    @Throws(Exception::class)
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
        store(response, establishesSession = true)
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
            // Offline, or the session was already gone. Clearing is what
            // matters — deliberately including a CancellationException here
            // rather than the `catch (e: CancellationException) { throw e }`
            // this module otherwise always leads with: if the coroutine
            // running this call is itself cancelled mid-revoke, `clear()`
            // below must still run, or a rider who tapped "Sign out" and
            // immediately navigated away would stay signed in on this
            // device with nothing left to reset the local state.
        }
        clear()
    }

    /** Forgets the session without telling the provider — for the one case where
     *  the provider has already told *us* the session is gone. Clears the handle
     *  as well: it is what the screens read to decide whether anyone is signed
     *  in, and a name left behind is a screen that thinks someone is.
     *
     *  Also resets [FriendsStore], [ConvoysStore], [CirclesStore] and
     *  [FriendFog] — every singleton in this module that caches another
     *  rider's data with no reset path of its own. They are per-account state
     *  living in objects with no lifecycle: nothing tears one down when a
     *  session ends, so without this, the next sign-in on the same device
     *  renders the previous rider's friend list, leaderboard, circle places
     *  and arrival/departure events, and shared fog-of-war traces — behind a
     *  spinner or an error banner, since every one of those screens shows
     *  last-known-good data while it reloads (see [FriendsState]'s own doc).
     *  This lives here, once, rather than in each screen's own effect,
     *  because [clear] is called from three places — [signOut], a 401 in
     *  [Api], and a server switch in `RoutingServer.save` — and a screen
     *  that forgets to reset on any one of them is a screen that leaks
     *  another rider's data. */
    fun clear() {
        // Bumped before the write, the same discipline [store] uses for the
        // establish side of this same guard (see its own doc) — kept
        // consistent rather than opposite disciplines on the two sides of
        // one mechanism.
        _sessionEpoch.update { it + 1 }
        Settings.setSession("", "", 0L, "", "")
        FriendsStore.reset()
        ConvoysStore.reset()
        CirclesStore.reset()
        FriendFog.clear()
        AccountScope.clear()
        resetAccountScopedStores()
    }

    /** The stores that hold a rider's file contents in memory. Everything else
     *  reads through to the file on every call, so moving the directory is all
     *  those need.
     *
     *  `internal` rather than private so a test can call it directly, the same
     *  shortcut [com.jellemax.detour.drive.ConvoyRelay.clearMembershipForSessionChange]
     *  and [CirclePresence.discardEvaluatorsIfSessionChanged] exist for: actually
     *  moving a session means writing [Settings], which needs platform prefs this
     *  module's test target does not have. Without the seam this function — the
     *  whole point of the task — has no coverage at all, which is the exact gap a
     *  review found in the circle-presence slice. */
    internal fun resetAccountScopedStores() {
        SavedPlaces.reset()
        RouteStore.reset()
        MunicipalityStore.reset()
        TraceStore.reset()
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
        store(response, establishesSession = false)
        return Settings.accessToken.value
    }

    private suspend fun post(name: String, form: Map<String, String>): String =
        Http.request(
            method = "POST",
            url = endpoint(name),
            body = parametersOf(form.mapValues { listOf(it.value) }).formUrlEncode(),
            contentType = Http.FORM_URLENCODED,
        )

    /**
     * Writes [tokenResponse] into [Settings], bumping [sessionEpoch] first
     * when [establishesSession] is true.
     *
     * [exchangeCode] passes true: that call is a brand-new session by
     * definition, the one place this device goes from "no session" (or
     * someone else's) to a rider's own. [refresh] passes false: refreshing
     * the access token continues the *same* session — [bearer] only calls it
     * while [signedIn] was already true — and bumping the epoch there would
     * discard a store action that legitimately spans a background refresh
     * (a reload in flight when the 15-minute access token happens to expire)
     * even though nothing about the session actually changed. See
     * [sessionEpoch]'s own doc for why the establish side still has to exist
     * despite that.
     *
     * `internal` rather than private because the test for it is the point,
     * the same reason [tokenFailureMessage] is: proving the epoch bumps (or
     * doesn't) needs to call this directly, since `Settings.init()` — real
     * platform prefs — never runs in this module's test target (see
     * `RouteStoreLoadOrderTest`'s doc for the same constraint, which this
     * reuses: the write into [Settings] below throws for lack of a Context
     * before it can do anything, and by then the bump above has already
     * happened).
     */
    internal fun store(tokenResponse: String, establishesSession: Boolean) {
        val o = jsonObjectOf(tokenResponse)
        val access = o.optString("access_token")
        if (access.isBlank()) throw AuthException("The identity provider returned no token")

        if (establishesSession) _sessionEpoch.update { it + 1 }

        val username = usernameFrom(access).ifBlank { Settings.authUsername.value }
        val scopeKey = AccountScope.keyFrom(subject = subjectFrom(access), username = username)
        Settings.setSession(
            accessToken = access,
            // Absent on a client configured without refresh tokens: keep the one
            // we have rather than silently downgrading the session to 15 minutes.
            refreshToken = o.optString("refresh_token").ifBlank { Settings.refreshToken.value },
            expiresAtMs = nowMs() + o.optLong("expires_in", 0L) * 1000L,
            username = username,
            scopeKey = scopeKey,
        )

        if (establishesSession) {
            // Adoption before the scope moves: adopt() reads the anonymous
            // bucket, and pointing accountFile() at the new key first would
            // leave a fresh empty directory beside the data it was supposed
            // to claim.
            AccountFiles.adopt(fileSystem, appFilesDir(), scopeKey)
            AccountScope.set(scopeKey)
            resetAccountScopedStores()
        }
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

    /**
     * The provider's stable identifier for this rider, read out of the same
     * token payload [usernameFrom] reads. Used to name the on-disk bucket
     * their files live in, which is why `sub` is preferred over the handle:
     * a rider who renames themselves must not lose their history.
     *
     * Signature verification is deliberately absent for the same reason it is
     * in [usernameFrom] — this token arrived from the provider over TLS and
     * is being read for a label. The API is the party that has to verify it,
     * and does.
     */
    internal fun subjectFrom(accessToken: String): String {
        val payload = accessToken.split(".").getOrNull(1) ?: return ""
        val json = payload.decodeBase64()?.utf8() ?: return ""
        return runCatching { jsonObjectOf(json).optString("sub") }.getOrDefault("")
    }
}

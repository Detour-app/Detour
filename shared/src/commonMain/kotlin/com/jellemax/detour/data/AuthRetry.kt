package com.jellemax.detour.data

/**
 * What to do when a request comes back `401`.
 *
 * The rule this exists to enforce: **a resource server never gets to end the
 * session.** Only the authorization server does, which is what [Auth.clear]'s
 * own contract says — "for the one case where the provider has already told
 * *us* the session is gone".
 *
 * ## Why the error code cannot decide this
 *
 * The obvious fix is to read `WWW-Authenticate` and clear only on
 * `error="invalid_token"`. It does not work. RFC 6750 §3.1 defines
 * `invalid_token` as covering a token that is "expired, revoked, malformed, or
 * invalid for other reasons", and an audience mismatch is one of those other
 * reasons — ASP.NET Core sends exactly `error="invalid_token"` for it. A revoked
 * session and a misconfigured `ValidAudience` arrive as the same three words, so
 * no amount of parsing separates them.
 *
 * What does separate them is asking the provider. If the refresh token still
 * works, the session is alive and this server is refusing the token for its own
 * reasons — an audience it does not accept, an issuer it does not trust, a scope
 * the endpoint wants. If the refresh token does not work, the provider has said
 * the session is over, and [Auth.refresh] clears it as it always has.
 *
 * That also fixes a second case for free: an access token that genuinely expired
 * in flight, which the old code could only turn into a lost session.
 *
 * ## Why this is a function taking lambdas
 *
 * `Http`'s client is private with no injection seam and there is no `MockEngine`
 * anywhere in `commonTest`, so a retry written inline in [Api] could not be
 * tested without first inventing an HTTP seam — a much larger change than the
 * bug. Taking [refresh] and [call] as parameters makes the *decision* testable
 * with plain fakes and no network, which is the part that was wrong.
 */
internal suspend fun <T> retryingRefusedAuth(
    /** False for the unauthenticated calls; there is no session to interrogate. */
    auth: Boolean,
    /**
     * Forces a token refresh. True when the token changed and a retry is worth
     * making. Throws when the provider refuses — see [Auth.refreshAfterRefusal].
     *
     * Takes no argument on purpose. The token that was refused is whatever the
     * last [call] actually sent, which only [call] knows — an earlier draft
     * passed it in as a parameter captured before the first attempt, and
     * `Auth.bearer()` can refresh between the two, making the captured value
     * the wrong one.
     */
    refresh: suspend () -> Boolean,
    /** Performs the request. Called at most twice. */
    call: suspend () -> T,
): T {
    try {
        return call()
    } catch (e: HttpStatusException) {
        if (e.code != 401 || !auth) throw e
        // A refusal we cannot interrogate. Keeping the session is the cheap
        // error — one more failed request — and clearing it is the expensive
        // one, because Auth.clear also drops FriendsStore, ConvoysStore,
        // CirclesStore and FriendFog along with the login.
        if (!refresh()) throw e
    }
    // Second and last attempt. A 401 here is this server refusing a token the
    // provider just vouched for, so it is a statement about the server's
    // configuration and not about the session. It propagates as an
    // HttpStatusException and the session stays.
    return call()
}

/**
 * What the app decided a `401` meant, as something a rider can quote.
 *
 * These identifiers exist because the decision is invisible otherwise. The bug
 * this replaced (#67) was undiagnosable precisely because the app made a
 * consequential choice — destroy the session — and said nothing about it or why,
 * so a two-hour diagnosis went looking at the sign-in flow, which was correct
 * throughout. A rider who can say "it told me AUTH-401-REFUSED" has handed the
 * maintainer the branch that ran.
 *
 * They ride the channel this codebase already uses: an exception's message
 * reaches the rider verbatim as `lastError` (`RelaySocket.kt:38`), so appending
 * the identifier to the message is the whole of the plumbing. This is the first
 * such identifier in the codebase; if a second family is added later, keep the
 * `AREA-CONDITION-OUTCOME` shape so they stay greppable.
 *
 * They are part of the app's contract with its users the moment one appears in a
 * bug report, so treat them as append-only: add a code, never quietly repurpose
 * one.
 */
internal object AuthRefusal {

    /**
     * The provider still vouches for the session, and this server refused the
     * token anyway. An audience the API does not accept, an issuer it does not
     * trust, a scope the endpoint wants. The session is kept, and the rider's
     * next step is the *server's* configuration rather than signing in again.
     */
    const val SERVER_REFUSED = "AUTH-401-REFUSED"

    /**
     * A `401` with no session to interrogate — an unauthenticated call, or a
     * signed-out app. Nothing was refreshed and nothing was cleared.
     */
    const val NO_SESSION = "AUTH-401-ANON"

    /**
     * The provider rejected the refresh token, so the session really is over and
     * [Auth.clear] ran. The one case [Auth.clear] is documented for.
     */
    const val SESSION_ENDED = "AUTH-401-ENDED"

    /**
     * The rider-facing message for a refusal, identifier last.
     *
     * The server's own `problem+json` `detail`/`title` leads, because it is the
     * part that says what is actually wrong; the identifier follows in brackets
     * so it survives being read aloud or pasted into an issue.
     */
    fun message(serverMessage: String, code: String): String = "$serverMessage [$code]"
}

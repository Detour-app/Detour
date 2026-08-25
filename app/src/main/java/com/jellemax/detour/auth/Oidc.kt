package com.jellemax.detour.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.jellemax.detour.data.Auth
import com.jellemax.detour.data.RoutingServer
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/**
 * The browser half of signing in: authorization code with PKCE, in a Custom Tab.
 *
 * This is the part that cannot live in the shared core — it needs a browser and
 * a SHA-256. Everything from the redirect onwards (exchanging the code,
 * refreshing, signing out) is plain HTTP and lives in [Auth].
 *
 * The flow: [start] parks a fresh verifier and opens the realm's login page;
 * the realm redirects to `detour://auth/callback?code=…&state=…`, which the
 * manifest points at MainActivity; MainActivity hands the URI to [complete],
 * which checks the state and trades the code for tokens.
 *
 * The verifier is kept in memory only. A sign-in that does not finish before the
 * process dies is a sign-in the user has to start again — which is the correct
 * outcome, and cheaper than persisting a secret to make an edge case smoother.
 */
object Oidc {

    /** The URI the realm redirects back to. Registered on the client there and
     *  declared in the manifest here; the two have to agree exactly. */
    private val REDIRECT_URI = Auth.REDIRECT_URI

    private var pendingVerifier: String? = null
    private var pendingState: String? = null

    /** Whether signing in is possible at all — false when no realm is
     *  configured, which is how a build with no secrets behaves until the rider
     *  sets one under Settings. Resolved rather than read off BuildDefaults: a
     *  store build ships no baked issuer, so the saved one is the only one. */
    val configured: Boolean get() = issuer().isNotBlank()

    private fun issuer(): String = RoutingServer.issuer(RoutingServer.loadCustom())

    /**
     * Opens the realm's login page. Returns false when there is no realm
     * configured or no browser to open it in, so the caller can say so instead
     * of leaving a button that does nothing.
     */
    fun start(context: Context): Boolean {
        if (!configured) return false

        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(16)
        pendingVerifier = verifier
        pendingState = state

        val authorize = Uri.parse("${issuer()}/protocol/openid-connect/auth")
            .buildUpon()
            .appendQueryParameter("client_id", Auth.CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "openid profile email")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challengeFor(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        return try {
            CustomTabsIntent.Builder().build().launchUrl(context, authorize)
            true
        } catch (e: ActivityNotFoundException) {
            // No browser at all: nothing here can substitute for one, and a
            // WebView deliberately is not an option.
            pendingVerifier = null
            pendingState = null
            false
        }
    }

    /** True when [uri] is the redirect this flow is waiting for. */
    fun isCallback(uri: Uri?): Boolean =
        uri != null && "$uri".startsWith(REDIRECT_URI)

    /**
     * Finishes the flow: verifies the state, then exchanges the code.
     *
     * Throws on anything that is not a completed sign-in — the realm reporting
     * an error, a state that does not match the request this device started, or
     * the token exchange failing — so the caller shows one message either way.
     */
    suspend fun complete(uri: Uri) {
        val verifier = pendingVerifier
        val expectedState = pendingState
        pendingVerifier = null
        pendingState = null

        uri.getQueryParameter("error")?.let { error ->
            // The code in parentheses is what a realm's own logs and docs call
            // this, so it is worth keeping even when a description is present.
            val described = uri.getQueryParameter("error_description")
            throw IllegalStateException(
                if (described.isNullOrBlank()) "The realm refused the sign-in ($error)"
                else "$described ($error)"
            )
        }
        if (verifier == null || expectedState == null) {
            // Not "nothing is in progress" — something plainly is, the rider just
            // came back from it. The verifier is held in memory on purpose (see
            // this file's header, and ASVS 5.0.0 V10.1.2: it has to stay bound to
            // the transaction and the user agent that started it), so the honest
            // reading of its absence is that this process is not the one that
            // started the sign-in. Android restarting the app behind the browser
            // is by far the likeliest way that happens, and the old wording sent
            // people looking for a broken realm instead.
            throw IllegalStateException(
                "The app restarted while the browser was open, so this sign-in " +
                    "could not be finished. Tap Sign in to start again."
            )
        }
        // A callback whose state is not the one we sent did not come from the
        // request we made, so the code in it is not ours to spend.
        if (uri.getQueryParameter("state") != expectedState) {
            throw IllegalStateException("Sign-in could not be verified — start again")
        }
        val code = uri.getQueryParameter("code")
            ?: throw IllegalStateException("The identity provider returned no code")

        Auth.exchangeCode(code, verifier)
    }

    /** 64 bytes of entropy, base64url — comfortably inside RFC 7636's 43..128
     *  character range for a verifier, and the same generator for the state. */
    private fun randomUrlSafe(bytes: Int): String =
        urlSafe(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

    private fun challengeFor(verifier: String): String =
        urlSafe(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    private fun urlSafe(raw: ByteArray): String =
        Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}

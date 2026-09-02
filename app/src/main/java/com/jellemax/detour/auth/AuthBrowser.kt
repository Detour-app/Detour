package com.jellemax.detour.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.jellemax.detour.data.Oidc
import java.security.SecureRandom
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The browser half of signing in: opens the realm's login page in a Custom Tab.
 *
 * This is all that cannot live in the shared core — a browser, and a CSPRNG.
 * The flow itself (the authorize URL, PKCE, the state check, spending the code)
 * is in [Oidc], shared with iOS; the redirect comes back to MainActivity, which
 * hands the URI straight to it.
 *
 * A WebView deliberately is not a fallback for a missing browser: it would put
 * the realm's login page inside this app's process, where this app could read
 * what is typed into it.
 */
object AuthBrowser {

    /** Whether signing in is worth offering — see [Oidc.configured], which is
     *  optimistic now: true when there is a realm *or* a server that might name
     *  one. A deployment that turns out not to answer surfaces as
     *  [StartFailure.NoRealmAdvertised] at tap time rather than as a missing
     *  button. */
    val configured: Boolean get() = Oidc.configured

    /** Whether there is a server a probe could ask; see [Oidc.hasApiServer]. */
    val hasApiServer: Boolean get() = Oidc.hasApiServer

    /**
     * Why [start] did not open the browser.
     *
     * Its own type rather than folding into a `Boolean`: a rider looking at a
     * typo'd realm address needs to be pointed at Settings, not at their
     * browser, and a `Boolean` return can't carry which of the two happened
     * — see `FriendsScreen.kt`'s `SignInSection`, the one caller.
     */
    sealed interface StartFailure {
        /** The realm's authorize URL is not a valid URL — most likely a
         *  malformed sign-in realm address; `RoutingServer.pick` only trims
         *  and strips a trailing slash, it does not validate.
         *  `android.net.Uri.parse` never throws on a malformed string the way
         *  iOS's `URL(string:)` rejects one, so this is caught by validating
         *  the URL up front rather than by an exception out of
         *  [CustomTabsIntent.launchUrl]. */
        data object InvalidRealmUrl : StartFailure

        /** The URL is valid; nothing on the device can open it. */
        data object NoBrowserAvailable : StartFailure

        /** No realm is configured — neither typed nor discoverable — so there
         *  is no authorize URL to open. `configured` is optimistic now, so a
         *  rider can reach this at tap time rather than the button never
         *  appearing; see [NoRealmAdvertised] for the other way a blank issuer
         *  happens. */
        data object NotConfigured : StartFailure

        /** There is an API server, but it did not name a realm — either it
         *  predates the capability endpoint or it was unreachable. Separate
         *  from [NotConfigured] because the rider's next move is different:
         *  update the server, rather than fill in a field. */
        data object NoRealmAdvertised : StartFailure
    }

    /**
     * Opens the realm's login page. Returns `null` on success, or the reason
     * it did not open, so the caller can report the actual cause instead of
     * defaulting every failure to "no browser available".
     *
     * `suspend` because the realm may have to be asked for: a deployment states
     * its own issuer, and finding out is a request. Must still be called from
     * the main thread — [Oidc]'s parked verifier and state are guarded by a
     * single-thread contract, not a lock, and the caller's
     * `rememberCoroutineScope()` satisfies it.
     */
    suspend fun start(context: Context): StartFailure? {
        // Before the entropy, deliberately: a refused start should not have
        // drawn from the CSPRNG, and this is the failure most likely to happen
        // on a self-hosted deployment.
        val issuer = Oidc.resolveIssuer()
        if (issuer.isBlank()) {
            return if (Oidc.hasApiServer) StartFailure.NoRealmAdvertised
            else StartFailure.NotConfigured
        }

        val entropy = ByteArray(Oidc.ENTROPY_BYTES).also { SecureRandom().nextBytes(it) }
        val authorize = Oidc.begin(entropy, issuer)
        // Blank here is now only "entropy too short", since the issuer was
        // checked above. begin() already dropped anything it parked in that
        // case, so there is nothing to abandon.
        if (authorize.isBlank()) return StartFailure.NotConfigured

        if (authorize.toHttpUrlOrNull() == null) {
            // begin() already parked a fresh verifier and state for this
            // attempt; nothing will ever spend them if the browser never
            // opens, so they have to be dropped here too — same reason the
            // launchUrl catch below does it.
            Oidc.abandon()
            return StartFailure.InvalidRealmUrl
        }

        return try {
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(authorize))
            null
        } catch (e: Exception) {
            // ActivityNotFoundException is the expected one — no browser at all,
            // and nothing here can substitute for one. Caught broadly anyway
            // because the thing that must happen on *any* failure to open the
            // browser is dropping the secrets [Oidc.begin] just parked: a
            // sign-in nobody can finish must not leave a verifier that a later
            // stray callback could still spend. A narrower catch would leave
            // that window open for every other way launchUrl can fail.
            Oidc.abandon()
            StartFailure.NoBrowserAvailable
        }
    }
}

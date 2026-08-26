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

    /** Whether signing in is possible at all — false when no realm is
     *  configured, which is how a build with no secrets behaves until the rider
     *  sets one under Settings. */
    val configured: Boolean get() = Oidc.configured

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
    }

    /**
     * Opens the realm's login page. Returns `null` on success, or the reason
     * it did not open, so the caller can report the actual cause instead of
     * defaulting every failure to "no browser available".
     */
    fun start(context: Context): StartFailure? {
        val entropy = ByteArray(Oidc.ENTROPY_BYTES).also { SecureRandom().nextBytes(it) }
        val authorize = Oidc.begin(entropy)
        // Blank here is "no realm configured" (or, in principle, entropy too
        // short) — begin() already dropped anything it parked in that case,
        // and the caller already gates the button on `configured`, so this
        // is not the malformed-URL case below and is reported the same way
        // it always was.
        if (authorize.isBlank()) return StartFailure.NoBrowserAvailable

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

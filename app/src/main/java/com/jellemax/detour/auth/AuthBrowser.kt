package com.jellemax.detour.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.jellemax.detour.data.Oidc
import java.security.SecureRandom

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
     * Opens the realm's login page. Returns false when there is no realm
     * configured or no browser to open it in, so the caller can say so instead
     * of leaving a button that does nothing.
     */
    fun start(context: Context): Boolean {
        val entropy = ByteArray(Oidc.ENTROPY_BYTES).also { SecureRandom().nextBytes(it) }
        val authorize = Oidc.begin(entropy)
        if (authorize.isBlank()) return false

        return try {
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(authorize))
            true
        } catch (e: ActivityNotFoundException) {
            // No browser at all: nothing here can substitute for one. Drop the
            // parked secrets, or the next callback to arrive from some earlier
            // attempt would still look spendable.
            Oidc.abandon()
            false
        }
    }
}

package com.jellemax.detour.notif

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.Devices
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.SyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Registers this install's FCM token with the server so the backend can wake it
 * for a circle event (`docs/PUSH.md` §3).
 *
 * FCM is **opt-in by build**: only when a `google-services.json` was baked in
 * does the Google Services plugin initialise a [FirebaseApp], so [available]
 * gates everything here. A self-hoster who ships no such file gets an app that
 * builds and runs with push simply inactive — the relay and its catch-up still
 * deliver. Sign-out's `DELETE /api/devices` lives in
 * [com.jellemax.detour.data.Social.signOut], keyed on [Settings.pushToken].
 */
object Push {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun available(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    /** Register the current FCM token, if signed in with a server configured and
     *  FCM available. A no-op when any precondition is unmet, so it is safe to
     *  call unconditionally on app start and after sign-in. */
    fun refresh(context: Context) {
        if (!available(context) || !Account.signedIn || !SyncClient.configured()) return
        scope.launch {
            runCatching {
                register(FirebaseMessaging.getInstance().token.await())
            }
        }
    }

    /** FCM rotated the token — re-register it (from [DetourMessagingService]). */
    fun onTokenRefreshed(token: String) {
        if (!Account.signedIn || !SyncClient.configured()) return
        scope.launch { runCatching { register(token) } }
    }

    // Idempotent server-side (upsert keyed on the token), so this re-registers on
    // every call rather than trying to be clever about "unchanged" — that keeps it
    // correct across a server switch, where the same token is unknown to the new
    // backend and must be re-sent.
    private suspend fun register(token: String) {
        Devices.register(token)
        Settings.setPushToken(token)
    }
}

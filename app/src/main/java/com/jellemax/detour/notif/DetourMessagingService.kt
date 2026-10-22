package com.jellemax.detour.notif

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking

/**
 * Receives the content-free wake-pings. FCM only ever instantiates this
 * when a [FirebaseApp][com.google.firebase.FirebaseApp] exists (a build with a
 * `google-services.json`), so it is dormant in a push-less self-hosted build.
 */
class DetourMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Push.onTokenRefreshed(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // onMessageReceived runs on FCM's own background thread, so blocking it
        // until the fetch finishes keeps the process alive for the short wake
        // window — a detached coroutine could be torn down with the process first.
        when (message.data["type"]) {
            // Carries only its type (docs/PUSH.md §2); the device decides which
            // circles to sweep and fetches the events itself.
            "circle_wake" -> {
                PlaceNotifications.ensureChannel(this)
                runBlocking { CircleCatchUp.sweep(applicationContext) }
            }
            // Carries its type and the ride's id (§11); the device fetches the ride.
            "ride_reminder" -> {
                val rideId = message.data["rideId"]?.takeIf { it.isNotBlank() } ?: return
                runBlocking { RideReminderNotification.remind(applicationContext, rideId) }
            }
        }
    }
}

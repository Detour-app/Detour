package com.jellemax.detour.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jellemax.detour.MainActivity

/**
 * The "Trip ended - saved to history." notification, raised by
 * TripTrackingService when auto-detection ends a trip the user did not end
 * themselves. Lives here rather than in the service so the debug trigger in
 * app/src/debug can raise the *same* notification: a debug hook that rebuilt
 * its own copy of this builder would prove nothing about the shipped one.
 *
 * Same split as [PlaceNotifications], which owns the circle arrival
 * notification for the same reason.
 */
object TripEndedNotification {

    /** Shared with the service's own ongoing notification - declared here so
     *  the id exists in one place, and [ensureChannel] can stand the channel up
     *  when nothing else has yet. */
    const val CHANNEL_ID = "trip_tracking"

    private const val NOTIFICATION_ID = 2

    /** Request code 3: 0 is taken by the ongoing and badge intents, which carry
     *  no extras and so are interchangeable with each other. This one carries a
     *  trip id, so it needs its own. */
    private const val REQUEST_CODE = 3

    /** The service creates this channel in onCreate, but the debug trigger can
     *  fire before the service has ever run - and a notification posted to a
     *  channel that does not exist is dropped without a word. Idempotent. */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Trip tracking", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    fun show(context: Context, startTimeMs: Long) {
        ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java)
            .putExtra(PendingTripOpen.EXTRA_OPEN_TRIP_START_MS, startTimeMs)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Detour")
            .setContentText("Trip ended — saved to history.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            // UPDATE_CURRENT is not cosmetic: the extra differs per trip, and a
            // PendingIntent reused under one request code keeps its *original*
            // extras, so without it the second auto-detected trip would open the
            // first one.
            .setContentIntent(
                PendingIntent.getActivity(
                    context, REQUEST_CODE, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            // Only works because there is a content intent to fire: the system
            // applies autoCancel when it delivers one.
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }
}

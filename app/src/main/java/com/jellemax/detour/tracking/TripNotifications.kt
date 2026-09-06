package com.jellemax.detour.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jellemax.detour.MainActivity
import com.jellemax.detour.R
import com.jellemax.detour.data.BadgeDef
import com.jellemax.detour.data.Settings
import com.jellemax.detour.notif.TripEndedNotification

/** Builds and posts [TripTrackingService]'s foreground notification and its
 *  one-off badge-earned notification. Takes a plain [Service] (not the
 *  concrete service) because all it needs from it is a [android.content.Context]
 *  — the live trip state [build] renders comes in as parameters instead, so
 *  this stays testable without standing up the whole service. */
class TripNotifications(private val service: Service) {

    companion object {
        // One definition, shared with the trip-ended notification that posts to
        // the same channel from notif/.
        const val CHANNEL_ID = TripEndedNotification.CHANNEL_ID
        const val NOTIFICATION_ID = 1
    }

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Trip tracking", NotificationManager.IMPORTANCE_LOW,
        )
        service.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun badgesEarned(badges: List<BadgeDef>) {
        val title = if (badges.size == 1) "Badge earned!" else "${badges.size} badges earned!"
        val notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(badges.joinToString(", ") { it.title })
            .setSmallIcon(android.R.drawable.btn_star_big_on)
            .setContentIntent(
                PendingIntent.getActivity(
                    service, 0, Intent(service, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setAutoCancel(true)
            .build()
        service.getSystemService(NotificationManager::class.java).notify(3, notification)
    }

    /** Rebuilds and re-posts the foreground notification. [stats] and
     *  [stationary] are the live trip state the text depends on; [endTripAction]
     *  is [TripTrackingService]'s private ACTION_END_TRIP, passed in rather than
     *  duplicated here. */
    fun update(stats: TripStats?, stationary: Boolean, endTripAction: String) {
        service.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, build(stats, stationary, endTripAction))
    }

    fun build(stats: TripStats?, stationary: Boolean, endTripAction: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            service, 0, Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            stats != null -> "Tracking your ${stats.mode.label.lowercase()} trip…"
            !Settings.autoDetectDrives.value -> "Auto-tracking off"
            stationary -> "Standing by"
            else -> "Watching for trips"
        }
        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle(service.getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(contentIntent)
            .setOngoing(true)
        // Ending a trip from the shade beats unlocking, finding the app, and
        // hunting for a button — which is the situation you are in at a kerbside.
        if (stats != null) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "End trip",
                PendingIntent.getForegroundService(
                    service, 2,
                    Intent(service, TripTrackingService::class.java).setAction(endTripAction),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder.build()
    }
}

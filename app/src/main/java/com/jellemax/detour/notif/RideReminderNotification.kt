package com.jellemax.detour.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jellemax.detour.MainActivity
import com.jellemax.detour.R
import com.jellemax.detour.data.PlannedRide
import com.jellemax.detour.data.PlannedRides
import java.util.Date

/**
 * "Ride starts soon", posted when the server's planned-ride reminder wakes the
 * phone (#759, docs/PUSH.md §11). The ping names only the ride, so this fetches
 * it and lets [PlannedRide.wantsReminder] decide - the rider may have declined,
 * or the owner moved the start, since the server's pass. Tapping opens the
 * ride's detail screen.
 */
object RideReminderNotification {

    private const val CHANNEL_ID = "planned_ride_reminders"

    /** Fetch [rideId] and post its reminder if it still wants one. A failed
     *  fetch posts nothing: the ride is still on the Planned list, and a
     *  reminder with no title or time would only say "something". */
    suspend fun remind(context: Context, rideId: String) {
        val ride = runCatching { PlannedRides.get(rideId) }.getOrNull() ?: return
        if (!ride.wantsReminder(System.currentTimeMillis())) return
        show(context, ride)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel_ride_reminders),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }

    private fun show(context: Context, ride: PlannedRide) {
        ensureChannel(context)
        // One id per ride, used as both the notification id and the request
        // code: a second reminder for the same ride (it was rescheduled)
        // replaces the first, and UPDATE_CURRENT keeps each ride's extra its own.
        val id = ride.id.hashCode()
        val openIntent = Intent(context, MainActivity::class.java)
            .putExtra(PendingRideOpen.EXTRA_OPEN_PLANNED_RIDE_ID, ride.id)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val time = DateFormat.getTimeFormat(context).format(Date(ride.startsAtMs))
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_ride_reminder_title))
            .setContentText(context.getString(R.string.notif_ride_reminder_text, ride.title, time))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, id, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}

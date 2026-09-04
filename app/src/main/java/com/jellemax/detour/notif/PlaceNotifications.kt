package com.jellemax.detour.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jellemax.detour.MainActivity
import com.jellemax.detour.R
import com.jellemax.detour.data.PlaceEvent
import com.jellemax.detour.data.catchUpSummaryText
import com.jellemax.detour.data.notificationText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Circle id a tapped arrival/departure notification wants the app to open,
 * consumed once by AppRoot. The same one-shot deep-link shape the sign-in
 * redirect uses (see [com.jellemax.detour.auth.PendingSignIn]), but this is
 * Android notification plumbing only, so it stays here.
 */
object PendingCircleOpen {
    private val _circleId = MutableStateFlow<String?>(null)
    val circleId: StateFlow<String?> = _circleId

    fun offer(id: String) {
        _circleId.value = id
    }

    fun clear() {
        _circleId.value = null
    }
}

/**
 * Raises local notifications for circle arrival/departure events. Which ones
 * a catch-up batch should actually raise is [CircleNotifyPolicy][com.jellemax.detour.data.CircleNotifyPolicy]'s
 * call now (shared/); [notificationText] is shared/'s too - see its doc for
 * why the wording itself never gets written here.
 */
object PlaceNotifications {

    private const val CHANNEL_ID = "circle_arrivals"
    private const val EXTRA_OPEN_CIRCLE_ID = "open_circle_id"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Circle arrivals", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    /** Reads a tapped notification's target circle, if any, into
     *  [PendingCircleOpen] - call from MainActivity's onCreate (its intent)
     *  and onNewIntent, same as it already does for the sign-in redirect. */
    fun takeOpenCircleId(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_OPEN_CIRCLE_ID)?.takeIf { it.isNotBlank() } ?: return
        PendingCircleOpen.offer(id)
    }

    /** [displayName] is the handle to draw — the event itself only names a
     *  rider by id now (#133), and membership is what resolves it; the
     *  caller already has it (see `CircleNotifyService.displayNameFor`). */
    fun notify(context: Context, groupId: String, event: PlaceEvent, displayName: String) {
        show(
            context, groupId,
            notificationIdFor(groupId, event.tsMs, event.riderId.value),
            event.notificationText(displayName),
        )
    }

    fun notifySummary(context: Context, groupId: String, collapsedCount: Int) {
        show(
            context, groupId, notificationIdFor(groupId, 0L, "__summary__"),
            catchUpSummaryText(collapsedCount),
        )
    }

    private fun show(context: Context, groupId: String, id: Int, text: String) {
        val openIntent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_CIRCLE_ID, groupId)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context, id, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    /** Stable per-event id, so a repeat delivery (a reconnect re-fetching a
     *  catch-up window it already handled) updates the same notification
     *  instead of stacking a duplicate. Can't use [PlaceEvent.id] - the live
     *  relay path's is always blank (see RelayPlaceEvent's doc in shared/). */
    private fun notificationIdFor(groupId: String, tsMs: Long, salt: String): Int =
        (groupId + tsMs.toString() + salt).hashCode()
}

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
 * consumed once by AppRoot. The app-local equivalent of
 * [com.jellemax.detour.data.PendingReset]'s deep-link pattern - that one
 * lives in shared/ because iOS needs it too for its own reset link; this one
 * is Android notification plumbing only, so it stays here.
 */
object PendingCircleOpen {
    private val _circleId = MutableStateFlow<Int?>(null)
    val circleId: StateFlow<Int?> = _circleId

    fun offer(id: Int) {
        _circleId.value = id
    }

    fun clear() {
        _circleId.value = null
    }
}

/**
 * Raises local notifications for circle arrival/departure events, and plans
 * which ones a catch-up batch should actually raise. [notificationText] is
 * shared/'s - see its doc for why the wording itself never gets written here.
 */
object PlaceNotifications {

    private const val CHANNEL_ID = "circle_arrivals"
    private const val EXTRA_OPEN_CIRCLE_ID = "open_circle_id"

    /** A caught-up transition older than this is stale history, not news -
     *  an "earlier today" scale, well past the couple of minutes circles
     *  normally sync on. */
    const val STALE_AFTER_MS = 3 * 60 * 60_000L

    /** Individual pings one catch-up batch may raise before the rest
     *  collapse into a single summary notification instead. */
    const val NOTIFY_CAP = 5

    data class CatchUpPlan(val individual: List<PlaceEvent>, val collapsedCount: Int)

    /**
     * Turns raw catch-up events into what's worth surfacing: never the
     * caller's own transitions (`GET /circles/{id}/events` returns those by
     * design - see [com.jellemax.detour.data.CircleEvents]'s doc - but a
     * user does not need telling where they themselves went), nothing older
     * than [staleAfterMs], and at most [cap] individual notifications - a
     * phone back from a week offline must not detonate into fifty pings.
     * Anything past the cap is dropped from [CatchUpPlan.individual] but
     * still counted in [CatchUpPlan.collapsedCount] rather than vanishing
     * without a trace.
     */
    fun planCatchUp(
        events: List<PlaceEvent>,
        myUsername: String,
        nowMs: Long,
        staleAfterMs: Long = STALE_AFTER_MS,
        cap: Int = NOTIFY_CAP,
    ): CatchUpPlan {
        val relevant = events
            .filter { it.username != myUsername }
            .filter { nowMs - it.tsMs <= staleAfterMs }
            .sortedBy { it.tsMs }
        if (relevant.size <= cap) return CatchUpPlan(relevant, 0)
        // Newest first: if only a handful can be shown, the most recent
        // arrivals are the ones still worth knowing about right now.
        return CatchUpPlan(relevant.takeLast(cap), relevant.size - cap)
    }

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
     *  and onNewIntent, same as it already does for a password-reset link. */
    fun takeOpenCircleId(intent: Intent?) {
        val id = intent?.getIntExtra(EXTRA_OPEN_CIRCLE_ID, -1)?.takeIf { it >= 0 } ?: return
        PendingCircleOpen.offer(id)
    }

    fun notify(context: Context, groupId: Int, event: PlaceEvent) {
        show(context, groupId, notificationIdFor(groupId, event.tsMs, event.username), event.notificationText())
    }

    fun notifySummary(context: Context, groupId: Int, collapsedCount: Int) {
        show(
            context, groupId, notificationIdFor(groupId, 0L, "__summary__"),
            catchUpSummaryText(collapsedCount),
        )
    }

    private fun show(context: Context, groupId: Int, id: Int, text: String) {
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
     *  relay path's is always 0 (see RelayPlaceEvent's doc in shared/). */
    private fun notificationIdFor(groupId: Int, tsMs: Long, salt: String): Int =
        (groupId.toString() + tsMs.toString() + salt).hashCode()
}

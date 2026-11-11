package com.jellemax.detour.convoy

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.jellemax.detour.MainActivity
import com.jellemax.detour.R
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.ConvoysStore
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.handleFor
import com.jellemax.detour.drive.BreakRequest
import com.jellemax.detour.drive.RegroupPin
import com.jellemax.detour.net.ConvoyLiveClient
import com.jellemax.detour.notif.PendingMapOpen
import com.jellemax.detour.presentation.BreakRequestAnnouncer
import com.jellemax.detour.presentation.RegroupPinAnnouncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** The handle to show for [riderId] in the joined convoy, from its roster. One
 *  copy for the car toast ([com.jellemax.detour.car.CarBreakRegroup]) and the
 *  phone's backgrounded notifications ([ConvoyAlertNotifications]). */
internal fun convoyRiderHandle(riderId: RiderId): String {
    val convoyId = ConvoyLiveClient.activeConvoyId.value
    val convoy = ConvoysStore.state.value.convoys.firstOrNull { it.id == convoyId }
    return convoy?.members.orEmpty().handleFor(riderId)
}

/**
 * Break requests and regroup pins as local notifications while the phone app
 * is in the background (#906). No push: [ConvoyLiveService] keeps the relay
 * socket open, so the events arrive here anyway.
 *
 * Which events count, baseline included, is shared with the car toast
 * ([BreakRequestAnnouncer], [RegroupPinAnnouncer]): what is there when
 * collection starts, and a pin replayed after a reconnect, are not announced. The announced state follows
 * every event, foreground or not; only posting waits for the app to be in the
 * background, so something already seen as a pill is not announced later.
 */
internal object ConvoyAlertNotifications {

    private const val CHANNEL_ID = "convoy_alerts"

    /** One id for the meeting point, so a new pin replaces the old one. */
    private const val REGROUP_NOTIFICATION_ID = 0x0C0A_0001

    /** Ids posted since [watch] started, for [cancelAll]. */
    private val posted = ConcurrentHashMap.newKeySet<Int>()

    /** Collects for as long as [scope] lives: [ConvoyLiveService]'s. */
    fun watch(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        ensureChannel(app)
        scope.launch {
            val breaks = BreakRequestAnnouncer()
            ConvoyLiveClient.breakRequests.collect { current ->
                breaks.next(current, Account.riderId.value).forEach { postBreak(app, it) }
            }
        }
        scope.launch {
            val pins = RegroupPinAnnouncer()
            ConvoyLiveClient.regroupPin.collect { current ->
                pins.next(current, Account.riderId.value)?.let { postRegroup(app, it) }
            }
        }
    }

    /** Takes down every alert [watch] posted: the convoy was left. */
    fun cancelAll(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        posted.forEach { manager.cancel(it) }
        posted.clear()
    }

    private fun postBreak(context: Context, request: BreakRequest) {
        val text = context.getString(R.string.map_break_pill, convoyRiderHandle(request.riderId))
        post(context, (request.riderId.value + request.receivedAtMs).hashCode(), text)
    }

    private fun postRegroup(context: Context, pin: RegroupPin) {
        val label = pin.label ?: context.getString(R.string.map_regroup_point)
        val text = context.getString(R.string.map_regroup_at, label)
        post(context, REGROUP_NOTIFICATION_ID, text)
    }

    private fun post(context: Context, id: Int, text: String) {
        if (inForeground()) return
        // Skip silently without the permission; asked for where circles ask.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(PendingMapOpen.EXTRA_OPEN_MAP, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.convoy_channel))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
        posted += id
    }

    /** Any activity of this process started: the pills are on screen. */
    private fun inForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel_convoy_alerts),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }
}

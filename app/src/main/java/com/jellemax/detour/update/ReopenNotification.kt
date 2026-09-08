package com.jellemax.detour.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jellemax.detour.BuildConfig
import com.jellemax.detour.MainActivity

/**
 * The way back into Detour after it has installed a replacement of itself.
 *
 * `MODE_FULL_INSTALL` of the running package kills the process and no
 * `PackageInstaller` flag restarts it, so the rider who tapped Install lands on
 * the launcher with nothing open. Reopening the app *for* them is not available:
 * every route was tried on an Android 15 device and every one was refused by the
 * background-activity-start rules —
 *
 * - `startActivity` straight from [InstallResultReceiver]: `BAL_BLOCK`,
 *   `autoOptInReason: notPendingIntent`.
 * - an activity `PendingIntent` fired by an allow-while-idle alarm, with the
 *   creator opting in through
 *   `ActivityOptions.setPendingIntentCreatorBackgroundActivityStartMode`: the
 *   opt-in registers (`balAllowedByPiCreator: BSP.ALLOW_BAL`) and is then
 *   overruled, because the creator process is dead by the time the alarm fires
 *   (`resultIfPiCreatorAllowsBal: BAL_BLOCK`).
 * - the same with `setExactAndAllowWhileIdle` and `USE_EXACT_ALARM` granted:
 *   identical refusal. An exact alarm's temporary power allowlist does **not**
 *   carry a background-activity-start exemption on 15 —
 *   `balAllowedByPiSender: BSP.NONE`, `tempAllowListReason:<null>` — which is
 *   the assumption #253 was written on.
 *
 * So this is a tap, and the tap is the whole point: it has to be *seen* by
 * somebody who is looking at their launcher wondering where the app went.
 */
object ReopenNotification {

    /**
     * A channel of its own, and deliberately not [UpdateNotification]'s
     * `"updates"`.
     *
     * That channel is `IMPORTANCE_LOW` — silent, no heads-up — which is right
     * for the three breadcrumbs on it (a version is available, a download is
     * running, a download finished): none of them is worth interrupting a
     * rider for. This one is the opposite. It is the only exit from a state the
     * app itself put the rider in, it is worthless unnoticed, and a channel's
     * importance cannot be raised after creation, so it could not live on
     * `"updates"` even if the subject matched. The rider gets a second switch,
     * which is the trade: one switch for "tell me about updates" and one for
     * "tell me when you have closed yourself".
     */
    private const val CHANNEL_ID = "update_reopen"

    /** After [UpdateNotification]'s 4201 and [UpdateDownloadService]'s 4202 and
     *  4203, none of which this may erase: the ready-to-install breadcrumb is
     *  still on screen when the install starts. */
    private const val NOTIFICATION_ID = 4204

    /** Its own, for the reason the request code in [UpdateNotification]
     *  documents: `PendingIntent` identity ignores extras, so two intents
     *  naming `MainActivity` under one request code are one `PendingIntent`
     *  and `FLAG_UPDATE_CURRENT` would rewrite the other's extras. */
    private const val OPEN_REQUEST_CODE = 4204

    /**
     * Posts "Detour is updated, tap to reopen".
     *
     * The version comes from [BuildConfig] rather than [UpdateState], and is
     * the *new* one: this runs in the process the system respawned from the
     * freshly installed APK, which is also why there is no state left to read
     * it from.
     */
    fun post(context: Context) {
        // POST_NOTIFICATIONS only exists from API 33 (TIRAMISU), and below it
        // notifications need no runtime grant — the same gate, for the same
        // reason, as UpdateNotification.notifyOnce. Refused, there is nothing
        // this can do: the rider is on the launcher and the app has no surface
        // to ask on. They reopen Detour by hand, as they do today.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Reopening after an update",
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
        val open = PendingIntent.getActivity(
            context,
            OPEN_REQUEST_CODE,
            // No EXTRA_OPEN_UPDATE_SETTINGS, unlike every other notification in
            // this package: the update is done, and the rider wants the app
            // back rather than the row that installed it.
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("Detour ${BuildConfig.VERSION_NAME} installed")
                .setContentText("Tap to reopen Detour.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * Drops it, however the rider got back in.
     *
     * `setAutoCancel` covers the tap and nothing else, so this is for the
     * launcher icon, a widget, Android Auto, or the notification having been
     * swiped and the app opened an hour later — in all of which the offer to
     * reopen an app that is open is noise. Called from `MainActivity.onCreate`:
     * a self-update always leaves a dead process behind, so every route back in
     * is a cold start.
     */
    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

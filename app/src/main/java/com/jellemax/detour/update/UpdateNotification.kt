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
import com.jellemax.detour.MainActivity
import com.jellemax.detour.data.Settings
import com.jellemax.detour.notif.PendingUpdateOpen

/**
 * One notification per available version, never repeated.
 *
 * The check runs in the foreground, so this always posts while the rider is
 * already in the app — it is a breadcrumb for after they leave, not an
 * announcement. The Settings row is what tells them now: the whole check,
 * download and install flow lives there since #277. (The Hub banner becomes a
 * pointer at that row in a later change; it does not do the telling itself.)
 */
object UpdateNotification {

    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 4201

    /**
     * Distinct from every other `PendingIntent` this app builds against
     * `MainActivity`.
     *
     * PendingIntent identity ignores extras — two intents naming the same
     * component under the same request code *are* the same PendingIntent, and
     * FLAG_UPDATE_CURRENT below rewrites the extras of whichever one already
     * exists. ConvoyLiveService builds one at request code 0
     * (`ConvoyLiveService.kt:228-231`), so sharing that code would hand this
     * notification's EXTRA_OPEN_UPDATE_SETTINGS to the convoy notification and
     * send a tap on it to Settings. Harmless until #277 put an extra on this
     * one; a request code of its own is what keeps them apart.
     */
    private const val OPEN_REQUEST_CODE = 4201

    fun notifyOnce(context: Context, version: String) {
        if (Settings.notifiedUpdateVersion() == version) return
        // POST_NOTIFICATIONS only exists from API 33 (TIRAMISU); below that
        // notifications need no runtime grant, and the platform itself
        // reports the (nonexistent-to-it) permission as granted. The
        // version gate here matches the check MapScreen's permission sweep
        // and CirclesScreen's per-circle toggle both use for the same
        // permission, rather than relying on that platform behaviour.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Requested elsewhere for trips and circles; if it was refused, the
            // banner is the whole prompt. Stamp anyway so a refused permission
            // does not re-attempt hourly.
            Settings.setNotifiedUpdateVersion(version)
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            context,
            OPEN_REQUEST_CODE,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(PendingUpdateOpen.EXTRA_OPEN_UPDATE_SETTINGS, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("Detour $version is available")
                .setContentText("Open Detour to install it.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        Settings.setNotifiedUpdateVersion(version)
    }
}

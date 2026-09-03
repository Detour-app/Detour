package com.jellemax.detour.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jellemax.detour.notif.CircleNotifyService
import com.jellemax.detour.notif.CircleSyncWorker

/** Restarts trip tracking, circle arrival notifications and the parked circle
 *  sync after a reboot - an arrival at 3pm on a Tuesday has to work whether or
 *  not the app has been opened since the phone restarted.
 *
 *  Since #90 the tracker is no longer always-on, and this still starts it
 *  unconditionally: the dormancy decision lives in one place, at the tail of
 *  `onStartCommand`, rather than being restated here. With `auto_detect_drives`
 *  off the service comes up, resolves STOP_BARE in that same pass and stands
 *  back down - no service and no geofence left behind, and the ongoing
 *  notification on screen only for the length of the pass. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            TripTrackingService.startMonitoring(context)
        } catch (e: Exception) {
            // Background-start not allowed (e.g. background location not
            // granted yet); tracking resumes next time the app opens.
        }
        try {
            CircleNotifyService.refresh(context)
        } catch (e: Exception) {
            // Same background-start restriction can apply here too; the
            // service starts anyway next time the app opens (MainActivity).
        }
        try {
            CircleSyncWorker.schedule(context)
        } catch (e: Exception) {
            // WorkManager not ready this early is rare and self-heals on next app open.
        }
    }
}

package com.jellemax.detour.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jellemax.detour.notif.CircleNotifyService

/** Restarts the always-on tracker, and circle arrival notifications, after
 *  a reboot - an arrival at 3pm on a Tuesday has to work whether or not the
 *  app has been opened since the phone restarted. */
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
    }
}

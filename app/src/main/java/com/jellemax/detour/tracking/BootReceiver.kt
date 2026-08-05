package com.jellemax.detour.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the always-on tracker after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            TripTrackingService.startMonitoring(context)
        } catch (e: Exception) {
            // Background-start not allowed (e.g. background location not
            // granted yet); tracking resumes next time the app opens.
        }
    }
}

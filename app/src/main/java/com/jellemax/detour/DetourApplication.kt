package com.jellemax.detour

import android.app.Application
import com.jellemax.detour.data.BuildDefaults
import com.jellemax.detour.data.initSharedCore

/**
 * Hands the shared core the two things it cannot get for itself on Android: an
 * application Context (for preferences and the files directory) and the
 * build-time server endpoints.
 *
 * This exists so there is exactly one place that does it. The app has three
 * entry points that can start the process on their own — the activity, the
 * tracking service and the car session — and Application.onCreate runs before
 * all of them, so none has to remember. The iOS counterpart is DetourApp.init
 * in iosApp/Detour/DetourApp.swift.
 */
class DetourApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initSharedCore(this)
        BuildDefaults.configure(
            routingUrl = BuildConfig.ROUTING_URL,
            routingCfId = BuildConfig.ROUTING_CF_ID,
            routingCfSecret = BuildConfig.ROUTING_CF_SECRET,
            syncUrl = BuildConfig.SYNC_URL,
            geocoderUrl = BuildConfig.GEOCODER_URL,
            liveUrl = BuildConfig.LIVE_URL,
            versionName = BuildConfig.VERSION_NAME,
        )
    }
}

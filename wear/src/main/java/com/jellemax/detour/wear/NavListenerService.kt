package com.jellemax.detour.wear

import android.content.Intent
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

private const val NAV_PATH = "/nav"

/**
 * Registered in the manifest (not just MainActivity's onResume listener), so
 * the system can wake this app on the watch when navigation starts on the
 * phone, instead of requiring the watch app to already be open.
 */
class NavListenerService : WearableListenerService() {

    companion object {
        private var navActive = false
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != NAV_PATH) return
        val json = JSONObject(String(event.data))
        if (json.has("stop")) {
            navActive = false
            return
        }
        if (navActive) return
        navActive = true
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }
}

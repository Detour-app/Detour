package com.jellemax.detour.notif

import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.prefs

/**
 * The one-time battery-optimization prompt flag: whether this device has
 * already been asked to exempt the app from battery optimization. That flag is
 * what earns this file - it is Android-only, since no other platform has a
 * battery-optimization whitelist to be exempted from, and it needs a bag of
 * its own (`prefs("circle_notify")`). Client-local, like the toggle below and
 * unlike a circle's `sharing` flag, which is real server state (see
 * `Groups.setSharing`).
 *
 * [notifyEnabled]/[setNotifyEnabled] are one-line passthroughs to shared's
 * [Settings], which owns the key and the "on" default because iOS shows the
 * same switch - they are here so the notif package has one place to ask,
 * not because there is anything platform-specific about them.
 */
object CircleNotifySettings {

    private val store by lazy { prefs("circle_notify") }

    /** Default on, matching both this phase's spec and the circle's own
     *  "Share my location" switch. */
    fun notifyEnabled(circleId: String): Boolean = Settings.notifyArrivals(circleId)

    fun setNotifyEnabled(circleId: String, enabled: Boolean) {
        Settings.setNotifyArrivals(circleId, enabled)
    }

    /** Whether the battery-optimization exemption prompt has already been
     *  shown once - set the moment it's shown, not on dismiss/confirm, so a
     *  user who kills the app mid-dialog doesn't see it again next launch. */
    fun batteryPromptShown(): Boolean = store.bool("battery_prompt_shown", false)

    fun setBatteryPromptShown() {
        store.put("battery_prompt_shown", true)
    }
}

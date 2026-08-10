package com.jellemax.detour.notif

import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.prefs

/**
 * The one-time battery-optimization prompt flag, plus thin access to the
 * per-circle "notify me about arrivals" toggle. Both are client-local -
 * unlike a circle's `sharing` flag (real server state, see
 * `Groups.setSharing`), neither has a server column.
 *
 * The toggle itself lives in shared's [Settings], not in the bag below,
 * because iOS shows the same switch: one key and one default there beat two
 * platform-local spellings that drift the first time either is touched. The
 * battery prompt stays here - Android is the only platform with a
 * battery-optimization whitelist to be exempted from.
 */
object CircleNotifySettings {

    private val store by lazy { prefs("circle_notify") }

    /** Default on, matching both this phase's spec and the circle's own
     *  "Share my location" switch. */
    fun notifyEnabled(circleId: Int): Boolean = Settings.notifyArrivals(circleId)

    fun setNotifyEnabled(circleId: Int, enabled: Boolean) {
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

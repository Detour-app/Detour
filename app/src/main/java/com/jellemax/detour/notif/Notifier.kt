package com.jellemax.detour.notif

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.jellemax.detour.data.NotificationGate
import com.jellemax.detour.data.NotificationKind

/**
 * The one place Android posts a notification after it is built, so the master
 * switch (#977) is checked once instead of at every call site. Foreground
 * services' first notification goes through `startForeground` and is ongoing
 * by definition; every later `notify` comes through here.
 */
object Notifier {

    /** Posts [notification] under [id] if [NotificationGate] allows [kind].
     *  False when the switch held it back, so a caller that stamps "already
     *  told the rider" can leave the stamp off. */
    fun post(context: Context, kind: NotificationKind, id: Int, notification: Notification): Boolean {
        if (!NotificationGate.allows(kind)) return false
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
        return true
    }
}

package com.jellemax.detour.diag

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.jellemax.detour.data.EventKind
import com.jellemax.detour.data.EventLog

/**
 * Writes the runtime permissions the tracker depends on to [EventLog] when
 * they change (#822), read on every activity resume: a rider who revoked
 * background location in system settings is half of #491's story, and nothing
 * else records it.
 *
 * Only a change is logged, so a rider switching apps all day adds nothing. The
 * first resume of a process always logs, which makes every report start from
 * a known state.
 */
object PermissionEvents {

    /** Last detail written, per process. Main thread only (onResume). */
    private var last: String? = null

    fun recordIfChanged(context: Context) {
        val detail = permissionDetail(Build.VERSION.SDK_INT) {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (detail == last) return
        last = detail
        EventLog.record(EventKind.PERMISSION, detail)
    }
}

/**
 * One line naming each permission the tracker uses and whether it is held,
 * e.g. `fine=granted coarse=granted bg=denied ar=granted notif=granted`. A
 * permission [sdkInt] doesn't have is left out rather than reported granted.
 */
internal fun permissionDetail(sdkInt: Int, granted: (String) -> Boolean): String = buildList {
    add("fine" to Manifest.permission.ACCESS_FINE_LOCATION)
    add("coarse" to Manifest.permission.ACCESS_COARSE_LOCATION)
    if (sdkInt >= Build.VERSION_CODES.Q) {
        add("bg" to Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        add("ar" to Manifest.permission.ACTIVITY_RECOGNITION)
    }
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) add("notif" to Manifest.permission.POST_NOTIFICATIONS)
}.joinToString(" ") { (name, permission) -> "$name=${if (granted(permission)) "granted" else "denied"}" }

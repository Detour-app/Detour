package com.jellemax.detour.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * Hands a downloaded APK to the system installer.
 *
 * PackageInstaller rather than ACTION_VIEW: it reports the outcome back through
 * an IntentSender, so "the rider dismissed the sheet"
 * (STATUS_FAILURE_ABORTED) is distinguishable from "it failed". ACTION_VIEW
 * returns nothing and leaves the app guessing.
 */
object UpdateInstaller {

    const val ACTION_INSTALL_RESULT = "com.jellemax.detour.INSTALL_RESULT"

    /**
     * Whether the rider has granted this app the per-app "Install unknown apps"
     * permission. REQUEST_INSTALL_PACKAGES in the manifest only makes the app
     * eligible to ask; this is the consent.
     */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Sends the rider to the one settings screen that grants it, rather than
     *  letting the install fail and leaving them to find it. */
    fun requestPermission(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Opens the install sheet for [apk]. Returns false if the session could not
     * be created at all; the sheet's own outcome arrives at
     * [ACTION_INSTALL_RESULT].
     */
    fun install(context: Context, apk: File): Boolean = try {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("detour", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(ACTION_INSTALL_RESULT).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
        true
    } catch (e: Exception) {
        Log.w("DetourUpdate", "install session failed", e)
        false
    }
}

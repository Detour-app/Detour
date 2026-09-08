package com.jellemax.detour.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * The other half of [UpdateInstaller]. PackageInstaller reports through an
 * IntentSender, and the first thing it reports is usually
 * STATUS_PENDING_USER_ACTION — an Intent that has to be launched to show the
 * install sheet at all. Committing without handling that looks like a silent
 * no-op.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let { context.startActivity(it) }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                // This branch already runs in the new build: the old process
                // was killed by its own install and the system respawned the
                // app from the new APK to deliver this queued broadcast. So
                // clearing the status is for the case where it somehow was
                // not, and keeps a stale row off the screen.
                UpdateState.set(UpdateStatus.None)
                UpdateDownloader.prune(context, keep = null)
                // Whichever screen the rider was on is gone with that process,
                // and they are looking at their launcher. ReopenNotification
                // documents why the app cannot simply reopen itself.
                ReopenNotification.post(context)
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // The rider dismissed the sheet. Keep the file and the
                // Downloaded state, so saying yes later costs a tap rather
                // than another 46 MB. This is the distinction ACTION_VIEW
                // cannot make, and the reason for using PackageInstaller.
                Log.d("DetourUpdate", "install dismissed by user")
            }

            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("DetourUpdate", "install failed: status=$status msg=$msg")
                UpdateState.current()?.let { UpdateState.set(UpdateStatus.Failed(it)) }
            }
        }
    }
}

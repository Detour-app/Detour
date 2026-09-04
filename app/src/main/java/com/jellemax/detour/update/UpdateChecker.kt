package com.jellemax.detour.update

import android.content.Context
import com.jellemax.detour.BuildConfig
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.UpdateClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The update check in one place, with the throttle and the notification as the
 * caller's decisions rather than facts baked into the only call site.
 *
 * Lifted out of `MainActivity.checkForUpdate()` for #147: a rider-initiated
 * check needs the same fetch-prune-publish core without the hourly throttle or
 * the silence, both of which are correct only for a check nobody asked for.
 */
object UpdateChecker {

    private const val AUTO_THROTTLE_MS = 60 * 60 * 1000L

    /** One coroutine in [performCheck] at a time, so the two entry points cannot
     *  both prune and publish over each other. A bare [Mutex] rather than the
     *  core's `SingleFlight`: that type is `internal` to `:shared` and invisible
     *  here, and its own KDoc says a gate that never re-enters itself — as this
     *  one does not — is what `ConvoysStore.refreshGate` and `Auth.refreshLock`
     *  stay bare for. */
    private val gate = Mutex()

    /**
     * What one run concluded.
     *
     * The distinction that matters is [Failed] against [UpToDate]: the
     * automatic path collapses both into silence, and the manual path exists to
     * tell them apart for the rider.
     */
    private sealed interface Outcome {
        data object UpToDate : Outcome
        data class Found(val version: String) : Outcome
        data object Failed : Outcome
    }

    /**
     * The automatic check: throttled to once an hour, and it never tells the
     * rider anything about the outcome beyond the one-per-version notification
     * posted when a newer release is found — a failed fetch is silent.
     */
    suspend fun automaticCheck(context: Context) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - Settings.lastUpdateCheckMs() < AUTO_THROTTLE_MS) return
        // Stamped before the request: a device with no connectivity would
        // otherwise retry on every foreground.
        Settings.setLastUpdateCheckMs(now)
        gate.withLock { performCheck(context, repo, notify = true) }
    }

    /**
     * Fetch, reconcile the downloaded-file cache, publish to [UpdateState], and
     * — only when [notify] — post the one-per-version notification.
     *
     * Caller holds [gate].
     */
    private suspend fun performCheck(context: Context, repo: String, notify: Boolean): Outcome =
        withContext(Dispatchers.IO) {
            val fetched = runCatching {
                UpdateClient.newerThan(repo, BuildConfig.VERSION_NAME)
            }
            // A thrown request and a "nothing newer" response both land here as
            // null, and the pruning below deliberately does not tell them
            // apart — that is pre-existing behaviour, kept verbatim. Only the
            // returned Outcome distinguishes them.
            val update = fetched.getOrNull()

            // Never prune while a download is running. prune deletes by name;
            // the downloader holds the file open, and unlinking an open file
            // succeeds silently on Linux — the download then "completes",
            // verify() passes on the in-memory digest, and the app reports
            // Downloaded for a path that no longer exists.
            //
            // Read status once and smart-cast it, rather than re-reading
            // UpdateState.current() afterwards: InstallResultReceiver can set
            // UpdateStatus.None between two reads (STATUS_SUCCESS clears it the
            // moment the install commits), which would otherwise turn a
            // download-in-flight into a false Outcome.UpToDate.
            val status = UpdateState.status.value
            if (status is UpdateStatus.Downloading) {
                // Whatever this run just saw is discarded here, not merged: if
                // it found a version newer than the one already downloading,
                // that find is never written to UpdateState and is lost.
                return@withContext if (fetched.isFailure) {
                    Outcome.Failed
                } else {
                    Outcome.Found(status.update.version)
                }
            }
            if (update == null) {
                UpdateDownloader.prune(context, keep = null)
                return@withContext if (fetched.isFailure) Outcome.Failed else Outcome.UpToDate
            }
            UpdateDownloader.prune(context, keep = update.asset)
            if (UpdateState.current()?.version != update.version) {
                UpdateState.set(UpdateStatus.Available(update))
            }
            if (notify) UpdateNotification.notifyOnce(context, update.version)
            Outcome.Found(update.version)
        }
}

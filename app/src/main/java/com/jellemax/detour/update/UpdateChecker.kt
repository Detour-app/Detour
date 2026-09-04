package com.jellemax.detour.update

import android.content.Context
import android.util.Log
import com.jellemax.detour.BuildConfig
import com.jellemax.detour.data.ManualCheckBudget
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.UpdateClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What the rider's own check last concluded, for the Settings row to render.
 *
 * Separate from [UpdateStatus], which describes the update itself and is shared
 * with the banner. This describes the *check* — including the two states the
 * banner has no way to show, a check that failed and a check that was refused.
 */
sealed interface ManualCheck {
    data object Idle : ManualCheck
    data object Running : ManualCheck
    data object UpToDate : ManualCheck
    data class Found(val version: String) : ManualCheck
    /** The check did not reach GitHub. Deliberately carries no detail: the
     *  throwable comes from an HTTP client and can hold hostnames, headers and
     *  paths that have no business on a rider's screen. */
    data object Failed : ManualCheck
    data class RateLimited(val retryAtMs: Long) : ManualCheck
}

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

    /** Guards [budget] alone. Separate from [gate] because the token is spent
     *  *before* the check is queued — a tap that arrives during an automatic
     *  check still costs a token, and still gets its own answer. */
    private val budgetGate = Mutex()
    private var budget = ManualCheckBudget()

    private val _manual = MutableStateFlow<ManualCheck>(ManualCheck.Idle)

    /**
     * The rider's own check. Held on the object rather than in the composition
     * for the same reason [UpdateState] is: Settings is disposed the moment the
     * rider opens a spoke, and an answer that vanished on the way to the next
     * screen is not an answer.
     */
    val manual: StateFlow<ManualCheck> = _manual

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
     * The rider asked. No throttle, and the outcome is reported rather than
     * swallowed — including the failure [automaticCheck] is deliberately silent
     * about.
     */
    suspend fun manualCheck(context: Context) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return
        val now = System.currentTimeMillis()
        val spend = budgetGate.withLock {
            val s = budget.spend(now)
            if (s is ManualCheckBudget.Spend.Granted) budget = s.budget
            s
        }
        if (spend is ManualCheckBudget.Spend.Denied) {
            _manual.value = ManualCheck.RateLimited(spend.retryAtMs)
            return
        }
        _manual.value = ManualCheck.Running
        // withLock, not tryLock. Skipping the request when a check is already
        // running would strand this on Running forever, because the automatic
        // path publishes nothing here and so nothing would ever resolve the
        // state the rider is looking at. Waiting costs at worst one duplicate
        // request inside a window of a few hundred milliseconds.
        // Every exit from here must resolve [_manual]. A throw that escaped
        // would strand the row on "Checking…" with nothing to clear it —
        // performCheck only wraps the fetch in runCatching, so prune and the
        // UpdateState writes can still throw past it.
        val outcome = try {
            gate.withLock { performCheck(context, repo, notify = false) }
        } catch (e: CancellationException) {
            // The rider left the screen. Not a failure, and reporting one would
            // be a lie — but the state must not stay on Running either.
            _manual.value = ManualCheck.Idle
            throw e
        } catch (e: Exception) {
            Log.w("DetourUpdate", "manual update check failed", e)
            Outcome.Failed
        }
        // Stamped only when the request came back. A failed manual check must
        // leave the automatic path free to run — burning the hour on a check
        // that never happened is the bug this whole feature exists to fix.
        if (outcome != Outcome.Failed) Settings.setLastUpdateCheckMs(now)
        _manual.value = when (outcome) {
            is Outcome.Found -> ManualCheck.Found(outcome.version)
            Outcome.UpToDate -> ManualCheck.UpToDate
            Outcome.Failed -> ManualCheck.Failed
        }
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

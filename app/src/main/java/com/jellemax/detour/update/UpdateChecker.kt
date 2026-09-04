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
    /**
     * [retryAtMs] stays published after that instant passes — nothing revises
     * it once the moment does. Do not render it as a live countdown; the vague
     * wording ("try again shortly") is right precisely because it promises
     * nothing more specific.
     */
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
     * for the same reason [UpdateState] is, though not for an in-flight check:
     * the caller launches into `rememberCoroutineScope()`, which is cancelled
     * by the very screen disposal that would otherwise strand the answer, so a
     * check abandoned mid-flight does not survive leaving the screen either
     * way. What living on the object buys is a *completed* answer surviving
     * navigation — the rider taps, glances away, and the result is still there
     * when they look back.
     *
     * A check cancelled by the rider leaving still spends its budget token,
     * deliberately: the request was already sent and GitHub's quota already
     * charged by the time the screen disposes, and refunding it would let a
     * tap-navigate-tap loop dodge the budget while still hitting the API.
     */
    val lastManualCheck: StateFlow<ManualCheck> = _manual

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

    private fun Outcome.toManualCheck(): ManualCheck = when (this) {
        is Outcome.Found -> ManualCheck.Found(version)
        Outcome.UpToDate -> ManualCheck.UpToDate
        Outcome.Failed -> ManualCheck.Failed
    }

    /**
     * Whether this build knows a repository to check. False in any build made
     * without `UPDATE_REPO` in the environment (`app/build.gradle.kts:107-108`
     * defaults it to blank), where every entry point here is a no-op — so the
     * Settings row is not rendered at all rather than sitting there dead.
     */
    val isConfigured: Boolean get() = BuildConfig.UPDATE_REPO.isNotBlank()

    /**
     * The automatic check: throttled to once an hour, and it never tells the
     * rider anything about the outcome beyond the one-per-version notification
     * posted when a newer release is found — a failed fetch is silent.
     */
    suspend fun automaticCheck(context: Context) {
        val repo = BuildConfig.UPDATE_REPO
        if (!isConfigured) return
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
     *
     * Every exit resolves [lastManualCheck] to something other than
     * [ManualCheck.Running]: a settled outcome, [ManualCheck.Idle] on
     * cancellation, or [ManualCheck.Failed] on any other [Exception]. An
     * [Error] or other non-[Exception] [Throwable] is not caught here and
     * leaves [lastManualCheck] on [ManualCheck.Running].
     */
    suspend fun manualCheck(context: Context) {
        val repo = BuildConfig.UPDATE_REPO
        if (!isConfigured) return
        val now = System.currentTimeMillis()
        // Reused below to stamp the throttle after the request returns, so the
        // automatic path's window can only open up to one round trip early.
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
        try {
            // withLock, not tryLock. Skipping the request when a check is
            // already running would strand this on Running forever, because
            // the automatic path publishes nothing here and so nothing would
            // ever resolve the state the rider is looking at. Waiting costs at
            // worst one duplicate request inside a window of a few hundred
            // milliseconds.
            //
            // The stamp and the terminal publish happen inside the lock too,
            // so two overlapping calls cannot publish out of order — except
            // RateLimited, published before the lock is ever taken: a denied
            // tap that lands while an earlier check is still queued can have
            // its refusal overwritten by that check's own outcome. Self-
            // limiting: the rider mashed the button and did get an answer.
            gate.withLock {
                // Re-asserted: without this, a check queued behind an
                // in-flight one would keep showing that other check's settled
                // answer for the whole time it is actually waiting its turn.
                _manual.value = ManualCheck.Running
                // performCheck can throw past its own runCatching.
                val outcome = performCheck(context, repo, notify = false)
                // Stamped only when the request came back. A failed manual
                // check must leave the automatic path free to run — burning
                // the hour on a check that never happened is the bug this
                // whole feature exists to fix.
                if (outcome != Outcome.Failed) Settings.setLastUpdateCheckMs(now)
                _manual.value = outcome.toManualCheck()
            }
        } catch (e: CancellationException) {
            // The rider left the screen. Not a failure, and reporting one
            // would be a lie — but the state must not stay on Running either.
            _manual.value = ManualCheck.Idle
            throw e
        } catch (e: Exception) {
            Log.w("DetourUpdate", "manual update check failed", e)
            _manual.value = ManualCheck.Failed
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
            // null. The returned Outcome tells them apart via
            // fetched.isFailure below, and so does the prune guard just below
            // that — pruning needs an answer, not just the absence of one.
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
                // Only prune on an answer. A thrown fetch also lands here with
                // a null update, and pruning then deletes a downloaded APK on
                // the strength of a request that never reached GitHub —
                // leaving UpdateState reporting Downloaded for a file that is
                // gone, and an Install button that silently does nothing.
                if (fetched.isSuccess) UpdateDownloader.prune(context, keep = null)
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

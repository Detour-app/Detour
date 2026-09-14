package com.jellemax.detour.update

/** What the Settings row's one button does when tapped, if anything. */
enum class UpdateAction { CHECK, DOWNLOAD, CANCEL, INSTALL }

/**
 * One row, every phase.
 *
 * [fraction] is non-null only while downloading, and is negative when the
 * length is unknown — the same convention `UpdateStatus.Downloading` uses, kept
 * rather than flattened so the button can tell "0%" from "no idea".
 */
data class UpdateRowState(
    val title: String,
    val subtitle: String?,
    val action: UpdateAction?,
    val actionLabel: String?,
    val fraction: Float?,
    /** What changed in [UpdateStatus.Available.update], for an expandable
     *  "What's new" under the row (#295). Null everywhere else — a download
     *  in progress or already finished isn't the moment to ask whether to
     *  read about it, and [UpdateClient.PendingUpdate.notes] itself is null
     *  for a release published with no body. */
    val notes: String? = null,
)

/**
 * The artefact outranks the check wherever both have something to say: a check
 * that failed while a download is in flight describes the check, and the rider
 * is looking at the download.
 *
 * The check-only wordings are the ones the row already showed before #277 —
 * they are check outcomes, and only this row ever rendered them.
 */
fun updateRowStateFrom(manual: ManualCheck, status: UpdateStatus): UpdateRowState = when (status) {
    is UpdateStatus.Available -> UpdateRowState(
        title = "Detour ${status.update.version} is available",
        subtitle = null,
        action = UpdateAction.DOWNLOAD,
        actionLabel = "Download ${status.update.version}",
        fraction = null,
        notes = status.update.notes,
    )

    is UpdateStatus.Downloading -> UpdateRowState(
        title = "Downloading ${status.update.version}",
        subtitle = null,
        action = UpdateAction.CANCEL,
        actionLabel = "Cancel",
        fraction = status.fraction,
    )

    is UpdateStatus.Downloaded -> UpdateRowState(
        title = "Detour ${status.update.version} is ready",
        subtitle = null,
        action = UpdateAction.INSTALL,
        actionLabel = "Install",
        fraction = null,
    )

    is UpdateStatus.Failed -> UpdateRowState(
        title = "Download of ${status.update.version} failed",
        subtitle = null,
        action = UpdateAction.DOWNLOAD,
        actionLabel = "Retry",
        fraction = null,
    )

    UpdateStatus.None -> UpdateRowState(
        title = "Check for updates",
        subtitle = when (manual) {
            ManualCheck.Idle -> "Check for a new release"
            ManualCheck.Running -> "Checking…"
            ManualCheck.UpToDate -> "No update found"
            is ManualCheck.Found -> "Detour ${manual.version} available"
            ManualCheck.Failed -> "Couldn't reach GitHub"
            is ManualCheck.RateLimited -> "Checked a few times just now — try again shortly"
        },
        // Guarded on Running only, as the row's tap always was: a tap with no
        // tokens left is allowed through so the budget can refuse it out loud.
        action = if (manual is ManualCheck.Running) null else UpdateAction.CHECK,
        actionLabel = null,
        fraction = null,
    )
}

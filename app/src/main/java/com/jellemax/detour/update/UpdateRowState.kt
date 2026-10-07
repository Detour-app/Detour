package com.jellemax.detour.update

import com.jellemax.detour.R

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
     *  for a release published with no body. With nothing on offer it is the
     *  running version's own notes, if it was installed in-app (#359). */
    val notes: String? = null,
)

/**
 * The artefact outranks the check wherever both have something to say: a check
 * that failed while a download is in flight describes the check, and the rider
 * is looking at the download.
 *
 * The check-only wordings are the ones the row already showed before #277 —
 * they are check outcomes, and only this row ever rendered them.
 *
 * [text] resolves a string resource with its one optional version argument —
 * `Context.getString` on a device, a fake in the JVM test.
 */
fun updateRowStateFrom(
    manual: ManualCheck,
    status: UpdateStatus,
    installedNotes: String? = null,
    text: (resId: Int, version: String?) -> String,
): UpdateRowState = when (status) {
    is UpdateStatus.Available -> UpdateRowState(
        title = text(R.string.update_available, status.update.version),
        subtitle = null,
        action = UpdateAction.DOWNLOAD,
        actionLabel = text(R.string.update_row_download, status.update.version),
        fraction = null,
        notes = status.update.notes,
    )

    is UpdateStatus.Downloading -> UpdateRowState(
        title = text(R.string.update_row_downloading, status.update.version),
        subtitle = null,
        action = UpdateAction.CANCEL,
        actionLabel = text(R.string.update_cancel, null),
        fraction = status.fraction,
    )

    is UpdateStatus.Downloaded -> UpdateRowState(
        title = text(R.string.update_ready, status.update.version),
        subtitle = null,
        action = UpdateAction.INSTALL,
        actionLabel = text(R.string.update_install, null),
        fraction = null,
    )

    is UpdateStatus.Failed -> UpdateRowState(
        title = text(R.string.update_row_failed, status.update.version),
        subtitle = null,
        action = UpdateAction.DOWNLOAD,
        actionLabel = text(R.string.update_row_retry, null),
        fraction = null,
    )

    UpdateStatus.None -> UpdateRowState(
        title = text(R.string.update_row_check_title, null),
        subtitle = when (manual) {
            ManualCheck.Idle -> text(R.string.update_row_check_idle, null)
            ManualCheck.Running -> text(R.string.update_row_checking, null)
            ManualCheck.UpToDate -> text(R.string.update_row_up_to_date, null)
            is ManualCheck.Found -> text(R.string.update_row_found, manual.version)
            ManualCheck.Failed -> text(R.string.update_row_check_failed, null)
            is ManualCheck.RateLimited -> text(R.string.update_row_rate_limited, null)
        },
        // Guarded on Running only, as the row's tap always was: a tap with no
        // tokens left is allowed through so the budget can refuse it out loud.
        action = if (manual is ManualCheck.Running) null else UpdateAction.CHECK,
        actionLabel = null,
        fraction = null,
        notes = installedNotes,
    )
}

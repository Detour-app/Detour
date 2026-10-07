package com.jellemax.detour.update

import com.jellemax.detour.R
import com.jellemax.detour.data.UpdateClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateRowStateTest {

    private fun update() = UpdateClient.PendingUpdate(
        version = "2.14.0",
        asset = "detour-2.14.0.apk",
        downloadUrl = "https://github.com/o/r/releases/download/v2.14.0/detour-2.14.0.apk",
        size = 40L,
        sha256 = "abc",
    )

    /** Stands in for `Context.getString`: the resource id, then the version
     *  argument if one was passed, so each assertion pins both. */
    private fun text(id: Int, version: String? = null) = listOfNotNull(id, version).joinToString(" ")

    private fun row(manual: ManualCheck, status: UpdateStatus, installedNotes: String? = null) =
        updateRowStateFrom(manual, status, installedNotes, ::text)

    @Test fun idleOffersTheCheck() {
        val row = row(ManualCheck.Idle, UpdateStatus.None)
        assertEquals(text(R.string.update_row_check_title), row.title)
        assertEquals(text(R.string.update_row_check_idle), row.subtitle)
        assertEquals(UpdateAction.CHECK, row.action)
        assertNull(row.fraction)
    }

    @Test fun eachCheckOutcomeHasItsOwnString() {
        assertEquals(text(R.string.update_row_checking), row(ManualCheck.Running, UpdateStatus.None).subtitle)
        assertEquals(text(R.string.update_row_up_to_date), row(ManualCheck.UpToDate, UpdateStatus.None).subtitle)
        assertEquals(text(R.string.update_row_check_failed), row(ManualCheck.Failed, UpdateStatus.None).subtitle)
        assertEquals(
            text(R.string.update_row_rate_limited),
            row(ManualCheck.RateLimited(0L), UpdateStatus.None).subtitle,
        )
    }

    @Test fun aRunningCheckOffersNoAction() {
        assertNull(row(ManualCheck.Running, UpdateStatus.None).action)
    }

    @Test fun anAvailableUpdateOffersTheDownload() {
        val row = row(ManualCheck.Found("2.14.0"), UpdateStatus.Available(update()))
        assertEquals(text(R.string.update_available, "2.14.0"), row.title)
        assertEquals(UpdateAction.DOWNLOAD, row.action)
        assertEquals(text(R.string.update_row_download, "2.14.0"), row.actionLabel)
        assertNull(row.notes)
    }

    /** #295: the release's own notes ride along on the Available row so the
     *  Settings screen can offer an expandable "What's new". */
    @Test fun anAvailableUpdateCarriesTheReleaseNotes() {
        val row = row(
            ManualCheck.Found("2.14.0"),
            UpdateStatus.Available(update().copy(notes = "* fix(nav): thing (#225)")),
        )
        assertEquals("* fix(nav): thing (#225)", row.notes)
    }

    /** Notes belong to the decision to download, not to a download already
     *  running or finished — every other status keeps [UpdateRowState.notes]
     *  null even when the underlying [UpdateClient.PendingUpdate] has some. */
    @Test fun notesDoNotSurviveIntoOtherPhases() {
        val withNotes = update().copy(notes = "* fix(nav): thing (#225)")
        assertNull(row(ManualCheck.Idle, UpdateStatus.Downloading(withNotes, 0.5f)).notes)
        assertNull(row(ManualCheck.Idle, UpdateStatus.Downloaded(withNotes, "/tmp/x.apk")).notes)
        assertNull(row(ManualCheck.Idle, UpdateStatus.Failed(withNotes)).notes)
    }

    @Test fun aDownloadInFlightCarriesItsFraction() {
        val row = row(ManualCheck.Found("2.14.0"), UpdateStatus.Downloading(update(), 0.54f))
        assertEquals(text(R.string.update_row_downloading, "2.14.0"), row.title)
        assertEquals(0.54f, row.fraction!!, 0.0001f)
        assertEquals(UpdateAction.CANCEL, row.action)
    }

    @Test fun anUnknownLengthIsANegativeFractionNotAMissingOne() {
        val row = row(ManualCheck.Idle, UpdateStatus.Downloading(update(), -1f))
        assertEquals(-1f, row.fraction!!, 0.0001f)
    }

    @Test fun aFinishedDownloadOffersTheInstall() {
        val row = row(ManualCheck.Idle, UpdateStatus.Downloaded(update(), "/tmp/x.apk"))
        assertEquals(text(R.string.update_ready, "2.14.0"), row.title)
        assertEquals(UpdateAction.INSTALL, row.action)
        assertEquals(text(R.string.update_install), row.actionLabel)
    }

    @Test fun aFailedDownloadOffersTheRetry() {
        val row = row(ManualCheck.Idle, UpdateStatus.Failed(update()))
        assertEquals(text(R.string.update_row_failed, "2.14.0"), row.title)
        assertEquals(UpdateAction.DOWNLOAD, row.action)
        assertEquals(text(R.string.update_row_retry), row.actionLabel)
    }

    @Test fun theArtefactOutranksTheCheck() {
        // A check that just failed must not overwrite a download in flight.
        val row = row(ManualCheck.Failed, UpdateStatus.Downloading(update(), 0.1f))
        assertEquals(text(R.string.update_row_downloading, "2.14.0"), row.title)
    }

    /** With nothing on offer, the row carries the running version's own notes (#359). */
    @Test fun idleCarriesInstalledNotes() {
        assertEquals("* feat: x", row(ManualCheck.Idle, UpdateStatus.None, "* feat: x").notes)
        assertNull(row(ManualCheck.Idle, UpdateStatus.Downloading(update(), 0.5f), "* feat: x").notes)
    }
}

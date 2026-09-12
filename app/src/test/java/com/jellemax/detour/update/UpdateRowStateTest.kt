package com.jellemax.detour.update

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

    @Test fun idleOffersTheCheck() {
        val row = updateRowStateFrom(ManualCheck.Idle, UpdateStatus.None)
        assertEquals("Check for updates", row.title)
        assertEquals("Check for a new release", row.subtitle)
        assertEquals(UpdateAction.CHECK, row.action)
        assertNull(row.fraction)
    }

    @Test fun theCheckOnlyOutcomesKeepTheirWording() {
        assertEquals("Checking…", updateRowStateFrom(ManualCheck.Running, UpdateStatus.None).subtitle)
        assertEquals("No update found", updateRowStateFrom(ManualCheck.UpToDate, UpdateStatus.None).subtitle)
        assertEquals("Couldn't reach GitHub", updateRowStateFrom(ManualCheck.Failed, UpdateStatus.None).subtitle)
        assertEquals(
            "Checked a few times just now — try again shortly",
            updateRowStateFrom(ManualCheck.RateLimited(0L), UpdateStatus.None).subtitle,
        )
    }

    @Test fun aRunningCheckOffersNoAction() {
        assertNull(updateRowStateFrom(ManualCheck.Running, UpdateStatus.None).action)
    }

    @Test fun anAvailableUpdateOffersTheDownload() {
        val row = updateRowStateFrom(ManualCheck.Found("2.14.0"), UpdateStatus.Available(update()))
        assertEquals("Detour 2.14.0 is available", row.title)
        assertEquals(UpdateAction.DOWNLOAD, row.action)
        assertEquals("Download 2.14.0", row.actionLabel)
        assertNull(row.notes)
    }

    /** #295: the release's own notes ride along on the Available row so the
     *  Settings screen can offer an expandable "What's new". */
    @Test fun anAvailableUpdateCarriesTheReleaseNotes() {
        val row = updateRowStateFrom(
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
        assertNull(updateRowStateFrom(ManualCheck.Idle, UpdateStatus.Downloading(withNotes, 0.5f)).notes)
        assertNull(updateRowStateFrom(ManualCheck.Idle, UpdateStatus.Downloaded(withNotes, "/tmp/x.apk")).notes)
        assertNull(updateRowStateFrom(ManualCheck.Idle, UpdateStatus.Failed(withNotes)).notes)
    }

    @Test fun aDownloadInFlightCarriesItsFraction() {
        val row = updateRowStateFrom(ManualCheck.Found("2.14.0"), UpdateStatus.Downloading(update(), 0.54f))
        assertEquals("Downloading 2.14.0", row.title)
        assertEquals(0.54f, row.fraction!!, 0.0001f)
        assertEquals(UpdateAction.CANCEL, row.action)
    }

    @Test fun anUnknownLengthIsANegativeFractionNotAMissingOne() {
        val row = updateRowStateFrom(ManualCheck.Idle, UpdateStatus.Downloading(update(), -1f))
        assertEquals(-1f, row.fraction!!, 0.0001f)
    }

    @Test fun aFinishedDownloadOffersTheInstall() {
        val row = updateRowStateFrom(ManualCheck.Idle, UpdateStatus.Downloaded(update(), "/tmp/x.apk"))
        assertEquals("Detour 2.14.0 is ready", row.title)
        assertEquals(UpdateAction.INSTALL, row.action)
        assertEquals("Install", row.actionLabel)
    }

    @Test fun aFailedDownloadOffersTheRetry() {
        val row = updateRowStateFrom(ManualCheck.Idle, UpdateStatus.Failed(update()))
        assertEquals("Download of 2.14.0 failed", row.title)
        assertEquals(UpdateAction.DOWNLOAD, row.action)
        assertEquals("Retry", row.actionLabel)
    }

    @Test fun theArtefactOutranksTheCheck() {
        // A check that just failed must not overwrite a download in flight.
        val row = updateRowStateFrom(ManualCheck.Failed, UpdateStatus.Downloading(update(), 0.1f))
        assertEquals("Downloading 2.14.0", row.title)
    }
}

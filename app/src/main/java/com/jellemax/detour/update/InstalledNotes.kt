package com.jellemax.detour.update

import com.jellemax.detour.BuildConfig
import com.jellemax.detour.data.Settings

/**
 * What changed in the version now running, for the rider who just installed it
 * through the in-app flow (#359).
 *
 * The notes are the pending release's, persisted when its download started
 * ([UpdateDownloadService]) — no request after the install, which may well be
 * offline. They apply only while that version is the one running
 * ([BuildConfig.VERSION_NAME]), so a fresh install has nothing to show.
 *
 * An update through Play, or an APK sideloaded by hand, is deliberately not
 * handled: neither went through the download that records the notes, so the
 * saved version does not match and nothing is shown.
 */
object InstalledNotes {

    /** The running version's notes, or null. For the Settings row, on demand. */
    fun current(): String? =
        installedNotes(BuildConfig.VERSION_NAME, Settings.pendingNotesVersion(), Settings.pendingNotes())

    /** [current], but only until [markSeen]. For the one-shot on first launch. */
    fun unseen(): String? = unseenNotes(
        BuildConfig.VERSION_NAME,
        Settings.pendingNotesVersion(),
        Settings.pendingNotes(),
        Settings.seenNotesVersion(),
    )

    fun markSeen() = Settings.setSeenNotesVersion(BuildConfig.VERSION_NAME)
}

internal fun installedNotes(running: String, savedVersion: String, savedNotes: String): String? =
    savedNotes.takeIf { savedVersion == running && it.isNotBlank() }

internal fun unseenNotes(running: String, savedVersion: String, savedNotes: String, seenVersion: String): String? =
    installedNotes(running, savedVersion, savedNotes)?.takeIf { seenVersion != running }

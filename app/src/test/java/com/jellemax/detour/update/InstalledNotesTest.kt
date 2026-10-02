package com.jellemax.detour.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstalledNotesTest {

    @Test fun notesForTheRunningVersionAreShown() {
        assertEquals("* fix: y", installedNotes("3.1.0", "3.1.0", "* fix: y"))
    }

    /** Fresh install: nothing was ever saved. */
    @Test fun freshInstallShowsNothing() {
        assertNull(installedNotes("3.1.0", "", ""))
    }

    /** Play update or a hand-sideloaded APK: the saved notes are for another version. */
    @Test fun notesForAnotherVersionAreNotShown() {
        assertNull(installedNotes("3.2.0", "3.1.0", "* fix: y"))
    }

    /** A release published with no body. */
    @Test fun blankNotesAreNotShown() {
        assertNull(installedNotes("3.1.0", "3.1.0", ""))
    }

    /** First launch of the version just installed in-app: shown once. */
    @Test fun unseenNotesAreShownOnce() {
        assertEquals("* fix: y", unseenNotes("3.1.0", "3.1.0", "* fix: y", seenVersion = "3.0.0"))
    }

    /** Dismissed already: the next cold start shows nothing. */
    @Test fun seenNotesAreNotShownAgain() {
        assertNull(unseenNotes("3.1.0", "3.1.0", "* fix: y", seenVersion = "3.1.0"))
    }

    /** Fresh install: nothing saved, nothing seen. */
    @Test fun freshInstallHasNoUnseenNotes() {
        assertNull(unseenNotes("3.1.0", "", "", seenVersion = ""))
    }

    /** Play or hand-sideloaded update: the saved notes are another version's. */
    @Test fun otherVersionsNotesAreNeverUnseen() {
        assertNull(unseenNotes("3.2.0", "3.1.0", "* fix: y", seenVersion = "3.1.0"))
    }
}

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
}

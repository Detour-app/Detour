package com.jellemax.detour.auth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1061: closing the login tab sends no redirect, and busy must still end. */
class PendingSignInTest {

    @After
    fun reset() {
        PendingSignIn.succeed("")
        PendingSignIn.clearSignedIn()
    }

    @Test
    fun returningWithoutARedirectClearsBusy() {
        PendingSignIn.begin()
        PendingSignIn.browserOpened()

        PendingSignIn.returnedFromBrowser()

        assertFalse(PendingSignIn.busy.value)
        assertNull(PendingSignIn.error.value)
    }

    @Test
    fun aRedirectBeforeTheResumeKeepsBusyThroughTheExchange() {
        PendingSignIn.begin()
        PendingSignIn.browserOpened()
        // onNewIntent -> takeSignInRedirect, then onResume, then the exchange.
        PendingSignIn.begin()

        PendingSignIn.returnedFromBrowser()
        assertTrue(PendingSignIn.busy.value)

        PendingSignIn.succeed("rider")
        assertFalse(PendingSignIn.busy.value)
        assertEquals("rider", PendingSignIn.signedInAs.value)
    }

    @Test
    fun aResumeDuringTheRealmProbeDoesNotClearBusy() {
        PendingSignIn.begin()

        PendingSignIn.returnedFromBrowser()

        assertTrue(PendingSignIn.busy.value)
    }

    @Test
    fun browserOpenedAfterAFailureDoesNotArmTheReturn() {
        PendingSignIn.begin()
        PendingSignIn.fail("no browser")
        PendingSignIn.browserOpened()
        PendingSignIn.begin()

        PendingSignIn.returnedFromBrowser()

        assertTrue(PendingSignIn.busy.value)
    }
}

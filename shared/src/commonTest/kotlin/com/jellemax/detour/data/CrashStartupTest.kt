package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** #824: what a start does with a pending crash, for every consent and send outcome. */
class CrashStartupTest {

    @Test
    fun `no pending crash does nothing whatever the consent`() {
        assertEquals(CrashOnStart.NOTHING, CrashStartup.decide(hasPending = false, sendCrashReports = false))
        assertEquals(CrashOnStart.NOTHING, CrashStartup.decide(hasPending = false, sendCrashReports = true))
    }

    @Test
    fun `a pending crash is sent when the rider opted in`() {
        assertEquals(CrashOnStart.SEND, CrashStartup.decide(hasPending = true, sendCrashReports = true))
    }

    @Test
    fun `a pending crash is offered when the rider has not opted in`() {
        assertEquals(CrashOnStart.OFFER, CrashStartup.decide(hasPending = true, sendCrashReports = false))
    }

    @Test
    fun `a sent crash is cleared`() {
        assertEquals(true, CrashStartup.clearAfter(SendResult.Sent("r-1")))
    }

    @Test
    fun `a crash the server can't take is cleared rather than retried every start`() {
        assertEquals(true, CrashStartup.clearAfter(SendResult.Unsupported))
    }

    @Test
    fun `a failed send keeps the crash for the next start`() {
        assertEquals(false, CrashStartup.clearAfter(SendResult.Failed))
    }
}

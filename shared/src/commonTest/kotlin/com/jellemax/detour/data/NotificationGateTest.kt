package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Covers NotificationGate.kt: what the master switch (#977) holds back. */
class NotificationGateTest {

    @Test
    fun masterOnAllowsEveryKind() {
        NotificationKind.entries.forEach {
            assertTrue(NotificationGate.allows(it, masterOn = true), "$it blocked with the switch on")
        }
    }

    @Test
    fun masterOffAllowsOnlyTheOngoingOnes() {
        val allowed = NotificationKind.entries.filter { NotificationGate.allows(it, masterOn = false) }
        assertEquals(
            setOf(NotificationKind.TRIP_RECORDING, NotificationKind.UPDATE_DOWNLOAD_PROGRESS),
            allowed.toSet(),
        )
    }

    @Test
    fun masterOffHoldsBackEveryKindTheIssueNames() {
        // #977's list: circle arrive/depart and catch-up, push (circle wake and
        // ride reminder), ride reminders, trip-ended, in-app update.
        listOf(
            NotificationKind.CIRCLE_PLACE,
            NotificationKind.RIDE_REMINDER,
            NotificationKind.TRIP_ENDED,
            NotificationKind.UPDATE_AVAILABLE,
            NotificationKind.UPDATE_INSTALLED,
        ).forEach {
            assertTrue(!NotificationGate.allows(it, masterOn = false), "$it posted with the switch off")
        }
    }
}

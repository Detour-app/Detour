package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayPinTest {
    private val board = "AA:BB:CC:DD:EE:01"
    private val stranger = "AA:BB:CC:DD:EE:02"

    @Test
    fun firstSubscriberIsPinned() {
        assertEquals(board, DisplayPin.pinOnSubscribe(null, board))
    }

    @Test
    fun laterSubscriberDoesNotMoveThePin() {
        assertEquals(board, DisplayPin.pinOnSubscribe(board, stranger))
    }

    @Test
    fun telemetryFromThePinnedDisplayIsBelieved() {
        assertTrue(DisplayPin.acceptsTelemetry(board, board))
    }

    @Test
    fun telemetryFromAnotherDeviceIsDropped() {
        assertFalse(DisplayPin.acceptsTelemetry(board, stranger))
    }

    @Test
    fun telemetryBeforeAnyDisplaySubscribedIsDropped() {
        assertFalse(DisplayPin.acceptsTelemetry(null, board))
    }
}

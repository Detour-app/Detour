package com.jellemax.detour.tracking

import com.jellemax.detour.data.EventLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** #822: a dormancy outcome is logged once per change, not once per evaluation. */
class DormancyEventsTest {

    private val lines = mutableListOf<String>()

    @Before
    fun install() {
        EventLog.sink = { lines += it.substringAfter(' ') }
    }

    @After
    fun uninstall() {
        EventLog.sink = null
    }

    @Test
    fun repeatsOfTheSameOutcomeLogOnce() {
        val events = DormancyEvents()
        repeat(3) { events.record(DormancyDecision.STAY_ALIVE, DormancyDecision.STAY_ALIVE) }
        events.record(DormancyDecision.STOP_BARE, DormancyDecision.STOP_BARE)
        events.record(DormancyDecision.STAY_ALIVE, DormancyDecision.STAY_ALIVE)
        assertEquals(listOf("DORMANCY STAY_ALIVE", "DORMANCY STOP_BARE", "DORMANCY STAY_ALIVE"), lines)
    }

    @Test
    fun aParkTheServiceCouldNotHonourSaysWhatItWanted() {
        val events = DormancyEvents()
        events.record(DormancyDecision.STOP_WITH_GEOFENCE, DormancyDecision.STAY_ALIVE)
        // Same resolved outcome, different reason: a new line.
        events.record(DormancyDecision.STAY_ALIVE, DormancyDecision.STAY_ALIVE)
        assertEquals(listOf("DORMANCY STAY_ALIVE (wanted STOP_WITH_GEOFENCE)", "DORMANCY STAY_ALIVE"), lines)
    }
}

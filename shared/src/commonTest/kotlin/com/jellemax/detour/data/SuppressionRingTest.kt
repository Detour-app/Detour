package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The durable record of what the #273 gate dropped. `Log.i` goes to logcat and
 * does not survive the session, and a gate that silently drops events makes a
 * genuinely swallowed transition invisible — so every suppression is kept,
 * bounded, and shown in Settings → Diagnostics.
 *
 * Codec and ring tested standalone: `Settings.init()` needs a real `Prefs` this
 * module does not have (see [SettingsPlaceFenceIdsTest]).
 */
class SuppressionRingTest {

    private val c = "11111111-2222-3333-4444-555555555555"

    private fun row(ts: Long) = SuppressedPlaceEvent(
        circleId = c, placeId = 7L, kind = GeofenceKind.ARRIVE,
        tsMs = ts, reason = SuppressionReason.DUPLICATE,
    )

    @Test
    fun oneRowRoundTrips() {
        val rows = listOf(row(1_700_000_000_000L))
        assertEquals(rows, decodeSuppressions(encodeSuppressions(rows)))
    }

    @Test
    fun anEmptyRingRoundTrips() {
        assertEquals(emptyList(), decodeSuppressions(encodeSuppressions(emptyList())))
    }

    @Test
    fun theStoredDefaultDecodesToEmpty() {
        assertEquals(emptyList(), decodeSuppressions(""))
    }

    @Test
    fun anUnparseableRowIsSkippedRatherThanCrashing() {
        assertEquals(emptyList(), decodeSuppressions("garbage"))
    }

    @Test
    fun appendingKeepsTheNewestAndDropsTheOldest() {
        var ring = emptyList<SuppressedPlaceEvent>()
        for (i in 1L..(MAX_SUPPRESSIONS_KEPT + 5L)) ring = appendSuppression(ring, row(i))
        assertEquals(MAX_SUPPRESSIONS_KEPT, ring.size)
        assertEquals(MAX_SUPPRESSIONS_KEPT + 5L, ring.first().tsMs) // newest first
        assertEquals(6L, ring.last().tsMs)
    }

    @Test
    fun bothReasonsSurviveTheRoundTrip() {
        val rows = listOf(
            row(1L).copy(reason = SuppressionReason.NO_ARRIVE_TO_DEPART_FROM, kind = GeofenceKind.DEPART),
            row(2L),
        )
        assertEquals(rows, decodeSuppressions(encodeSuppressions(rows)))
    }
}

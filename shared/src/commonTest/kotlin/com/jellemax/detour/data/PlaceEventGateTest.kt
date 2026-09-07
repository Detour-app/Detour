package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The alternation rule that makes one real transition produce one announcement
 * (#273). Pure over the confirmed-inside set, because `shared` tests have no
 * `Prefs` backend — the same reason [SettingsPlaceFenceIdsTest] tests the codec
 * rather than [Settings].
 */
class PlaceEventGateTest {

    private val c = "11111111-2222-3333-4444-555555555555"

    @Test
    fun anArriveIsPostedWhenNothingWasAnnouncedForThatPlace() {
        assertEquals(
            PlaceEventDecision.Post,
            decidePlaceEvent(emptySet(), c, 7L, GeofenceKind.ARRIVE),
        )
    }

    /** The #273 duplicate: the OS fence and the poll tick both detect one
     *  arrival, and the second one lands with the memory already set. */
    @Test
    fun aSecondArriveWithNoDepartBetweenIsSuppressed() {
        assertEquals(
            PlaceEventDecision.Suppress(SuppressionReason.DUPLICATE),
            decidePlaceEvent(setOf("$c:7"), c, 7L, GeofenceKind.ARRIVE),
            "#273: the OS fence and the poll tick both detecting one arrival must not announce it twice",
        )
    }

    @Test
    fun aDepartIsPostedOnlyFromAConfirmedArrive() {
        assertEquals(
            PlaceEventDecision.Post,
            decidePlaceEvent(setOf("$c:7"), c, 7L, GeofenceKind.DEPART),
        )
    }

    /** A drive-past clips the outer ring without ever dwelling; the evaluator
     *  produces nothing there and neither may this. */
    @Test
    fun aDepartWithNoArriveToDepartFromIsSuppressed() {
        assertEquals(
            PlaceEventDecision.Suppress(SuppressionReason.NO_ARRIVE_TO_DEPART_FROM),
            decidePlaceEvent(emptySet(), c, 7L, GeofenceKind.DEPART),
            "#273: a drive-past clipping the outer ring with no dwell must not announce a depart nobody arrived for",
        )
    }

    @Test
    fun keysAreScopedToTheirCircleAndPlace() {
        val confirmed = setOf("$c:7")
        assertEquals(PlaceEventDecision.Post, decidePlaceEvent(confirmed, c, 8L, GeofenceKind.ARRIVE))
        assertEquals(PlaceEventDecision.Post, decidePlaceEvent(confirmed, "other", 7L, GeofenceKind.ARRIVE))
    }

    @Test
    fun theKeyIsTheCircleAndPlaceJoinedByAColon() {
        assertEquals("$c:7", placeEventKey(c, 7L))
    }
}

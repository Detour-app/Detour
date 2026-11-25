package com.jellemax.detour.car

import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import com.jellemax.detour.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** #1082: the host draws no turn card for a maneuver without an icon, and
 *  prints a metre distance exactly as given. */
class CarManeuverTest {

    @Test
    fun everyManeuverTheRouterProducesGetsItsOwnGlyph() {
        val expected = mapOf(
            Maneuver.TYPE_U_TURN_LEFT to R.drawable.ic_car_maneuver_u_turn_left,
            Maneuver.TYPE_U_TURN_RIGHT to R.drawable.ic_car_maneuver_u_turn_right,
            Maneuver.TYPE_KEEP_LEFT to R.drawable.ic_car_maneuver_keep_left,
            Maneuver.TYPE_KEEP_RIGHT to R.drawable.ic_car_maneuver_keep_right,
            Maneuver.TYPE_TURN_SHARP_LEFT to R.drawable.ic_car_maneuver_turn_sharp_left,
            Maneuver.TYPE_TURN_NORMAL_LEFT to R.drawable.ic_car_maneuver_turn_left,
            Maneuver.TYPE_TURN_SLIGHT_LEFT to R.drawable.ic_car_maneuver_turn_slight_left,
            Maneuver.TYPE_STRAIGHT to R.drawable.ic_car_maneuver_straight,
            Maneuver.TYPE_TURN_SLIGHT_RIGHT to R.drawable.ic_car_maneuver_turn_slight_right,
            Maneuver.TYPE_TURN_NORMAL_RIGHT to R.drawable.ic_car_maneuver_turn_right,
            Maneuver.TYPE_TURN_SHARP_RIGHT to R.drawable.ic_car_maneuver_turn_sharp_right,
            Maneuver.TYPE_DESTINATION to R.drawable.ic_car_maneuver_destination,
            Maneuver.TYPE_ROUNDABOUT_ENTER_CCW to R.drawable.ic_car_maneuver_roundabout,
            Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW to R.drawable.ic_car_maneuver_roundabout,
            Maneuver.TYPE_ROUNDABOUT_EXIT_CCW to R.drawable.ic_car_maneuver_roundabout,
        )
        expected.forEach { (type, icon) -> assertEquals("type $type", icon, maneuverIcon(type)) }
    }

    @Test
    fun metreDistanceIsRoundedToTenMetres() {
        val d = carDistance(431.9)
        assertEquals(430.0, d.displayDistance, 0.0)
        assertEquals(Distance.UNIT_METERS, d.displayUnit)
    }

    @Test
    fun distanceThatRoundsToAKilometreSwitchesUnit() {
        val d = carDistance(996.0)
        assertEquals(1.0, d.displayDistance, 0.0)
        assertEquals(Distance.UNIT_KILOMETERS_P1, d.displayUnit)
        assertEquals(12.3, carDistance(12_345.0).displayDistance, 0.0)
    }
}

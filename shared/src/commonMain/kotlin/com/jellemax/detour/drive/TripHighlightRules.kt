package com.jellemax.detour.drive

import com.jellemax.detour.data.RidingEvent
import com.jellemax.detour.data.RidingEventKind
import com.jellemax.detour.data.TripMoments
import com.jellemax.detour.data.TripPeak
import kotlin.math.abs

/** An event's strength in words. [LEAN] is the one that stays a number:
 *  riders already read a lean angle. */
enum class EventStrength { HARD, VERY_HARD, SHARP, VERY_SHARP, LEAN }

/** The pins a trip earns, each null when the trip recorded none. The best
 *  stretch is a line, not a pin, so it is not here. */
data class TripHighlightPicks(
    val lean: TripPeak?,
    val corner: RidingEvent?,
    val brake: RidingEvent?,
    val topSpeed: TripPeak?,
)

/**
 * Which moments of a trip get a pin on its detail screen, and how hard an
 * event reads in words. The rule lives here so the phone and iOS agree; each
 * surface keeps its own strings and formatters and only maps [EventStrength]
 * and [TripHighlightPicks] onto them.
 */
object TripHighlightRules {
    /** How far past its detection threshold an event has to go to read as "very". */
    const val VERY_HARD_FACTOR = 1.5

    fun isCorner(e: RidingEvent): Boolean =
        e.kind == RidingEventKind.HARD_CORNER_LEAN || e.kind == RidingEventKind.HARD_CORNER_TURN

    fun strength(e: RidingEvent): EventStrength {
        val threshold = when (e.kind) {
            RidingEventKind.HARD_BRAKE -> HardEventDetector.HARD_BRAKE_MPS2
            RidingEventKind.HARD_ACCEL -> HardEventDetector.HARD_ACCEL_MPS2
            RidingEventKind.HARD_CORNER_TURN -> HardEventDetector.HARD_CORNER_DEG_PER_SEC
            RidingEventKind.HARD_CORNER_LEAN -> return EventStrength.LEAN
        }
        val very = abs(e.magnitude) >= abs(threshold) * VERY_HARD_FACTOR
        return when {
            e.kind == RidingEventKind.HARD_CORNER_TURN -> if (very) EventStrength.VERY_SHARP else EventStrength.SHARP
            else -> if (very) EventStrength.VERY_HARD else EventStrength.HARD
        }
    }

    /** [tracksLean] is the trip's mode: a car's lean is noise and gets no pin. */
    fun picks(moments: TripMoments, tracksLean: Boolean): TripHighlightPicks = TripHighlightPicks(
        lean = moments.maxLean?.takeIf { tracksLean && it.value != 0.0 },
        corner = moments.events.filter(::isCorner).maxByOrNull { abs(it.magnitude) },
        // Braking is negative, so the hardest is the smallest.
        brake = moments.events.filter { it.kind == RidingEventKind.HARD_BRAKE }.minByOrNull { it.magnitude },
        topSpeed = moments.topSpeed,
    )
}

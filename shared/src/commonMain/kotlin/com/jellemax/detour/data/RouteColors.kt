package com.jellemax.detour.data

/**
 * The two colours a route line is drawn in: the one for the road ahead, and the
 * dimmed one the part you have already driven fades to.
 *
 * Both live in `:shared` rather than in each platform's map code so a stored
 * [Settings.RouteColor] resolves to exactly the same hex on the phone map, the
 * car screen and the iPhone. A "green route" that is one green on Android and
 * another on iOS is precisely the drift a shared setting exists to prevent —
 * and the dimmed colour is worse still, since it is derived rather than picked,
 * so two implementations of the same blend would never quite agree.
 */
object RouteColors {

    /** What [Settings.RouteColor.THEME] follows: the Graphite night accent on
     *  the dark basemap, the day accent on the light one. This pair is what the
     *  route line was before it could be recoloured at all. */
    const val THEME_DARK = "#E8B04B"
    const val THEME_LIGHT = "#2F80ED"

    /** The line ahead of you, as `#RRGGBB`. */
    fun hex(color: Settings.RouteColor, darkTheme: Boolean): String = when (color) {
        Settings.RouteColor.THEME -> if (darkTheme) THEME_DARK else THEME_LIGHT
        Settings.RouteColor.AMBER -> "#E8B04B"
        Settings.RouteColor.BLUE -> "#2F80ED"
        Settings.RouteColor.GREEN -> "#35C759"
        Settings.RouteColor.TEAL -> "#00BCD4"
        Settings.RouteColor.PURPLE -> "#9B5DE5"
        Settings.RouteColor.PINK -> "#F15BB5"
        Settings.RouteColor.RED -> "#EF4444"
    }

    /**
     * The same line once you have driven it: blended most of the way into the
     * basemap, so the road behind reads as spent while keeping enough of its
     * hue to still be recognisably *the route*.
     *
     * Opaque rather than translucent, still. Android now paints it as one end
     * of a gradient along a single line rather than as a second line laid over
     * the first, so it no longer has anything to hide — but the route line sits
     * on a dark casing, and an alpha here would let that casing decide how dim
     * "driven" comes out. Opaque keeps the two ends of [drivenRamp] the same
     * distance apart wherever the line runs.
     */
    fun drivenHex(color: Settings.RouteColor, darkTheme: Boolean): String =
        mix(hex(color, darkTheme), if (darkTheme) DRIVEN_TOWARDS_DARK else DRIVEN_TOWARDS_LIGHT, DRIVEN_MIX)

    /** One entry of a route-line colour ramp: [hex] at [at] of the way along
     *  the line. */
    data class ColorStop(val at: Double, val hex: String)

    /**
     * The route line as a colour ramp along its own length, with
     * [drivenFraction] (0..1) of it behind you: [drivenHex] up to the seam,
     * [hex] after it. Always four stops, strictly ascending, spanning 0..1 —
     * a renderer rejects a ramp whose stops repeat a position, and a fixed
     * shape is one a caller can hand straight to an interpolation.
     *
     * The seam is a short blend rather than a hard edge because the ramp is
     * *sampled*, not drawn: MapLibre bakes it into a 256-pixel texture spanning
     * the whole line, so a hard step snaps to whichever of those 256 positions
     * is nearest and jumps a whole one at a time as you drive. Blending across
     * one sample keeps the sampled values changing continuously while the seam
     * advances, which is what lets it glide instead of hop.
     */
    fun drivenRamp(
        color: Settings.RouteColor,
        darkTheme: Boolean,
        drivenFraction: Double,
    ): List<ColorStop> {
        val ahead = hex(color, darkTheme)
        val behind = drivenHex(color, darkTheme)
        val f = drivenFraction.coerceIn(0.0, 1.0)
        // Within one blend of either end there is no seam to place: both sides
        // take the same colour, and the two inner stops stay only to keep the
        // four ascending. Parked just inside the end the seam is nearest, so
        // the ramp still reads in the direction of travel.
        val seam = f.coerceIn(SEAM_BLEND, 1.0 - 2.0 * SEAM_BLEND)
        val start = if (f < SEAM_BLEND) ahead else behind
        val end = if (f > 1.0 - SEAM_BLEND) behind else ahead
        return listOf(
            ColorStop(0.0, start),
            ColorStop(seam, start),
            ColorStop(seam + SEAM_BLEND, end),
            ColorStop(1.0, end),
        )
    }

    /** Picker label. */
    fun label(color: Settings.RouteColor): String = when (color) {
        Settings.RouteColor.THEME -> "Theme"
        Settings.RouteColor.AMBER -> "Amber"
        Settings.RouteColor.BLUE -> "Blue"
        Settings.RouteColor.GREEN -> "Green"
        Settings.RouteColor.TEAL -> "Teal"
        Settings.RouteColor.PURPLE -> "Purple"
        Settings.RouteColor.PINK -> "Pink"
        Settings.RouteColor.RED -> "Red"
    }

    /** Every choice, in picker order. `enum.entries` has no Objective-C
     *  representation, so iOS builds its picker from this. */
    val all: List<Settings.RouteColor> = Settings.RouteColor.entries.toList()

    /** How far the driven colour is blended away from the live one. High enough
     *  that a glance separates "behind me" from "ahead of me" without a second
     *  look, low enough that the hue survives it. */
    private const val DRIVEN_MIX = 0.62

    /** How much of the line the seam between driven and undriven fades over,
     *  as a share of the line's length: one pixel of MapLibre's 256-pixel
     *  gradient ramp, the narrowest blend that still moves smoothly. */
    private const val SEAM_BLEND = 1.0 / 256.0

    /** What the driven colour is blended *towards*: the dark route casing under
     *  the night basemap, white under the day one. Fading towards the map is
     *  what makes it read as faded rather than as a second, different route. */
    private const val DRIVEN_TOWARDS_DARK = "#0B1220"
    private const val DRIVEN_TOWARDS_LIGHT = "#FFFFFF"

    /** [from] blended [t] of the way to [to], both `#RRGGBB`. */
    private fun mix(from: String, to: String, t: Double): String {
        val a = rgb(from)
        val b = rgb(to)
        return "#" + (0..2).joinToString("") { i ->
            val v = (a[i] + (b[i] - a[i]) * t).toInt().coerceIn(0, 255)
            v.toString(16).padStart(2, '0').uppercase()
        }
    }

    /** `#RRGGBB` split into three 0..255 channels. */
    private fun rgb(hex: String): DoubleArray {
        val body = hex.removePrefix("#")
        return DoubleArray(3) { i ->
            body.substring(i * 2, i * 2 + 2).toInt(16).toDouble()
        }
    }
}

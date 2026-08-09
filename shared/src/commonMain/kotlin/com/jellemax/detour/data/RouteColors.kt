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
     * Opaque rather than translucent on purpose. The driven line is drawn over
     * the live one (see `MapOverlays` on Android), and a translucent colour
     * would simply let the bright line show through instead of dimming it.
     */
    fun drivenHex(color: Settings.RouteColor, darkTheme: Boolean): String =
        mix(hex(color, darkTheme), if (darkTheme) DRIVEN_TOWARDS_DARK else DRIVEN_TOWARDS_LIGHT, DRIVEN_MIX)

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

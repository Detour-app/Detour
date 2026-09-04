package com.jellemax.detour.presentation

/** Everything the You screen renders, already formatted. No Android or file types. */
data class YouState(
    val signedIn: Boolean = false,
    val username: String = "",
    val avatarInitial: String = "",
    val kilometresLabel: String = "—",
    val rides: Int = 0,
    val places: Int = 0,
    val badgesEarned: Int = 0,
    val badgeFractionLabel: String = "—",
)

/**
 * Pure map from raw stats to display values. Kept separate from [YouPresenter] so
 * it is callable from commonTest with literal arguments and no file system — the
 * house rule for testable shared logic.
 */
fun youStateFrom(
    username: String,
    signedIn: Boolean,
    totalDistanceMeters: Double,
    tripCount: Int,
    placesCount: Int,
    badgesEarned: Int,
    badgesTotal: Int,
): YouState = YouState(
    signedIn = signedIn,
    username = username,
    avatarInitial = avatarInitialOf(username),
    kilometresLabel = groupThousandsWithSpace((totalDistanceMeters / 1000.0).toLong()),
    rides = tripCount,
    places = placesCount,
    badgesEarned = badgesEarned,
    badgeFractionLabel = "$badgesEarned / $badgesTotal",
)

/**
 * "12480" -> "12 480". Locale-independent: commonMain has no NumberFormat.
 *
 * Strips the sign off the string form first (rather than `abs()`, which
 * overflows on [Long.MIN_VALUE]) so it never counts the `-` as a digit
 * position, then reattaches it to the first grouped digit.
 */
private fun groupThousandsWithSpace(n: Long): String {
    val negative = n < 0
    val digits = n.toString().removePrefix("-")
    val sb = StringBuilder()
    for ((i, c) in digits.withIndex()) {
        if (i > 0 && (digits.length - i) % 3 == 0) sb.append(' ') // plain ASCII space, the char the test asserts
        sb.append(c)
    }
    return if (negative) "-$sb" else sb.toString()
}

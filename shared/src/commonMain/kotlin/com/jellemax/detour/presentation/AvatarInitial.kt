package com.jellemax.detour.presentation

/**
 * The letter shown in an avatar circle: the first character of [username]
 * after trimming, upper-cased, or `"?"` when that leaves nothing (a blank or
 * all-whitespace username, including the signed-out case).
 *
 * Shared so every screen that renders an avatar circle — You, Social,
 * Profile — agrees. Trimming first matters: a leading space is not `null`,
 * so a naive `firstOrNull()` renders a blank circle instead of the rider's
 * real initial.
 */
fun avatarInitialOf(username: String): String =
    username.trim().take(1).uppercase().ifEmpty { "?" }

package com.jellemax.detour.presentation

/** How long the map waits without a touch before it dims (#902, owner decision 2A on #892). */
const val MAP_DIM_AFTER_MS = 30_000L

/** The window brightness fraction (0..1) the idle map dims to. A window override,
 *  not the system setting, so it lapses the moment the map is left. */
const val MAP_DIM_BRIGHTNESS = 0.1f

/**
 * Whether the map should be dimmed right now (#902). The map keeps the screen on
 * (#703); off-route, a map nobody has touched for [MAP_DIM_AFTER_MS] drops to
 * [MAP_DIM_BRIGHTNESS] instead. Navigating a route never dims, nor does a dialog
 * or the search field: those are separate windows the map never sees a touch
 * from, so the rider would be typing into a screen that dims under them.
 */
fun mapScreenDimmed(
    enabled: Boolean,
    navigating: Boolean,
    dialogOpen: Boolean,
    msSinceInteraction: Long,
): Boolean = enabled && !navigating && !dialogOpen && msSinceInteraction >= MAP_DIM_AFTER_MS

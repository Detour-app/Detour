package com.jellemax.detour.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Every place the phone UI can be, as a key on an owned back stack.
 *
 * This replaces three separate single-value models: `screen` in `MainActivity`,
 * `page` in `SettingsScreen`, and `CirclesStore.selectedId`, which drove Circles'
 * list/detail navigation from a store rather than from navigation state at all.
 * None of the three could say which way the rider travelled — `SETTINGS -> HUB`
 * and `HUB -> SETTINGS` are the same pair of values in the opposite order — so
 * each needed a `depth` table and a hand-written parent-guessing `BackHandler`
 * beside it, with nothing checking that the two agreed.
 *
 * A stack does not need either. Direction is whether the list grew or shrank,
 * and a destination's parent is whatever is underneath it, recorded when the
 * rider pushed rather than declared in a table.
 *
 * ## Why these carry ids and not objects
 *
 * [TripDetail] and [RouteEditor] used to be handed a `Trip` and a `SavedRoute?`
 * held in `AppRoot` locals. A key has to survive being written to saved state
 * and read back after the process died, so they carry the identity instead and
 * each screen loads its own subject. Note `Trip` has no id field — trips are
 * keyed by `startTimeMs` (`shared/.../data/TripStore.kt`) — and `TripStore.load()`
 * reads and parses a file, so that load stays off the main thread the way
 * `HistoryScreen` already does it.
 *
 * ## What must never become a key
 *
 * The stack is persisted into the Activity's saved state, which reaches disk.
 * So no key carries a token, a credential, or anything derived from one. The
 * sign-in leg deliberately does **not** survive a process death: `Oidc`'s
 * pending verifier and state are held in memory only, because ASVS 5.0.0
 * V10.1.2 wants the PKCE verifier bound to the transaction and the user agent
 * that began it (`shared/.../data/Oidc.kt:39-55`). Restoring where the rider
 * *was* is this file's job; resuming an interrupted sign-in is not, and making
 * it one would mean writing that secret down.
 */
@Serializable
sealed interface Destination : NavKey {

    /** The map. The root of the stack: back from here leaves the app. */
    @Serializable
    data object Map : Destination

    @Serializable
    data object Hub : Destination

    @Serializable
    data object History : Destination

    /** A recorded ride, identified the only way a trip can be. */
    @Serializable
    data class TripDetail(val startTimeMs: Long) : Destination

    @Serializable
    data object Badges : Destination

    @Serializable
    data object CoverageMap : Destination

    @Serializable
    data object Friends : Destination

    @Serializable
    data object Circles : Destination

    /**
     * One circle. Was `CirclesStore.selectedId` plus a `BackHandler` inside the
     * screen's own top bar, which had to de-select before it would let `onBack()`
     * leave (`CirclesScreen.kt:126-129`). On the stack that is one pop.
     */
    @Serializable
    data class CircleDetail(val circleId: String) : Destination

    @Serializable
    data object SavedPlaces : Destination

    @Serializable
    data object Routes : Destination

    /** The route editor. A null [routeId] means a new route. */
    @Serializable
    data class RouteEditor(val routeId: Long?) : Destination

    /** The Settings root: the six rows that lead to the [SettingsSpoke]s. */
    @Serializable
    data object Settings : Destination

    /**
     * The pages under Settings.
     *
     * App-level destinations rather than a nested stack, so each owns its own
     * `Scaffold` and `SubScreenTopBar` the way `HistoryScreen` and `BadgesScreen`
     * do. Settings used to be the one push in this app where the top bar did not
     * travel with the content — it animated inside the Scaffold, so the title
     * snapped while the body slid. That was a consequence of #66 keeping the
     * spokes out of the `Screen` enum, a constraint this file removes, and the
     * outlier is gone with it.
     *
     * Their own sealed sub-interface so the `when` that renders them is
     * exhaustive over the spokes rather than over every destination in the app.
     * #102 added the seventh, OBD2, while this was in review — the exhaustive
     * `when` in `spokeTitle` is what refused to compile until it had a title.
     */
    @Serializable
    sealed interface SettingsSpoke : Destination

    @Serializable
    data object SettingsAppearanceMap : SettingsSpoke

    @Serializable
    data object SettingsTrackingVehicles : SettingsSpoke

    @Serializable
    data object SettingsNavigation : SettingsSpoke

    @Serializable
    data object SettingsFog : SettingsSpoke

    @Serializable
    data object SettingsDisplaysMedia : SettingsSpoke

    @Serializable
    data object SettingsServersSync : SettingsSpoke

    @Serializable
    data object SettingsObd2 : SettingsSpoke
}

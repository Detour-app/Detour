package com.jellemax.detour.car

import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.jellemax.detour.R
import com.jellemax.detour.data.GeoTarget
import com.jellemax.detour.data.GeocodeResult
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RecentSearchStore
import com.jellemax.detour.data.RoutingClient
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.SavedPlaces
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.data.parseGeoUri
import com.jellemax.detour.map.startHeading
import com.jellemax.detour.presentation.failureText
import com.jellemax.detour.presentation.SearchSession
import com.jellemax.detour.tracking.TripTrackingService
import com.jellemax.detour.tracking.hasLocationPermission
import com.jellemax.detour.ui.androidSearchSession
import com.jellemax.detour.ui.labelRes
import com.jellemax.detour.ui.searchResultDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pick a destination by name from the car screen, or hand off to
 * [SpinScreen]'s roulette instead. Same Photon backend as the phone's search,
 * and the same [RecentSearchStore] — so a place searched on the phone is one
 * tap away here, which matters when typing on a head unit is the slowest
 * thing in the car. The search itself is the phone's too: the shared
 * [SearchSession] (#1005), so its floor, debounce, bias and recents merge
 * match the map's search island.
 *
 * This is also where Spin lives now — as the first row, before any recents —
 * rather than as a second, title-bearing action strip button crowding the map
 * screen. One door into "pick a destination", typed or spun, is the layout
 * Google's Auto App Quality review expected.
 */
class SearchScreen(
    carContext: CarContext,
    private val renderer: CarMapRenderer,
    /** Runs [SpinScreen]'s spin and pops back to it to show the pick. Owned by
     *  that screen, not duplicated here, since it already holds the location,
     *  radius and preview state a spin needs. */
    private val onSpin: () -> Unit,
    /** A `geo:` URI the head unit handed us ("Navigate to X"): coordinates
     *  start routing straight away, a `q=` text runs as a search. */
    private val geoUri: Uri? = null,
) : Screen(carContext) {

    private val session = androidSearchSession()
    private val queries = MutableStateFlow("")
    private var query = ""
    /** Routing to a picked result is in flight. */
    private var routing = false
    /** Why routing to a pick failed, or that there is no location to route from yet. */
    private var routeError: String? = null
    private var myLocation: LatLon? = null
    /** Picked before there was a location to route from; routes when one comes in. */
    private var pendingDestination: GeocodeResult? = null
    /** [geoUri] acted on already: onStart runs again each time the screen comes back. */
    private var geoTaken = false

    init {
        // Created once per screen: the session answers every keystroke until the screen goes.
        lifecycleScope.launch { session.run(queries) }
        lifecycleScope.launch { session.state.collect { invalidate() } }
        // Same bias source as the phone's search: the tracker's last accurate fix.
        lifecycleScope.launch {
            TripTrackingService.lastFix.collect { fix ->
                fix ?: return@collect
                session.onFix(LatLon(fix.lat, fix.lon), fix.accuracyMeters)
                // A tracker fix is as good a start as the fused last location,
                // and the only one there is when that comes back empty.
                if (myLocation == null) onLocation(LatLon(fix.lat, fix.lon))
            }
        }
        // Saved places above recents, as on the phone (#1007). The car can start without
        // MainActivity ever running, so this screen loads the store itself.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { SavedPlaces.ensureLoaded() }
            SavedPlaces.places.collect { session.setSavedPlaces(it) }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                fetchLocation()
                takeGeoUri()
                lifecycleScope.launch {
                    session.setRecents(withContext(Dispatchers.IO) { RecentSearchStore.load() })
                }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val builder = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = onQuery(searchText)
            override fun onSearchSubmitted(searchText: String) = onQuery(searchText)
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint(carContext.getString(R.string.car_search_hint))
            .setShowKeyboardByDefault(false)

        val search = session.state.value
        if (routing || search.searching) return builder.setLoading(true).build()

        val list = ItemList.Builder()
        // First, whatever the query: a "Navigate to X" pick has no query, and its
        // wait for a location or routing failure has to show all the same.
        if (routeError != null) {
            list.setNoItemsMessage(routeError!!)
        } else if (query.isBlank()) {
            // Spin only makes sense before you've typed anything — once
            // there's a query the list is search results, not destination
            // pickers. Always present even with no recents yet, so the list
            // is never empty here and "type to search" never has to be said.
            list.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_search_spin_title))
                    .addText(carContext.getString(R.string.car_search_spin_text))
                    .setOnClickListener {
                        onSpin()
                        screenManager.pop()
                    }
                    .build(),
            )
            // Suggestions (saved places, then recents), not results: the session answers a
            // cleared query a moment after the host reports it, and results would hold the
            // old hits till then.
            search.suggestions.forEach { result ->
                list.addItem(resultRow(result, search.near))
            }
        } else when {
            search.failure != null -> list.setNoItemsMessage(failureText("Search", search.failure!!))
            search.noResults -> list.setNoItemsMessage(carContext.getString(R.string.car_search_nothing_found))
            else -> search.results.forEach { result ->
                list.addItem(resultRow(result, search.near))
            }
        }
        return builder.setItemList(list.build()).build()
    }

    /** A hit as the phone lists it, kind, second line and distance as the row's text (#1006). */
    private fun resultRow(result: GeocodeResult, near: LatLon?): Row =
        Row.Builder()
            .setTitle(result.name)
            .addText(searchResultDetail(result, carContext.getString(result.kind.labelRes()), near))
            .setOnClickListener { navigateTo(result) }
            .build()

    private fun onQuery(text: String) {
        query = text
        routeError = null
        pendingDestination = null
        queries.value = text
        invalidate()
    }

    private fun navigateTo(result: GeocodeResult) {
        val from = myLocation ?: run {
            // Routes once the first location comes in (onLocation).
            pendingDestination = result
            routeError = carContext.getString(R.string.car_waiting_for_location)
            fetchLocation()
            invalidate()
            return
        }
        routing = true
        routeError = null
        invalidate()
        lifecycleScope.launch {
            try {
                val config = withContext(Dispatchers.IO) { RoutingServer.load() }
                // #719: picked on the move, the first line continues forward
                // like NavScreen's reroute does, instead of leaving via an onramp.
                val heading = startHeading()
                val route = withContext(Dispatchers.IO) {
                    // The rider's preferences, because `NavScreen`'s reroute
                    // passes them and a trip whose first reroute changes its
                    // own routing policy is worse than either setting applied
                    // consistently. RoutingClient.route defaults to none, so
                    // omitting them silently requested a default route.
                    RoutingClient.route(config, from, result.location, TravelMode.CAR.ghProfile,
                        Settings.routePreferences(), heading)
                }
                if (!session.state.value.isSaved(result)) {
                    withContext(Dispatchers.IO) { RecentSearchStore.save(result) }
                }
                routing = false
                if (route.instructions.isEmpty()) {
                    // No turn data to drive our own nav screen with — hand off
                    // the same way SpinScreen does. Should that land back in
                    // Detour, the session stops it (CarNavHandOff.isEcho).
                    invalidate()
                    CarNavHandOff.start(carContext, result.location)
                } else {
                    screenManager.push(
                        NavScreen(carContext, renderer, from, result.location, route, config, result.name),
                    )
                }
            } catch (e: Exception) {
                routing = false
                routeError = failureText("Routing", e)
                invalidate()
            }
        }
    }

    private fun fetchLocation() {
        if (myLocation != null) return
        if (!hasLocationPermission(carContext)) return
        LocationServices.getFusedLocationProviderClient(carContext).lastLocation
            .addOnSuccessListener { loc ->
                if (loc != null && myLocation == null) {
                    session.onFix(LatLon(loc.latitude, loc.longitude), loc.accuracy)
                    onLocation(LatLon(loc.latitude, loc.longitude))
                }
            }
    }

    /** First location: a pick that was waiting on it routes now. */
    private fun onLocation(at: LatLon) {
        myLocation = at
        pendingDestination?.let { pendingDestination = null; navigateTo(it) }
    }

    private fun takeGeoUri() {
        val uri = geoUri ?: return
        if (geoTaken) return
        geoTaken = true
        when (val target = parseGeoUri(uri.schemeSpecificPart.orEmpty())) {
            is GeoTarget.Point ->
                navigateTo(GeocodeResult(target.label ?: carContext.getString(R.string.car_nav_destination), target.at))
            is GeoTarget.Query -> onQuery(target.text)
            null -> {
                routeError = carContext.getString(R.string.car_search_nothing_found)
                invalidate()
            }
        }
    }
}

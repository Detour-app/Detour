package com.jellemax.detour.car

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.jellemax.detour.data.GeocodeResult
import com.jellemax.detour.data.Geocoder
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RecentSearchStore
import com.jellemax.detour.data.RoutingClient
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SEARCH_DEBOUNCE_MS = 350L

/**
 * Pick a destination by name from the car screen, or hand off to
 * [SpinScreen]'s roulette instead. Same Photon backend as the phone's search,
 * and the same [RecentSearchStore] — so a place searched on the phone is one
 * tap away here, which matters when typing on a head unit is the slowest
 * thing in the car.
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
) : Screen(carContext) {

    private var results: List<GeocodeResult> = emptyList()
    private var recents: List<GeocodeResult> = emptyList()
    private var query = ""
    private var searching = false
    private var errorText: String? = null
    private var myLocation: LatLon? = null
    private var searchJob: Job? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                fetchLocation()
                lifecycleScope.launch {
                    recents = withContext(Dispatchers.IO) { RecentSearchStore.load() }
                    if (query.isBlank()) invalidate()
                }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val builder = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = onQuery(searchText)
            override fun onSearchSubmitted(searchText: String) = onQuery(searchText, immediate = true)
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("Search for a destination")
            .setShowKeyboardByDefault(false)

        if (searching) return builder.setLoading(true).build()

        val list = ItemList.Builder()
        if (query.isBlank()) {
            // Spin only makes sense before you've typed anything — once
            // there's a query the list is search results, not destination
            // pickers. Always present even with no recents yet, so the list
            // is never empty here and "type to search" never has to be said.
            list.addItem(
                Row.Builder()
                    .setTitle("Spin for a Destination")
                    .addText("Pick a random road nearby")
                    .setOnClickListener {
                        onSpin()
                        screenManager.pop()
                    }
                    .build(),
            )
            recents.forEach { result ->
                list.addItem(
                    Row.Builder()
                        .setTitle(result.name)
                        .setOnClickListener { navigateTo(result) }
                        .build(),
                )
            }
        } else when {
            errorText != null -> list.setNoItemsMessage(errorText!!)
            results.isEmpty() -> list.setNoItemsMessage("Nothing found")
            else -> results.forEach { result ->
                list.addItem(
                    Row.Builder()
                        .setTitle(result.name)
                        .setOnClickListener { navigateTo(result) }
                        .build(),
                )
            }
        }
        return builder.setItemList(list.build()).build()
    }

    private fun onQuery(text: String, immediate: Boolean = false) {
        query = text
        searchJob?.cancel()
        if (text.isBlank()) {
            results = emptyList()
            searching = false
            errorText = null
            invalidate()
            return
        }
        searchJob = lifecycleScope.launch {
            // The host fires a callback per keystroke; without this every letter
            // would be its own request to the geocoder.
            if (!immediate) delay(SEARCH_DEBOUNCE_MS)
            searching = true
            errorText = null
            invalidate()
            try {
                results = withContext(Dispatchers.IO) {
                    Geocoder.search(text, myLocation)
                }
            } catch (e: Exception) {
                results = emptyList()
                errorText = e.message ?: "Search failed"
            } finally {
                searching = false
                invalidate()
            }
        }
    }

    private fun navigateTo(result: GeocodeResult) {
        val from = myLocation ?: run {
            errorText = "Waiting for your location…"
            fetchLocation()
            invalidate()
            return
        }
        searching = true
        invalidate()
        lifecycleScope.launch {
            try {
                val config = withContext(Dispatchers.IO) { RoutingServer.load() }
                val route = withContext(Dispatchers.IO) {
                    // Both flags, because `NavScreen`'s reroute passes them
                    // (NavScreen.kt:257-258) and a trip whose first reroute
                    // changes its own routing policy is worse than either
                    // setting applied consistently. RoutingClient.route
                    // defaults both to false, so omitting them silently
                    // requested a default route.
                    RoutingClient.route(config, from, result.location, TravelMode.CAR.ghProfile,
                        Settings.avoidHighways.value, Settings.avoidSmallRoads.value)
                }
                withContext(Dispatchers.IO) { RecentSearchStore.save(result) }
                searching = false
                if (route.instructions.isEmpty()) {
                    // No turn data to drive our own nav screen with — hand off
                    // the same way SpinScreen does.
                    handOff(result.location)
                } else {
                    screenManager.push(
                        NavScreen(carContext, renderer, from, result.location, route, config, result.name),
                    )
                }
            } catch (e: Exception) {
                searching = false
                errorText = e.message ?: "Could not build a route"
                invalidate()
            }
        }
    }

    private fun handOff(dest: LatLon) {
        val uri = Uri.parse("geo:${dest.lat},${dest.lon}?q=${dest.lat},${dest.lon}")
        carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, uri))
    }

    private fun fetchLocation() {
        if (myLocation != null) return
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        LocationServices.getFusedLocationProviderClient(carContext).lastLocation
            .addOnSuccessListener { loc ->
                if (loc != null) myLocation = LatLon(loc.latitude, loc.longitude)
            }
    }
}

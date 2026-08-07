package com.jellemax.detour.car

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.jellemax.detour.R
import com.jellemax.detour.data.ExploredArea
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.PoiKind
import com.jellemax.detour.data.RouteCandidate
import com.jellemax.detour.data.RoutingServer
import com.jellemax.detour.data.ServerConfig
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.data.pickCandidate
import com.jellemax.detour.ui.formatDistanceKm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Car-screen "Spin": road-only, [TravelMode.CAR] fixed — no POI kinds or the
 *  moto round-trip loop (v1 scope, agreed with the phone-app parity question
 *  parked for later). Radius has no slider widget on a car template, so it
 *  cycles through a fixed preset list instead. */
class SpinScreen(carContext: CarContext) : Screen(carContext) {

    private val radiusPresetsKm = listOf(10f, 25f, 50f, 100f)
        .filter { it in TravelMode.CAR.minKm..TravelMode.CAR.maxKm }
        .ifEmpty { listOf(TravelMode.CAR.defaultKm) }
    private var radiusIndex = radiusPresetsKm.indices
        .minByOrNull { kotlin.math.abs(radiusPresetsKm[it] - TravelMode.CAR.defaultKm) } ?: 0

    private var myLocation: LatLon? = null
    private var candidate: RouteCandidate? = null
    private var serverConfig: ServerConfig? = null
    private var spinning = false
    private var errorText: String? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = fetchLocation()
        })
    }

    override fun onGetTemplate(): Template {
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return MessageTemplate.Builder(
                "Open Detour on your phone once to grant location access, then come back."
            ).setTitle("Location needed").setHeaderAction(Action.APP_ICON).build()
        }

        val pane = Pane.Builder()
        pane.addRow(
            Row.Builder()
                .setTitle("Radius: ${radiusPresetsKm[radiusIndex].toInt()} km")
                .addText("Tap the radius button below to change it")
                .build()
        )
        // Lives in the pane, not the action strip: a strip allows only one
        // action with a custom title, and that slot goes to Spin.
        pane.addAction(
            Action.Builder()
                .setTitle("Radius: ${radiusPresetsKm[radiusIndex].toInt()} km")
                .setOnClickListener {
                    radiusIndex = (radiusIndex + 1) % radiusPresetsKm.size
                    invalidate()
                }
                .build()
        )
        when {
            spinning -> pane.addRow(Row.Builder().setTitle("Spinning…").build())
            errorText != null -> pane.addRow(
                Row.Builder().setTitle("Couldn't find a destination").addText(errorText!!).build()
            )
            candidate != null -> {
                val c = candidate!!
                val meters = c.route?.distanceMeters ?: c.straightLineMeters
                pane.addRow(
                    Row.Builder()
                        .setTitle(c.name ?: "Random road")
                        .addText(formatDistanceKm(meters))
                        .build()
                )
                // Turn-by-turn only when the pick actually has turn data (own
                // server reachable); otherwise fall back to handing off to
                // whatever nav app is default on the head unit.
                val hasTurnData = c.route?.instructions?.isNotEmpty() == true
                pane.addAction(
                    Action.Builder()
                        .setTitle(if (hasTurnData) "Start Navigation" else "Navigate")
                        .setOnClickListener {
                            val config = serverConfig
                            val from = myLocation
                            val navScreen = if (config != null && from != null)
                                NavScreen.forCandidate(carContext, from, c, config) else null
                            if (navScreen != null) screenManager.push(navScreen)
                            else navigate(c.destination)
                        }
                        .build()
                )
            }
        }

        return PaneTemplate.Builder(pane.build())
            .setTitle("Detour")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(
                ActionStrip.Builder()
                    // Icon-only: a strip allows just one action with a custom
                    // title, and that is Spin.
                    .addAction(
                        Action.Builder()
                            .setIcon(
                                CarIcon.Builder(
                                    IconCompat.createWithResource(carContext, R.drawable.ic_car_search),
                                ).build(),
                            )
                            .setOnClickListener { screenManager.push(SearchScreen(carContext)) }
                            .build(),
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle("Spin")
                            .setOnClickListener { spin() }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun fetchLocation() {
        if (myLocation != null) return
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        LocationServices.getFusedLocationProviderClient(carContext).lastLocation
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    myLocation = LatLon(loc.latitude, loc.longitude)
                    invalidate()
                }
            }
    }

    private fun spin() {
        val loc = myLocation ?: run {
            errorText = "Waiting for your location…"
            fetchLocation()
            invalidate()
            return
        }
        if (spinning) return
        spinning = true
        errorText = null
        invalidate()
        lifecycleScope.launch {
            try {
                val config = withContext(Dispatchers.IO) { RoutingServer.load() }
                serverConfig = config
                val explored = withContext(Dispatchers.IO) { ExploredArea.load() }
                val radiusMeters = radiusPresetsKm[radiusIndex].toDouble() * 1000.0
                candidate = withContext(Dispatchers.IO) {
                    pickCandidate(config, loc, radiusMeters, 0.0,
                        TravelMode.CAR, PoiKind.ROAD, bearing = null, explored)
                }
            } catch (e: Exception) {
                errorText = e.message ?: "Spin failed"
            } finally {
                spinning = false
                invalidate()
            }
        }
    }

    private fun navigate(dest: LatLon) {
        val uri = Uri.parse("geo:${dest.lat},${dest.lon}?q=${dest.lat},${dest.lon}")
        carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, uri))
    }
}

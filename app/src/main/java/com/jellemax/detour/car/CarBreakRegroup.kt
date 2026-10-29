package com.jellemax.detour.car

import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Row
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jellemax.detour.R
import com.jellemax.detour.data.Account
import com.jellemax.detour.data.ConvoysStore
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RiderId
import com.jellemax.detour.data.handleFor
import com.jellemax.detour.drive.BreakRequest
import com.jellemax.detour.drive.RegroupPin
import com.jellemax.detour.net.ConvoyLiveClient
import com.jellemax.detour.presentation.BREAK_AGE_TICK_MS
import com.jellemax.detour.presentation.ConvoyBreakRegroupState
import com.jellemax.detour.presentation.RegroupBanner
import com.jellemax.detour.presentation.breakRequestsToAnnounce
import com.jellemax.detour.presentation.convoyBreakRegroupStateFrom
import com.jellemax.detour.presentation.regroupPinToAnnounce
import com.jellemax.detour.tracking.TripTrackingService
import com.jellemax.detour.ui.formatDistanceKm
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

private const val TAG = "CarBreakRegroup"

/**
 * Break and regroup on the car screen (#657): the Break please action, the
 * break pills and the regroup banner, and the toast that announces either on
 * whichever car screen is up. The rules — expiry, host, who has arrived — are
 * [convoyBreakRegroupStateFrom]'s, the same the phone's MapBreakRegroup.kt
 * reads; this file only lays them out on car templates.
 *
 * Car-only differences, per the owner's cut on #657 (reusing #655's): no host
 * pin drop or Clear (phone only), and Route to the pin only from [SpinScreen],
 * since [NavScreen] has no way to change its destination. No voice: the phone
 * keeps that, and doesn't know a car is connected.
 */
internal object CarBreakRegroup {

    /** What the controls show now, or null outside a convoy. Read on demand
     *  from the relay's StateFlows, so a template built at any time is current. */
    fun state(): ConvoyBreakRegroupState? {
        val convoyId = ConvoyLiveClient.activeConvoyId.value ?: return null
        val selfId = Account.riderId.value
        val breakRequests = ConvoyLiveClient.breakRequests.value
        val own = ownLocation()
        return convoyBreakRegroupStateFrom(
            convoy = ConvoysStore.state.value.convoys.firstOrNull { it.id == convoyId },
            selfId = selfId,
            breakRequests = breakRequests,
            pin = ConvoyLiveClient.regroupPin.value,
            positions = ConvoyLiveClient.peers.value.mapValues { (_, p) -> LatLon(p.lat, p.lon) } +
                listOfNotNull(own?.let { selfId to it }),
            // As the phone: a request stamped since this clock read is not from the future.
            nowMs = maxOf(System.currentTimeMillis(), breakRequests.values.maxOfOrNull { it.receivedAtMs } ?: 0L),
        )
    }

    /**
     * Calls [onChange] while [owner] is started whenever [rows] or the actions
     * would read differently: any relay or roster change, a fix (the banner's
     * distance), and a [BREAK_AGE_TICK_MS] tick so a request ages out with
     * nothing else moving. Fires on the first value too, so a screen coming back
     * to the front redraws what changed while it was stopped.
     */
    fun watch(owner: LifecycleOwner, onChange: () -> Unit) {
        owner.lifecycleScope.launch {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var shown: String? = null
                val tick = flow {
                    while (true) {
                        emit(Unit)
                        delay(BREAK_AGE_TICK_MS)
                    }
                }
                merge(
                    ConvoyLiveClient.activeConvoyId.map { },
                    ConvoyLiveClient.connected.map { },
                    ConvoyLiveClient.breakRequests.map { },
                    ConvoyLiveClient.regroupPin.map { },
                    ConvoyLiveClient.peers.map { },
                    ConvoysStore.state.map { },
                    TripTrackingService.lastFix.map { },
                    tick,
                ).collect {
                    val key = displayKey()
                    if (key != shown) {
                        shown = key
                        onChange()
                    }
                }
            }
        }
    }

    /** Calls [onChange] while [owner] is started when [breakAction] would read
     *  differently — joining or leaving a convoy, the relay going up or down.
     *  For a screen that shows the action but not the [rows]. */
    fun watchBreakAction(owner: LifecycleOwner, onChange: () -> Unit) {
        owner.lifecycleScope.launch {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(ConvoyLiveClient.activeConvoyId, ConvoyLiveClient.connected) { id, live ->
                    (id != null) to live
                }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { onChange() }
            }
        }
    }

    /** Break please, as an icon: a car action strip allows one titled action,
     *  and that is Exit on [NavScreen] and Regroup on [SpinScreen]. Null
     *  outside a convoy; disabled while the relay is down, as the phone's
     *  button is. */
    fun breakAction(carContext: CarContext): Action? {
        ConvoyLiveClient.activeConvoyId.value ?: return null
        return Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car_break)).build())
            .setEnabled(ConvoyLiveClient.connected.value)
            .setOnClickListener { askForBreak(carContext) }
            .build()
    }

    /** "Regroup" to drive to the pin, while there is one and this rider is not
     *  already there. For [SpinScreen]'s map strip only (car API 7+): the
     *  pane-only strip below that holds two actions, search and Break please.
     *  [onRoute] starts the route. */
    fun routeAction(carContext: CarContext, banner: RegroupBanner?, onRoute: (LatLon, String) -> Unit): Action? {
        if (banner == null || banner.hereAlready) return null
        val name = banner.label ?: carContext.getString(R.string.map_regroup_point)
        return Action.Builder()
            .setTitle(carContext.getString(R.string.map_regroup_here))
            .setOnClickListener { onRoute(banner.location, name) }
            .build()
    }

    /** The regroup banner and the break pills as pane rows — at most two, so
     *  the screen's own rows still fit the pane's row limit. */
    fun rows(carContext: CarContext, state: ConvoyBreakRegroupState?): List<Row> {
        state ?: return emptyList()
        val rows = mutableListOf<Row>()
        state.regroup?.let { banner ->
            val row = Row.Builder().setTitle(
                carContext.getString(
                    R.string.map_regroup_at,
                    banner.label ?: carContext.getString(R.string.map_regroup_point),
                ),
            )
            banner.distanceM?.let {
                row.addText(
                    if (banner.hereAlready) carContext.getString(R.string.map_regroup_you_are_here)
                    else formatDistanceKm(it),
                )
            }
            if (banner.arrived.isNotEmpty()) {
                row.addText(carContext.getString(R.string.map_regroup_arrived, banner.arrived.joinToString(", ")))
            }
            rows += row.build()
        }
        if (state.breaks.isNotEmpty()) {
            val lines = state.breaks.map {
                if (it.mine) carContext.getString(R.string.map_break_pill_mine)
                else carContext.getString(R.string.map_break_pill, it.name)
            }
            val row = Row.Builder().setTitle(lines.first())
            if (lines.size > 1) row.addText(lines.drop(1).joinToString(", "))
            rows += row.build()
        }
        return rows
    }

    /**
     * Toasts each break request and meeting point from another rider as it
     * arrives, on whichever car screen is up — the navigation template has no
     * free text slot, so a toast is how [NavScreen] gets them at all. Collected
     * only while [owner] is started, like #655's gap alerts: what arrived with
     * the car screen stopped is the baseline, not announced late.
     */
    fun announce(owner: LifecycleOwner, carContext: CarContext) {
        owner.lifecycleScope.launch {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var announced: Map<RiderId, BreakRequest>? = null
                launch {
                    ConvoyLiveClient.breakRequests.collect { current ->
                        val seen = announced
                        announced = current
                        if (seen == null) return@collect
                        breakRequestsToAnnounce(seen, current, Account.riderId.value).forEach {
                            toast(carContext, carContext.getString(R.string.map_break_pill, handle(it.riderId)))
                        }
                    }
                }
                var baseline = true
                var announcedPin: RegroupPin? = null
                ConvoyLiveClient.regroupPin.collect { current ->
                    val pin = if (baseline) null else regroupPinToAnnounce(announcedPin, current, Account.riderId.value)
                    baseline = false
                    if (current != null) announcedPin = current
                    pin ?: return@collect
                    toast(
                        carContext,
                        carContext.getString(
                            R.string.map_regroup_at,
                            pin.label ?: carContext.getString(R.string.map_regroup_point),
                        ),
                    )
                }
            }
        }
    }

    private fun askForBreak(carContext: CarContext) {
        val at = ownLocation()
        if (at == null) {
            toast(carContext, carContext.getString(R.string.car_waiting_for_location))
            return
        }
        ConvoyLiveClient.sendBreakRequest(at)
        toast(carContext, carContext.getString(R.string.map_break_pill_mine))
    }

    private fun ownLocation(): LatLon? = TripTrackingService.lastFix.value?.let { LatLon(it.lat, it.lon) }

    private fun handle(riderId: RiderId): String {
        val convoyId = ConvoyLiveClient.activeConvoyId.value
        val convoy = ConvoysStore.state.value.convoys.firstOrNull { it.id == convoyId }
        return convoy?.members.orEmpty().handleFor(riderId)
    }

    /** Everything [rows], [breakAction] and [routeAction] read, as one string. */
    private fun displayKey(): String {
        val state = state()
        return buildString {
            append(ConvoyLiveClient.activeConvoyId.value).append('|')
            append(ConvoyLiveClient.connected.value).append('|')
            state?.breaks?.forEach { append(it.riderId.value).append(it.mine).append(it.name).append(',') }
            append('|')
            state?.regroup?.let { r ->
                append(r.label).append(r.location).append(r.hereAlready).append(r.arrived)
                append(r.distanceM?.let { formatDistanceKm(it) })
            }
        }
    }

    private fun toast(carContext: CarContext, text: String) {
        runCatching { carContext.getCarService(AppManager::class.java).showToast(text, CarToast.LENGTH_LONG) }
            .onFailure { Log.w(TAG, "break/regroup toast failed", it) }
    }
}

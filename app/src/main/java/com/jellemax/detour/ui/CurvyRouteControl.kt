package com.jellemax.detour.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.detour.R
import com.jellemax.detour.data.Curviness
import com.jellemax.detour.data.CurvyPreset
import com.jellemax.detour.data.CurvyRoute
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.RoutingClient
import com.jellemax.detour.data.RoutingSupport
import com.jellemax.detour.data.ServerConfig
import com.jellemax.detour.data.Settings
import com.jellemax.detour.data.TravelMode
import com.jellemax.detour.presentation.CurvyControl
import com.jellemax.detour.presentation.CurvyRouteState
import com.jellemax.detour.presentation.curvyControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The navigation dock's curviness row (#652), with the request behind its
 * cost line. Composed only while the dock is up, so the preview request lives
 * and dies with the card that shows it.
 *
 * The preset is [Settings.curvyPreset], not dock state: Start and every
 * reroute read it back through [Settings.routePreferences], so the route the
 * rider rides is asked for with the preset the slider showed. The preview is
 * a separate `curvyRouteVia` call — Start still fetches its own line from
 * wherever the rider is by then, as it does for every destination.
 *
 * [origin] is read when the request starts rather than keyed on, so a fix a
 * second doesn't re-ask the router; the preview is from where the rider was
 * when they picked the preset.
 */
@Composable
internal fun CurvyRouteControl(
    serverConfig: ServerConfig,
    origin: LatLon?,
    destination: LatLon?,
    mode: TravelMode,
) {
    val preset by Settings.curvyPreset.collectAsStateWithLifecycle()
    val supported = remember(serverConfig) { RoutingSupport.curvyRoutingSupported() }
    val currentOrigin by rememberUpdatedState(origin)
    var preview by remember { mutableStateOf<CurvyRoute?>(null) }
    var loading by remember { mutableStateOf(false) }
    val state = CurvyRouteState(curvyControl(mode, supported), preset, preview, loading)

    LaunchedEffect(state.wantsPreview, preset, destination, mode, serverConfig, origin != null) {
        preview = null
        val from = currentOrigin
        if (!state.wantsPreview || destination == null || from == null) return@LaunchedEffect
        loading = true
        try {
            preview = withContext(Dispatchers.IO) {
                RoutingClient.curvyRouteVia(
                    serverConfig, listOf(from, destination), mode.ghProfile, Settings.routePreferences(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No preview is no cost line; Start makes its own request and
            // reports its own failure.
        } finally {
            loading = false
        }
    }

    CurvyRouteRow(state = state, onPresetChange = Settings::setCurvyPreset)
}

/**
 * Stateless: the three-stop slider with its labels and the cost line, or the
 * one-line reason the server can't do it, or nothing on a car route.
 */
@Composable
private fun CurvyRouteRow(
    state: CurvyRouteState,
    onPresetChange: (CurvyPreset) -> Unit,
) {
    when (state.control) {
        CurvyControl.HIDDEN -> Unit
        CurvyControl.UNSUPPORTED -> Text(
            stringResource(R.string.nav_curvy_unsupported),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CurvyControl.SLIDER -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.nav_curvy), style = MaterialTheme.typography.labelLarge)
            val labels = listOf(
                stringResource(R.string.nav_curvy_fast),
                stringResource(R.string.nav_curvy_balanced),
                stringResource(R.string.nav_curvy_twisty),
            )
            val selected = labels[state.preset.ordinal]
            Slider(
                value = state.preset.ordinal.toFloat(),
                onValueChange = { v ->
                    val picked = CurvyPreset.entries[v.toInt().coerceIn(CurvyPreset.entries.indices)]
                    if (picked != state.preset) onPresetChange(picked)
                },
                valueRange = 0f..CurvyPreset.entries.lastIndex.toFloat(),
                // `steps` counts the stops between the two ends.
                steps = CurvyPreset.entries.size - 2,
                modifier = Modifier.semantics { stateDescription = selected },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
            CurvyCostLine(state)
        }
    }
}

/** "+12 min · +9.4 km · Twistiness 3 of 5", or "Comparing…" while it's asked for. */
@Composable
private fun CurvyCostLine(state: CurvyRouteState) {
    val text = when {
        state.loading -> stringResource(R.string.nav_curvy_comparing)
        else -> {
            val cost = state.costLine?.let {
                stringResource(R.string.nav_curvy_cost, it.extraMinutes, formatDistanceKm(it.extraMeters))
            }
            val bends = state.preview?.curviness?.let {
                stringResource(R.string.spin_bends, Curviness.bends(it), Curviness.MAX_BENDS)
            }
            listOfNotNull(cost, bends).joinToString(" · ").ifEmpty { null }
        }
    } ?: return
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

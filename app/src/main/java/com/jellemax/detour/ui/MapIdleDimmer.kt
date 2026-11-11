package com.jellemax.detour.ui

import android.os.SystemClock
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.jellemax.detour.R
import com.jellemax.detour.data.Settings
import com.jellemax.detour.presentation.MAP_DIM_AFTER_MS
import com.jellemax.detour.presentation.MAP_DIM_BRIGHTNESS
import com.jellemax.detour.presentation.mapScreenDimmed
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onSubscription

/**
 * The idle dim's clock (#902): touches on the map, held as a flow rather than
 * snapshot state. A pan delivers a pointer event per frame, and writing state
 * for each would recompose `MapScreen` through the whole gesture (#33). Put
 * [touchModifier] on the map root; it watches on [PointerEventPass.Initial] and
 * consumes nothing, so the map and every control above it still get the event.
 * [DimMapWhenIdle] reads it.
 */
internal class MapIdleDimmer {
    private val events = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val touchModifier: Modifier = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
                events.tryEmit(Unit)
            }
        }
    }

    /** Emits once at subscription, so the idle clock starts when collecting does. */
    val touches = events.onSubscription { emit(Unit) }
}

/**
 * Dims the window while the map sits untouched off-route (#902, owner decision
 * 2A on #892); the screen itself stays on (`KeepScreenOn`, #703). The policy is
 * [mapScreenDimmed]; this only feeds it and applies the answer as a window
 * brightness override, which the system drops when another window is in front.
 *
 * Restarts, and so un-dims at once, whenever the inputs change: navigation
 * starting, a dialog or the search opening, the setting, or the app leaving
 * RESUMED. Leaving the map disposes it and un-dims the same way.
 */
@Composable
internal fun DimMapWhenIdle(dimmer: MapIdleDimmer, s: MapScreenState) {
    val window = LocalActivity.current?.window ?: return
    val enabled by Settings.dimMapWhenIdle.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = enabled && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val navigating = s.navigating
    // The map's own dialogs and the search field are separate windows (or own
    // the keyboard), so the map root never sees the touches typed into them.
    val dialogOpen = s.searchOpen || s.savePinTarget != null || s.saveSpinDraft != null ||
        s.showBgLocationDisclosure || s.showPermissionExplainer
    LaunchedEffect(window, active, navigating, dialogOpen) {
        // A state that never dims has no clock to run.
        if (!mapScreenDimmed(active, navigating, dialogOpen, Long.MAX_VALUE)) return@LaunchedEffect
        var dimmed = false
        try {
            dimmer.touches.collectLatest {
                if (dimmed) {
                    window.setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
                    dimmed = false
                }
                val touchedAt = SystemClock.uptimeMillis()
                delay(MAP_DIM_AFTER_MS)
                if (mapScreenDimmed(active, navigating, dialogOpen, SystemClock.uptimeMillis() - touchedAt)) {
                    window.setBrightness(MAP_DIM_BRIGHTNESS)
                    dimmed = true
                }
            }
        } finally {
            if (dimmed) window.setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        }
    }
}

private fun Window.setBrightness(value: Float) {
    attributes = attributes.apply { screenBrightness = value }
}

/** The Settings → Map row for [Settings.dimMapWhenIdle]. */
@Composable
internal fun DimMapWhenIdleRow() {
    val on by Settings.dimMapWhenIdle.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_dim_map_idle), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.settings_dim_map_idle_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = { Settings.setDimMapWhenIdle(it) })
    }
}

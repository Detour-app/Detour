package com.jellemax.detour.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jellemax.detour.audio.PushToTalk
import com.jellemax.detour.data.Settings
import com.jellemax.detour.presentation.SpeedHudState
import com.jellemax.detour.presentation.activeTripCardStateFrom
import com.jellemax.detour.tracking.TripStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Always on screen while a trip is running, in the corner your thumb rests in. */
@Composable
internal fun EndTripButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Icon(Icons.Outlined.Stop, contentDescription = null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("End trip", maxLines = 1, fontWeight = FontWeight.Bold)
    }
}

/** Hold to talk; only shown while a convoy's live relay is connected (see
 *  ConvoyLiveService). Solid red while you're pressing it; a primary-colored
 *  ring while [talking] — a friend currently transmitting — so incoming PTT
 *  audio isn't silent-and-invisible. */
@Composable
internal fun PushToTalkButton(talking: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val containerColor = when {
        pressed -> MaterialTheme.colorScheme.error
        talking -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(
        modifier = modifier
            .size(64.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            return@detectTapGestures
                        }
                        pressed = true
                        // Off the main thread: AudioRecord construction and
                        // stopTalking's join(500) can both take real time,
                        // and this fires from a gesture handler on a screen
                        // meant to be glanced at while riding.
                        scope.launch(Dispatchers.IO) { PushToTalk.startTalking() }
                        try {
                            awaitRelease()
                        } finally {
                            pressed = false
                            scope.launch(Dispatchers.IO) { PushToTalk.stopTalking() }
                        }
                    },
                )
            },
        shape = CircleShape,
        color = containerColor,
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = "Push to talk",
                tint = if (pressed) MaterialTheme.colorScheme.onError
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** The island's width, and so the posted-limit sign's diameter: the sign runs
 *  edge to edge at the bottom of it, as the prototype draws it. Wider than the
 *  prototype's 56 px because the trajectcontrole average keeps a slot in here
 *  and "avg km/h" has to fit under it. */
private val ISLAND_WIDTH = 72.dp

/** Speed, the posted limit for the road we're on and — inside a
 *  trajectcontrole — the running average, stacked in one island at the top-left
 *  of the map. The whole island turns red once we're over the limit by more
 *  than the tolerance the caller passed to `speedHudStateFrom`.
 *
 *  Renders [state] and computes nothing: the numbers and their wording come
 *  from `:shared`. Drawn whether or not the vehicle is moving — a map parked at
 *  a light keeps its instruments, the way the head unit always has. */
@Composable
internal fun SpeedHud(state: SpeedHudState, modifier: Modifier = Modifier) {
    val onIsland = if (state.speeding) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSurface
    val onIslandMuted = if (state.speeding) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        modifier = modifier.width(ISLAND_WIDTH).glassBorder(CircleShape),
        shape = CircleShape,
        colors = if (state.speeding) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ) else glassCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                state.speedText,
                fontSize = 26.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Bold,
                color = onIsland,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                "km/h",
                style = MaterialTheme.typography.labelSmall,
                color = onIslandMuted,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // Inside a trajectcontrole: the running average is the number the
            // camera pair actually measures — the one that writes the fine —
            // so it gets a rule above it and reads as its own instrument
            // rather than a second line of the dial.
            //
            // Its over-limit state is signalled independently of the dial's:
            // "over right now *and* over on average" is the ordinary case in a
            // section, and is exactly when the average is the number that
            // matters. Error red on the red island would be unreadable, so the
            // rule carries the signal instead of the digits — it thickens and
            // takes the accent, `error` on the glass island and
            // `onErrorContainer` on the red one, and the label drops its
            // muting with it.
            state.averageText?.let { average ->
                val overAccent = when {
                    !state.averageOverLimit -> null
                    state.speeding -> MaterialTheme.colorScheme.onErrorContainer
                    else -> MaterialTheme.colorScheme.error
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = if (overAccent == null) 1.dp else 2.dp,
                    color = overAccent ?: (if (state.speeding) onIslandMuted
                        else MaterialTheme.colorScheme.outlineVariant).copy(alpha = 0.4f),
                )
                Text(
                    average,
                    fontSize = 17.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = overAccent ?: onIsland,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "avg km/h",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overAccent == null) onIslandMuted else onIsland,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            // Edge to edge at the foot of the island, and absent entirely when
            // there is no posted limit — SpeedLimitSign draws nothing on null.
            Crossfade(
                targetState = state.limitSignText,
                animationSpec = tween(300),
                label = "speedLimit",
            ) {
                SpeedLimitSign(it, size = ISLAND_WIDTH)
            }
        }
    }
}

/** "OBD2 signal lost" — shown under the speed HUD when an adapter that was
 *  feeding this trip has dropped and not yet reconnected (gated by the caller).
 *  A no-op when [lost] is false so the caller can place it unconditionally. */
@Composable
internal fun Obd2SignalLostLabel(lost: Boolean) {
    if (!lost) return
    Text(
        "OBD2 signal lost",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/** Live trip numbers, minus the ones already on screen: current speed is the
 *  HUD, and a car has no lean angle worth printing. */
@Composable
internal fun ActiveTripCard(stats: TripStats) {
    // Tick every second so duration counts up even without GPS updates. The
    // tick stays here — it is a UI concern — but the clock goes *into* the
    // mapper as nowMs rather than being read inside a formatter.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(stats.startTimeMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    // The whole Android-shaped part of this card: unpacking TripStats. The
    // service records metres per second and the mapper renders km/h, and the
    // card wants the trip's peak g, not the g of the current corner.
    val state = activeTripCardStateFrom(
        startTimeMs = stats.startTimeMs,
        nowMs = now,
        distanceMeters = stats.distanceMeters,
        topSpeedKmh = stats.topSpeedMps * 3.6,
        leanDeg = stats.currentLeanAngleDeg,
        maxLeanDeg = stats.maxLeanAngleDeg,
        maxGForce = stats.maxGForce,
        hardEvents = stats.hardBrakeCount + stats.hardAccelCount + stats.hardCornerCount,
        stopCount = stats.stopCount,
        currentlyOverLimit = stats.currentlyOverLimit,
        // Resolved on the render path and passed down, as Format.kt does it.
        sep = Settings.decimalSeparatorChar(),
    )
    Card(
        modifier = Modifier.glassBorder(MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatItem("Time", state.durationText)
            StatItem("Distance", state.distanceText)
            StatItem("Top", state.topSpeedText)
            if (stats.mode.tracksLean) {
                StatItem("Lean", state.leanText)
                StatItem("Max lean", state.maxLeanText)
            }
            if (stats.mode.tracksGForce) {
                StatItem("Max G", state.maxGForceText)
            }
        }
        if (state.detailsShown) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (stats.hardBrakeCount > 0) StatItem("Hard brakes", "${stats.hardBrakeCount}")
                if (stats.hardAccelCount > 0) StatItem("Hard accel", "${stats.hardAccelCount}")
                if (stats.hardCornerCount > 0) StatItem("Hard corners", "${stats.hardCornerCount}")
                if (stats.stopCount > 0) StatItem("Stops", "${stats.stopCount}")
                if (stats.currentlyOverLimit) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Speed", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Over limit", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Text(
                "Not a score to chase — these numbers are informational only.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

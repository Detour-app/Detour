package com.jellemax.detour.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.jellemax.detour.data.SavedPlace
import com.jellemax.detour.tracking.TripStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One-tap saved-place chips over the map, plus a "Save pin" chip when a
 *  destination pin is on screen. Scrolls horizontally when they overflow. */
@Composable
internal fun ShortcutChips(
    places: List<SavedPlace>,
    canSavePin: Boolean,
    onPick: (SavedPlace) -> Unit,
    onSavePin: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (canSavePin) {
            AssistChip(
                onClick = onSavePin,
                label = { Text("Save pin") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null,
                    Modifier.size(18.dp)) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = glassContainerColor()),
            )
        }
        places.forEach { p ->
            AssistChip(
                onClick = { onPick(p) },
                label = { Text(p.name, maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.Place, contentDescription = null,
                    Modifier.size(18.dp)) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = glassContainerColor()),
            )
        }
    }
}

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

/** Current speed next to the posted limit for the road we're on. Used both while
 *  cruising and while navigating; the whole dial turns red once we're more than
 *  5 km/h over. Sized to be read at a glance, not to dominate the map — the trip
 *  card no longer repeats the number underneath it. */
@Composable
internal fun SpeedHud(
    speedKmh: Double,
    limitKmh: Double?,
    averageKmh: Double? = null,
    averageLimitKmh: Double? = null,
    modifier: Modifier = Modifier,
) {
    val speeding = limitKmh != null && speedKmh > limitKmh + 5
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Inside a trajectcontrole: the running average is what the section
        // measures, so it sits front and centre and turns red once it's over.
        averageKmh?.let { avg ->
            SectionAverageChip(avg, averageLimitKmh)
        }
        Crossfade(targetState = limitKmh, animationSpec = tween(300), label = "speedLimit") {
            SpeedLimitSign(it, size = 48.dp)
        }
        Card(
            modifier = Modifier.glassBorder(CircleShape),
            shape = CircleShape,
            colors = if (speeding) CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ) else glassCardColors(),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column(
                Modifier.size(80.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "%.0f".format(speedKmh),
                    fontSize = 32.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (speeding) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "km/h",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (speeding) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Running average speed through a trajectcontrole, next to the live speed.
 *  Red once the average is over the section's posted limit — that's the number
 *  the camera pair is actually about to fine you on. */
@Composable
private fun SectionAverageChip(averageKmh: Double, limitKmh: Double?, modifier: Modifier = Modifier) {
    val over = limitKmh != null && averageKmh > limitKmh
    Card(
        modifier = modifier,
        shape = CircleShape,
        colors = CardDefaults.cardColors(
            containerColor = if (over) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.tertiaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            Modifier.size(72.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val onColor = if (over) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onTertiaryContainer
            Text(
                "Ø %.0f".format(averageKmh),
                fontSize = 26.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Bold,
                color = onColor,
            )
            Text("avg km/h", style = MaterialTheme.typography.labelSmall, color = onColor)
        }
    }
}

/** Live trip numbers, minus the ones already on screen: current speed is the
 *  HUD, and a car has no lean angle worth printing. */
@Composable
internal fun ActiveTripCard(stats: TripStats) {
    // Tick every second so duration counts up even without GPS updates.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(stats.startTimeMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
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
            StatItem("Time", formatDuration(now - stats.startTimeMs))
            StatItem("Distance", formatDistanceKm(stats.distanceMeters))
            StatItem("Top", formatSpeedKmh(stats.topSpeedMps))
            if (stats.mode.tracksLean) {
                StatItem("Lean", formatLeanAngle(stats.currentLeanAngleDeg))
                StatItem("Max lean", formatLeanAngle(stats.maxLeanAngleDeg))
            }
            if (stats.mode.tracksGForce) {
                StatItem("Max G", formatGForce(stats.maxGForce))
            }
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

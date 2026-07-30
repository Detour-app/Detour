package com.jellemax.maproulette.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ForkLeft
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.RoundaboutLeft
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.UTurnRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jellemax.maproulette.data.NavEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** GraphHopper sign code → maneuver arrow. */
private fun signIcon(sign: Int): ImageVector = when (sign) {
    -98, -8 -> Icons.Default.UTurnLeft
    8 -> Icons.Default.UTurnRight
    -7 -> Icons.Default.ForkLeft
    7 -> Icons.Default.ForkRight
    -3 -> Icons.Default.TurnSharpLeft
    -2 -> Icons.Default.TurnLeft
    -1 -> Icons.Default.TurnSlightLeft
    1 -> Icons.Default.TurnSlightRight
    2 -> Icons.Default.TurnRight
    3 -> Icons.Default.TurnSharpRight
    4, 5 -> Icons.Default.SportsScore
    6 -> Icons.Default.RoundaboutLeft
    else -> Icons.Default.Straight
}

/** Top banner during navigation: next maneuver, distance to it. */
@Composable
fun NavigationBanner(
    progress: NavEngine.Progress?,
    rerouting: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.glassBorder(MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            // Kept a touch more opaque than the other overlays: the turn
            // instruction has to read at a glance at speed.
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The speed limit lives on the speed HUD; showing it twice was noise.
            val instruction = progress?.nextInstruction
            Icon(
                signIcon(instruction?.sign ?: 0),
                contentDescription = null,
                Modifier.size(64.dp),
            )
            Column {
                Text(
                    when {
                        rerouting -> "Rerouting…"
                        progress == null -> "Waiting for GPS…"
                        else -> formatDistanceKm(progress.distanceToTurnMeters)
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    instruction?.text ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                )
            }
        }
    }
}

/** Small glass pill under the banner naming the maneuver after the current
 *  one, so a driver can see a turn-then-turn coming before the first is done. */
@Composable
fun ThenPill(
    progress: NavEngine.Progress?,
    modifier: Modifier = Modifier,
) {
    val nextNext = progress?.nextNextInstruction ?: return
    val distance = progress.distanceToNextNextMeters ?: return
    Card(
        modifier = modifier.glassBorder(CircleShape),
        shape = CircleShape,
        colors = glassCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "then",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(signIcon(nextNext.sign), contentDescription = null, Modifier.size(20.dp))
            Text(formatDistanceKm(distance), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Bottom bar during navigation: route progress, remaining distance, arrival
 *  time, exit. */
@Composable
fun NavigationBottomBar(
    progress: NavEngine.Progress?,
    offRoute: Boolean,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.glassBorder(MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        colors = glassCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            // Guard divide-by-zero: no route yet (routeMeters == 0) reads as
            // just-departed rather than crashing the fraction.
            val fraction = progress?.let {
                if (it.routeMeters > 0) (1.0 - it.remainingMeters / it.routeMeters)
                    .coerceIn(0.0, 1.0).toFloat()
                else 0f
            } ?: 0f
            RouteProgressTrack(fraction, Modifier.fillMaxWidth())
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        progress?.let {
                            "${formatDistanceKm(it.remainingMeters)} · " +
                                "%.0f min".format((it.remainingTimeMs ?: 0L) / 60_000.0)
                        } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when {
                            offRoute -> "Off route"
                            else -> progress?.remainingTimeMs?.let { "Arrival ${eta(it)}" } ?: ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (offRoute) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onExit,
                    modifier = Modifier.size(34.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Exit navigation",
                        Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Thin route-progress bar: primary fill up to [fraction], with a dot riding
 *  its leading edge. */
@Composable
private fun RouteProgressTrack(fraction: Float, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val fillColor = MaterialTheme.colorScheme.primary
    Canvas(modifier.height(4.dp)) {
        drawLine(
            trackColor,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
        )
        val x = size.width * fraction
        if (x > 0f) {
            drawLine(
                fillColor,
                start = Offset(0f, size.height / 2f),
                end = Offset(x, size.height / 2f),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
        }
        drawCircle(fillColor, radius = 5.dp.toPx(), center = Offset(x, size.height / 2f))
    }
}

private fun eta(remainingMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault())
        .format(Date(System.currentTimeMillis() + remainingMs))

/** EU-style round speed limit sign: white disc, thick red ring, big black number. */
@Composable
fun SpeedLimitSign(kmh: Double?, size: Dp = 64.dp, modifier: Modifier = Modifier) {
    if (kmh == null) return
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White)
            .border(BorderStroke(size * 0.08f, Color(0xFFD32F2F)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            kmh.toInt().toString(),
            color = Color.Black,
            fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.38f).sp,
            textAlign = TextAlign.Center,
        )
    }
}

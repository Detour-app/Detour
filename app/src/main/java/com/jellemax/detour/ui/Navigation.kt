package com.jellemax.detour.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ForkLeft
import androidx.compose.material.icons.rounded.ForkRight
import androidx.compose.material.icons.rounded.RoundaboutLeft
import androidx.compose.material.icons.rounded.SportsScore
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.TurnSharpLeft
import androidx.compose.material.icons.rounded.TurnSharpRight
import androidx.compose.material.icons.rounded.TurnSlightLeft
import androidx.compose.material.icons.rounded.TurnSlightRight
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.material.icons.rounded.UTurnRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jellemax.detour.presentation.NavState
import com.jellemax.detour.presentation.NavThenPill

/** GraphHopper sign code → maneuver arrow. */
private fun signIcon(sign: Int): ImageVector = when (sign) {
    -98, -8 -> Icons.Rounded.UTurnLeft
    8 -> Icons.Rounded.UTurnRight
    -7 -> Icons.Rounded.ForkLeft
    7 -> Icons.Rounded.ForkRight
    -3 -> Icons.Rounded.TurnSharpLeft
    -2 -> Icons.Rounded.TurnLeft
    -1 -> Icons.Rounded.TurnSlightLeft
    1 -> Icons.Rounded.TurnSlightRight
    2 -> Icons.Rounded.TurnRight
    3 -> Icons.Rounded.TurnSharpRight
    4, 5 -> Icons.Rounded.SportsScore
    6 -> Icons.Rounded.RoundaboutLeft
    else -> Icons.Rounded.Straight
}

/**
 * Top banner during navigation: the maneuver arrow, how far to it, the
 * instruction, and — in the trailing slot — the maneuver after it. Every
 * readout is a pre-formatted string off [state]; nothing is computed here.
 */
@Composable
fun NavigationBanner(state: NavState, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.glassBorder(MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        colors = glassCardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The speed limit lives on the speed HUD; showing it twice was noise.
            Icon(
                signIcon(state.maneuverSign),
                contentDescription = null,
                Modifier.size(38.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    state.headlineText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    state.maneuverText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            state.thenPill?.let { ThenChip(it) }
        }
    }
}

/** The maneuver after the current one, tucked into the banner's trailing slot
 *  so a driver can see a turn-then-turn coming before the first is done. Drawn
 *  only when there is one: past the last turn [NavState.thenPill] is null. */
@Composable
private fun ThenChip(pill: NavThenPill) {
    Column(
        Modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            signIcon(pill.sign),
            contentDescription = null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "then ${pill.distanceText}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Bottom bar during navigation: route progress, arrival time, what is left of
 *  the route, and the way out. */
@Composable
fun NavigationBottomBar(
    state: NavState,
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
            RouteProgressTrack(state.progressFraction, Modifier.fillMaxWidth())
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.arrivalText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (state.offRoute) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        state.remainingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = onExit,
                    // The visible label is "End"; keep the fuller phrase for a
                    // screen reader, which has no map next to it for context.
                    modifier = Modifier.semantics { contentDescription = "End navigation" },
                    shape = CircleShape,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("End", style = MaterialTheme.typography.labelLarge)
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

/** EU-style round speed limit sign: white disc, thick red ring, big black number. */
@Composable
fun SpeedLimitSign(kmh: Double?, size: Dp = 64.dp, modifier: Modifier = Modifier) {
    if (kmh == null) return
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White)
            // Traffic red (≈RAL 3020), not Material red 700 — see the car HUD's
            // signRimPaint for why the darker brick had to go.
            .border(BorderStroke(size * 0.10f, Color(0xFFE8112D)), CircleShape),
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

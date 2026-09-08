package com.jellemax.detour.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ForkLeft
import androidx.compose.material.icons.rounded.ForkRight
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jellemax.detour.presentation.NavState
import com.jellemax.detour.presentation.NavThenPill
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** GraphHopper sign code → maneuver arrow. Same table as `car/NavScreen.kt`'s
 *  `maneuverType()`. Roundabouts (6, -6) are not here — they get [RoundaboutGlyph],
 *  drawn to the measured exit angle rather than a fixed arrow. */
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
    else -> Icons.Rounded.Straight
}

/** The maneuver glyph for the banner and the "then" pill: a roundabout drawn to
 *  its real exit angle for [sign] 6/-6, otherwise the [signIcon] arrow. */
@Composable
private fun ManeuverGlyph(
    sign: Int,
    roundaboutTurnDeg: Double?,
    size: Dp,
    tint: Color,
) {
    if (sign == 6 || sign == -6) {
        RoundaboutGlyph(
            exitTurnDeg = (roundaboutTurnDeg ?: 0.0).toFloat(),
            tint = tint,
            modifier = Modifier.size(size),
        )
    } else {
        Icon(signIcon(sign), contentDescription = null, Modifier.size(size), tint = tint)
    }
}

/**
 * Roundabout maneuver glyph drawn to the actual exit angle. [exitTurnDeg] is how
 * far the rider's heading turns from the approach to the exit — negative bears
 * left, positive right, ~0 straight through — measured off the route polyline in
 * `RoutingClient`. The ring always circulates anticlockwise, the way traffic runs
 * a roundabout everywhere the app is used; [exitTurnDeg] moves only the exit spur.
 */
@Composable
private fun RoundaboutGlyph(exitTurnDeg: Float, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val w = s * 0.11f
        val r = s * 0.30f
        val c = Offset(size.width / 2f, size.height / 2f)
        val cap = StrokeCap.Round

        // Faint full ring, then the travelled arc thick over it.
        drawCircle(tint.copy(alpha = 0.3f), r, c, style = Stroke(w))

        // Screen angle, degrees, clockwise from +x (matches atan2(y, x) with y
        // pointing down): the south entry is at 90°, straight-on exit at -90°.
        val exitPhi = -90f + exitTurnDeg
        drawArc(
            color = tint,
            startAngle = 90f,
            // Anticlockwise from the entry to the exit — negative sweep.
            sweepAngle = (exitPhi - 90f).coerceIn(-360f, 0f),
            useCenter = false,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(w, cap = cap),
        )

        // Entry stub, bottom edge up to the ring.
        drawLine(tint, Offset(c.x, size.height), Offset(c.x, c.y + r), w, cap)

        // Exit spur along the exit heading, with an arrowhead.
        val a = (exitPhi * PI.toFloat() / 180f)
        val dir = Offset(cos(a), sin(a))
        val tip = c + dir * (s * 0.52f)
        drawLine(tint, c + dir * r, tip, w, cap)
        val head = s * 0.14f
        for (spread in listOf(140f, -140f)) {
            val b = a + spread * PI.toFloat() / 180f
            drawLine(tint, tip, tip + Offset(cos(b), sin(b)) * head, w, cap)
        }
    }
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
            ManeuverGlyph(
                sign = state.maneuverSign,
                roundaboutTurnDeg = state.maneuverRoundaboutTurnDeg,
                size = 38.dp,
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
        ManeuverGlyph(
            sign = pill.sign,
            roundaboutTurnDeg = pill.roundaboutTurnDeg,
            size = 18.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "then ${pill.distanceText}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Thin route-progress bar: primary fill up to [fraction], with a dot riding
 *  its leading edge. The top edge of the nav sheet (`RideSheet.kt`). */
@Composable
internal fun RouteProgressTrack(fraction: Float, modifier: Modifier = Modifier) {
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

/** EU-style round speed limit sign: white disc, thick red ring, big black
 *  number. Takes the number already rendered (`speedHudStateFrom`'s
 *  `limitSignText`) —
 *  a sign shows the posted figure and the truncation that gets there lives with
 *  the rest of the HUD's wording, in `:shared`. Null draws nothing, which is how
 *  callers say "no posted limit here". */
@Composable
fun SpeedLimitSign(text: String?, size: Dp = 64.dp, modifier: Modifier = Modifier) {
    if (text == null) return
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
            text,
            color = Color.Black,
            fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.38f).sp,
            textAlign = TextAlign.Center,
        )
    }
}

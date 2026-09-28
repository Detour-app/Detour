package com.jellemax.detour.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jellemax.detour.data.LogbookItem
import com.jellemax.detour.data.PlaceVisit

/* The Logbook's scrapbook pieces — highlight stickers and town stamps —
 * shared by the ride cards and the trip detail screen. */

/** How many towns a ride card stamps before counting the rest. */
private const val MAX_STAMPS = 4
private const val STICKER_TILT_DEG = 3f
private const val STAMP_TILT_DEG = 1.5f
private val NEW_PLACE_DARK = Color(0xFF6CC4A8)
private val NEW_PLACE_LIGHT = Color(0xFF1B7A62)

/** A ride's one highlight ([LogbookItem.Ride.chip]), as a tilted sticker slapped
 *  on its map: the Logbook should read like a scrapbook, not a report. */
@Composable
internal fun Sticker(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.rotate(STICKER_TILT_DEG),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 3.dp,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** The towns a ride passed through, as passport stamps: towns never ridden
 *  before first, dashed and in [newPlaceColor], then the familiar ones, up to
 *  [MAX_STAMPS] with the rest counted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaceStamps(places: List<PlaceVisit>, modifier: Modifier = Modifier) {
    val ordered = places.filter { it.isNew } + places.filterNot { it.isNew }
    val shown = ordered.take(MAX_STAMPS)
    val newColor = newPlaceColor()
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        shown.forEachIndexed { i, place ->
            // Alternating tilt: stamps pressed by hand, not typeset.
            val tilt = if (i % 2 == 0) -STAMP_TILT_DEG else STAMP_TILT_DEG
            if (place.isNew) {
                Stamp(place.name, newColor, dashed = true, Modifier.rotate(tilt))
            } else {
                Stamp(place.name, MaterialTheme.colorScheme.onSurfaceVariant, dashed = false)
            }
        }
        if (ordered.size > shown.size) {
            Stamp("+${ordered.size - shown.size}", MaterialTheme.colorScheme.onSurfaceVariant, dashed = false)
        }
    }
}

@Composable
private fun Stamp(text: String, color: Color, dashed: Boolean, modifier: Modifier = Modifier) {
    val outline = if (dashed) color else MaterialTheme.colorScheme.outlineVariant
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        color = color,
        modifier = modifier
            .drawBehind {
                val w = 1.5.dp.toPx()
                val dash = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null
                drawRoundRect(
                    outline,
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(width = w, pathEffect = dash),
                )
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Teal for a town ridden for the first time — apart from the amber/blue
 *  primary, so "new" never reads as just another highlight. Darker on a
 *  light surface to keep the stamp text readable. */
@Composable
private fun newPlaceColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) NEW_PLACE_DARK else NEW_PLACE_LIGHT

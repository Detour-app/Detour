package com.jellemax.detour.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.jellemax.detour.data.LogbookItem
import com.jellemax.detour.data.PlaceVisit

/* The Logbook's card pieces — the highlight sticker and the towns line —
 * shared by the ride cards and the trip detail screen. */

/** How many towns a ride card names before counting the rest. */
private const val MAX_PLACES = 4
private val NEW_PLACE_DARK = Color(0xFF6CC4A8)
private val NEW_PLACE_LIGHT = Color(0xFF1B7A62)

/** A ride's one highlight ([LogbookItem.Ride.chip]), as a flat pill on its
 *  map: one visual language with the rest of the card, no tilt, no shadow. */
@Composable
internal fun Sticker(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** The towns a ride passed through, as one line of text: towns never ridden
 *  before first and in [newPlaceColor], then the familiar ones muted, up to
 *  [max] with the rest counted. [maxLines] 1 ellipsises a long line on the
 *  card; the trip detail lets it wrap. */
@Composable
internal fun PlaceLine(
    places: List<PlaceVisit>,
    modifier: Modifier = Modifier,
    max: Int = MAX_PLACES,
    maxLines: Int = 1,
) {
    val ordered = places.filter { it.isNew } + places.filterNot { it.isNew }
    val shown = ordered.take(max)
    val newColor = newPlaceColor()
    val text = buildAnnotatedString {
        shown.forEachIndexed { i, place ->
            if (i > 0) append(" · ")
            if (place.isNew) withStyle(SpanStyle(color = newColor, fontWeight = FontWeight.SemiBold)) {
                append(place.name)
            } else {
                append(place.name)
            }
        }
        if (ordered.size > shown.size) append(" +${ordered.size - shown.size}")
    }
    Text(
        text,
        modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Teal for a town ridden for the first time — apart from the amber/blue
 *  primary, so "new" never reads as just another highlight. Darker on a
 *  light surface to keep the text readable. */
@Composable
private fun newPlaceColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) NEW_PLACE_DARK else NEW_PLACE_LIGHT

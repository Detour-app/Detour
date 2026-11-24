package com.jellemax.detour.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddRoad
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jellemax.detour.R
import com.jellemax.detour.data.GeocodeResult
import com.jellemax.detour.data.LatLon
import com.jellemax.detour.data.PlaceKind

/*
 * How one search hit reads, on every Android surface that lists them (#1006): the map's island,
 * the route editor, saved places, and — as text, through [searchResultDetail] — Android Auto.
 * Four rows of "Namur, België" are the city, the province, the arrondissement and the station;
 * the kind, the second line and the distance are what tell them apart, so no surface may drop
 * one of the three.
 */

private val DENSE_PADDING = PaddingValues(vertical = 10.dp)
private val CARD_PADDING = PaddingValues(horizontal = 16.dp, vertical = 14.dp)

/** The one word under a hit's title saying what it is. */
@StringRes
fun PlaceKind.labelRes(): Int = when (this) {
    PlaceKind.ADDRESS -> R.string.place_kind_address
    PlaceKind.STREET -> R.string.place_kind_street
    PlaceKind.TOWN -> R.string.place_kind_town
    PlaceKind.REGION -> R.string.place_kind_region
    PlaceKind.FUEL -> R.string.place_kind_fuel
    PlaceKind.PARKING -> R.string.place_kind_parking
    PlaceKind.FOOD -> R.string.place_kind_food
    PlaceKind.SHOP -> R.string.place_kind_shop
    PlaceKind.STATION -> R.string.place_kind_station
    PlaceKind.PLACE -> R.string.place_kind_place
}

private fun PlaceKind.icon(): ImageVector = when (this) {
    PlaceKind.ADDRESS -> Icons.Rounded.Home
    PlaceKind.STREET -> Icons.Rounded.AddRoad
    PlaceKind.TOWN -> Icons.Rounded.LocationCity
    PlaceKind.REGION -> Icons.Rounded.Map
    PlaceKind.FUEL -> Icons.Rounded.LocalGasStation
    PlaceKind.PARKING -> Icons.Rounded.LocalParking
    PlaceKind.FOOD -> Icons.Rounded.Restaurant
    PlaceKind.SHOP -> Icons.Rounded.Storefront
    PlaceKind.STATION -> Icons.Rounded.Train
    PlaceKind.PLACE -> Icons.Rounded.Place
}

/**
 * The detail under a hit's title as one line — "Station · Place de la Station 1, 5000 Namur ·
 * 2.3 km" — for a surface that has text only (Android Auto's `Row.addText`). [kindLabel] is
 * [labelRes] resolved by the caller, [near] the point the distance is measured from.
 */
fun searchResultDetail(result: GeocodeResult, kindLabel: String, near: LatLon?): String =
    listOfNotNull(
        kindLabel,
        result.secondary.ifEmpty { null },
        result.distanceFrom(near)?.let { formatDistanceKm(it) },
    ).joinToString(" · ")

/**
 * One tappable search hit: the kind's icon (history for a [recent] pick), the title, the kind and
 * second line under it, and the distance from [near] on the right when there is a point to
 * measure from. [dense] is the in-form list (route editor, saved places): smaller title, no
 * horizontal inset of its own; otherwise the map island's card row.
 */
@Composable
fun SearchResultRow(
    result: GeocodeResult,
    near: LatLon?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
    recent: Boolean = false,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(if (dense) DENSE_PADDING else CARD_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (recent) Icons.Rounded.History else result.kind.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            val titleStyle = if (dense) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
            Text(result.name, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(stringResource(result.kind.labelRes()), result.secondary.ifEmpty { null })
            Text(
                detail.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        result.distanceFrom(near)?.let {
            Spacer(Modifier.width(12.dp))
            Text(
                formatDistanceKm(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

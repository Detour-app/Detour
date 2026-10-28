package com.jellemax.detour.ui

import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

/** What a base-map layer becomes on the quiet Logbook map (#833). */
internal enum class QuietRole { Land, Water, Green, Road, Casing, Town }

/**
 * The OpenFreeMap positron/dark layers the quiet map keeps, by id, and what each
 * becomes. Both styles share one table: an id the loaded style doesn't have is
 * skipped. Every other id in [QUIET_HIDDEN] is switched off.
 */
internal val QUIET_KEPT: Map<String, QuietRole> = mapOf(
    "background" to QuietRole.Land,
    "water" to QuietRole.Water,
    "waterway" to QuietRole.Water,
    "park" to QuietRole.Green,
    "landuse_park" to QuietRole.Green,
    "landcover_wood" to QuietRole.Green,
    "highway_minor" to QuietRole.Road,
    "highway_major_inner" to QuietRole.Road,
    "highway_major_subtle" to QuietRole.Road,
    "highway_motorway_inner" to QuietRole.Road,
    "highway_motorway_subtle" to QuietRole.Road,
    "highway_motorway_bridge_inner" to QuietRole.Road,
    "highway_major_casing" to QuietRole.Casing,
    "highway_motorway_casing" to QuietRole.Casing,
    "highway_motorway_bridge_casing" to QuietRole.Casing,
    // The one place name: a town, or a city when the ride spans one.
    "label_town" to QuietRole.Town,
    "label_city" to QuietRole.Town,
    "place_town" to QuietRole.Town,
    "place_city" to QuietRole.Town,
)

/** Layers of either style switched off: buildings, paths, rail, borders, and every label but the town. */
internal val QUIET_HIDDEN: List<String> = listOf(
    "landcover_ice_shelf", "landcover_glacier", "landuse_residential", "building",
    "tunnel_motorway_casing", "tunnel_motorway_inner",
    "aeroway-taxiway", "aeroway-runway-casing", "aeroway-area", "aeroway-runway",
    "road_area_pier", "road_pier", "highway_path", "road_oneway", "road_oneway_opposite",
    "railway_transit", "railway_transit_dashline", "railway_service", "railway_service_dashline",
    "railway_minor", "railway_minor_dashline", "railway", "railway_dashline",
    "boundary_3", "boundary_2", "boundary_disputed", "boundary_state",
    "boundary_country_z0-4", "boundary_country_z5-",
    "waterway_line_label", "water_name_point_label", "water_name_line_label", "water_name",
    "highway-name-path", "highway-name-minor", "highway-name-major", "highway_name_other",
    "highway_name_motorway", "highway-shield-non-us", "highway-shield-us-interstate", "road_shield_us",
    "airport", "label_other", "label_village", "label_state", "label_city_capital",
    "label_country_3", "label_country_2", "label_country_1",
    "place_other", "place_suburb", "place_village", "place_city_large", "place_state",
    "place_country_other", "place_country_minor", "place_country_major",
)

/** Pale land, soft water and parks, white roads; the dark variant keeps the same steps, lowered. */
internal fun QuietRole.color(dark: Boolean): String = when (this) {
    QuietRole.Land -> if (dark) "#1E1F21" else "#F4F3EF"
    QuietRole.Water -> if (dark) "#25313A" else "#D3E0E8"
    QuietRole.Green -> if (dark) "#222B24" else "#E1EADB"
    QuietRole.Road -> if (dark) "#393B3F" else "#FFFFFF"
    QuietRole.Casing -> if (dark) "#2B2C2F" else "#E3E1DB"
    QuietRole.Town -> if (dark) "#8C8E92" else "#76766F"
}

/**
 * Recolours or hides the loaded positron/dark style's layers into the quiet
 * Logbook map. [layer] is the loaded style's lookup — `Style::getLayer` on a
 * map view, `MapSnapshotter::getLayer` once a snapshotter's style has loaded —
 * so the map view and the thumbnails get the same look from the same tiles.
 */
internal fun quietenLogbookMap(dark: Boolean, layer: (String) -> Layer?) {
    for (id in QUIET_HIDDEN) layer(id)?.setProperties(PropertyFactory.visibility(Property.NONE))
    for ((id, role) in QUIET_KEPT) {
        val color = role.color(dark)
        when (val l = layer(id)) {
            is BackgroundLayer -> l.setProperties(PropertyFactory.backgroundColor(color))
            is FillLayer -> l.setProperties(PropertyFactory.fillColor(color))
            is LineLayer -> l.setProperties(PropertyFactory.lineColor(color))
            is SymbolLayer -> l.setProperties(
                PropertyFactory.textColor(color),
                PropertyFactory.textHaloColor(QuietRole.Land.color(dark)),
            )
            else -> Unit
        }
    }
}

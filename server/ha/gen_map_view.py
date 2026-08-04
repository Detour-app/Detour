"""Emit the dashboard's ride-map cards: three overlays x two rebuild slots.

Two card-side facts drive this shape, both learned the hard way:

  * `entities:` GeoJSON is drawn from the hass snapshot the card was built
    with — `Entity` stores hass in its constructor and nothing ever refreshes
    it — so picking another ride changed the sensors and not the map. The
    *top-level* `geojson:` layers go through GeoJsonRenderService, which is
    handed the current hass on every render, so those do follow. They are keyed
    by entity id, hence one sensor per band.
  * The map's *view* comes from marker entities, which were stale for the same
    reason. That is fixed for good by /config/www/map-card-hass-refresh.js,
    which hands the card's entities the current hass on every render, so
    `focus_follow: contains` can refit when a newly picked ride falls outside
    the current view. Before that patch this file carried two copies of every
    overlay and an input_boolean that flipped on each ride change to force a
    rebuild; it still framed the previous ride about a quarter of the time.

    python3 server/ha/gen_map_view.py > section.yaml
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'sync'))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_bands import layers  # noqa: E402

TILE = """          tile_layer_url: https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png
          tile_layer_attribution: >-
            &copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>
            &copy; <a href="https://carto.com/attributions">CARTO</a>
          tile_layer_options:
            subdomains: abcd
            maxZoom: 20
"""

CORNERS = """          entities:
            - entity: sensor.map_roulette_track_ne
              display: state
              size: 1
              css: "opacity: 0;"
            - entity: sensor.map_roulette_track_sw
              display: state
              size: 1
              css: "opacity: 0;"
"""

ROUTE_LAYER = """            - entity: sensor.map_roulette_track
              attribute: geojson
              color: "#e8b04b"
              width: 5
              opacity: 0.9
"""


def card(overlay):
    body = [
        "      - type: conditional\n",
        "        conditions:\n",
        "          - condition: state\n",
        "            entity: input_select.map_roulette_overlay\n",
        "            state: %s\n" % overlay,
        "        card:\n",
        "          type: custom:map-card\n",
        "          card_size: 12\n",
        "          theme_mode: auto\n",
        # Refit when the picked ride falls outside what is on screen, and leave
        # the view alone when it doesn't — panning around a ride should stick.
        "          focus_follow: contains\n",
        TILE,
        "          geojson:\n",
    ]
    if overlay == "Route":
        body.append(ROUTE_LAYER)
    else:
        body.append(layers(overlay.lower(), indent=12))
    body.append(CORNERS)
    return "".join(body)


if __name__ == "__main__":
    out = []
    for overlay in ("Route", "Speed", "Lean"):
        out.append("      # --- %s ---\n" % overlay)
        out.append(card(overlay))
        out.append("\n")
    sys.stdout.write("".join(out))

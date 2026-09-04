# Detour in Home Assistant

Lifetime totals, badges and your recent rides as Home Assistant entities, read
from the service's `/api/dashboard/*` endpoints. Those are read-only and
authenticate with a dashboard API key, so no rider session ever lands in your HA
config — a key can only ever read its own owner's data.

## 1. Mint a key

Signed in as yourself, ask the API for one:

```
curl -X POST https://your-server.example/api/me/api-keys \
  -H "Authorization: Bearer <your access token>" \
  -H 'Content-Type: application/json' \
  -d '{"label": "home-assistant"}'
```

It comes back once — only its hash is stored. `GET /api/me/api-keys` lists what
you have issued, and `DELETE /api/me/api-keys/{id}` revokes one that leaks.

## 2. Add secrets

In `config/secrets.yaml`:

```yaml
detour_stats_url: https://your-server.example/api/dashboard/stats
detour_rides_url: https://your-server.example/api/dashboard/rides
detour_key: <the key from step 1>
```

## 3. Install the package

Copy `detour.yaml` to `config/packages/detour.yaml`, and make sure
`configuration.yaml` loads packages:

```yaml
homeassistant:
  packages: !include_dir_named packages
```

Restart Home Assistant (Developer tools → YAML → Restart). Fifteen entities
appear under `sensor.map_roulette_*` — the app's old name, kept because renaming
an entity breaks every card and automation already pointing at it.

| Entity | What it holds |
| --- | --- |
| `sensor.map_roulette_total_distance` | Lifetime km, as `total_increasing` so it charts |
| `sensor.map_roulette_rides` | Rides stored on the server |
| `sensor.map_roulette_badges` | Badges earned; the full id → timestamp map is an attribute |
| `sensor.map_roulette_top_speed` | Lifetime top speed |
| `sensor.map_roulette_longest_ride` | Longest single ride |
| `sensor.map_roulette_max_lean` | Deepest lean; unavailable if nothing has recorded lean |
| `sensor.map_roulette_municipalities` | Municipalities entered |
| `sensor.map_roulette_best_coverage` | Best per-municipality road coverage, % |
| `sensor.map_roulette_last_ride` | Start time; distance, mode, speed, lean, g and map link as attributes |
| `sensor.map_roulette_last_ride_distance` / `_top_speed` / `_vehicle` | Last ride, split out for cards |
| `sensor.map_roulette_last_ride_duration` / `_average_speed` | Derived from the attributes above |
| `sensor.map_roulette_recent_rides` | Count of the fetched page; the ride list is an attribute |

Both endpoints are polled every 5 minutes; `/api/dashboard/rides` fetches the newest 25
(raise `params: limit:` up to 200).

## 4. The dashboard

`dashboard.yaml` is a ready-made dashboard for these entities: lifetime totals
and a coverage gauge, speed/lean gauges with a kilometres-per-day bar chart, the
last ride broken out, and a table of the last 15 rides. A second view lists the
badges with their earned dates.

The one card it does **not** fill in is the map: it ships an empty iframe where
the old server's rendered track page used to sit. See
[Cards of your own](#5-cards-of-your-own) for the endpoint that replaced it.

Copy the file to `config/dashboards/detour.yaml` and register it in
`configuration.yaml`:

```yaml
lovelace:
  # Storage mode is the default; naming it here keeps the UI-editable
  # dashboards working alongside the YAML one.
  mode: storage
  dashboards:
    detour:
      mode: yaml
      title: Detour
      icon: mdi:motorbike
      show_in_sidebar: true
      filename: dashboards/detour.yaml
```

Restart, and *Detour* appears in the sidebar. YAML dashboards have no UI
editor — edit the file and use the three-dot menu → *Reload*. Everything is a
built-in card, so no HACS packages are needed.

## 5. Cards of your own

Recent rides as a table:

```yaml
type: markdown
title: Recent rides
content: >-
  | When | Vehicle | Distance | Top |
  |---|---|---|---|
  {% for r in state_attr('sensor.map_roulette_recent_rides', 'rides')[:10] -%}
  | {{ (r.startMs / 1000) | timestamp_custom('%a %d %b %H:%M') }} | {{ r.mode | title }} | {{ r.distanceKm }} km | {{ r.topSpeedKmh }} km/h |
  {% endfor %}
```

The last ride's track needs a card of your own. The old iframe card pointed at a
map page the previous server rendered itself; this service renders nothing and
serves geometry as JSON instead:

```
GET /api/dashboard/rides/track?start=<ms>&tolerance=6&max=400
```

Leave `start` off and it returns the **newest** ride, so a polling sensor needs
no second request to discover what "latest" is. `tolerance` is the
simplification distance in metres and `max` the point budget — the defaults are
sized to fit inside a Home Assistant entity attribute. Speed and lean peaks are
read off the raw track rather than off what survived thinning, so a dropped
sample never drops the corner it was carrying.

Draw it with whatever you already use for geometry — a `rest` sensor feeding a
map card, or a custom card that reads the coordinate list. The rest of the
dashboard does not depend on this one.

## 6. Optional: skip the tunnel on your LAN

Point the secrets at wherever the service listens on your network:

```yaml
detour_stats_url: http://192.168.0.8:7500/api/dashboard/stats
detour_rides_url: http://192.168.0.8:7500/api/dashboard/rides
```

What that changes: every host on the LAN can reach the API. Reads still need a
dashboard key and still only ever return that key's owner's data, and everything
else needs a token from the realm — but it is reachable where before only the
tunnel was. Keep the port off any WAN port-forward.

## Notes

- `municipalitiesVisited`, `bestCoveragePercent` and the other `stats` values
  are whatever the phone last uploaded — they refresh on the next sync, not on
  the next poll.
- `rideCount` counts what the server actually stores, which is what the ride
  list can drill into; `stats.tripCount` is the phone's own count. They agree
  once a sync has completed.

## Where the older notes went

`docs/HOME_ASSISTANT_RIDES.md` and `docs/HOME_ASSISTANT_HEATMAP.md` documented
the `/ha/*` endpoints of the server this one replaced, and a proposal to add a
heatmap endpoint to it. Both are gone: this file is the install guide, and the
service's own read-only surface — stats, rides, ride geometry, traces and
coverage — is documented with the API in [`backend/`](../../backend/README.md).

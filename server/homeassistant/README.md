# Map Roulette in Home Assistant

Lifetime totals, badges and your recent rides as Home Assistant entities, read
from the sync server's `/ha/*` endpoints. Those are read-only and authenticate
with a dashboard API key, so no login token ever lands in your HA config.

## 1. Mint a key

On the sync server:

```
cd /opt/maproulette-sync
DATA_DIR=/var/lib/maproulette-sync python3 sync_server.py --api-key <username> home-assistant
```

It prints the key once — only its hash is stored. `--revoke-keys <username>`
drops every dashboard key for that user if one leaks.

## 2. Add secrets

In `config/secrets.yaml`:

```yaml
maproulette_stats_url: https://your-sync-server.example/ha/stats
maproulette_rides_url: https://your-sync-server.example/ha/rides
maproulette_key: <the key from step 1>
# Only if the server sits behind Cloudflare Access — the same service token
# the app uses for the routing server works here:
maproulette_cf_id: <service token client id>
maproulette_cf_secret: <service token client secret>
```

If your server is *not* behind Cloudflare Access, delete the two
`CF-Access-*` header lines from each `rest:` block in `maproulette.yaml`.

## 3. Install the package

Copy `maproulette.yaml` to `config/packages/maproulette.yaml`, and make sure
`configuration.yaml` loads packages:

```yaml
homeassistant:
  packages: !include_dir_named packages
```

Restart Home Assistant (Developer tools → YAML → Restart). Fifteen entities
appear under `sensor.map_roulette_*`.

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

Both endpoints are polled every 5 minutes; `/ha/rides` fetches the newest 25
(raise `params: limit:` up to 200).

## 4. Cards

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

The last ride's track, coloured by lean angle:

```yaml
type: iframe
url: https://your-sync-server.example/ha/ride.html?key=YOUR_KEY
aspect_ratio: 75%
```

An iframe cannot send a header, so the map URL carries the key as a query
parameter — which means it sits in your dashboard config. It is read-only and
scoped to your rides, but treat that dashboard as sensitive, and revoke the key
if you share screenshots of the raw config. Append `&start=<startMs>` to pin the
card to one specific ride instead of the newest.

**Behind Cloudflare Access, the iframe card will not load**: the browser can't
send the service-token headers, so Access answers with its login page instead of
the map. The REST sensors are unaffected (they do send the headers). Two ways
round it, both on your side:

- add a Cloudflare Access *bypass* policy for the `/ha/ride.html` path on that
  hostname — the API key still gates the data; or
- reach the sync server directly on your LAN instead of through the tunnel. It
  listens on `127.0.0.1:8790` by default, so this means setting
  `Environment=HOST=0.0.0.0` in the systemd unit and pointing the card (and, if
  you like, the sensors) at `http://<server-ip>:8790`. That exposes the API to
  everything on your LAN, still key-gated.

## Notes

- `municipalitiesVisited`, `bestCoveragePercent` and the other `stats` values
  are whatever the phone last uploaded — they refresh on the next sync, not on
  the next poll.
- `rideCount` counts what the server actually stores, which is what the ride
  list can drill into; `stats.tripCount` is the phone's own count. They agree
  once a sync has completed.

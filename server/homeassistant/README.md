# Detour in Home Assistant

Lifetime totals, badges and your recent rides as Home Assistant entities, read
from the sync server's `/ha/*` endpoints. Those are read-only and authenticate
with a dashboard API key, so no login token ever lands in your HA config.

## 1. Mint a key

On the sync server:

```
cd /opt/detour-sync
DATA_DIR=/var/lib/detour-sync python3 sync_server.py --api-key <username> home-assistant
```

It prints the key once — only its hash is stored. `--revoke-keys <username>`
drops every dashboard key for that user if one leaks.

## 2. Add secrets

In `config/secrets.yaml`:

```yaml
detour_stats_url: https://your-sync-server.example/ha/stats
detour_rides_url: https://your-sync-server.example/ha/rides
detour_key: <the key from step 1>
# Only if the server sits behind Cloudflare Access — the same service token
# the app uses for the routing server works here:
detour_cf_id: <service token client id>
detour_cf_secret: <service token client secret>
```

If your server is *not* behind Cloudflare Access, delete the two
`CF-Access-*` header lines from each `rest:` block in `detour.yaml`.

## 3. Install the package

Copy `detour.yaml` to `config/packages/detour.yaml`, and make sure
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

## 4. The dashboard

`dashboard.yaml` is a ready-made dashboard for these entities: lifetime totals
and a coverage gauge, speed/lean gauges with a kilometres-per-day bar chart, the
last ride broken out with the track map beside it, and a table of the last 15
rides. A second view lists the badges with their earned dates.

Copy it to `config/dashboards/detour.yaml`, set the iframe card's `url` to
your own server and key, and register it in `configuration.yaml`:

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
- reach the sync server directly on your LAN instead of through the tunnel (see
  below), which also gets Home Assistant off the tunnel for the sensors.

## 6. Optional: skip the tunnel on your LAN

The server listens on `127.0.0.1:8790` by default. To let Home Assistant talk to
it directly, drop a systemd override in:

```
# /etc/systemd/system/detour-sync.service.d/lan.conf
[Service]
Environment=HOST=0.0.0.0
```

then `systemctl daemon-reload && systemctl restart detour-sync`. Point the
secrets at the LAN address and drop the two `CF-Access-*` header lines from both
`rest:` blocks in `detour.yaml`:

```yaml
detour_stats_url: http://192.168.0.8:8790/ha/stats
detour_rides_url: http://192.168.0.8:8790/ha/rides
```

The iframe card then works too, at `http://192.168.0.8:8790/ha/ride.html?key=…`.

Two things this changes. Every host on the LAN can now reach the API — still
key-gated for `/ha/*`, and login/register still need credentials, but they are
reachable where before only the tunnel was. And if the unit sets
`TRUST_CF_HEADER=1` (correct while the only way in was Cloudflare), a LAN client
can now send its own `CF-Connecting-IP` and get a fresh rate-limit bucket per
guess on `/login`. Keep the port off any WAN port-forward, and use a real
password.

## Notes

- `municipalitiesVisited`, `bestCoveragePercent` and the other `stats` values
  are whatever the phone last uploaded — they refresh on the next sync, not on
  the next poll.
- `rideCount` counts what the server actually stores, which is what the ride
  list can drill into; `stats.tripCount` is the phone's own count. They agree
  once a sync has completed.

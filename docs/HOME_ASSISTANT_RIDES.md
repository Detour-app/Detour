# Rides in Home Assistant — speed and lean per point

Every recorded point now carries **when**, **how fast** and **how far leaned**,
not just where. The sync server unpacks those into a table and serves them to
Home Assistant: a map of a ride coloured by lean, and the ride's numbers as
sensors.

Companion to `HOME_ASSISTANT_HEATMAP.md`, which covers the all-rides heatmap.
Same server, same key mechanism.

## How the data gets there

1. **App** — a trace point is `[lat, lon, timeMs, speedKmh, leanDeg]`.
   Decimated to ~25 m as before, so a ride is a few hundred points.
   `leanDeg` is signed (**positive = leaning right**) and is the *peak* since
   the previous point — 25 m is a whole corner at town speed, and the deepest
   lean through it is the number worth keeping. It is `null` on a vehicle that
   doesn't measure lean, which is anything but `MOTO`.
2. **Sync** — unchanged protocol. Lines are uploaded whole as before; the
   server unpacks the ones that are new to it into `track_points`.
3. **Ride linkage is by time.** A trace line has no trip id, so a point belongs
   to whichever trip was running at its `t_ms`. Nothing to keep in step.

Rides recorded before this change have two-element points: they still draw fog,
but they have no time, speed or lean, and no map. Those five gimbal-locked lean
values are gone for good — see the note at the end.

## Server setup

```bash
# on the server host
systemctl restart maproulette-sync          # picks up the new tables

# read-only key for the dashboard — printed once, store it in HA secrets
python3 sync_server.py --api-key YOURNAME home-assistant
```

The key is **not** a login token: `/ha/*` is read-only, and revoking a key
(delete its row from `api_keys`) doesn't sign the phone out. Never put the app's
bearer token in an HA config.

Points arrive on the next sync after a ride. If `track_points` is ever cleared,
`python3 sync_server.py --backfill-points YOURNAME` re-unpacks everything
already stored.

## Endpoints

| Endpoint | Returns |
|---|---|
| `GET /ha/rides?key=…[&limit=25]` | rides newest first: `startMs`, `endMs`, `mode`, `distanceKm`, `topSpeedKmh`, `maxLeanDeg`, `maxGForce`, `pointCount`, `map` |
| `GET /ha/ride.geojson?key=…&start=<startMs>` | one `Feature` per segment, each with `leanDeg`, `speedKmh`, `tMs` |
| `GET /ha/ride.html?key=…[&start=<startMs>]` | Leaflet page, path coloured by lean. No `start` = newest ride |

`X-API-Key: …` works instead of `?key=` wherever a header can be sent; an
iframe can't send one, which is why the query form exists. Server logs redact
`key=`.

`maxLeanDeg` is `null`, not `0`, when a ride has no lean recorded — unknown and
"upright the whole way" are different things.

## Home Assistant config

Max lean and distance of the latest ride, as sensors:

```yaml
# configuration.yaml
rest:
  - resource: https://YOUR-SYNC-HOST/ha/rides?limit=1
    headers:
      X-API-Key: !secret maproulette_key
    scan_interval: 300
    sensor:
      - name: "Last ride max lean"
        value_template: "{{ value_json.rides[0].maxLeanDeg | default(0) }}"
        unit_of_measurement: "°"
        state_class: measurement
      - name: "Last ride distance"
        value_template: "{{ value_json.rides[0].distanceKm }}"
        unit_of_measurement: "km"
        state_class: measurement
      - name: "Last ride top speed"
        value_template: "{{ value_json.rides[0].topSpeedKmh }}"
        unit_of_measurement: "km/h"
        state_class: measurement
```

The lean-coloured map, as a dashboard card:

```yaml
type: iframe
url: https://YOUR-SYNC-HOST/ha/ride.html?key=YOUR_KEY
aspect_ratio: 75%
```

That card always shows the newest ride. For a fixed one, append
`&start=<startMs>` from `/ha/rides`.

Behind Cloudflare Access, the dashboard host needs a service-token bypass for
`/ha/*`, or the iframe gets the Access login page instead of the map.

## Colour scale

Diverging, on the rendered page: blue leaning left, grey upright, red leaning
right, saturating at 45°. Segments with no lean recorded stay grey. The raw
signed numbers are in the GeoJSON if you'd rather render it yourself.

## Note on old rides

Lean was recorded through `getOrientation()`'s roll, which is undefined for a
phone standing upright in a handlebar mount — it pinned to ±180° regardless of
the actual lean. Five stored rides carried that value; they were zeroed, and
zeroed is what they stay: the raw sensor stream was never persisted, so there is
nothing to recompute from. Everything from the fix onward is real.

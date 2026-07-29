# Map Roulette

Don't know where to drive? Set a radius, spin, get a random point on a real road, and go.

## Screenshots

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/spin-dark.png" width="260" alt="Spin screen, dark theme"><br><sub>Set a radius and spin — dark</sub></td>
    <td align="center"><img src="docs/screenshots/map-light.png" width="260" alt="Map with fog of war, light theme"><br><sub>Map &amp; fog of war — light</sub></td>
  </tr>
</table>

## Features

- **Modes**: walk (1–15 km), bike (1–30 km), car (5–100 km), and moto (30–400 km) — each
  picking the road types that suit it: footways and paths for walking, cycleways for the
  bike, motorways only for the car, the rural network for the moto.
- **Spin**: picks a random point within your chosen radius, using OpenStreetMap data via
  the Overpass API. No API key needed. Samples a random sub-area instead of downloading
  every road in the circle, so it stays fast at large radii. Aim it at a random road, or
  at a viewpoint, somewhere to eat, or a sight worth stopping for.
- **Moto round trips**: a moto spin doesn't give you a destination, it gives you a loop of
  waypoints through the curviest roads around you — the slider sets the total trip length
  — and hands it to Google Maps as a multi-waypoint route. Curviness is junction-aware:
  turn radius is estimated from road geometry (circumcircle per vertex triple), and
  vertices at intersections are excluded — so a left turn at a crossroads doesn't count
  as a "curve", only sweeping bends within the road do.
- **Navigate**: turn-by-turn inside the app — maneuver arrow, distance to the turn, speed
  against the posted limit, rerouting when you leave the line — routed by your own
  GraphHopper instance. Or hand the destination off to Google Maps, Waze, or any
  installed maps app.
- **Speed cameras**: fixed cameras and Belgian trajectcontrole sections from OpenStreetMap
  (via Overpass), drawn on the map. A chime warns when a camera lies ahead and you're over
  the posted limit, and while driving an average-speed section the running average is shown
  next to your speed — the number the camera pair actually judges you on.
- **On your wrist**: a Wear OS companion shows the next maneuver and how far to it, and
  wakes itself on the watch when navigation starts on the phone.
- **Track**: foreground service records your ride — duration, distance, current and top
  speed. On the moto it also records maximum lean angle and cornering g, and in the car
  just the g. A bicycle and a walk get neither: from a rigid mount a lean angle means
  something, from a cradle it is the phone sliding around.
- **Vehicle auto-detect**: assign a paired Bluetooth device to a vehicle (a Cardo intercom
  to the moto, the car's infotainment to driving, your earbuds to walking) and a trip logs
  under that vehicle whenever the device is connected. With nothing connected, a sustained
  walking pace logs as a walk. These are Bluetooth Classic bonds, so no scanning and no
  location permission — just connect/disconnect.
- **Fog of war**: everywhere you have driven is uncovered on the map, and stays uncovered.
- **Coverage and badges**: how much of each municipality you have driven — resolved from
  OSM `admin_level=8` boundaries — plus badges for distance, top speed, rides,
  municipalities covered, and coverage.
- **Friends**: opt in to a shared fog of war and see where they have been. Off by default,
  and reciprocal: the server only hands you a friend's traces if you are sharing yours.
- **History**: past trips with duration, distance, average and top speed, and — where the
  mode records them — peak lean angle and g. Correct a trip's vehicle if it was
  misclassified, or delete a false-positive detection.

## Stack

Kotlin, Jetpack Compose, Material 3, MapLibre GL (OpenFreeMap vector tiles), Overpass API,
GraphHopper for routing, fused location provider, Wearable Message API for the watch.
Trips and traces stored as JSON in app-private storage. Min SDK 26 (Android 8.0).

## Build

```
./gradlew assembleDebug
```

Phone APK lands in `app/build/outputs/apk/debug/app-debug.apk`, watch APK in
`wear/build/outputs/apk/debug/wear-debug.apk`. Install with
`adb install app/build/outputs/apk/debug/app-debug.apk`.

## Self-hosting the server

The app can sync to your own server (accounts, trips, fog of war, friends) and
route against your own GraphHopper instance. One script installs either or both
— on a Proxmox host it builds an LXC for you, anywhere else it installs in place.

```
bash server/install.sh
```

See [`server/INSTALL.md`](server/INSTALL.md) for exposing it safely, choosing an
OSM region, backups, and the API. Verify a running install with
`bash server/verify.sh`.

Sync is optional; with no server configured everything stays on the phone. With
one, your trips and traces live on hardware you own.

### What leaves your device

Even without a sync server, a few features talk to the network by design:
Overpass sees the spin center and radius you choose, OpenFreeMap's tiles see
your current map viewport, and address/place search sends your query (and an
approximate location, to rank nearby results first) to Photon — your own
instance if you've set one in Settings, otherwise the public
`photon.komoot.io`. If you self-host Photon, search falls back to the public
instance only when yours is unreachable, and only if you leave "Fall back to
public search" (Settings → Server) turned on; turn it off to keep search on
your own hardware even when your instance is down.

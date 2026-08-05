# Map Roulette

Don't know where to drive? Set a radius, spin, get a random point on a real road, and go.

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/map.png" width="240" alt="Map screen"><br><sub>The map</sub></td>
    <td align="center"><img src="docs/screenshots/spin.png" width="240" alt="Spin sheet"><br><sub>Set up a spin</sub></td>
    <td align="center"><img src="docs/screenshots/route.png" width="240" alt="Route to a spun destination"><br><sub>A destination, routed</sub></td>
  </tr>
</table>

<sub>Every screenshot in this README was taken on a phone running a throwaway
profile: synthetic trips, invented saved places and a mocked GPS position around
Ghent. Nothing in them is a real location.</sub>

## Contents

- [Install](#install)
- [First run](#first-run)
- [The map screen](#the-map-screen)
- [Spinning a destination](#spinning-a-destination)
- [Getting there](#getting-there)
- [Recording a ride](#recording-a-ride)
- [Fog of war](#fog-of-war)
- [Search and saved places](#search-and-saved-places)
- [You: history, badges, friends](#you-history-badges-friends)
- [Settings reference](#settings-reference)
- [On your wrist](#on-your-wrist)
- [On the car screen](#on-the-car-screen)
- [Stack](#stack)
- [Build](#build)
- [Self-hosting the server](#self-hosting-the-server)
- [Attribution](#attribution)

## Install

Grab the APK from the latest release and install it, or build it yourself (see
[Build](#build)). Min SDK 26 (Android 8.0). A Wear OS APK ships alongside if you
want the watch companion.

Releases from CI are signed with a key that a local build does not have, so you
cannot install a debug build over a release one (or the other way round) without
uninstalling first — which deletes your trips and fog. Stick to one source.

## First run

1. **Grant location.** Precise location is required: the whole app is "where am
   I, and where could I go from here". Background location is only needed if you
   want trips to keep recording with the screen off.
2. **Allow notifications** if you want the ride-tracking notification and the
   speed-camera chime.
3. **Pick a mode** in the bar at the bottom — walk, bike, moto or car. It sets
   the radius range and which roads a spin is allowed to land on.
4. **Spin.** The dice button picks a random point within your radius and offers
   three routed candidates.
5. **Go.** Navigate in the app, or hand the destination to Google Maps, Waze or
   anything else installed.

Everything works with no account and no server. Sign-in only buys you sync,
friends and a shared fog of war.

## The map screen

<img src="docs/screenshots/map.png" width="260" align="right" alt="Map screen with fog and shortcut chips">

- **Search pill** (top) — address and place search. See
  [Search and saved places](#search-and-saved-places).
- **Avatar** (top right of the pill) — opens **You**: history, badges, friends,
  saved places, settings.
- **Crosshair** — follow mode. On, the map tracks you and rotates to your
  heading; tap it off (or pan the map) to look around freely, tap again to snap
  back.
- **Layers** — the fog-of-war toggle.
- **Shortcut chips** — one tap sets a saved place as the destination. A **Save
  pin** chip appears whenever there's a destination on the map that isn't saved
  yet.
- **Spin dock** (bottom) — current mode, radius and direction; the dice button;
  and the navigate button. Tap the left half to expand it into the full spin
  sheet.
- **Mode bar** — walk, bike, moto, car.

While you are moving, a speed dial appears above the dock, with the posted limit
next to it when the road has one.

<br clear="right">

## Spinning a destination

<img src="docs/screenshots/spin.png" width="260" align="right" alt="Spin sheet">

Expand the dock to get the full sheet.

**Destination type** — what the spin should aim at:

| Type | What it lands on |
| --- | --- |
| Road | Any road the mode is allowed on — the default lucky-dip |
| Viewpoint | OSM `tourism=viewpoint` |
| Food & drink | Cafés, restaurants, pubs, bars, ice cream |
| Sight | Castles, ruins, monuments, forts, memorials, attractions |

**Radius** — how far out to look, as the crow flies. Each mode has its own range
and picks road types to match:

| Mode | Radius | Default | Roads it uses |
| --- | --- | --- | --- |
| Walk | 1–15 km | 3 km | Footways, paths, pedestrian and quiet residential streets |
| Bike | 1–30 km | 10 km | Cycleways and quiet roads |
| Moto | 30–400 km | 120 km | The rural network — see round trips below |
| Car | 5–100 km | 25 km | Everything up to and including motorways |

**Min distance** — a floor, so a 100 km car spin can't drop you three streets
away. Leave it at *Off* for a true random draw.

**Direction** — bias the draw towards one of the eight compass sectors, or
*Any*. Useful when the coast is one way and you'd rather not be sent into it.

**Spin** fires the draw. It samples a random sub-area of your circle rather than
downloading every road inside it, which is what keeps a 400 km moto spin quick.
Tap it again while it's running to cancel.

The draw is fog-aware: it biases destinations towards ground your fog of war
hasn't uncovered yet, so spinning tends to send you somewhere new rather than
down the road you take every day.

<br clear="right">

<img src="docs/screenshots/candidates.png" width="260" align="right" alt="Three routed candidates">

A spin returns **three candidates**, each routed, with distance and drive time.
They are all drawn on the map as lettered pins — tap a pin or a row to commit to
one. **Reroll** draws three new ones; **Cancel** drops them and leaves the map
as it was.

Picking one draws the route and leaves the destination pinned. From there you
can save it as a shortcut with the **Save pin** chip.

<br clear="right">

### Moto round trips

Moto mode doesn't hand you a destination — it builds a **loop**. The slider sets
total trip length, and the spin returns a ride out through the curviest roads
around you and back to where you started.

Curviness is junction-aware: turn radius is estimated per vertex triple from the
road geometry, and vertices that sit at intersections are excluded — so a left
turn at a crossroads doesn't score as a "curve", only sweeping bends within a
road do. The loop is handed to Google Maps as a multi-waypoint route, or driven
in-app like any other route.

With a routing server configured, the loop is a single request that comes back
following real roads. Without one, the app plans an approximate loop from
Overpass data and says so.

## Getting there

<img src="docs/screenshots/handoff.png" width="260" align="right" alt="Navigation hand-off menu">

The navigate button offers:

- **Navigate in app** — turn-by-turn inside Map Roulette, routed by your own
  GraphHopper instance (configured under Settings → Servers & sync). Without a
  routing server configured, this option isn't available.
- **Google Maps** / **Waze** / **Other app** — hand the destination off. For a
  moto round trip, Google Maps gets the whole waypoint chain.

<br clear="right">

<img src="docs/screenshots/navigate.png" width="260" align="right" alt="In-app navigation">

In-app navigation shows the next maneuver and the distance to it, a **then**
pill for the maneuver after that (so a turn-then-turn doesn't ambush you), and a
bottom bar with remaining distance, remaining time, arrival clock time and a
progress track. Leave the line and it reroutes; while it's off the route the bar
says so.

Your speed sits bottom-right with the posted limit beside it, and goes red when
you're over. **Speed cameras** — fixed cameras and Belgian *trajectcontrole*
sections, both from OpenStreetMap — are drawn on the map; a chime warns when one
is ahead and you're over the limit. Inside an average-speed section, the running
average for that section is shown next to your live speed, since that is the
number the camera pair actually judges.

Navigation can avoid motorways or avoid narrow rural lanes — see
[Settings reference](#settings-reference).

<br clear="right">

## Recording a ride

<img src="docs/screenshots/speed.png" width="260" align="right" alt="Speed dial while driving">

Two ways in:

- **Automatically.** With *Auto-detect drives* on (the default), a sustained
  driving pace starts a trip on its own and backdates it to when the drive
  really began. It ends itself when you stop for good, or when you come back to
  where you started after a real ride. A brief stop — traffic light, fuel — does
  not end it.
- **Manually.** *Track walk / bike / moto / car* in the spin sheet starts one
  immediately. The red **End trip** button on the map ends whichever trip is
  running.

A live card shows elapsed time, distance, top speed and — depending on the
vehicle — max lean angle and cornering g. On a moto both are recorded; in a car
only g. A bike and a walk get neither: from a rigid mount a lean angle means
something, from a jacket pocket it's the phone sliding around.

**Vehicle auto-detect**: assign paired Bluetooth devices to a vehicle (an
intercom to the moto, the car's infotainment to the car, earbuds to walking) and
a trip logs under that vehicle whenever the device is connected. With nothing
connected, a sustained walking pace logs as a walk. These are Bluetooth Classic
bonds, so there's no scanning and no location permission involved — only
connect/disconnect.

If a trip is filed under the wrong vehicle, fix it afterwards from the history
list; false-positive detections can be deleted outright.

<br clear="right">

## Fog of war

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/fog.png" width="240" alt="Fogged map"><br><sub>Unexplored ground stays covered</sub></td>
    <td align="center"><img src="docs/screenshots/fog-toggle.png" width="240" alt="Fog of war toggle"><br><sub>Layers → Fog of war</sub></td>
  </tr>
</table>

Everywhere you have been is uncovered on the map, permanently. Everywhere else
is under a scrim. The reveal radius around your track is configurable (200 m by
default) under Settings → Fog of war, and the whole overlay can be switched off
from the layers button when you just want to read the map.

*Reset explored area* wipes it and starts you back at nothing.

With **Share fog with friends** on, accepted friends' explored ground is drawn
alongside yours, and yours alongside theirs. It's off by default and strictly
reciprocal: the server only hands you a friend's traces while you are sharing
your own.

## Search and saved places

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/search.png" width="240" alt="Place search"><br><sub>Search</sub></td>
    <td align="center"><img src="docs/screenshots/places.png" width="240" alt="Saved places"><br><sub>Saved places</sub></td>
  </tr>
</table>

Search runs against Photon and streams suggestions as you type — no search
button. Recent picks stay on top of the list, then live results, ranked with
nearby hits first. Tapping a result drops it as the destination and moves the
map there.

Saved places are named shortcuts. Add one from the **Save pin** chip after
dropping or spinning a destination, or with **Add place** on the Saved places
screen. They show up as chips over the map; one tap makes a place the current
destination.

## You: history, badges, friends

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/you.png" width="240" alt="You screen"><br><sub>You</sub></td>
    <td align="center"><img src="docs/screenshots/history.png" width="240" alt="Trip history"><br><sub>History</sub></td>
    <td align="center"><img src="docs/screenshots/badges.png" width="240" alt="Badges"><br><sub>Badges</sub></td>
  </tr>
</table>

The avatar on the map opens **You**: lifetime distance, ride count,
municipalities visited and badges earned, with everything else hanging off it.

**Trip history** lists every ride, newest first, grouped by month with a monthly
total. Each row has a thumbnail of the route's shape, duration, distance,
average and top speed, plus peak lean and g where the mode records them. The ⋮
menu on a row lets you **change vehicle** (for a misclassified trip) or
**delete** it.

**Badges** track five categories — Distance, Top speed, Single ride, Places and
Coverage — with progress shown on the ones you haven't earned yet. Coverage is
how much of a municipality's road network you've actually driven, resolved from
OSM `admin_level=8` boundaries; "Places" counts municipalities entered at all.

**Friends** needs an account on a sync server. Once signed in you can add
friends and compare totals, rides and badges on a leaderboard. Friends never see
your trips or your map — only totals and badges, plus your fog if you have
opted into sharing it.

<img src="docs/screenshots/account.png" width="260" align="right" alt="Account screen">

Signing in takes a username, a password and — if your server asks for one — an
invite code. The same account drives trip/trace sync, so a reinstall restores
your history and fog from the server. Leave an email address and the server can
mail you a reset link if you forget the password; the link opens straight back
into this screen.

<br clear="right">

## Settings reference

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/settings.png" width="240" alt="Settings"><br><sub>Settings</sub></td>
    <td align="center"><img src="docs/screenshots/tracking.png" width="240" alt="Tracking and vehicles"><br><sub>Tracking &amp; vehicles</sub></td>
  </tr>
</table>

**Appearance & map**
- *Theme* — System, Light, Dark, or **Auto** (light by day, dark by night,
  following sunrise and sunset at your location).
- *Default zoom* — where the map sits while following you. It zooms out up to
  two levels at speed and back in near a turn.

**Tracking & vehicles**
- *Auto-detect drives* — start trips by themselves.
- *Vehicles* — assign paired Bluetooth devices per mode.
- *Vehicle mounting* — calibrate a mount that isn't perfectly upright, so
  straight-line riding reads as 0° lean. Sit the bike upright, engine off, phone
  in its cradle, then calibrate.

**Navigation**
- *Avoid highways* — in-app navigation skips motorways in car mode.
- *Avoid small roads* — prefer real roads over narrow rural lanes.

**Fog of war**
- *Reveal radius* — how wide a corridor your track uncovers.
- *Share fog with friends* — reciprocal fog sharing, off by default.
- *Reset explored area* — wipe the fog.

**Displays & media**
- *External display* — broadcast navigation to an external screen, and show
  what's playing on it. Needs Bluetooth permission, and notification access for
  the now-playing part.

**Servers & sync**
- *Routing server* — your GraphHopper URL, with optional Cloudflare Access
  client ID/secret. Without one, in-app navigation is unavailable and spin
  candidates show straight-line distance instead of a routed distance and ETA.
- *Search server* — your own Photon instance, plus *Fall back to public search*
  (on by default) which uses `photon.komoot.io` when yours is unreachable.
- *Sync server* — trips, traces, saved places and friends. *Sync now* forces a
  round trip and reports what merged.
- *Server config file* — export the whole server setup to a file and import it
  on another device, so you configure this once.

## On your wrist

A Wear OS companion shows the next maneuver and the distance to it, and wakes
itself on the watch when navigation starts on the phone. Install the watch APK
from the same release.

## On the car screen

Android Auto gets a car-sized spin: pick a radius, spin a destination, and drive
it turn by turn on the head unit, with the same map, speed readout and camera
warnings as the phone. Search works there too.

One catch, and it is Google's rather than the app's: a real head unit only lists
apps built on the Android for Cars App Library when they were installed **from
Google Play**. The Desktop Head Unit accepts a sideloaded APK, a car never does.
[docs/ANDROID_AUTO.md](docs/ANDROID_AUTO.md) covers the Internal App Sharing
route and how to debug the car screen.

## Stack

Kotlin, Jetpack Compose, Material 3, MapLibre GL (OpenFreeMap vector tiles),
Overpass API, GraphHopper for routing, fused location provider, Wearable Message
API for the watch. Trips and traces stored as JSON in app-private storage. Min
SDK 26 (Android 8.0).

## Build

```
./gradlew assembleDebug
```

Phone APK lands in `app/build/outputs/apk/debug/app-debug.apk`, watch APK in
`wear/build/outputs/apk/debug/wear-debug.apk`. Install with
`adb install app/build/outputs/apk/debug/app-debug.apk`.

Releases published from CI are signed and minified (R8). To verify a
downloaded release APK's signature yourself:

```
apksigner verify --print-certs map-roulette-<version>.apk
```

## Self-hosting the server

The app can sync to your own server (accounts, trips, fog of war, friends) and
route against your own GraphHopper instance. One script installs either or both
— on a Proxmox host it builds an LXC for you, anywhere else it installs in place.

```
bash server/install.sh
```

Accounts are managed from a web dashboard at `/admin` on the sync hostname:
hand out single-use invite codes, mail password resets, revoke sessions, remove
people. It shows account metadata and row counts only — no admin can read
anyone's rides.

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

## Attribution

Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright)
contributors, [ODbL](https://opendatacommons.org/licenses/odbl/). Spin
destinations, speed cameras and coverage are all derived from OpenStreetMap
via the Overpass API. Map tiles by [OpenFreeMap](https://openfreemap.org/).
Geocoding by [Photon](https://photon.komoot.io) (komoot) when the public
fallback is used.

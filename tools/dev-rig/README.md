# Local routing + road-data rig

One command that gives an Android emulator everything spin and in-app
navigation need, on your own machine: a router, an API with road, POI and
speed-limit data, and the debug build pointed at both (#612).

Without it the emulator has no routing server, so in-app navigation never
starts, and spin of kind *Road* has no road data to pick from (#535).

```sh
tools/dev-rig/up.sh emulator-5556     # bring it up and point that emulator at it
tools/dev-rig/down.sh                 # stop it; graph and database are kept
tools/dev-rig/down.sh --purge         # stop it and delete everything it downloaded or built
```

`up.sh` with no serial (and no `ANDROID_SERIAL`) touches no device; run
`tools/dev-rig/point-emulator.sh <serial>` later for that half.

## What `up.sh` does

| Step | What | Cold | Warm |
| --- | --- | --- | --- |
| 1 | Downloads the extract (Luxembourg by default) into `docker/dev/data/rig/osm/`, md5-checked against Geofabrik | ~45 MB | skipped |
| 2 | Writes `docker/dev/data/rig/graphhopper-config.yml`: `docker/dev/config/graphhopper/config.yml` with only `datareader.file` changed, and fails if `toll`, `surface` or `road_environment` are missing from its encoded values | instant | instant |
| 3 | Builds the API image from this checkout's `backend/`, starts `db` and `api` and waits for them healthy (the API migrates on boot), then starts GraphHopper | a few min (image build) | seconds |
| 4 | Runs `tools/roads-importer`, `tools/poi-importer` and `tools/speedlimit-importer` in a throwaway `python:3.12-bookworm` container — no pyosmium on the host (the `-slim` image lacks the libexpat pyosmium links) | under a minute | skipped while the JSON is newer than the extract |
| 5 | `import-roads`, `import-pois`, `import-speedlimits` via `docker compose exec` into the rig's own `api` container — Luxembourg is 35,791 roads, 3,746 POIs, 29,956 speed-limit ways | under a minute | skipped while the same file was loaded into the same database volume |
| 6 | Waits for the graph, then smoke-tests: a `car` and a `moto` route, a custom model reading `toll`, and `/api/roads` returning ways around the extract's centre | ~25 s graph build, ~1.3 GB heap | seconds |
| 7 | Prints the URLs, and with a serial runs `point-emulator.sh` | | |

Measured on 2026-10-09: a cold run is about five minutes, most of it the API
image build and the importers; a warm re-run is 14-24 s.

It runs as its own compose project, `detour-rig`, on `docker/dev/docker-compose.yml`
plus `compose.rig.yml`. That reuses the dev stack's service definitions, so
GraphHopper's image, profiles and encoded values are always the repo's, while
the rig's volumes stay separate from a `detour-dev` stack's — a Belgium graph
built there is never replaced by this small one.

It uses the canonical dev ports — API **7500**, GraphHopper **7510**, Postgres
**7532** — so it cannot run at the same time as a `detour-dev` GraphHopper or an
API started from your IDE. `up.sh` checks and says which one is in the way.
Keycloak, Redis, Traefik and Grafana are not started: spin and navigation are
anonymous.

## What `point-emulator.sh` writes

Into the **debug** package's `shared_prefs/routing_server.xml`, through
`run-as`, the same keys Settings → Servers & sync would save:

| Key | Value |
| --- | --- |
| `url`, `api_url` | `http://10.0.2.2:7500` — roads, POIs and speed limits resolve to it |
| `routing_url` | `http://10.0.2.2:7510` |
| `geocoder_url` | `https://photon.komoot.io`, **only if it was blank** — otherwise search would fall back to `url`, which is the API and does not serve Photon |
| `saved` | `true` |

`10.0.2.2` is the emulator's name for the host's loopback, where the rig's
ports are bound. Every other key in the file is kept; the ones a server change
invalidates (discovered issuer, server features, announced service bases) are
dropped, the same as `RoutingServer.save` does. The app is force-stopped first
so it cannot overwrite the file from memory. Nothing else on the device is
touched — no `pm clear`, no uninstall, nothing under `files/` (see the
`detour-adb` skill for why). It refuses a physical phone unless you set
`RIG_API_URL`/`RIG_ROUTING_URL` yourself, e.g. to `http://localhost:7500` after
`adb reverse tcp:7500 tcp:7500`.

It also does not pick a device for you, even when only one is attached: it
force-stops the app, and the emulator that happens to be up may be somebody
else's test run.

## Putting the emulator inside the extract

Routing only answers inside the extract. A request outside it comes back as
"Cannot find point", which in the app looks exactly like no router at all. The
emulator starts in Mountain View, so move it first — `up.sh` prints the
extract's centre:

```sh
adb -s emulator-5556 emu geo fix 6.13 49.61     # longitude first
```

For anything that has to move (a trip that records, arrival, reroute) use a
replayed route rather than `geo fix` steps — see the `detour-gps-replay` skill.
A replay with the navigation map on screen crashed the app within seconds here
on MapLibre 11.8.0 (#301); 11.8.8 fixed that for the idle map, but the
navigation map has not been re-measured on this rig. If it still crashes,
start navigation, then move the app to Settings before starting the replay —

```sh
adb -s emulator-5556 shell am start -n io.github.maxke24.detour.debug/com.jellemax.detour.MainActivity --ez open_update_settings true
.claude/skills/detour-gps-replay/scripts/start-port-replay.sh route.txt emulator-5556 1000 5
```

— and the trip records with `startedBy: NAVIGATION` and the navigation
destination on it. A route file for that can come straight from the rig:
`curl 'http://127.0.0.1:7510/route?point=LAT,LON&point=LAT,LON&profile=car&points_encoded=false'`,
resampled to one point per second (lon first) with a standstill tail so
auto-stop fires.

## What reads what

With a routing server set, spin of kind *Road* snaps its random point through
GraphHopper (`RoutingClient.randomRoadDestination`); `/api/roads` is the
fallback when that fails. The imported roads are still read on the device, by
the road-type mix on a recorded trip ("mostly main roads"), and the speed-limit
ways by the posted-limit sign, so both tables are exercised by a replayed drive.

Speed cameras are not imported (`tools/camera-importer` is not part of the rig),
so `/api/cameras` answers an empty list and the map shows *Couldn't load speed
cameras*.

## Another region

```sh
RIG_EXTRACT_URL=https://download.geofabrik.de/europe/belgium-latest.osm.pbf RIG_GH_HEAP=8g tools/dev-rig/up.sh
```

Changing the extract (or the GraphHopper config) drops the rig's graph volume
and rebuilds it; `up.sh` keys the graph on the md5 of all three files. Belgium is
~660 MB and wants a much bigger heap and a much longer first build than
Luxembourg's — budget as `docker/dev/README.md` describes.

The extract is not refreshed on its own: Geofabrik republishes daily, and a new
extract under an existing graph means a rebuild. Delete
`docker/dev/data/rig/osm/region.osm.pbf` to fetch a fresh one.

## Logs

```sh
docker compose -p detour-rig -f docker/dev/docker-compose.yml -f tools/dev-rig/compose.rig.yml logs --since 5m --tail 100 graphhopper
```

That needs `RIG_REPO` and `RIG_DATA` exported (the overlay's paths are
absolute); `docker logs --tail 100 detour-rig-graphhopper-1` does not.

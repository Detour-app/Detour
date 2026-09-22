# OSM drivable-road importer

`osm_import.py` reads a Geofabrik `.pbf` extract and writes a canonical JSON
file of every drivable way — the shape `RoadImport.cs` (backend import hook)
loads into the `roads` table. Serves issue #380 (phase 3 of #302, the
road-type mix) and issue #382 (phase 5, spin's random-road feature) — same
OSM-extract-off-Overpass approach as `tools/speedlimit-importer/` (#379),
applied to every drivable way instead of only the ones with a posted
`maxspeed`.

## Usage

```sh
pip install -r requirements.txt
python3 osm_import.py <region.osm.pbf> --region <name> --out <out.json>
```

Run it against each region the self-host stack serves (see
`docker/prod/config/regions.env`), using the same `.pbf` extract
`fetch-extract.sh` already downloads for GraphHopper.

Self-check (downloads the Luxembourg extract, the smallest Geofabrik region):

```sh
python3 osm_import.py --demo
```

Unit test against a tiny synthetic `.pbf` built in-process:

```sh
python3 test_osm_import.py
```

## How it works

One pass, like `tools/speedlimit-importer/osm_import.py`: a way carries its
own tags and, with `locations=True`, its own node coordinates inline as the
file streams past. Every drivable way (`RoadRoulette.DRIVABLE_HIGHWAYS`'s
mirror, see `osm_import.py`) is kept whole, geometry and all — unlike the
speed-limit importer, a `maxspeed` tag is not required, since the road-type
mix and spin need the untagged residential streets that importer drops.

## Output shape

```jsonc
{
  "source": "osm",
  "region": "luxembourg",
  "generatedAt": "2026-09-16T12:00:00+00:00",
  "ways": [
    { "sourceId": "w123", "highway": "primary", "polyline": [[49.6, 6.1], [49.61, 6.11]] }
  ]
}
```

`highway` is the raw OSM tag value, not the client's coarser three-bucket
`HighwayClass` — that mapping, and any per-travel-mode regex filtering, is a
client-side judgement call this importer stays out of. `polyline` is the
way's own node order — at least two points, since a single-node way cannot
carry geometry to snap a fix against.

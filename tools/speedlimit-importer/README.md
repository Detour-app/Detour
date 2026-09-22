# OSM speed-limit importer

`osm_import.py` reads a Geofabrik `.pbf` extract and writes a canonical JSON
file of drivable ways with a posted `maxspeed` — the shape
`SpeedLimitImport.cs` (backend import hook) loads into the
`speed_limit_ways` table. Serves issue #379 (phase 3 of #302, the ambient
speed-limit sign) — same OSM-extract-off-Overpass approach as
`tools/camera-importer/` (#303), applied to a different tag.

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

One pass, unlike `tools/camera-importer/osm_import.py`'s four: a way carries
its own tags and, with `locations=True`, its own node coordinates inline as
the file streams past. There are no relations to resolve device nodes
against here, so no want-list pass is needed — every drivable way
(`RoadRoulette.DRIVABLE_HIGHWAYS`'s mirror, see `osm_import.py`) with a
parseable `maxspeed` is kept whole, geometry and all.

## Output shape

```jsonc
{
  "source": "osm",
  "region": "luxembourg",
  "generatedAt": "2026-09-16T12:00:00+00:00",
  "ways": [
    { "sourceId": "w123", "maxSpeedKmh": 70, "polyline": [[49.6, 6.1], [49.61, 6.11]] }
  ]
}
```

`polyline` is the way's own node order — at least two points, since a
single-node way cannot carry geometry to snap a fix against.

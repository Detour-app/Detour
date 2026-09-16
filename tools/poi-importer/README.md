# OSM point-of-interest importer

`osm_import.py` reads a Geofabrik `.pbf` extract and writes a canonical JSON
file of viewpoint/food/sight points of interest — the shape `PoiImport.cs`
(backend import hook) loads into the `pois` table. Serves issue #383 (phase 5
of #302, spin's random-POI feature) — same OSM-extract-off-Overpass approach
as `tools/roads-importer/` (#380/#382) and `tools/speedlimit-importer/`
(#379), applied to points instead of ways.

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

One pass, like `tools/roads-importer/osm_import.py`: a node or way carries
its own tags and, with `locations=True`, its own node coordinates inline as
the file streams past. Three kinds are recognised (mirrors `PoiKind` in
`shared/`'s `PoiRoulette.kt`):

- `viewpoint` — `tourism=viewpoint`
- `food` — `amenity` in `cafe`/`restaurant`/`pub`/`bar`/`ice_cream`
- `sight` — `historic` in `castle`/`ruins`/`monument`/`fort`/`memorial`, or `tourism=attraction`

A node's own location is its point; a way's point is the plain average of
its own nodes' locations — a centroid, not a true polygon centroid, which is
good enough for "where do I point the map" and keeps this importer a single
pass. No relations: `historic`/`tourism` multipolygons exist but are a small
minority of matches, left as a follow-up rather than a second pass.

## Output shape

```jsonc
{
  "source": "osm",
  "region": "luxembourg",
  "generatedAt": "2026-09-16T12:00:00+00:00",
  "pois": [
    { "sourceId": "n123", "kind": "viewpoint", "name": "Belvedere", "lat": 49.6, "lon": 6.1 }
  ]
}
```

`name` is blank when the OSM element carries no `name` tag — plenty of small
cafes don't. `PoiRoulette.randomPoi` (`shared/`) falls back to a
kind-specific label for those, the same rule it already applies to an
Overpass element with no name.

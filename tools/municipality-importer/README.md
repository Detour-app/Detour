# OSM municipality-boundary importer

`osm_import.py` reads a Geofabrik `.pbf` extract and writes a canonical JSON
file of every `admin_level=8` administrative boundary — the shape
`MunicipalityImport.cs` (backend import hook) loads into the
`municipality_boundaries` table. Serves issue #381 (phase 4 of #302), moving
`MunicipalityStore.fetch`'s live Overpass relation query off the public API,
same OSM-extract-off-Overpass approach as `tools/speedlimit-importer/` (#379)
and `tools/roads-importer/` (#380/#382).

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

Two passes, via pyosmium's `FileProcessor(...).with_areas()`: unlike a plain
way, an administrative boundary is usually a *relation* of member ways, which
needs the whole file read once to collect relation candidates before their
member ways' geometry can be assembled into rings on the second pass.

Only relation-based boundaries are kept (`area.from_way() is False`) —
a plain closed way carrying `boundary=administrative`/`admin_level=8`
directly is a shape `MunicipalityStore.fetch`'s Overpass query
(`relation(pivot.a)[...]`) never returned, and keeping it would need a
second id namespace to avoid colliding with a relation's numeric id in
`MunicipalityBoundaryDto.Id`.

Inner rings (enclaves — a neighbouring town wholly surrounded by this one)
are kept alongside the outer ring or rings: the client's even-odd ray cast
(`Municipality.contains`, `shared/`) and the backend's own
(`MunicipalityBoundary.Contains`) both subtract them for free.

## Output shape

```jsonc
{
  "source": "osm",
  "region": "luxembourg",
  "generatedAt": "2026-09-16T12:00:00+00:00",
  "boundaries": [
    {
      "sourceId": "r1234",
      "name": "Esch-sur-Alzette",
      "rings": [[[49.6, 6.1], [49.61, 6.11], [49.60, 6.12]]]
    }
  ]
}
```

`sourceId` is `r` + the OSM relation id, matching
`MunicipalityStore.fetch`'s own `el.optLong("id")` read of the Overpass
relation — the numeric part is what the client keeps as `Municipality.id`,
so a boundary already cached from an earlier Overpass lookup and the same
one served by this importer compare equal rather than duplicating. Each ring
is closed implicitly (first point is not repeated as the last).

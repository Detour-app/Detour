# OSM camera importer

`osm_import.py` reads a Geofabrik `.pbf` extract and writes a canonical JSON
file of speed cameras, red-light cameras and average-speed enforcement
sections — the shape `CameraImport.cs` (backend import hook) loads into the
`cameras` table.

## Usage

```sh
pip install -r requirements.txt
python3 osm_import.py <region.osm.pbf> --region <name> --out <out.json>
```

Run it against each region the self-host stack serves — `benelux`, `france`,
`germany` (see `docker/prod/config/regions.env`) — using the same `.pbf`
extract `fetch-extract.sh` already downloads for GraphHopper, so there's no
second download or live-query round trip.

Self-check (downloads the Luxembourg extract, the smallest Geofabrik region,
and sanity-checks real counts — pyosmium has no easy way to fake a `.pbf` in
a few lines, so this exercises the real reader):

```sh
python3 osm_import.py --demo
```

## Why pyosmium, not Overpass

`tools/belgium-enforcement/build_dataset.py` does the same node/relation
folding via a live Overpass query — fine for one small country, but doesn't
scale to `benelux`/`france`/`germany`: Overpass queries that size risk timing
out, and a live query is a separate network dependency you couldn't run
against a saved extract. `osm_import.py` reads the same `.pbf` extract the
GraphHopper routing container already fetches, streamed locally with
`pyosmium` — no timeout risk, no extra download, and it reuses one file for
both jobs.

`build_dataset.py` is left in place — nothing else references it yet — but
this importer supersedes it for the backend pipeline.

## How it works

Same folding rules as `build_dataset.py` (device-role fallback, same-camera
clustering at 12 m, section end-clustering at 120 m, 200 m minimum span) —
ported from Overpass JSON onto `pyosmium`'s streaming reader. A `.pbf`
way/relation references node *ids*, not inline coordinates, so this runs two
passes over the file:

1. `NodeLocationPass` — indexes every node's `(lat, lon)` and picks out
   `highway=speed_camera` nodes as candidate `FixedSpeed` cameras.
2. `RelationPass` — resolves `enforcement=maxspeed|traffic_signals` (point
   cameras, upgrading a coincident node to `SpeedAndRedLight` when both
   encodings hit the same physical camera) and `enforcement=average_speed`
   (sections: furthest-apart member points become the ends, anything within
   120 m of an end joins that end's cluster) against pass 1's locations.

## Output shape

```jsonc
{
  "source": "osm",
  "region": "luxembourg",
  "generatedAt": "2026-09-14T12:00:00+00:00",
  "cameras": [
    { "sourceId": "n123", "kind": "FixedSpeed", "lat": 49.6, "lon": 6.1,
      "polyline": null, "maxSpeedKmh": 50, "roadRef": "N1" },
    { "sourceId": "r456", "kind": "Section", "lat": null, "lon": null,
      "polyline": [[49.6, 6.1], [49.61, 6.11]], "maxSpeedKmh": 70, "roadRef": null }
  ]
}
```

`kind` is one of `FixedSpeed`, `RedLight`, `SpeedAndRedLight`, `Section` —
these match `CameraKind`'s C# enum member names exactly (case-sensitive
`System.Text.Json` binding), not the OSM tag spellings.

# LUFOP Camera Importer

A pure-Python GPX-based importer for speed-camera data from LUFOP (Ligue Unifiée pour la Prévention) exportsmade available via lufop.net.

## Why a separate parser?

LUFOP's "OsmAnd" export format is plain GPX 1.1 (`<gpx><wpt lat lon><name>...`), not OSM format. Unlike the OSM importer (which uses `pyosmium` to parse XML with structured tags), LUFOP encodes all camera type and speed limit information as free-text French names with a fixed vocabulary. This tool parses that vocabulary without external dependencies.

## Name Vocabulary

LUFOP's confirmed vocabulary across Belgium, Netherlands, Luxembourg, France, and Germany (verified 2026-09-15):

| Name Pattern | Kind | maxSpeedKmh |
|---|---|---|
| `Radar Fixe <CC> <speed>` | FixedSpeed | `<speed>` (parsed) |
| `Radar Fixe <CC> Passage Niveau` | FixedSpeed | null (level crossing) |
| `Radar Fixe <CC> Covoiturage` | FixedSpeed | null (carpool lane) |
| `Radar Poid lourd <CC>` | FixedSpeed | null (HGV-specific) |
| `Radar Tunnel <CC>` | FixedSpeed | null |
| `Radar Feu Rouge <CC>` | RedLight | null |
| `Radar Chantier <CC>` | MobileHotspot | null (roadworks/temporary) |

where `<CC>` is a country code (`LU`, `FR`, `BE`, etc.).

### Intentionally Skipped

- `Radar Troncon Debut/Fin <CC>`: Section cameras spanning a road segment. Skipped because LUFOP provides no ID linking a `Debut` to its `Fin`, and they are not adjacent in the file (all `Debut` entries, then all `Fin` entries). Pairing them would require an unverifiable distance or road-matching heuristic. **LUFOP's value is point-camera density, not section coverage** — OSM's relation-based sections (via `osm_import.py`) already cover trajectcontrole zones with real geometry.

## Usage

```bash
python3 lufop_import.py <country.osm> --region <region-name> --out <output.json>
python3 lufop_import.py --demo  # self-check against an inline fixture
```

### Example

```bash
python3 lufop_import.py LUXEMBOURG.osm --region luxembourg --out cameras.json
```

Output is JSON with the canonical shape (matches `osm_import.py`'s output):

```json
{
  "source": "lufop",
  "region": "luxembourg",
  "generatedAt": "2026-09-15T08:34:51+00:00",
  "cameras": [
    {
      "sourceId": "49.599791_6.119708",
      "lat": 49.599791,
      "lon": 6.119708,
      "polyline": null,
      "maxSpeedKmh": null,
      "kind": "RedLight",
      "roadRef": null
    }
  ]
}
```

## Getting Fresh Data

LUFOP exports are available at **https://lufop.net** via the "OsmAnd" download button on each country's radar list. The site is protected by Cloudflare bot-check, so **exports must be downloaded manually** — they cannot be fetched via automated scripts or this tool itself.

Download files are named `<COUNTRY>.osm` (despite the `.osm` extension, they are GPX files, not OpenStreetMap format).

## Licensing

LUFOP data is licensed under **ODbL 1.0** (Open Data Commons Open Database License), with the same share-alike obligations as OSM data. In-app UI attribution is tracked separately (issue #371).

## Testing

Run the inline self-check:

```bash
python3 lufop_import.py --demo
```

Expected output:
```
warning: 1 unrecognised waypoint name(s), skipped:
  'Something Unexpected'
OK: 4 cameras, kinds={'MobileHotspot', 'FixedSpeed', 'RedLight'}
```

# Attribution and licence for the `public-*` route files

The three `public-*.txt` files in this directory are **derived from OpenStreetMap**.
The other three (`trajectcontrole.txt`, `urban-limits.txt`, `stop-start.txt`) are
the maintainer's own recorded drives and are not covered by this notice.

## The notice

> Contains information from OpenStreetMap, which is made available under the
> [Open Database License (ODbL) 1.0](https://opendatacommons.org/licenses/odbl/1-0/).
> © OpenStreetMap contributors. Individual contents are additionally covered by the
> [Database Contents License (DbCL) 1.0](https://opendatacommons.org/licenses/dbcl/1-0/).

A route file is a filtered, resampled, coordinate-only extract of OSM data. Under ODbL
that is most naturally a **Derivative Database** rather than a Produced Work — a list of
coordinates is still a database — so **assume share-alike applies**: anyone
redistributing these files must keep *these files* under ODbL. That is an obligation on
the data, not on the software.

The licence chain is the OSMF
[Contributor Terms](https://osmfoundation.org/wiki/Licence/Contributor_Terms) §3, under
which an uploader grants OSMF the right to distribute their submission under "ODbL 1.0
for the database and DbCL 1.0 for the individual contents". Be honest about one gap:
none of the OSMF documents names GPS traces explicitly, and the
[Licence and Legal FAQ](https://osmfoundation.org/wiki/Licence/Licence_and_Legal_FAQ)
has no section on them. ODbL 1.0 + DbCL 1.0 is the correct working assumption for a GPX
uploaded through an OSM account, but it is an inference from the Terms' broad wording
("data and/or any other content"), not a quotation of a trace-specific clause.

## Sources, per file

Retrieved **2026-08-13**. Record the date as well as the ID: an uploader can delete a
trace, and then the committed file survives while the provenance link rots.

| File | Source | URL | Uploader | Notes |
|---|---|---|---|---|
| `public-trajectcontrole.txt` | **Synthetic.** OSRM route over OSM road geometry, from a point on the E40 **eastbound** carriageway 4.72 km west of the west `device` node **through** that node and on **to** the east `device` node of OSM relation **15682532** ("Trajectcontrole E40", `description=Zaventem - Bertem`), densified to one point per second. | [relation 15682532](https://www.openstreetmap.org/relation/15682532) · [node 6763749685](https://www.openstreetmap.org/node/6763749685) (west) · [node 10784337380](https://www.openstreetmap.org/node/10784337380) (east) · [OSRM demo server](https://router.project-osrm.org/) | n/a | No personal data of anyone. Still ODbL — see below. Request and lead-in rationale below the table. |
| `public-stop-start.txt` | **Real public OSM GPS trace 1741287** (2014-04-16, Leuven), resampled to 1 Hz. | [trace 1741287](https://www.openstreetmap.org/trace/1741287) · raw GPX at `https://www.openstreetmap.org/trace/1741287/data` | `-ad-` | Converted with `--stop-span 0 --interval-ms 1000`; the default `--max-kmh 200` dropped 2 samples, worst 341.2 km/h. |
| `public-trajectcontrole-reverse.txt` | **Real public OSM GPS trace 8820623** (2023, 10 Hz openpilot device log), resampled to 1 Hz. | [trace 8820623](https://www.openstreetmap.org/trace/8820623) · raw GPX at `https://www.openstreetmap.org/trace/8820623/data` | `sunnypilot` | Converted with `--stop-span 0 --interval-ms 1000`; 1253 repeated-timestamp samples and 118 outliers dropped. |

Re-fetching a source is one command and needs no credentials — but note **which**
endpoint: `https://www.openstreetmap.org/trace/<id>/data` serves a public trace
anonymously (follow the 301), while `https://api.openstreetmap.org/api/0.6/gpx/<id>/data`
answers **401** without OAuth.

### The exact OSRM request behind `public-trajectcontrole.txt`

Recorded because it is **not** the request in
[`17-public-trace-datasets.md`](../../../docs/refactor/mapscreen/17-public-trace-datasets.md)
§7.1 step 2 any more. That note routed device node → device node, which put the entry
gate at line 0 and left no time for the section prefetch; see
[`README.md`](README.md#the-lead-in-and-why-the-file-is-1269-km-rather-than-797-km).

```sh
curl -s "https://router.project-osrm.org/route/v1/driving/4.428603,50.858668;4.60503,50.86183?overview=full&geometries=geojson"
#  -> Ok, 12733.5 m, OSRM duration 484.3 s, 80 shape points
```

Then densify at `100 / 3.6 = 27.7778` m per 1000 ms line, carrying the remainder across
shape segments — unchanged from §7.1 step 3, so the implied 100.0 km/h stays a stated
input rather than an artefact.

Two things about the start coordinate are worth writing down, because both cost an
iteration to find:

- **It is on the eastbound carriageway, deliberately.** The west `device` node sits on
  the eastbound side. A start point taken from the westbound carriageway routes to the
  east node via an interchange U-turn (13 487 m instead of 12 734 m), and a point 24 m
  south of it snapped onto a service road (18 564 m). The committed request uses
  (50.858668, 4.428603), which OSRM snaps to (50.858656, 4.428613) — about 2 m — and
  yields 12 733.5 m, i.e. exactly the 4.72 km lead-in plus the 8.01 km node-to-node
  route. **A total distance materially above 12.74 km means the start snapped to the
  wrong carriageway**; that check is cheaper than reading the geometry.
- **The west node is now a pass-through, not an endpoint.** Its gate clearance is
  therefore *measured* (10.6 m at line 170), where in the first revision it was 0 m by
  construction. Only the east gate is still an endpoint (2.9 m at line 457).

**Never commit a raw GPX.** Keep it in the scratchpad. That rule already exists here for
the maintainer's own exports and applies just as much to somebody else's: the raw file
carries per-point timestamps for a named third party, and the committed route carries
neither timestamps nor anything outside the window under test.

## The synthetic file carries the same obligation

Worth stating plainly, because it is easy to assume otherwise: an OSRM route is computed
from OSM road geometry, so a densified copy of it is **equally ODbL-derived**. Generating
a route synthetically buys privacy, determinism and regenerability. It does **not** buy
licence freedom. There is no licence-clean option here short of surveying the roads
yourself — which is exactly what the maintainer's three personal drives are.

## Why none of this reaches the shipped APK

`tools/mocklocation/` is a **standalone Gradle build**, not a module of the root build.
Its own `settings.gradle.kts` says why — "this is a test harness, not part of Detour, and
nothing in the app should ever depend on it" — and
`.claude/skills/detour-gps-replay/scripts/check-preconditions.sh` asserts it, so a change
that quietly made it a module would fail that check.

Consequence: these files are never compiled into, packaged with, or shipped alongside the
app. The APK is built at `tools/mocklocation/build/outputs/apk/debug/`, separately, and
Detour's own APK contains none of this. **An ODbL fixture in a test harness is not an ODbL
obligation on the product.** The repository's [`LICENSE`](../../../LICENSE) (MIT) governs
the software and is untouched by this notice; ODbL governs these route files and imposes
share-alike on them alone. The two licences cover different files, which is the normal
arrangement for OSM-derived assets.

## The ethical footnote

Two of these files are somebody else's driving. Retiring the maintainer's drives by
adopting public traces trades the maintainer's privacy for a third party's, and "it was
already public" is a licence argument, not an ethics one. Two mitigations are applied and
should stay applied when adding more:

- **Prefer bulk/automated uploader accounts.** `sunnypilot` is a project's fleet of device
  logs, not one person's commute diary — same data quality, far less about any individual.
  That is why `public-trajectcontrole-reverse.txt` uses trace 8820623 rather than the
  equally suitable trace 1992572, which is one identifiable person's day.
  **`public-stop-start.txt` does not get that mitigation**, and this should not be glossed
  over: `-ad-` is an individual, and the file publishes 50 minutes of their 2014 driving
  around Leuven at 1 s resolution, including where they stopped and for how long. No
  bulk-uploader trace with real standstills was found — the only automated-fleet traces
  located were motorway transits with no stops at all. If one turns up, swap it in.
- **Clip to the window under test.** The same reasoning as `gpx2route.py --trim`, applied
  to a stranger. `public-stop-start.txt` does **not** yet do this and is the full 50-minute
  trace; see the "not clipped" note in [`README.md`](README.md).

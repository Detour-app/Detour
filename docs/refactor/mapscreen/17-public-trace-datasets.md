# 17 — Public GPS trace data as a replacement for the maintainer's own drives

Research note, 2026-08-13. **No code or fixture changed by this document.** It answers one
question: can the three canonical replay routes in
[`tools/mocklocation/routes/`](../../../tools/mocklocation/routes/) stop being derived from the
maintainer's personal drives?

Today they are. `routes/README.md` is explicit: "These are real drives of the maintainer's",
mitigated with `gpx2route.py --trim 1000` so no endpoint is a home or a workplace. That trim
removes the endpoints and nothing else — the remaining 27.28 / 23.49 / 9.70 km still publish
where the maintainer actually drives, at 1 s resolution.

**Answer: yes for all three routes that exist.** Public data covers every scenario the current
fixtures cover, and the fourth scenario (reroute) is blocked by missing infrastructure rather
than by missing data, so no dataset fixes it. Details, verification and the exact recipes below.

---

## 0. Correction to the premise: this repo is MIT, not GPLv3

[`LICENSE`](../../../LICENSE) is the **MIT License**, "Copyright (c) 2026 Jelle Max". The brief
for this investigation assumed "a GPLv3-ish app repo". That changes the licence analysis in one
direction that matters:

MIT is a **software** licence and imposes no share-alike on anything. So there is no
GPL-style compatibility question at all. The real question is the reverse one — whether a
share-alike **data** licence (ODbL, CC-BY-SA) can live inside an MIT repo. It can, because the
two govern different files, and that is the normal arrangement for OSM-derived assets: the code
stays MIT, the data directory carries its own notice. What it costs is a downstream obligation:
anyone redistributing the route file must keep *the route file* under its data licence. See
§4.

One fact makes this cheap here: `tools/mocklocation/` is a standalone Gradle build and
explicitly **not** a module of the root build (`settings.gradle.kts`: "this is a test harness,
not part of Detour, and nothing in the app should ever depend on it"). The route files are never
compiled into the shipped APK. An ODbL-licensed fixture in a test harness is not an ODbL
obligation on the product.

---

## 1. What a candidate has to survive

From [`.claude/skills/detour-gps-replay/SKILL.md`](../../../.claude/skills/detour-gps-replay/SKILL.md)
and `routes/README.md`, restated as pass/fail criteria, because most candidates die on the first
two:

| # | Criterion | Why |
|---|---|---|
| A | **Per-point timestamps** | `MockService.kt:87` derives speed as `distance / (intervalMs/1000)`. There is no speed column. A route file is a *timeline*, so the source must be resampled against its own timestamps, and a source without them cannot be resampled. |
| B | **Sampling ≈ every few seconds or denser** | The target is one line per 1000 ms. Interpolating a 15 s gap invents 14 s of driving; interpolating a 177 s gap invents a route. |
| C | **Licence permitting a derived file to be committed here** | §4. |
| D | **Geography containing `type=enforcement` + `enforcement=average_speed` relations** | Requirement 1 is the hard one. Belgium/Netherlands has these; Beijing, Porto and California do not. |
| E | **Vehicle, not pedestrian or cyclist** | The auto-start gate is 3 fixes ≥ 7.0 m/s sustained ≥ 8 s and ≥ 120 m (`TripTrackingService.kt:141-147`). A cycling trace never arms it. This one bit several candidates below. |

And the four scenarios to cover:

1. **Average-speed section, gantry to gantry** — within 60 m of one `device` node then the
   other, of the same relation, in the direction the measurement runs.
2. **Urban stop-start** — several genuine full standstills mid-drive.
3. **Posted-limit variety** — several different `maxspeed` values, cross streets, cameras near.
4. **Deviation from an obvious route** — for reroute.

---

## 2. Candidate table

Verified figures are marked ✓ and were measured during this investigation (commands in §7).
Unmarked figures are from the cited source.

| Candidate | Licence (as found) | Sampling | Timestamps | Geography | Req 1 | Req 2 | Req 3 | Req 4 | Committable here? |
|---|---|---|---|---|---|---|---|---|---|
| **OSM public GPS traces, live API** (`/api/0.6/trackpoints`, `/trace/<id>/data`) | ODbL 1.0 + DbCL 1.0 via Contributor Terms §3 | ✓ median-of-median **1.0 s**; 26/45 segs ≤3 s; best sources **10 Hz** | ✓ **100 %** (20 000/20 000 pts) | Worldwide; ✓ **170** avg-speed relations in the BE/NL box | ✓ **yes, verified** | ✓ yes | ✓ yes | no | **Yes**, with ODbL notice + attribution |
| **Synthetic: OSRM/GraphHopper route over OSM geometry, densified** | ODbL 1.0 (OSM-derived geometry) | You choose it (1 s exactly) | n/a — you construct the timeline | Anywhere OSM covers | ✓ **yes, verified, exactly** | fabricated only | ✓ yes | ✓ in principle | **Yes**, same notice |
| **OSM planet GPX dump** (`planet.openstreetmap.org/gps/`) | ✓ page header: "OpenStreetMap and contributors, **CC-BY-SA**" → CC-BY-SA 2.0 | n/a | ✓ **no** for the cheap files | Worldwide | no | no | no | no | Moot — **disqualified**, see §3.2 |
| **GeoLife** (MSR Asia) | Microsoft Research Licence Agreement (MSR-LA), non-commercial research | 1–5 s for ~91 % of trajectories | yes | Beijing | **no** — no such enforcement | maybe | weak | no | **No** — MSR-LA does not permit redistribution |
| **Porto taxi** (ECML/PKDD 2015 / UCI #339) | UCI dataset terms / CC BY 4.0 on the UCI page | **15 s** — fails B | implicit only (trip start + 15 s spacing) | Porto | no | no | no | no | Licence fine, **data unusable** |
| **T-Drive** (MSR) | MSR-LA | **177 s average** — fails B badly | yes | Beijing | no | no | no | no | **No** |
| **SF Cabspotting** (CRAWDAD / IEEE DataPort) | CRAWDAD terms — registration, no redistribution | **~60 s** — fails B | yes | San Francisco | no | no | no | no | **No** |
| **Mapillary sequences** | CC BY-SA 4.0 for imagery; derived data permitted "provided it is ODbL" | ~1 image / 1–3 s or per few metres | yes (`captured_at`) | Worldwide incl. BE | possible | weak | maybe | no | Yes, but **strictly worse** than OSM traces — camera positions, often smoothed, needs an access token |
| **comma2k19** (comma.ai) | ✓ **MIT License** (`commaai/comma2k19/LICENSE`) | 20 Hz raw GNSS — best in class | yes | **California I-280 only** | **no** — no such enforcement in CA | freeway stop-and-go, no traffic lights | no | no | Licence is *ideal* (same as this repo), **geography is wrong** |

Two things this table is meant to make obvious:

- **Every research trajectory dataset fails, and mostly on sampling rate, not licence.** Porto
  at 15 s, SF at 60 s, T-Drive at 177 s. These datasets were built to answer "where did the taxi
  go", not "how fast was it going second by second". Detour needs the second question.
- **The one dataset with a perfect licence and perfect sampling — comma2k19, MIT, 20 Hz — is in
  the wrong country.** California has no average-speed enforcement, so it cannot touch
  requirement 1 no matter how good the data is. Listing it and rejecting it is the honest
  outcome; padding the list with more AV datasets (KITTI, Oxford RobotCar, nuScenes, Argoverse)
  would add nothing, since those are all CC BY-NC-SA or equivalent **non-commercial** licences
  and are disqualified by licence *and* geography together.

---

## 3. The OSM trace investigation, in detail

This was the most promising lead and it holds up. Everything in this section was measured, not
assumed.

### 3.1 The live API: timestamps survive, and the sampling is better than expected

`GET https://api.openstreetmap.org/api/0.6/trackpoints?bbox=<minlon,minlat,maxlon,maxlat>&page=N`
returns GPX 1.0, up to 5000 points per page.

Pulled over a bbox covering the full E40 section that `trajectcontrole.txt` already uses
(`4.4900,50.8560,4.6080,50.8730`), 4 pages:

```
page 0: 4 trk, 5000 points      TOTAL: 20000 points, 20000 with <time> (100.0%),
page 1: 4 trk, 5000 points             47 trk groups, 48 segments
page 2: 8 trk, 5000 points
page 3: 31 trk, 5000 points     median-of-median sampling interval: 1.0 s (p10 1.0s, p90 16.0s)
                                segments with median dt <= 3s: 26 / 45
                                segments with median dt <= 5s: 30 / 45
```

**Timestamps survive, but which ones you get depends on the uploader's visibility setting.** Per
[Visibility of GPS traces](https://wiki.openstreetmap.org/wiki/Visibility_of_GPS_traces):

| Visibility | In public trace list | Points via API | **Timestamps via API** | Linked to user |
|---|---|---|---|---|
| `identifiable` | yes | yes | **yes** | yes |
| `trackable` | no | yes | **yes** | no |
| `public` (deprecated Aug 2026) | yes | yes | **no** | no |
| `private` (deprecated Aug 2026) | no | yes | **no** | no |

So criterion A is satisfied by `identifiable` and `trackable` traces only. In practice this is
not a constraint: 100 % of the 20 000 points pulled above carried `<time>`. Note the API strips
`public`/`private` timestamps but the *raw file* of a `public` trace is still downloadable with
its timestamps intact — which is the path §5 uses anyway.

### 3.2 The bulk dump is dead, and this is a firm negative

`https://planet.openstreetmap.org/gps/` — the live directory listing, fetched:

```
planet.openstreetmap.org - gps
OpenStreetMap and contributors, CC-BY-SA      <- the licence, as stated on the page

gpx-planet-2013-04-09.tar.xz        2013-04-12 06:49   21G
simple-gps-points-120604.csv.xz     2012-08-22 16:28   15G
simple-gps-points-120312.txt.xz     2012-03-30 06:38  7.0G
```

Disqualified twice over:

- **Stale.** Newest file is 2013-04-09. The wiki confirms "The latest version is now from
  2013", and a 2024 community thread asking for a current dump got no solution.
- **The cheap files have no timestamps.** [Planet.gpx](https://wiki.openstreetmap.org/wiki/Planet.gpx)
  on the `simple-gps-points` files: *"The data consists of coordinate pairs only, with no track
  file or meta data."* That fails criterion A outright. Only the 21 GB tarball retains GPX
  structure, and downloading 21 GB of 13-year-old traces to find one Belgian motorway transit is
  absurd when the API answers the same question in four requests.

**Use the API, not the dump.** This reverses the brief's expectation that the dump was worth
investigating for bulk access.

### 3.3 Requirement 1: verified gantry-to-gantry transits exist

There are **170** `type=enforcement` + `enforcement=average_speed` relations in the box
`(50.0,2.5)–(51.6,6.5)` (Belgium, Netherlands, northern France), spans up to 13.73 km. Sample of
the longest:

| Relation | Name | Span | Devices |
|---|---|---|---|
| 15659457 | Trajectcontrole E40 | 13.73 km | 2 |
| 15686122 | Trajectcontrole E40 | 12.26 km | 2 |
| 16251379 | (unnamed, `maxspeed=120`) | 10.43 km | 2 |
| 15659102 | Trajectcontrole E19 | 9.14 km | 2 |
| 11165720/1 | Trajectcontrole N256 rechts/links | 8.52 km | 2 |
| 6997044/5 | Trajectcontrole N62 rechts/links | 7.88 km | 6 / 5 |
| **15682532** | **Trajectcontrole E40** ("Zaventem - Bertem") | **7.94 km** | **2** |

15682532 is the relation the current fixture transits, so it is the one to reproduce against.
Its device nodes are **west (50.86929, 4.49257)** and **east (50.86183, 4.60503)**. Verified
membership: `{('node','device'): 2, ('node','from'): 1}` — **no `way` members at all**, which
matters for §5: you cannot get the section's geometry from the relation, only its two endpoints.
The `from` role plus the description "Zaventem - Bertem" (Zaventem west, Bertem east) establish
the measured direction as **west → east**, matching `routes/README.md`.

Three public traces were downloaded and measured against the app's actual gate — closest
approach to **both** device nodes, and the order:

| Trace | Uploader | Rate | Whole trace | Section transit | Direction |
|---|---|---|---|---|---|
| **[1992572](https://www.openstreetmap.org/trace/1992572)** | `-ad-` | 1 Hz | 7556 pts, 199.3 km, 383.5 min | west 15 m @ 16:20:14 → east 17 m @ 16:23:53; **7.98 km in 218 s = 131.7 km/h** | **west → east** ✓ |
| [10731546](https://www.openstreetmap.org/trace/10731546) | `sunnypilot` | **10 Hz** | 6000 pts, 11.5 km, 10.0 min | east 18 m → west 17 m; **7.99 km in 455 s = 63.2 km/h** | east → west |
| [8820623](https://www.openstreetmap.org/trace/8820623) | `sunnypilot` | **10 Hz** | 6000 pts, 19.4 km, 10.0 min | east 15 m → west 23 m; **7.99 km in 241 s = 119.5 km/h** | east → west |

All three clear the 60 m gate at both gantries. Read the columns together, because they say
different things:

- **1992572 is the drop-in replacement** for the current fixture: same relation, same direction
  the measurement runs, 1 Hz native so resampling is near-lossless. Its transit average of
  **131.7 km/h** is *above* the posted limit, so it exercises the over-the-limit section average,
  where the current fixture's 75.4 km/h exercises the compliant one. That is a different test,
  not a worse one — but it is a change in what the fixture asserts, and the baseline numbers in
  `routes/README.md` would have to be re-derived, not copied.
- **The two `sunnypilot` traces run the opposite way**, so against relation 15682532 they hit the
  device nodes in reverse order. The app's heading test toward the far end should therefore
  *refuse* to arm — which makes them a **negative fixture the suite currently lacks**: a
  wrong-direction transit of a real section that must not trigger. `routes/README.md` documents
  entry-only and re-arm cases but no wrong-direction case.
- **`sunnypilot` is also the best-quality source found anywhere in this investigation**: 10 Hz,
  exactly 6000 points per 10-minute segment, sub-second timestamps
  (`2023-09-29T11:27:50.700000`). These are openpilot/comma device logs bulk-uploaded to OSM —
  effectively comma2k19-grade data, in the right country, already under OSM's terms. If a
  west→east `sunnypilot` segment over any Belgian section can be found (probe more pages, or
  other relations from the table above), it dominates every other option for requirement 1.

### 3.4 Requirement 2: real standstills exist in public traces

Probing a Leuven-centre bbox (`4.6850,50.8720,4.7150,50.8880`) for segments containing runs
below 0.6 m/s:

```
stops  rate/s   pts     km    min  durations(s)             url
   25    0.40  1003   6.12   41.8  [130, 97, 64, 46, 44, 42]  /user/-ad-/traces/1741287
   51    0.83  5000   4.58   99.8  [128, 118, 107, 85, 72, 47] /user/jcob1374/traces/2952816
    8    0.15   219   2.23   23.6  [73, 56, 46, 38, 30, 29]   /user/wegspotter/traces/4475350
```

Full download of the best candidate, **[trace 1741287](https://www.openstreetmap.org/trace/1741287)**
(`-ad-`, 2014-04-16):

```
1581 pts, 18.45 km, 50.1 min, mean 22.1 km/h
p50 26.8  p90 113.6  max 341.2 km/h;  consecutive samples >=7 m/s: 353
stops >=8 s: 26 -> [130, 97, 64, 46, 44, 42, 33, 30, 30, 29]
```

This one trace covers requirements 2 **and** 3 at once, and it is a car, not a bicycle: p90 of
113.6 km/h and a 353-sample run above 7.0 m/s, so it clears the auto-start gate with enormous
margin. 26 standstills against `stop-start.txt`'s four, including a 130 s and a 97 s wait — well
past anything the current fixture contains.

Two caveats, both actionable:

- **Sampling is 2.5 s median (0.40 pts/s), not 1 Hz.** Within criterion B, but it means the 1 Hz
  output is interpolated between real samples for most lines. Acceptable for stop behaviour,
  where the quantity under test is the duration of a held position, not the shape of the
  approach.
- **`max 341.2 km/h` is a GPS outlier spike, and `gpx2route.py` has no outlier filter.** This is
  the single concrete gap this investigation found in the existing tooling. The maintainer's own
  fixtures never needed one, because they were exported by *this app*, already gated at
  `MAX_START_ACCURACY_M = 25f` and decimated at 25 m. A third-party consumer-GPS log has not been
  through any of that. A 341 km/h spike replayed at 1 Hz is a 95 m jump between consecutive
  lines, which will corrupt `topSpeedMps` and can trip the 500 m segment break if two spikes
  compound. **Any adoption of public traces needs a speed-outlier clamp added to
  `gpx2route.py` first** — reject a sample implying more than, say, 200 km/h and interpolate
  across it.

`jcob1374`'s traces were rejected despite scoring well on stop count: 8.08 km at 14.2 km/h mean
and 0.80 km over 13.7 min in other segments make it a cyclist, so criterion E fails. The Brussels
inner-city probe (`4.3400,50.8400,4.3700,50.8600`) returned only `AmOosm` traces at ~1 Hz with
stops, but covering 0.06–2.70 km in 3–23 min — survey walking, not driving. **Filtering
candidates by p90 speed is essential**; stop count alone selects for pedestrians.

### 3.5 Requirement 3: posted-limit variety, verified

`maxspeed` values on highway ways in trace 1741287's Leuven core sub-bbox
(`50.8739,4.6888 → 50.9200,4.7400`):

```
{'30': 1148, '20': 341, '50': 312, '70': 53, '120': 37, '10': 19, '15': 4, '5': 1}
distinct: 8
```

Eight distinct posted limits including motorway 120 and the full urban ladder 50/30/20, in a
single trace's footprint. This exercises ambient-limit snapping and the three-miss clear
hysteresis at least as well as `urban-limits.txt`.

**Not verified: the speed-camera count.** The `highway=speed_camera` query over the trace's full
bbox 504'd repeatedly after Overpass began rate-limiting this session. The query to finish the
job is in §7; the method is `routes/README.md`'s own (count nodes within `WARN_METERS = 400.0` of
the driven line, and within `SpeedCameras.PREFETCH_RADIUS_M = 4000.0` for prefetch reach).
Treat requirement 3's camera half as **probable but unconfirmed**.

### 3.6 Requirement 4 is not a data problem and no dataset solves it

`routes/README.md` already settled this: *"Route (iii), `off-route.txt`, does not exist and is
not coming… Deviation is measured against a route the app computed, and in-app navigation
requires a reachable routing server (`ServerConfig.usable`)."*

No public dataset changes that. A trace of somebody taking a wrong turn is not a deviation
unless the app has a computed route to deviate *from*. **Requirement 4 is blocked on
infrastructure, not on fixtures**, and it is equally blocked for the maintainer's own drives —
which is why the fourth route was never built. If a GraphHopper instance is ever configured, the
answer is synthetic and trivial: compute the route, then hand-deviate a copy of it. No dataset
needed then either.

---

## 4. The licence position

### What covers OSM traces

The chain, with what each source actually says:

1. **[Contributor Terms](https://osmfoundation.org/wiki/Licence/Contributor_Terms) §3** — the
   contributor grants OSMF the right to distribute under **"ODbL 1.0 for the database and DbCL
   1.0 for the individual contents"**, or CC-BY-SA 2.0, or "such other free and open licence…as
   may from time to time be chosen by a vote". "Contents" is defined broadly as **"data and/or
   any other content (collectively, 'Contents')"**.
2. **[openstreetmap.org/copyright](https://www.openstreetmap.org/copyright)** — data is under
   the **"Open Data Commons Open Database License (ODbL) 1.0"**: *"free to copy, distribute,
   transmit and adapt our data, as long as you credit OpenStreetMap and its contributors. If you
   alter or build upon our data, you may distribute the result only under the same license."*
3. **`planet.openstreetmap.org/gps/`** — the trace dump directory states **"OpenStreetMap and
   contributors, CC-BY-SA"** (CC-BY-SA 2.0), a survival from the licence transition.

**Be honest about the gap:** none of these names GPS traces explicitly. The Contributor Terms
say "data and/or any other content" without mentioning GPX; `openstreetmap.org/copyright` does
not mention traces; [Upload GPS tracks](https://wiki.openstreetmap.org/wiki/Upload_GPS_tracks)
states no licence for uploaded traces; and the **OSMF
[Licence and Legal FAQ](https://osmfoundation.org/wiki/Licence/Licence_and_Legal_FAQ) has no
section on GPS traces at all** — it directs edge cases to `legal-questions@osmfoundation.org`.
A GPX uploaded through your OSM account is a submission of "Contents" under the terms you
accepted, so **ODbL 1.0 + DbCL 1.0 is the correct working assumption**, but it is an inference
from broad wording, not a quotation of a trace-specific clause.

### What that means for committing a derived route file

A route file is a filtered, resampled, coordinate-only extract of trace data. Under ODbL that is
most naturally a **Derivative Database** (the FAQ: a derivative involves data that is
*"adapt[ed], modify[ed], enhance[d], correct[ed] or extend[ed]"*) rather than a **Produced
Work** (*"where you take the OSM data and turn it into a finished work (as opposed to it being
made available as a database)"*). A list of coordinates is still a database, so assume
share-alike applies.

**Verdict: committable, with a notice.** Concretely:

- Keep [`LICENSE`](../../../LICENSE) (MIT) as the repo's software licence, untouched.
- Add a `LICENSE` or `ATTRIBUTION.md` **inside `tools/mocklocation/routes/`** stating that the
  route files are derived from OpenStreetMap data, **© OpenStreetMap contributors**, available
  under the **Open Database License 1.0**, and naming the source trace IDs.
- Record the provenance per file — trace ID, uploader, retrieval date — in `routes/README.md`,
  which already carries per-file measurements and is the right home for it.

No conflict with MIT: MIT governs the software and imposes nothing on data files; ODbL governs
those files and imposes share-alike on *them*. The obligation this accepts is real but narrow —
a redistributor must keep the route files ODbL. They are test fixtures in a harness that is not
a module of the root build, so this never reaches the shipped app.

**The synthetic route is under exactly the same obligation**, and this is worth stating plainly
because it is easy to assume otherwise: an OSRM route is computed from OSM road geometry, so a
densified copy of it is equally ODbL-derived. Synthetic generation buys privacy and determinism.
It does **not** buy licence freedom. There is no licence-clean option here short of surveying
roads yourself — which is what the maintainer's personal drives are.

### The ethical footnote nobody asks for but should

Retiring the maintainer's drives by adopting `identifiable` OSM traces trades the maintainer's
privacy for a **named third party's**. Trace 1992572 is attributable to user `-ad-` and shows
them transiting a Belgian average-speed section at 131.7 km/h. The data is already public, under
a licence permitting reuse, and committing a derived extract adds no new disclosure — but "it was
already public" is a licence argument, not an ethics one. Two mitigations, cheap:

- **Prefer bulk/automated uploader accounts.** `sunnypilot` is a project's fleet of device logs,
  not one person's commute diary. Same data quality, far less about any individual.
- **Clip to the section.** A 218 s window over 8 km of motorway is what the fixture needs; the
  other 191 km of trace 1992572's day is not, and should not be committed. This is the same
  reasoning as `--trim 1000`, applied to somebody else.

---

## 5. Recommendation per requirement

The four scenarios want **different sources**, and forcing one dataset to cover all four would
be a worse answer.

### Requirement 1 — average-speed section, gantry to gantry: **synthetic, with a real trace as companion**

**Primary: synthetic OSRM route over the real relation.** Verified end to end during this
investigation, and it is exact:

```
OSRM code: Ok
route: 8.01 km, OSRM duration 5.0 min, 37 shape pts
  @100 km/h step 27.78 m -> 288 lines (288 s), 7.97 km
     west gantry 0 m at line 0; east gantry 13 m at line 287; order W->E OK;
     transit 287 s, mean 100.0 km/h
  @120 km/h step 33.33 m -> 240 lines (240 s), 7.97 km
     west gantry 0 m at line 0; east gantry 19 m at line 239; order W->E OK;
     transit 239 s, mean 120.0 km/h
```

It wins requirement 1 for reasons no real trace can match:

- **0 m and 13 m from the two device nodes, in the correct west→east order**, by construction —
  because you route *from one device node to the other*, so the endpoints are the gate.
- **The expected section average is an input, not a measurement.** 100 km/h in, 100.0 km/h out.
  `routes/README.md` currently has to *derive* "that is the value a correct section average
  should settle at" (75.4 km/h) from the fixture. With synthetic, you assert it. You can also
  generate a compliant transit and an over-limit transit of the *same* section by changing one
  number, which is a whole test matrix a single recorded drive cannot provide.
- **Regenerable when OSM changes.** `routes/README.md` warns: "If OSM changes — a gantry moved, a
  relation retagged, a section decommissioned — this route stops testing what it claims to." A
  synthetic route is re-derived by re-running the script, so drift is a rebuild, not an
  archaeology exercise. This is arguably a bigger win than the privacy one.
- **Zero privacy exposure**, for the maintainer and for strangers.

**Companion: trace 1992572, clipped to the section.** Keep one real transit, because synthetic
cannot tell you whether the section logic survives real GPS noise — see §6. 1 Hz native,
west→east, real jitter.

### Requirement 2 — urban stop-start: **a real public trace. Synthetic is not honest here.**

**Trace 1741287** (`-ad-`, 18.45 km, 26 standstills, longest 130 s, p90 113.6 km/h). This is the
one requirement where synthetic generation should be refused: you *can* emit runs of identical
coordinates and `MockService` will report speed 0, but you would be asserting the answer you are
testing for. A fabricated stop proves the harness holds a position; a recorded stop is evidence
a real vehicle did, with the approach and pull-away that surround it. The behaviour under test —
camera park and resume, HUD easing to zero, bearing hold below 2 m/s — is about the transition,
and the transition is what synthetic invents.

Convert with **`--stop-span 0`**. This is not optional and it is easy to get wrong:
`gpx2route.py`'s stop-reconstruction defaults (`--stop-span 12 --stop-kmh 8`) exist to undo the
app's own **25 m decimation**, and SKILL.md is explicit that "**Both thresholds assume the 25 m
decimation**; a source that was not decimated (a raw fix log from another recorder) needs
`--stop-span 0` and its stops replayed from its own low-speed samples." An OSM trace was never
decimated by Detour. Leave the default on and the converter will hunt for stops that are already
present, and reconstruct them a second time.

### Requirement 3 — posted-limit variety: **the same trace, 1741287**

Eight distinct `maxspeed` values verified in its footprint (5/10/15/20/30/50/70/120), motorway
through living-street. It covers requirements 2 and 3 together, exactly as one drive should.
Confirm the camera count with the query in §7 before writing numbers into `routes/README.md`.

### Requirement 4 — deviation: **no source, and none will help**

Blocked on a reachable routing server (§3.6). Synthetic once unblocked. Do not go looking for a
dataset for this.

### Bonus the current suite lacks: **wrong-direction transit**

`sunnypilot` traces 10731546 (63.2 km/h) and 8820623 (119.5 km/h) transit relation 15682532's
gantries **east → west**, against the measured direction. Cheap negative fixtures for the
heading test, at 10 Hz, and the suite has nothing like them today.

---

## 6. What is lost by moving off real drives

Stated plainly, because the brief asked and because the answer is not "nothing".

**Lost by going synthetic:**

- **No GPS noise.** No jitter, no multipath under gantries or in cuttings, no accuracy
  degradation. Replay never exercised the degraded-accuracy paths anyway — `MockService` reports
  a constant `accuracy = 4f` (`MockService.kt:113`) — but a *real* trace at least wobbles, and
  the position filtering and bearing derivation see a realistic input. Synthetic geometry is
  smooth to the metre, so a bug that only appears when consecutive fixes disagree will not show.
- **No genuine standstills.** Held coordinates are the assertion, not the evidence (§5).
- **Perfectly regular sampling.** Real recorders skip, buffer and burst. A fixed step tests the
  happy path only.
- **Constant speed unless scripted.** No acceleration profile, no braking, no traffic. The
  section average settles because arithmetic says it must, not because a driver did that.

**Lost by moving to somebody else's real trace:**

- **Control over direction, speed and where the stops fall.** You take what the driver did. The
  west→east candidate found transits at 131.7 km/h, so the compliant-transit case that
  `trajectcontrole.txt` currently covers at 75.4 km/h is *not* directly replaced — it needs a
  different trace, or synthetic.
- **Calibration to this app's own pipeline.** The current fixtures came out of Detour's exporter:
  already accuracy-gated, already 25 m-decimated, and the converter's heuristics were tuned for
  exactly that. Third-party logs are a different input class (`--stop-span 0`, outlier clamp,
  p90 speed check for pedestrians). That is a one-time tooling cost, but it is real work, not a
  drop-in swap.
- **Outliers.** 341 km/h in trace 1741287. The maintainer's exports never contained those.
- **Longevity risk.** A trace can be deleted by its uploader. A committed derived file survives,
  but the provenance link rots — argues for recording trace ID *and* retrieval date, and for
  preferring synthetic where reproducibility matters most.

**Which scenarios genuinely still need a real recording?** Requirement 2, and only requirement 2.
Standstill behaviour is the one place where fabricating the input fabricates the result.
Requirements 1 and 3 are geometry-and-gating tests that synthetic data covers better than a real
drive does, and requirement 4 is blocked regardless.

**Does any scenario need a *personal* recording? No.** Requirement 2's real-recording need is
satisfied by trace 1741287. The maintainer's drives can be retired from the repository
entirely — provided the outlier clamp lands in `gpx2route.py` first.

---

## 7. Reproducing this, and the acquisition recipes

### 7.1 Recipe for requirement 1 — synthetic, the recommended path

Four steps, no credentials, no bulk download.

**1. Find the relation's device nodes.** Overpass (`overpass-api.de` was unreachable from here;
`overpass.private.coffee` and `overpass.kumi.systems` both work):

```sh
curl -s https://overpass.private.coffee/api/interpreter --data-urlencode 'data=
[out:json][timeout:120];
rel["type"="enforcement"]["enforcement"="average_speed"](50.0,2.5,51.6,6.5);
out body;
node(r:"device");
out body;'
```

For relation **15682532** ("Trajectcontrole E40", Zaventem → Bertem) the answer is
**west (50.86929, 4.49257)** and **east (50.86183, 4.60503)**. Confirm the direction from the
`from` member and the `description`; the relation has **no `way` members**, so its geometry must
come from step 2.

**2. Route device node → device node**, in the measured direction:

```sh
curl -s "https://router.project-osrm.org/route/v1/driving/4.49257,50.86929;4.60503,50.86183?overview=full&geometries=geojson"
```

Returns 8.01 km / 37 shape points. `router.project-osrm.org` is a **demo server for light
testing** — fine for generating a fixture once, not for automation in CI. Self-host, or use the
project's own GraphHopper when one exists.

**3. Densify to one point per interval** at the speed you want the section average to be.
Step = `target_kmh / 3.6` metres per line for a 1000 ms interval; walk the polyline emitting a
point every step, carrying the remainder across shape segments. 100 km/h → 27.78 m → 288 lines.

**4. Write `lon lat` per line, longitude first**, and validate. `start-replay.sh` checks line
count, distance, mean speed and warns if column 1 looks like a latitude; it will also refuse to
start if the harness is not the designated mock-location app.

Verify before committing — the real acceptance test, not "it passed near a trajectcontrole":
closest approach to **both** device nodes must be ≤ 60 m (`SECTION_GATE_METERS`) **in the
measured order**. The check used here is in
`/tmp/…/scratchpad/synth.py` and reduces to: for each output line, haversine to each device
node; assert `min(d_west) <= 60`, `min(d_east) <= 60`, `argmin(d_west) < argmin(d_east)`.

### 7.2 Recipe for requirements 2 and 3 — a real public trace

**1. Find candidates in a bbox** (note `bbox=minlon,minlat,maxlon,maxlat`, and pages of 5000):

```sh
curl -s -A "your-app/1.0" \
  "https://api.openstreetmap.org/api/0.6/trackpoints?bbox=4.6850,50.8720,4.7150,50.8880&page=0"
```

Score each returned segment on: median `dt`, **p90 speed** (rejects pedestrians and cyclists —
this matters, see §3.4), and count of runs below 0.6 m/s lasting ≥ 8 s.

**2. Download the full trace.** Note which endpoint works:

```sh
#  https://api.openstreetmap.org/api/0.6/gpx/<id>/data   -> HTTP 401 Unauthorized  (needs OAuth)
curl -s -A "your-app/1.0" -o trace.gpx "https://www.openstreetmap.org/trace/<id>/data"   # -> 200
```

The website endpoint serves public traces anonymously; the API endpoint does not. Verified on
four traces.

**3. Convert**, keeping the raw GPX out of the working tree (SKILL.md: scratchpad, not the repo):

```sh
.claude/skills/detour-gps-replay/scripts/gpx2route.py trace.gpx urban-stops.txt \
    --interval-ms 1000 --stop-span 0
```

`--stop-span 0` because the source was never 25 m-decimated (§5). `--trim` is **not** needed for
a third-party trace — it exists to protect the maintainer's own endpoints — but clipping to the
window you actually need is still right, on both size and courtesy grounds (§4).
`parse_time` handles both timestamp forms seen in the wild:
`2015-04-05T13:04:02.995Z` and the naive `2023-09-29T11:27:50.700000`.

**4. Blocker to fix first:** add a speed-outlier clamp to `gpx2route.py`. Trace 1741287 contains
a 341.2 km/h sample. Reject samples implying more than ~200 km/h and interpolate across them.
Without this, `topSpeedMps` in every A/B baseline is garbage.

### 7.3 The unfinished check

Speed cameras along trace 1741287. Overpass rate-limited this session; run it later, in two
light queries rather than one compound one:

```sh
curl -s https://overpass.private.coffee/api/interpreter --data-urlencode 'data=
[out:json][timeout:100];
node["highway"="speed_camera"](50.8739,4.6888,50.9531,4.7990);
out body;'
```

Then count nodes within 400 m (`WARN_METERS`) of the driven line, and within 4000 m
(`SpeedCameras.PREFETCH_RADIUS_M`) — the same two columns `routes/README.md` already reports.

### 7.4 If this is adopted

Not part of this note's scope, but the sequence is: outlier clamp in `gpx2route.py` → generate
fixtures → **re-derive every baseline number in `routes/README.md` from the new files** (do not
copy the old ones; different drives, different distances, different section averages) →
`routes/ATTRIBUTION.md` with trace IDs and retrieval dates → and per SKILL.md's A/B protocol,
record fresh baselines under `tools/mocklocation/baseline/` before anything else changes.

---

## 8. Sources

- [OSM API v0.6 — trackpoints](https://wiki.openstreetmap.org/wiki/API_v0.6) · [Visibility of GPS traces](https://wiki.openstreetmap.org/wiki/Visibility_of_GPS_traces) · [Upload GPS tracks](https://wiki.openstreetmap.org/wiki/Upload_GPS_tracks)
- [Planet.gpx](https://wiki.openstreetmap.org/wiki/Planet.gpx) · [planet.openstreetmap.org/gps/](https://planet.openstreetmap.org/gps/) (licence header + file dates read live) · [Public GPS Traces Download thread](https://community.openstreetmap.org/t/public-gps-traces-download/77886)
- [OSMF Contributor Terms](https://osmfoundation.org/wiki/Licence/Contributor_Terms) · [openstreetmap.org/copyright](https://www.openstreetmap.org/copyright) · [OSMF Licence and Legal FAQ](https://osmfoundation.org/wiki/Licence/Licence_and_Legal_FAQ)
- Traces measured: [1992572](https://www.openstreetmap.org/trace/1992572) · [10731546](https://www.openstreetmap.org/trace/10731546) · [8820623](https://www.openstreetmap.org/trace/8820623) · [1741287](https://www.openstreetmap.org/trace/1741287)
- [GeoLife user guide](https://www.microsoft.com/en-us/research/publication/geolife-gps-trajectory-dataset-user-guide/) · [GeoLife download](https://www.microsoft.com/en-us/download/details.aspx?id=52367)
- [Porto taxi — UCI #339](https://archive.ics.uci.edu/dataset/339/taxi+service+trajectory+prediction+challenge+ecml+pkdd+2015) · [T-Drive](http://urban-computing.com/index-909.htm) · [CRAWDAD epfl/mobility](https://ieee-dataport.org/open-access/crawdad-epflmobility)
- [Mapillary CC-BY-SA for open data](https://help.mapillary.com/hc/en-us/articles/115001770409-CC-BY-SA-license-for-open-data) · [Mapillary API](https://www.mapillary.com/developer/api-documentation) · [OSM wiki Template:Mapillary](https://wiki.openstreetmap.org/wiki/Template:Mapillary)
- [comma2k19](https://github.com/commaai/comma2k19) · [LICENSE (MIT)](https://github.com/commaai/comma2k19/blob/master/LICENSE) · [paper, arXiv:1812.05752](https://arxiv.org/abs/1812.05752)
- [OSRM demo server](https://router.project-osrm.org/) — light testing only

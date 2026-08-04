# Future ideas

## Real routing engine for Moto round trips (calimoto-style)

Current MVP hands waypoints to Google Maps, which may pick boring connector roads.
Upgrade path with an actual routing engine:

- **Engine: GraphHopper** — JVM, open source, built-in `round_trip` algorithm
  (point + distance + seed → loop), custom models for re-weighting roads.
- **Pipeline**: Geofabrik country extract → run the junction-aware curviness scorer
  (`Curviness.kt` algorithm) over every way in the extract → write `curvy_score` tag
  into the pbf (osmium) → GraphHopper imports it as a custom encoded value.
- **Custom model**: motorcycle profile, priority boost where `curvy_score` high,
  penalize trunk/urban. Round trips then follow curvy roads end to end.
- **Navigation handoff**: sample 9 waypoints from the computed route into a Google
  Maps directions URL — Maps follows essentially our route with voice/traffic.
  In-app turn-by-turn is months of work; skip.
- **Hosting tiers**: (1) self-hosted Docker server, ~€5/mo VPS or home server,
  ~2–4 days work — recommended first step; (2) on-device GraphHopper with prebuilt
  graph (~100–300 MB/country, build in CI, host on GitHub releases), offline, ~1 week+;
  (3) GraphHopper Cloud API — custom models are paywalled, least control.
- Tuning knobs once real: bend radius window (now 25–300 m), score threshold (0.12),
  loop shape, avoid-repeat-roads.

## Other ideas (unprioritized)
- Avoid destinations too close to start (min distance slider or % of radius)
- Export trips (GPX/JSON share)

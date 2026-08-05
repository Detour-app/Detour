# Future ideas

## Done: real routing engine for Moto round trips

Shipped. Self-hosted GraphHopper (`server/install.sh`, `server/INSTALL.md`) with a
`moto` profile weighted on the built-in `curvature` encoded value, `round_trip`
loops from `RoutingServer.roundTrip`, and in-app turn-by-turn (`NavEngine.kt`,
`car/NavScreen.kt`) — the Google Maps handoff this file once planned around is
only the fallback now.

The one part of the original plan that was **not** built, deliberately: baking a
`curvy_score` tag into the pbf with osmium and importing it as a custom encoded
value. GraphHopper cannot define an encoded value from an arbitrary OSM tag
without a source fork and recompile, which would mean maintaining a fork across
every monthly OSM refresh. Instead the app rolls `CURVY_CANDIDATES` loops per
spin and keeps the one whose polyline spends the most length in 25–300 m bends
(`Curviness.routeScore`) — the same junction-aware metric, applied to the routes
that actually came back, with junctions identified from GraphHopper's own turn
instructions.

Tuning knobs, in the order worth touching: bend radius window (now 25–300 m),
`CURVY_CANDIDATES` (now 3), the moto profile's curvature ladder in `install.sh`,
avoid-repeat-roads.

## Other ideas (unprioritized)
- Avoid destinations too close to start (min distance slider or % of radius)
- Voice guidance — turn-by-turn is silent on both phone and car screen
- Import a GPX and ride it (export exists; `Gpx.kt` has no reader)

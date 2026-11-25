# Future ideas

Things not built yet, and one note on why a plan that looked obvious was
dropped. Anything here that ships should move out of this file — a list that
still advertises finished work is worse than no list.

## Not built

- **Push on iPhone.** Android has it: circle arrival/departure wake pings go
  out over FCM ([docs/PUSH.md](docs/PUSH.md) §10), and the backend already has
  an `ApnsGateway` and an `ios` device platform. The iOS app never calls
  `registerForRemoteNotifications`, never hands its APNs token to the shared
  `Devices.register(token, platform = "ios")`, and doesn't handle the wake, so
  on an iPhone a circle arrival still waits for the app to be opened. It needs
  the push capability in a paid Apple Developer account, and a Mac plus a real
  iPhone to check the wake. Scope and notes: #702 (closed as parked).
- **Running the iPhone section-average chip.** The trajectcontrole average
  readout's iOS half (`SectionAverageHolder`, `SectionReadingWatcher` and the
  SwiftUI chip) compiles and links in CI's `ios.yml`, but `iosApp/` has no test
  target and nothing has ever run it. It needs a Mac session with the simulator
  or an iPhone, replaying `trajectcontrole.txt`. The car half is on the
  field-test checklist (#809). Notes: #34 (closed as parked).
- **Push-to-talk, again.** The relay drops voice frames today. What comes back
  will be Opus over binary frames rather than the base64'd PCM the old server
  relayed at ~40 KB/s per talker per listener —
  [§6.4](docs/CIRCLES_AND_CONVOYS.md#64-voice).
- **Sign-in on iPhone.** Sign-in moved to the identity provider's own page in a
  browser and the iOS side of that was never written, so every account feature
  is unreachable there. `ASWebAuthenticationSession` plus PKCE is the missing
  piece; everything after the redirect already exists in shared `Auth`. This is
  the largest single gap in the project — [docs/IOS_PORT.md](docs/IOS_PORT.md).
- **Spoken guidance on the Android phone screen.** The car screen speaks
  (`car/NavVoice.kt`) and so does iOS (`NavVoice.swift`); in-app turn-by-turn on
  an Android phone is still silent, even though the *Spoken guidance* setting is
  right there. Wiring the same prompts into the phone's nav path is small.
- **A watchOS companion.** There is no Android watch app to port from any
  more (#57), so this would be built from scratch.
- **Avoid repeating roads** across consecutive moto loops.

## Shipped, and how it turned out

**Real routing for moto round trips.** Self-hosted GraphHopper with a `moto`
profile weighted on the built-in `curvature` encoded value, `round_trip` loops
from `RoutingClient.roundTrip`, and in-app turn-by-turn (`NavEngine.kt`,
`car/NavScreen.kt`). The Google Maps handoff this file once planned around is
only the fallback now.

The one part of the original plan deliberately **not** built: baking a
`curvy_score` tag into the pbf with osmium and importing it as a custom encoded
value. GraphHopper cannot define an encoded value from an arbitrary OSM tag
without a source fork and a recompile, which would mean maintaining that fork
across every monthly OSM refresh. Instead the app rolls `CURVY_CANDIDATES` loops
per spin and keeps the one whose polyline spends the most length in 25–300 m
bends (`Curviness.routeScore`) — the same junction-aware metric, applied to the
routes that actually came back, with junctions identified from GraphHopper's own
turn instructions.

Tuning knobs, in the order worth touching: the bend-radius window (now
25–300 m), `CURVY_CANDIDATES` (now 3), then the moto profile's curvature ladder
in whatever provisions your GraphHopper.

Also since shipped, and no longer ideas: a **minimum distance** floor on a spin,
**GPX import** (`RouteGpx.parseRouteFile`, on both platforms), and spoken
guidance on the car screen and on iPhone.

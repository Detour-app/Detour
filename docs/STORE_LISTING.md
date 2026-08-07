# Play Store listing — Detour

Copy for the Play Console fields, plus the answers the App content section
needs. Character limits are Play's; counts below are current.

## App name (max 30)

```
Detour: random drive roulette
```

29 characters. Plain `Detour` also works but gives search nothing to match on.

## Short description (max 80)

```
Don't know where to drive? Spin for a random destination, then track the ride.
```

78 characters.

## Full description (max 4000)

```
Some days the only thing stopping a ride is not knowing where to go. Detour
picks for you.

Set a radius, pick a direction if you care about one, and hit the dice. Detour
drops a random point on a real road inside that radius, offers three routed
candidates, and sends you off. Then it records what you actually rode.

SPIN A DESTINATION
• A random point on a genuine road, not an empty field in the middle of nowhere
• Walk, bike, moto and car modes, each with its own radius range and its own
  idea of which roads count
• Three candidates per spin, with routed distance and ETA
• Navigate in the app, or hand the destination to Google Maps, Waze or whatever
  else you have installed

RECORD THE RIDE
• Trips start on their own when you settle into a driving pace, and end
  themselves when you are done — a traffic light does not cut a ride short
• Distance, duration, average and top speed, and on a moto the peak lean angle
  and cornering g
• Assign paired Bluetooth devices to a vehicle — intercom to the moto,
  infotainment to the car — and every trip files itself under the right one
• Full history grouped by month, with the shape of each route drawn on the row

FOG OF WAR
Everywhere you have ridden is permanently uncovered on the map. Everywhere else
sits under a scrim, waiting. The reveal corridor is yours to set, and the whole
overlay switches off when you just want to read the map.

BADGES AND COVERAGE
Distance, top speed, single ride, places and coverage. Coverage is measured
honestly: the share of a municipality's actual road network you have driven,
resolved from OpenStreetMap boundaries.

ON YOUR WRIST AND IN THE CAR
A Wear OS companion for spinning and controlling a ride from the watch, and an
Android Auto screen for the car.

YOURS, NOT OURS
Detour needs no account and no server. Trips, traces and fog live in private
storage on your phone. There is no advertising, no analytics and no crash
reporting of any kind — none, not "anonymised".

If you want sync, friends and a shared fog of war, you point the app at a sync
server you run yourself; the install script builds one for you. Routing and
address search can point at your own instances too. Your rides stay on hardware
you own.

Map data © OpenStreetMap contributors, ODbL. Tiles by OpenFreeMap. Geocoding by
Photon.
```

## Graphics needed

| Asset | Spec | Source |
|---|---|---|
| App icon | 512×512 PNG, 32-bit | `docs/play/icon-512.png` |
| Feature graphic | 1024×500 PNG, no alpha | `docs/play/feature-graphic.png` |
| Phone screenshots | 2–8, min 1080px on the short side | `docs/screenshots/` — map, spin, route, fog, history |
| Wear OS screenshots | ≥1, square or round | Needed once the Wear form factor is added |

The first two are generated from the launcher icon's own geometry by
`python3 tools/icon/gen_play_assets.py`, so they follow any change made in
`tools/icon/gen_icon.py` instead of drifting from what installs on the phone.

The README screenshots were taken on a throwaway profile with a mocked GPS
position, so they are safe to publish as-is.

## App content answers

- **Privacy policy URL** — `https://maxke24.github.io/Detour/privacy.html`
  (enable GitHub Pages first: repo Settings → Pages → source `main` / `/docs`)
- **App access** — all functionality is available without restrictions. Sign-in
  is optional and only reaches a server the user supplies, so no test
  credentials are required. Say so in the free-text box.
- **Ads** — no ads.
- **Content rating** — Utility/Productivity/Communication questionnaire; answer
  no to every content category. User-to-user communication: **yes**, because of
  convoy push-to-talk and friends.
- **Target audience** — 18+ (it is a driving app), not appealing to children.
- **News app** — no.
- **Data safety** — see below.
- **Government apps** — no.
- **Financial features** — none.

## Data safety form

Detour collects nothing to a developer-operated server, so the form is short.
There is no default sync server; any sync target is one the user configures.

- **Does your app collect or share any of the required user data types?** — No.

That answer is defensible because the developer operates no endpoint that
receives user data. If you would rather over-disclose than argue the point
later, declare instead:

| Data type | Collected | Shared | Purpose | Optional |
|---|---|---|---|---|
| Location (precise) | Yes | No | App functionality | Yes, user-configured sync only |
| Personal info (name/email) | Yes | No | Account management | Yes, sign-in is optional |
| Audio | No | No | — | Convoy audio is transient, never stored |

- **Data encrypted in transit** — yes (HTTPS/WSS throughout).
- **Users can request data deletion** — yes; uninstall wipes local data, and the
  server admin dashboard deletes an account.

## Wear OS

The release carries a watch bundle that shares the phone package name, so Play
needs the Wear OS form factor added under Store listing before an edit
containing it can be committed. Wear listings need their own short description
and at least one watch screenshot.

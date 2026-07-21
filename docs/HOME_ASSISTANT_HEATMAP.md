# Home Assistant heatmap integration — plan (to build later)

Idea: show a heatmap of everywhere you've driven, inside Home Assistant,
fed by the self-hosted sync server.

## Verdict
Very doable, **low–moderate effort**. The server already stores the hard part:
per-user trips + fog-of-war **trace lines** in SQLite, behind bearer auth
(`server/sync/sync_server.py`). That's exactly the data a heatmap needs.

## The one catch
Home Assistant has **no native density heatmap**. Its `map` card only plots
markers/tracks, not heat density. So heat rendering happens **server-side** and
HA just displays the result.

## Path A — server renders, HA embeds (recommended, ~half a day)
1. Add a read-only endpoint to `sync_server.py`, e.g. `GET /heatmap?key=…`:
   - Look up the user by a scoped API key (see below).
   - Read their `traces` rows (JSON line arrays of `[lat,lon]`).
   - Render with **folium + HeatMap plugin** → self-contained HTML
     (interactive, Leaflet basemap + heat overlay). Or a PNG via
     datashader/PIL if a static image is preferred.
2. In Home Assistant, display it:
   - **Webpage card** (iframe of the HTML), or
   - **generic camera** entity pointing at the PNG, refreshed periodically.
- No custom HA component, no HACS. Least friction, looks good.

## Path B — native custom integration (~2–3 days + upkeep)
Proper HA integration: config flow + entities (device_tracker/sensor for last
trip, distance, coverage). More "HA-native", but a true heatmap **still** needs
an image/iframe — HA won't density-render it. More work, marginal gain over A.
Only worth it if entities/automations (not just the map) are wanted.

## Add regardless of path
- **Scoped API key per user** — do NOT paste the app's bearer token into HA
  config. Small addition to the auth/users table + a header/query check on the
  new endpoint.
- Reuse the existing fog trace format (`traces` = JSON line arrays); the heatmap
  is just all those points, optionally weighted by overlap/frequency.

## Recommendation
Path A: one server endpoint (traces → folium HeatMap HTML) + an HA Webpage card.
Interactive heatmap of everywhere driven, in the dashboard, almost no HA-side
code. Self-contained on the server; does not touch the Android app.

## First step when resuming
- Add `GET /heatmap` + API-key auth to `server/sync/sync_server.py`.
- Deps: `folium` (pip) on the server host.
- Then a Lovelace `type: iframe` / webpage card pointing at the endpoint.

# Web parity fixtures

Logic the web planner (`web/`) copies from the Kotlin core instead of linking it, pinned
to the same answers on both sides. Each case is a pair:

- `<case>.input.json` — what arrives (a stored route document, a request's inputs).
- `<case>.expected.json` — what both copies must make of it.

| Folder | Kotlin side | TypeScript side |
| --- | --- | --- |
| `routes/` | `routeFromJson` (`shared/.../data/Routes.kt`), checked by `WebParityRoutesTest` in `shared/src/androidHostTest` | `web/src/routes.ts`, checked by `web/src/routes.test.ts` |
| `gpx/export/`, `gpx/import/` | `RouteGpx.buildGpx`/`fileName` and `RouteGpx.parseRouteFile` (`RouteGpx.kt`), checked by `WebParityGpxTest` | `web/src/gpx/routeGpx.ts`, checked by `web/src/gpx/routeGpx.test.ts` |
| `routing/` | `routeViaRequest` (`RoutingClient.kt`) after `RoutePreferences.supportedBy`, checked by `WebParityRoutingTest` | `web/src/routing/request.ts`, checked by `web/src/routing/request.test.ts` |

`routes/` expected files are the parsed route in a neutral shape (stops as objects,
polyline as `[lat, lon]` pairs, no pass-through keys), or `null` for a document both sides
drop. Numbers compare by value, so `1` and `1.0` are the same.

`routing/` inputs are a mode (`CAR`/`MOTO`), the stops, the rider's options (only the ones
set; the rest default as in `RoutePreferences`) and the encoded values `/info` reported
(`null` for "couldn't ask"). Expected is the `/route` request: method, path, the query as
ordered `[key, value]` pairs with each `point` split into `[lat, lon]` numbers (so `4` and
`4.0` match), and the POST body parsed, or `null` for a GET.

`gpx/export/` inputs are a route's `name`, `stops` and `polyline` (as `[lat, lon]` pairs);
expected is the suggested `fileName` and the whole `gpx` text, compared as a string. `gpx/import/`
inputs are the `file` text and the `nowMs` an import stamps; expected is the parsed route in the
`routes/` shape, or `null` for a file both sides reject.

Adding a case: drop a new pair in the folder. Both tests pick up every pair, and
`shared`'s host-test task declares this directory as an input, so a fixture-only change
re-runs it. CI runs both (`build.yml` for `shared`, `web.yml` for `web/`).

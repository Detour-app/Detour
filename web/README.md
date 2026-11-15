# Web planner

The browser half of route planning (#636), served by the API at
`https://<host>/planner`. Sign in, see the routes saved on the phone, open one to
edit it or start a new one (#913, #914).

## Editing

Click the map to add a stop, drag a stop or the line to reroute (a stop dragged
off the line goes into the leg it was grabbed from), right-click a stop or use its
× to remove it. Mode (`car`/`moto`), the curvy preset (moto only) and the avoid
toggles reroute too; options the server's graph can't honour (`/info`) are
greyed out rather than sent, as on the phone. The options are this session's,
not the route's: a saved route keeps only its mode, as the phone's do.

Routing goes to same-origin `/route` once per finished gesture, at most once a
second, through `RouteThrottle`; nginx's rate limit answering 429 shows "slow
down". Save is `PUT /api/routes/{id}` stamped `editedAtMs = Date.now()`, and needs
two stops. A 409 offers the newer stored copy, a 410 offers to save it as a new
route. Delete is `DELETE /api/routes/{id}`.

The open edit is kept in the tab's `sessionStorage` (`src/draft.ts`) under the
rider's `sub`. A 401 on save, or an expired token, signs the tab out; after
"Sign in again" the edit is back. Signing out drops it.

TypeScript, Vite, [MapLibre GL JS](https://maplibre.org/) on OpenFreeMap tiles (the
phone's), and [`oidc-client-ts`](https://github.com/authts/oidc-client-ts) for
sign-in. No framework: one page, a list and a map.

## Running it

With the dev stack up (`docker/dev`, which imports the `detour-web` client) and
the API on `:7500` (`dotnet run --project backend/Detour/Detour.Api`):

```sh
cd web
npm ci
npm run dev        # http://localhost:5173/planner/
```

The page only calls its own origin, as it does behind nginx in production, so
the dev server proxies `/api` to `:7500` and `/route`, `/info` to the dev
GraphHopper on `:7510`. Point them elsewhere with `DETOUR_DEV_API` and
`DETOUR_DEV_ROUTING`. Sign in as a dev realm rider (`docker/dev/config/keycloak/REALM.md`).

```sh
npx tsc --noEmit   # types
npx vitest run     # tests, including the parity fixtures
npx vite build     # dist/, what the image serves
```

CI runs those three (`.github/workflows/web.yml`). The API image builds `dist/`
itself in `backend/Dockerfile`'s `planner` stage, from this directory as the
named build context `web`; `backend/INSTALL.md` has the `docker build` line and
what serving it needs.

## Sign-in

The realm's `detour-web` client: authorization code with PKCE S256, no refresh
token, no silent renewal. The token lives in the tab's `sessionStorage`. A new tab
or an expired token shows "Sign in again", one click while the Keycloak session
lives. "Sign out" ends the Keycloak session. The issuer comes from
`/api/capabilities`, so nothing about the server is built in.

## Logic copied from the app

The planner can't link the Kotlin core (`shared/`), so the rules it needs are
copied, each with a note at the top of the file naming the original:

| Here | Original |
| --- | --- |
| `src/routes.ts` `routeFromJson` | `shared/.../data/Routes.kt` `routeFromJson` |
| `src/draft.ts` `routeDocument` | `shared/.../data/Routes.kt` `SavedRoute.toJson` (no fixture: its output is read back through `routeFromJson` in `draft.test.ts`) |
| `src/routing/request.ts` | `RoutingClient.kt` `routeViaRequest`, `RoutePreferences.kt`, `CurvyRouting.kt` `CurvyPreset`, `RoutingSupport.kt` `encodedValues` |

Every copy is pinned by fixtures in `testdata/web-parity/`, checked here by
`vitest` and on the Kotlin side by a `shared/src/androidHostTest` test. Changing
the Kotlin rule means changing a fixture, and then this copy until `vitest` passes
again. Keys this build doesn't know are kept on the parsed route (`raw`), so a
later save can send them back.

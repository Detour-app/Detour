# Web planner

The browser half of route planning (#636), served by the API at
`https://<host>/planner`. This first slice (#913) is read-only: sign in, see the
routes saved on the phone, pick one to draw it with its stops, distance and time.

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

Every copy is pinned by fixtures in `testdata/web-parity/`, checked here by
`vitest` and on the Kotlin side by a `shared/src/androidHostTest` test. Changing
the Kotlin rule means changing a fixture, and then this copy until `vitest` passes
again. Keys this build doesn't know are kept on the parsed route (`raw`), so a
later save can send them back.

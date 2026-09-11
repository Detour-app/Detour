# Running Detour somewhere real

The whole stack from published images, rather than a development machine with an
IDE attached. For what each configuration key means, see
[`backend/INSTALL.md`](../../backend/INSTALL.md); this covers standing it up.

> **Breaking change (2026-09).** Keycloak is no longer in `docker-compose.yml`.
> It moved to the `docker-compose.idp.yml` layer, and `keycloak-db` is now
> Postgres 18 (was 17). If you were running
> `docker compose -f docker/prod/docker-compose.yml up -d`, that now starts the
> API and its database **only** — every authenticated request 401s until an
> issuer is reachable. Set `COMPOSE_FILE` in `.env` (below) and a fresh copy is
> correct. Upgrading an existing install: dump `keycloak-db` first — see
> [Upgrading](#upgrading).

## Quick start

```bash
cp .env.example .env
# Fill every blank secret — compose refuses to start otherwise. Pick a
# COMPOSE_FILE line near the top of .env (the default is the full stack).
docker compose up -d
```

`COMPOSE_FILE` in `.env` lists the layer files to apply. `docker compose` reads
it, so every command from this directory needs no `-f` flags — `up`, `pull`,
`logs`, `down` all just work. Run compose from `docker/prod/`: the paths in
`COMPOSE_FILE` are relative to it.

To add or drop a layer later, edit that one line and `docker compose up -d`
again.

## The layers

| Add to `COMPOSE_FILE` | Brings up | You want it when |
| --- | --- | --- |
| `docker-compose.yml` | `api`, `db` | always — this is the base |
| `:docker-compose.idp.yml` | `keycloak`, `keycloak-db` | you are not running your own identity provider. Without it, nobody can sign in |
| `:docker-compose.routing.yml` | `osm-extract` (one-shot), `graphhopper` | you want in-app turn-by-turn and routed spin distances/ETAs. Without it, "Navigate in app" is absent and spins fall back to crow-flies |
| `:docker-compose.search.yml` | `photon` | you want address search on your own hardware. Without it, the app uses the public `photon.komoot.io` (Settings can forbid that) |
| `:docker-compose.cloudflare.yml` | `cloudflared` | you expose the stack through a Cloudflare tunnel — see [CLOUDFLARE.md](CLOUDFLARE.md) |
| `:docker-compose.proxy.yml` | `proxy` (nginx) | you want one public hostname path-routed to API + GraphHopper + Photon instead of one address per service. Assumes `+routing` and `+search` are also in the list |

The order is the colon-separated list in `.env`, e.g.

```properties
COMPOSE_FILE=docker-compose.yml:docker-compose.idp.yml:docker-compose.routing.yml:docker-compose.search.yml
```

`cloudflare` / `proxy` go on the end of whatever list you chose.

## First boot

The base and `+idp` layers come up in under a minute. `+routing` and `+search`
do not, and that is expected, not a failure:

- **GraphHopper** downloads the OSM extract for your region, then builds the
  routing graph and the contraction hierarchies for both profiles. That is
  **25 min (`benelux`) to 65 min (`germany`)** and wants `GRAPHHOPPER_HEAP` of
  RAM on the machine doing it. `/health` reports down for the whole build and the
  container sits at `health: starting` — `start_period` is sized to outlast it.
  `docker compose logs -f graphhopper` shows progress. The result lands in the
  `graphhopper-data` volume, so later starts are seconds; deleting that volume,
  or changing `OSM_REGION` or the profile set, pays for the build again.
- **Photon** downloads `PHOTON_REGION`'s prebuilt country index (a few hundred MB
  and up), extracts it, then serves. Until it finishes, `/status` is down and
  every query returns zero results rather than an error.
  `docker compose logs -f photon`.

Nothing `depends_on` GraphHopper or Photon with a hard condition, so the rest of
the stack is usable while they build.

## Regions

`OSM_REGION` picks a curated extract; `OSM_EXTRACT_URL` overrides it with any
Geofabrik `.pbf` path for a region we do not curate (you own the RAM budget
then). The curated three, first-boot cost on GraphHopper 11 (graph + CH for
`car` and `moto`):

| `OSM_REGION` | Heap | Build |
| --- | --- | --- |
| `benelux` | ~10 GB | ~25 min |
| `france` | ~18 GB | ~50 min |
| `germany` | ~22 GB | ~65 min |

*Figures are estimates from the extract sizes, not yet measured against a real
build — watch `docker compose logs -f graphhopper` for your actual numbers.*

`benelux` runs on a modest box. `france` and `germany` need a build machine with
real RAM until sub-project 2's prebuilt graphs land (they drop the serve need to
~4 GB). The list lives in
[`config/regions.env`](config/regions.env), mounted into the init container — a
new region is a one-line PR, no image rebuild.

## What you get

| Service | Published on | Layer | Notes |
| --- | --- | --- | --- |
| `api` | `127.0.0.1:7500` | base | The image from GHCR, applying its own migrations at startup |
| `db` | internal only | base | The API's Postgres |
| `keycloak` | `127.0.0.1:7580` | `+idp` | Owns every account and password |
| `keycloak-db` | internal only | `+idp` | Separate instance on purpose |
| `graphhopper` | `127.0.0.1:7510` | `+routing` | GraphHopper 11, profiles `car` and `moto` |
| `photon` | `127.0.0.1:2322` | `+search` | One country per `PHOTON_REGION` |
| `redis` | internal only | base | Off unless `--profile cache` |

Published ports bind to loopback. Put a reverse proxy with TLS in front — Keycloak
issues tokens against one fixed issuer URL, and that URL wants to be `https`,
permanently, because changing it invalidates every token and every stored
redirect URI. The app talks to GraphHopper and Photon directly, so those need to
be reachable too — the `proxy` / `cloudflare` layers do that.

## The realm is not created for you

`docker/dev` imports a realm on first start. This does not, and the difference is
deliberate: that realm ships a user with a published password and a client that
accepts any `localhost` redirect. Importing it here would be a back door.

After the stack is up, at `https://idp.example.com/admin`:

1. Create a realm — `detour` unless you change `IDP_ISSUER` to match.
2. Add realm roles `detour-user` and `detour-admin`. Only `detour-admin` gates
   anything (the administration endpoints); an ordinary rider needs no role.
3. Create client `detour-api`. Confidential, no flows enabled — it exists only as
   the audience the API validates.
4. Create client `detour-app`. **Public**, standard flow on, direct access grants
   **off**, PKCE `S256`. Redirect URI `detour://auth/callback`.
5. Set the username policy to match what the backend enforces, or riders will
   register handles the API then rejects.
6. `editUsernameAllowed` is Keycloak's default (off) and there is no longer a reason here to
   keep it that way: relationships key on the account id, so a rename changes the label
   riders see and nothing else. Turn it on if you want riders renaming themselves. Note that
   handles remain unique per realm, so a rename into a handle someone else holds is refused.
7. Create your own administrator, then clear `KC_ADMIN_*` from `.env`.

`docker/dev/config/keycloak/detour-realm.json` is a useful shape to copy from.
Copy the clients and roles; never the users.

## Upgrading

```bash
docker compose pull
docker compose up -d
```

`docker compose` reads `COMPOSE_FILE` from `.env`, so this pulls and recreates
exactly the layers you run.

`DETOUR_IMAGE_TAG=latest` follows `main`. Pin it to a commit sha for a deployment
you can roll back to a known build — `latest` cannot be rolled back to, because it
has already moved.

The API applies migrations on startup, so a `pull` that crosses a schema change
applies it the moment the container comes up. Back up `postgres-data` first.

This release moves `keycloak-db` from Postgres 17 to 18. Postgres does not
upgrade its data directory across a major version in place. Before `pull`ing,
dump the realm database:

```bash
docker compose exec -T keycloak-db pg_dump -U keycloak keycloak > kc.sql
```

Then, after the new images are up, replace only the `keycloak-db` volume and
restore into it — never `docker compose down -v`, which would also delete
`postgres-data` and every trip and trace with it:

```bash
docker compose stop keycloak-db
# The volume is named <project>_keycloak-db-data — "detour_keycloak-db-data" with
# the default PROJECT_NAME. Confirm the exact name first with `docker volume ls`.
docker volume rm detour_keycloak-db-data
docker compose up -d keycloak-db
docker compose exec -T keycloak-db psql -U keycloak keycloak < kc.sql
```

Or accept a fresh realm database and recreate the realm — see "The realm is not
created for you" above.

## Backups

Two volumes matter and losing either is unrecoverable:

- `postgres-data` — every trip, trace, group and shared route.
- `keycloak-db-data` — every account. There is no local registration to fall back
  on, so losing this means nobody can log in, and the rows in `postgres-data`
  belong to subjects that no longer exist.

`keycloak-data`, `redis-data`, `osm-data`, `graphhopper-data` and `photon-data`
are caches and downloads — safe to drop. GraphHopper and Photon rebuild them on
the next `up` (the long first boot again), so snapshot them if that wait matters.

## What is still missing

- **No importer for an existing `detour.db`.** Moving off the Python server means
  starting fresh, and there is no way to carry passwords across regardless —
  Keycloak owns them now and never saw the old hashes.
- **No reverse proxy in the base file.** Traefik, Caddy and nginx all work;
  picking one for you would mean picking your TLS story too. The `proxy` and
  `cloudflare` layers are options, not the default — see
  [CLOUDFLARE.md](CLOUDFLARE.md) if you want a tunnel, which needs no open port
  and terminates TLS for you.
- **No prebuilt routing graphs.** Every `+routing` first boot builds the graph
  from scratch. Sub-project 2 adds downloadable per-region tarballs and a
  `~4 GB` runtime; until then `france` / `germany` need a real build box.
- **Multi-country search.** `PHOTON_REGION` is one country. Multi-country is the
  image's experimental mode, deferred to sub-project 2.

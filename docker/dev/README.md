# Local development stack

Everything the backend talks to. The backend itself is not in here — run
`Detour.Api` from your IDE or with `dotnet run`, pointed at these.

```bash
docker compose -f docker/dev/docker-compose.yml up -d
```

## Canonical ports

These are fixed. If one is taken, kill whatever is holding it — do not move the
service. Keycloak bakes its issuer and redirect URIs into the realm, so
renumbering it means wiping the `keycloak-db` volume and re-importing.

Detour owns the **74xx–75xx block**, deliberately clear of the defaults
(5432, 6379, 8080) so it can coexist with whatever else is running on the same
machine. Nothing here uses a stock port.

| Service | Port | Also at |
|---|---|---|
| Traefik | 7000 | dashboard on 7090 |
| Grafana | 7440 | `http://grafana.localhost:7000` |
| OTLP gRPC | 7441 | what the API exports to |
| OTLP HTTP | 7442 | |
| Detour.Api | 7500 | `http://api.detour.localhost:7000` |
| Postgres (app) | 7532 | |
| Redis | 7579 | |
| Keycloak | 7580 | `http://idp.localhost:7000` |

Everything is bound to `127.0.0.1`, not `0.0.0.0`. The stack holds a Keycloak
admin account and an unauthenticated Grafana; neither belongs on your LAN.

## Keycloak

Realm `detour`, imported from `config/keycloak/detour-realm.json` on first start.

| | |
|---|---|
| Admin console | http://localhost:7580/admin — `admin` / `admin` |
| Dev rider | `rider` / `detour-dev`, holds both realm roles |
| Issuer | `http://localhost:7580/realms/detour` |
| App client | `detour-app` — public, PKCE S256, redirects to `detour://auth/callback` |
| API audience | `detour-api` — bearer-only |

Import runs **once**, on an empty database. Editing the realm file afterwards
changes nothing until you either apply the change in the admin console or wipe
the volume:

```bash
docker compose -f docker/dev/docker-compose.yml down -v keycloak-db keycloak
```

Getting a token by hand (the app uses the authorization-code flow; this is the
shortcut for poking at the API with curl, and it needs
`directAccessGrantsEnabled` turned on for `detour-app` in the console first):

```bash
curl -s -X POST http://localhost:7580/realms/detour/protocol/openid-connect/token -d grant_type=password -d client_id=detour-app -d username=rider -d password=detour-dev
```

## Postgres

The API connects as `detour`, not as the superuser. That role owns the `detour`
schema and nothing else — it cannot create databases, reach other databases, or
install extensions. The init script that creates it runs once, on an empty data
directory.

```bash
psql postgresql://detour:detour@localhost:7532/detour
```

## Logs

Bounded, always. A full dump of a container that has been up for a day is
several megabytes and tells you nothing the last few minutes would not.

```bash
docker compose -f docker/dev/docker-compose.yml logs --since 5m --tail 100 keycloak
```

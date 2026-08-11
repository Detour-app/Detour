# Running the .NET backend yourself

This covers the service under `backend/`. It is **not** what the app talks to
today — [`server/INSTALL.md`](../server/INSTALL.md) still is, and
`bash server/install.sh` still works. Read this when you are standing the new
service up alongside it, not instead of it.

## Be honest about the jump first

The Python server is one file and a SQLite database. `install.sh` puts it on a
Raspberry Pi and walks away. This one needs:

| Piece | Why | Optional? |
| --- | --- | --- |
| The API itself | .NET 10 runtime, or the container below | no |
| Postgres | citext handles, jsonb columns, real unique indexes | no |
| Keycloak | it owns accounts and passwords now; there is no local registration | no |
| Keycloak's own Postgres | Keycloak will not keep a realm in memory across restarts | no |
| A reverse proxy with TLS | the realm issues tokens against one fixed issuer URL | in practice, no |
| Redis | second-level cache only; a miss is a slower request | **yes** |

That is five processes where there was one, and the README's promise that "your
trips and traces live on hardware you own" now costs meaningfully more of that
hardware. If that trade is not worth it for your install, stay on the Python
server — it is not going anywhere in this change.

## The container

```bash
docker build -t detour-api backend
```

The build context is `backend/`, not the repository root. It listens on 7500, the
same port it uses everywhere else in this repo, and runs as the runtime image's
non-root user.

There is **no production compose file here yet.** `docker/dev/` is a development
stack — it ships default passwords in `.env.example` on purpose and Keycloak runs
in dev mode — so copy its shape, not its values. What it does show correctly is
how the pieces wire together and which ports each expects.

## Configuration

Everything below is `appsettings.json` keys, so each has an environment-variable
form too (`Idp__Authority`, `ConnectionStrings__DefaultConnection`, and so on).

| Key | Notes |
| --- | --- |
| `ConnectionStrings:DefaultConnection` | The API applies its own migrations at startup. Point it at a role that may create tables, or run migrations separately and use a narrower one. |
| `Idp:Authority` | The exact `iss` claim to require, e.g. `https://idp.example/realms/detour`. Exact, not a prefix. |
| `Idp:Audience` | `detour-api` unless you renamed the client. |
| `Idp:RequireHttpsMetadata` | Leave `true`. Off is for a local stack on plain HTTP. |
| `Cors:AllowedOrigins` | Only needed for a browser origin. The app is not one. |
| `Cache:RedisConnectionString` | Empty means memory-only, which is a correct single-instance deployment. |
| `ForwardedHeaders:KnownProxies` / `KnownNetworks` | **Set one of these if anything sits in front of the API.** See below. |

### Behind a reverse proxy

Both lists are empty by default, which means the API ignores `X-Forwarded-*`
entirely. That is the safe default and it is also the wrong one once there is a
proxy: the per-IP rate limit would partition every caller into a single bucket
keyed on the proxy's address, and HTTPS redirection would see `http` on requests
that arrived over `https` and answer a redirect the proxy resolves over `http`
again.

So name the proxy:

```json
"ForwardedHeaders": {
  "KnownNetworks": ["172.18.0.0/16"]
}
```

Do not reach for the usual container advice of clearing the lists to trust
everything. It trades one shared rate-limit bucket for a fresh bucket per spoofed
header, which is worse.

### The realm

[`docker/dev/config/keycloak/REALM.md`](../docker/dev/config/keycloak/REALM.md)
explains why the realm is configured the way it is — registration, lockout, token
lifetimes, roles, clients — and
`docker/dev/config/keycloak/detour-realm.json` is an importable example. Two
things there are load-bearing rather than cosmetic:

- The token must carry `preferred_username`. Keycloak emits it by default, so this
  only bites a realm with a trimmed-down scope configuration — and it bites hard,
  because that claim becomes the handle other riders search for and a token
  without it cannot provision an account at all.
- Usernames must be constrained to `^[A-Za-z0-9_.-]{3,24}$`. Keycloak does not
  enforce this and the backend does — the dev realm gets there with
  `editUsernameAllowed: false` and `registrationEmailAsUsername: false`, but a
  realm that allows an email address as a username will produce accounts this
  service refuses to provision.

## Checking it came up

```bash
curl -s http://localhost:7500/api/health
```

That answers with a per-dependency breakdown: Postgres is critical, Redis is
degraded-only, and the whole thing is unauthenticated on purpose so an
orchestrator can probe it. The image ships no `HEALTHCHECK` — the runtime image
has no HTTP client, and installing one so the container can curl itself is worse
than letting whatever runs it do the probing.

## What is not here

- **No data migration from the Python server.** Nothing reads `detour.db` yet.
- **Passwords cannot be migrated.** Keycloak will not accept the old hashes, so
  every existing rider goes through a password reset at cutover. That is a
  user-visible event and needs announcing, not just scheduling.
- **No production compose file**, per above.

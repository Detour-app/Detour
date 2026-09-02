# Running Detour somewhere real

The whole stack from a published image, rather than a development machine with an
IDE attached. For what each configuration key means, see
[`backend/INSTALL.md`](../../backend/INSTALL.md); this covers standing it up.

```bash
cp docker/prod/.env.example docker/prod/.env
# fill in every blank — compose refuses to start otherwise
docker compose -f docker/prod/docker-compose.yml up -d
```

## What you get

| Service | Published on | Notes |
| --- | --- | --- |
| `api` | `127.0.0.1:7500` | The image from GHCR, applying its own migrations at startup |
| `keycloak` | `127.0.0.1:7580` | Owns every account and password |
| `db`, `keycloak-db` | internal only | Separate instances on purpose |
| `redis` | internal only | Off unless `--profile cache` |

Both published ports bind to loopback. Put a reverse proxy with TLS in front of
them — Keycloak issues tokens against one fixed issuer URL, and that URL wants to
be `https`, permanently, because changing it invalidates every token and every
stored redirect URI.

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
6. Leave `editUsernameAllowed` **off** — Keycloak's default, so a realm created by
   hand already has it. Friend relationships and circle membership are stored
   against the username, so a rider who renames themselves is detached from their
   own relationships until the server keys them on `sub` instead (tracked
   separately). Note that `loginWithEmailAllowed: true` already means the handle a
   rider thinks of as theirs is not necessarily the one the system keys on.
7. Create your own administrator, then clear `KC_ADMIN_*` from `.env`.

`docker/dev/config/keycloak/detour-realm.json` is a useful shape to copy from.
Copy the clients and roles; never the users.

## Upgrading

```bash
docker compose -f docker/prod/docker-compose.yml pull api
docker compose -f docker/prod/docker-compose.yml up -d api
```

`DETOUR_IMAGE_TAG=latest` follows `main`. Pin it to a commit sha for a deployment
you can roll back to a known build — `latest` cannot be rolled back to, because it
has already moved.

The API applies migrations on startup, so a `pull` that crosses a schema change
applies it the moment the container comes up. Back up `postgres-data` first.

## Backups

Two volumes matter and losing either is unrecoverable:

- `postgres-data` — every trip, trace, group and shared route.
- `keycloak-db-data` — every account. There is no local registration to fall back
  on, so losing this means nobody can log in, and the rows in `postgres-data`
  belong to subjects that no longer exist.

`keycloak-data` and `redis-data` are caches and can be thrown away.

## What is still missing

- **No importer for an existing `detour.db`.** Moving off the Python server means
  starting fresh, and there is no way to carry passwords across regardless —
  Keycloak owns them now and never saw the old hashes.
- **No reverse proxy in the base file.** Traefik, Caddy and nginx all work;
  picking one for you would mean picking your TLS story too. One is provided as
  an overlay rather than a default — see [CLOUDFLARE.md](CLOUDFLARE.md) if you
  want a tunnel, which needs no open port and terminates TLS for you.

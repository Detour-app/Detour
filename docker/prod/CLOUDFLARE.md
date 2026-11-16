# Exposing the stack through a Cloudflare Tunnel

`docker/prod` binds everything to loopback on purpose. A tunnel is one way to put
it on the internet without opening a port, and it is the one this project's
clients already have support for.

Read [`README.md`](README.md) first — this only covers the tunnel.

## The one thing to get right

**Do not put Access in front of anything the app talks to — neither the API
nor Keycloak.**

The client no longer holds a Cloudflare Access service token: every route it
calls — the REST API, the live socket, and the Keycloak token exchange — goes
out with no `CF-Access-Client-Id` / `CF-Access-Client-Secret` headers. Access
in front of any of them means every request from the app gets Access's HTML
sign-in page instead of a response, which reads as "server unreachable" with
nothing useful logged.

Sign-in makes this doubly true for Keycloak specifically: the browser leg of
the authorization-code flow could pass an Access challenge, because a human is
there to answer it, but the next step cannot. The app posts the code to
`/protocol/openid-connect/token` from its own HTTP client, with no Access
cookie, and the refresh that follows every fifteen minutes would fail the same
way. `idp.` has to be a public hostname; Keycloak is the thing guarding it,
that is its whole job.

If you want an edge control in front of the API's anonymous surfaces
(`/api/health`, `/api/capabilities`) or the routing/geocoding engines, use a
Cloudflare rate-limit or WAF rule instead — no shared secret, and it does not
break the app.

## Two hostnames

Keycloak hands out absolute URLs built from its own hostname, so it needs one of
its own rather than a path under the API's.

| Hostname | Origin | Access |
| --- | --- | --- |
| `api.example.com` | `http://api:7500` | **none** |
| `idp.example.com` | `http://keycloak:8080` | **none** |

Both must be `https`. The realm bakes its issuer into every token, and changing
scheme or host later invalidates all of them.

## Adding cloudflared to the stack

Create the tunnel in the Cloudflare dashboard (Zero Trust → Networks → Tunnels),
copy the token, and put it in `docker/prod/.env`:

```properties
CLOUDFLARE_TUNNEL_TOKEN=eyJ...
```

Then a compose override, so the base file stays tunnel-agnostic:

```yaml
# docker/prod/docker-compose.cloudflare.yml
services:
  cloudflared:
    image: cloudflare/cloudflared:latest
    restart: unless-stopped
    # Joins both service networks because it is the only thing that talks to the
    # API and to Keycloak, and they are deliberately on separate networks.
    networks:
      - edge
    command: tunnel --no-autoupdate run
    environment:
      TUNNEL_TOKEN: ${CLOUDFLARE_TUNNEL_TOKEN:?Set CLOUDFLARE_TUNNEL_TOKEN in docker/prod/.env}
    depends_on:
      api:
        condition: service_healthy
      keycloak:
        condition: service_healthy
```

```bash
docker compose -f docker/prod/docker-compose.yml -f docker/prod/docker-compose.cloudflare.yml up -d
```

Route the two hostnames to `http://api:7500` and `http://keycloak:8080` in the
dashboard. Container names resolve because cloudflared shares the `edge` network
with both.

With the tunnel terminating TLS you can drop the published ports — edit them out
of the base file, and nothing is reachable except through Cloudflare. Keep
Keycloak's `127.0.0.1:${KC_PORT}` one: it binds loopback only, and it is how you
reach the admin console once the edge blocks it (next section).

## Keycloak's admin console stays off `idp.`

Routing `idp.` straight to Keycloak publishes everything Keycloak serves,
including the admin console (`/admin/master/console/`) and the `master` realm's
sign-in. The admin REST API still wants a token, but the console's login is a
brute-force target open to the whole internet, and any future admin-console or
`master`-realm CVE is too. The caddy layer blocks `/auth/admin` for the same
reason; on a tunnel, the block goes at Cloudflare.

**1. Point the console at the loopback port.** In `docker/prod/.env`, with
`KC_PORT` at its default:

```properties
KC_HOSTNAME_ADMIN=http://127.0.0.1:7580
```

**2. Point the `master` realm there too.** Without this the console loads over
the tunnel, but its sign-in form posts to `https://idp.example.com/realms/master/…`,
the session cookie it needs belongs to `127.0.0.1`, and the browser gets a
`400`. Run once, from `docker/prod/`, after `up -d`:

```bash
# KC_CLI_PASSWORD rather than --password, so the password stays out of `ps`.
docker compose exec keycloak sh -c '
  export KC_CLI_PASSWORD="$KC_BOOTSTRAP_ADMIN_PASSWORD" &&
  /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 \
    --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" &&
  /opt/keycloak/bin/kcadm.sh update realms/master -s attributes.frontendUrl=http://127.0.0.1:7580'
```

`./detour` and anything else using `kcadm.sh` inside the container sign in over
`localhost:8080`, not through the edge, so they still sign in. The `detour` realm is untouched; this moves
only the admins' realm.

**3. Block both at the edge.** Security → WAF → Custom rules, action **Block**,
expression:

```text
(http.host eq "idp.example.com" and
  (http.request.uri.path contains "/admin" or
   http.request.uri.path contains "/realms/master"))
```

`contains` rather than `starts_with`, so `//admin/` and `/admin;x/` (Keycloak
ignores matrix parameters) are caught as well. Percent-encoded paths
(`/%61dmin/`) are caught only because Cloudflare normalizes incoming URLs
(Rules → Settings, on by default); leave it on. Nothing the app or a rider's
browser calls has either string in its path: they use `/realms/detour/…` and
`/resources/…/login/…`.

**4. Open the console over SSH.**

```bash
ssh -L 7580:127.0.0.1:7580 <server>
# then http://127.0.0.1:7580/admin/master/console/ in your browser
```

Keep `KC_ADMIN_PASSWORD` long and unique anyway. The README says why the
bootstrap admin user stays.

## What the stack needs to know about it

In `docker/prod/.env`:

```properties
KC_HOSTNAME=https://idp.example.com
IDP_ISSUER=https://idp.example.com/realms/detour
IDP_REQUIRE_HTTPS=true

# cloudflared is the only thing talking to the API, and it is on the Docker
# bridge. Without this the API ignores X-Forwarded-* and every request looks like
# it came from the tunnel — including to the rate limiter, which then throttles
# all your riders as though they were one.
FORWARDED_KNOWN_NETWORKS=172.16.0.0/12
```

Keycloak already has `KC_PROXY_HEADERS: xforwarded` set in the base compose file,
which is what makes it trust the scheme cloudflared reports rather than deciding
it is serving plain HTTP.

## WebSockets

Nothing to configure. cloudflared proxies the upgrade, and the relay is an
ordinary endpoint under `/api/live` rather than a second listener on its own
port — which is the part that used to need its own hostname and its own Access
rule.

Two things to know anyway:

- **Idle timeouts.** Cloudflare closes an idle connection after roughly 100
  seconds. The client pings every 20 s and the server's `KeepAliveInterval` is
  also 20 s, so a live socket stays open — but do not raise either past 100 s
  thinking it saves battery.

## Client configuration

The rider fills these in under Settings → Servers & sync, or you bake them in at
build time (`local.properties` on Android, `Config.xcconfig` on iOS):

```properties
api.url=https://api.example.com
idp.issuer=https://idp.example.com/realms/detour
live.url=wss://api.example.com/api/live
```

## Checking it

```bash
# Both public, no token — either answering anything but 200, an Access login
# page especially, means that hostname is behind a policy it must not be behind.
curl -s -o /dev/null -w '%{http_code}\n' \
  https://idp.example.com/realms/detour/.well-known/openid-configuration
curl -s -o /dev/null -w '%{http_code}\n' https://api.example.com/api/health
```

The admin console and the `master` realm must be refused at the edge — `403`
from Cloudflare, not `200` or a redirect from Keycloak:

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://idp.example.com/admin/master/console/
curl -s -o /dev/null -w '%{http_code}\n' https://idp.example.com/realms/master
```

For the socket, `401` without a bearer token is the right answer: it proves the
upgrade reached the API rather than being refused by the edge.

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://api.example.com/api/live
```

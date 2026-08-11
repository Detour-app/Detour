# Exposing the stack through a Cloudflare Tunnel

`docker/prod` binds everything to loopback on purpose. A tunnel is one way to put
it on the internet without opening a port, and it is the one this project's
clients already have support for.

Read [`README.md`](README.md) first — this only covers the tunnel.

## The one thing to get right

**Put the API behind Access if you want. Never put Keycloak behind it.**

The clients send `CF-Access-Client-Id` / `CF-Access-Client-Secret` on the REST
calls and on the live socket, so a service token in front of the API works:

| Client | Sends Access headers |
| --- | --- |
| `Api.kt` — every REST call | yes |
| `ConvoyLiveClient` — the live socket, both platforms | yes |
| `Auth.kt` — the Keycloak token exchange | **no** |

Sign-in is an authorization-code flow. The browser part could pass an Access
challenge, because a human is there to answer it. The next step cannot: the app
posts the code to `/protocol/openid-connect/token` from its own HTTP client, with
no Access cookie and no service-token header, and Cloudflare answers 403. The
refresh that follows every fifteen minutes fails the same way.

The symptom is nasty — sign-in appears to work, the browser closes, and the app
lands back on "not signed in" with nothing useful logged. So: `idp.` is a public
hostname. Keycloak is the thing guarding it; that is its whole job.

## Two hostnames

Keycloak hands out absolute URLs built from its own hostname, so it needs one of
its own rather than a path under the API's.

| Hostname | Origin | Access |
| --- | --- | --- |
| `api.example.com` | `http://api:7500` | optional service token |
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

With the tunnel terminating TLS you can drop the published ports entirely — edit
them out of the base file, and nothing is reachable except through Cloudflare.

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
- **Access on the socket.** If the API is behind a service token, the live socket
  needs it too. Both clients send it on the upgrade request, so this works, but
  the token has to be the same one the REST calls use — the app stores exactly one
  pair.

## Client configuration

The rider fills these in under Settings → Servers & sync, or you bake them in at
build time (`local.properties` on Android, `Config.xcconfig` on iOS):

```properties
api.url=https://api.example.com
idp.issuer=https://idp.example.com/realms/detour
live.url=wss://api.example.com/api/live
```

The service token, if you used one, goes in the CF Access fields on that same
settings screen. There is one pair for the whole app.

## Checking it

```bash
# Public, no token — Keycloak must answer, or sign-in cannot work.
curl -s -o /dev/null -w '%{http_code}\n' \
  https://idp.example.com/realms/detour/.well-known/openid-configuration

# Behind a service token: 200 with it, 403 without.
curl -s -o /dev/null -w '%{http_code}\n' https://api.example.com/api/health
curl -s -o /dev/null -w '%{http_code}\n' \
  -H "CF-Access-Client-Id: $CF_ID" -H "CF-Access-Client-Secret: $CF_SECRET" \
  https://api.example.com/api/health
```

The first returning anything but `200` — an Access login page especially — means
the IdP hostname is behind a policy it must not be behind.

For the socket, `401` without a bearer token is the right answer: it proves the
upgrade reached the API rather than being refused by the edge.

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  -H "CF-Access-Client-Id: $CF_ID" -H "CF-Access-Client-Secret: $CF_SECRET" \
  https://api.example.com/api/live
```

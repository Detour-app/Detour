# Detour — Server Install Guide

Detour is an Android app that records rides and paints a "fog of war" of
the roads you have explored. It can talk to two self-hosted services:

| Service | What it does | Needs |
|---|---|---|
| **Sync server** | Accounts, trips, fog of war, badges, friends | Python 3.8+, ~50 MB. Nothing else. |
| **Routing server** | Generates curvy motorcycle round trips, car and bike routes | Docker, a multi-GB OSM download, 4–8 GB RAM during import |
| **Geocoder** | Self-hosted address/place search (Photon), so search is fast, private and not rate-limited | Docker, a per-country prebuilt index (~1–2 GB), 2 GB RAM |

You can install any combination. `install.sh` does the whole thing.

---

## Quick start

### On a Proxmox host

Run it as root on the **host**, not inside a container. It creates an
unprivileged LXC, sizes it for what you asked for, and installs inside it.

```bash
git clone https://github.com/maxke24/detour.git
bash detour/server/install.sh
```

It will ask what to install, then print an invite code and what to do next.
Non-interactive:

```bash
bash detour/server/install.sh --all --yes --region europe/belgium
```

### On any Debian or Ubuntu machine

A VM, a Raspberry Pi, an existing LXC, bare metal — anything with systemd.
The same script detects it is not a Proxmox host and installs in place.

```bash
sudo bash detour/server/install.sh --sync
```

Force this mode on a Proxmox host (installing directly onto the hypervisor,
which is usually a bad idea) with `--in-place`.

### Options

| Flag | Meaning |
|---|---|
| `--sync` / `--routing` / `--geocoder` / `--all` | What to install. Default: ask, or all with `--yes`. |
| `--region europe/belgium` | Geofabrik path, or a full `.osm.pbf` URL (routing). |
| `--geo-country be` | Two-letter country code for the geocoder's prebuilt index (see below). |
| `--yes` | No prompts. |
| `--ctid 150` | Pick the container ID instead of the next free one. |
| `--storage local-lvm` `--bridge vmbr0` `--hostname detour` | Container placement. |
| `--open-registration` | Skip the invite code. Read the warning below first. |
| `--in-place` | Install here, never create a container. |
| `--uninstall` | Remove the services, keep the data. |
| `--purge` | Remove the services **and delete all data**. Asks first. |

---

## What you end up with

```
/opt/detour-sync/sync_server.py     the service (stdlib Python; python3-websockets
                                          is the one optional dep, for convoy live location/PTT)
/var/lib/detour-sync/detour.db SQLite: accounts, trips, traces, badges, invites
/var/backups/detour/                nightly backups, 14 days
/opt/graphhopper/                        docker-compose.yml, config.yml, OSM data
/opt/photon/                             docker-compose.yml, photon.jar, the country index

systemd: detour-sync.service        the sync API,       127.0.0.1:8790
                                          + convoy live relay (WebSocket), 127.0.0.1:8990
         detour-backup.timer        nightly, 00:00 + jitter
         graphhopper-refresh.timer       monthly OSM refresh + re-import
         photon-refresh.timer            monthly index refresh
         docker: graphhopper             the routing API, 127.0.0.1:8989
         docker: photon                  the geocoder API, 127.0.0.1:2322
```

**Both services listen on localhost only.** The installer does not open a port,
touch your firewall, or configure a tunnel. Exposing them is your decision, and
the next section is about making it safely. (`SYNC_BIND=0.0.0.0 bash install.sh`
makes the sync service listen on the LAN as well — worth it for Home Assistant,
see [`homeassistant/README.md`](homeassistant/README.md#6-optional-skip-the-tunnel-on-your-lan).
Pass it as an environment variable, not by hand-editing `HOST=` in the unit
file: the installer rewrites that file on every run and a hand edit is lost.
`GH_BIND` and `PHOTON_BIND` do the same for routing and the geocoder.)

Re-running the installer is safe. It will not overwrite your database, and it
keeps the invite code it generated the first time — and any SMTP settings you
have put in `detour-sync.service.d/mail.conf`.

The sync service also serves `/admin`, the [manager
dashboard](#the-manager-dashboard): invites, password resets and account
removal, on the same hostname and behind the same Access policy.

---

## Exposing it to your phone

The app needs to reach the server from outside your LAN. Three ways, in the
order I would recommend them.

### 1. Cloudflare Tunnel + Access — no open ports

This is what the author runs. Cloudflare terminates TLS and refuses anything
without a valid service token, so neither service is ever reachable by a
stranger, and you open nothing on your router.

1. Install `cloudflared` on the same machine and create a tunnel
   (Zero Trust → Networks → Tunnels).
2. Add a public hostname per service, pointing at the local port:

   | Hostname | Service |
   |---|---|
   | `sync.example.com` | `http://localhost:8790` |
   | `live.example.com` | `http://localhost:8990` (WebSocket — convoy live location/PTT, optional) |
   | `routing.example.com` | `http://localhost:8989` |
   | `geocoder.example.com` | `http://localhost:2322` |

   The live relay needs its own rule — a WebSocket upgrade doesn't ride along
   on the sync hostname's rule, even though both are the same process. Skip it
   if you don't want the convoy feature; the rest of the server works fine
   without it.

   **Or: one hostname, path-routed.** Each client appends a path the others
   never use, so all four fit behind a single hostname with a `Path` on each
   rule. Order matters — the bare rule matches everything, so it goes last:

   | Hostname | Path | Service |
   |---|---|---|
   | `detour.example.com` | `^/route` | `http://localhost:8989` |
   | `detour.example.com` | `^/api` | `http://localhost:2322` |
   | `detour.example.com` | `^/live` | `http://localhost:8990` |
   | `detour.example.com` | *(none)* | `http://localhost:8790` |

   cloudflared matches the path and passes it through unchanged, which is
   what each origin wants: GraphHopper serves `/route`, Photon serves
   `/api/`, and the relay ignores the path entirely. One hostname means one
   Access application and one address to type into the app. The cost is that
   a future sync endpoint named `/route…` or `/api…` would be shadowed by
   the rule above it.
3. Create a **service token** (Access → Service Auth).
4. Create an **Access application** for each hostname you added, with one
   policy: Action = **Service Auth**, include → that service token. Reuse the
   same token everywhere — the app sends one CF Access ID/secret pair to
   routing, sync, live and the geocoder alike, entered once under Settings.
5. Check it from anywhere:

   ```bash
   # blocked without the token: expect 403, never 200
   curl -o /dev/null -w '%{http_code}\n' https://sync.example.com/health
   # allowed with it: expect 200
   curl -o /dev/null -w '%{http_code}\n' https://sync.example.com/health \
     -H "CF-Access-Client-Id: <id>" -H "CF-Access-Client-Secret: <secret>"
   ```

> **The routing server and the geocoder have no authentication of their own.**
> If you publish either without Access in front, you have published an open
> service anyone can use to burn your CPU or scrape. The sync server does
> authenticate users, but it should still sit behind Access.

If cloudflared runs in a *different* container than the services, `localhost`
will not resolve to them — use the LAN IP, and bind the services to it.

> The sync server's rate limiter reads the real client IP from the
> `CF-Connecting-IP` header, which only Cloudflare can set truthfully. The
> installer sets `TRUST_CF_HEADER=1` in the sync service's systemd unit
> because this topology puts it behind the tunnel; if you deploy the server
> any other way, leave `TRUST_CF_HEADER` unset (or `0`) so a client can't
> spoof that header and dodge the login/registration rate limit.

### 2. Tailscale or WireGuard

Put the phone and the server on the same private network and point the app at
the server's VPN address. Nothing is public at all. Simplest to reason about;
requires the VPN to be up on the phone.

### 3. Reverse proxy with TLS

Caddy or nginx with a real certificate. If you do this, keep registration
invite-gated, and put HTTP basic auth in front of the routing port.

---

## Two credentials, two different jobs

Do not confuse these. Both are involved when the app talks to the server.

| Credential | Issued by | Proves | Shared? |
|---|---|---|---|
| CF Access service token | Cloudflare | you may reach the hostname | **Yes** — the same one is in every copy of the APK |
| Bearer token | the sync server | **which user you are** | No — one per login |

The service token is a lock on the front door. Everyone who uses your server
holds the same key, so it is *not* identity. Identity is the bearer token, and
that is what decides whose trips come back from `/sync`.

### Registration and the invite code

The sync server would otherwise let anyone who reaches it create an account, so
the installer generates a shared invite code and prints it. Enter it in the
app's sign-in screen. It lives in
`/etc/systemd/system/detour-sync.service.d/invite.conf`.

That one code is the same for everybody and never expires. For handing out
access one person at a time, generate single-use codes in the [manager
dashboard](#the-manager-dashboard) instead — a code there works once, can carry
an expiry date, and shows you afterwards who used it.

The server itself fails closed: run `sync_server.py` by hand with no env set
and `/auth/register` returns 403 until you set `REGISTRATION_OPEN=1` or
`INVITE_CODE=...`. The installer sets one of these for you; if you start the
server manually, do the same.

Once everyone you care about has an account, close the door entirely:

```bash
echo 'Environment=REGISTRATION_OPEN=0' \
  >> /etc/systemd/system/detour-sync.service.d/invite.conf
systemctl daemon-reload && systemctl restart detour-sync
```

### The privacy rule

Friends can see each other's **totals and badges**. They can never see each
other's trips or traces. Exactly one endpoint returns another user's data
(`/friends/stats`), and it reads only the stats and badges columns.

The one exception is opt-in: `/friends/fog` returns a friend's fog-of-war
traces, but only when **both** people have turned sharing on. It is off by
default, reciprocal (stop sharing and you stop receiving), and revocable on the
next request. Trips are never returned to anyone but their owner, by any
endpoint.

---

## The manager dashboard

`https://<your sync hostname>/admin` is a small web page for running the
server's accounts: who has one, who gets one, and how someone gets back in
after forgetting a password. It is behind Cloudflare Access like everything
else on that hostname, so reaching it at all already takes your Access login.

**Getting in the first time.** The dashboard has no separate password — you
sign in with your own account, and that account needs the admin flag. Nothing
in the dashboard can grant the first one, so do it on the server:

```bash
sudo -u detour-sync DATA_DIR=/var/lib/detour-sync \
  python3 /opt/detour-sync/sync_server.py --make-admin YOURNAME
```

`--drop-admin YOURNAME` takes it away again. The server refuses to remove the
last admin, from the CLI and the dashboard both — otherwise the only way back
in would be editing SQLite by hand.

**What it does.**

| | |
|---|---|
| **Invite someone** | Generates a single-use code, optionally addressed to an email and expiring after N days (0 = never). Mails it if SMTP is set up, otherwise shows it to you to pass on. The invite list shows which codes are still live and who spent the used ones. |
| **Reset a password** | *Reset mail* sends a `detour://reset?token=…` link that opens the app's reset form; the code is valid once, for an hour. *Set password* changes it there and then and shows you the new one — use it when the account has no email. Either way every device the account was signed in on is signed out. |
| **Emails** | Set or change the address on any account. It is only ever used for reset links; nothing else mails users. |
| **Sessions and keys** | Sign an account out everywhere, or mint/revoke read-only `/ha/*` dashboard keys without touching the phone's login. |
| **Admin rights** | Promote or demote other accounts. |
| **Delete** | Removes the account and everything it owns — trips, traces, points, saved places, friendships, convoy membership, keys, sessions. There is no undo; take a backup first if you might regret it. |

**What it deliberately does not do.** No admin can read anyone's rides. The
dashboard shows counts (trips, trace lines, kilometres from the totals the
user's own app computed) and never the routes themselves — the [privacy
rule](#the-privacy-rule) is not relaxed for the person who owns the server.

**Sending mail.** Fill in the SMTP block in
`/etc/systemd/system/detour-sync.service.d/mail.conf`, then
`systemctl daemon-reload && systemctl restart detour-sync`:

```ini
[Service]
Environment=SMTP_HOST=smtp.gmail.com
Environment=SMTP_PORT=587
Environment=SMTP_SECURITY=starttls
Environment=SMTP_USER=you@gmail.com
Environment=SMTP_PASS=your-app-password
Environment=SMTP_FROM=you@gmail.com
Environment=SITE_NAME=Detour
```

Gmail needs an [app password](https://myaccount.google.com/apppasswords), not
your account password. `SMTP_SECURITY` is `starttls` (port 587), `ssl` (465) or
`none`. Leave `SMTP_HOST` unset and no mail is ever sent — every code and link
is shown in the dashboard instead, which is enough if you can text it to
whoever needs it.

Reset links use a custom scheme rather than an `https://` page on purpose: the
server sits behind Cloudflare Access, and a browser following an `https` link
would hit the Access wall, while the app already holds the service token. If a
mail client refuses to make `detour://…` tappable, the mail also carries
the bare code — paste it into **Friends → I have a reset code**.

**A user can start a reset themselves** with **Friends → Forgot password**,
which needs an email on the account. That endpoint answers identically whether
or not the account exists, so it cannot be used to find out who has an account
here.

**Tuning** (all optional, in the same drop-in file):

| Variable | Default | Meaning |
|---|---|---|
| `RESET_TTL_MINUTES` | 60 | How long a reset link stays valid |
| `INVITE_DEFAULT_DAYS` | 14 | Prefilled expiry for new invites |
| `ADMIN_SESSION_HOURS` | 12 | Idle timeout for the dashboard cookie |
| `SITE_NAME` | Detour | Name used in the mails |

---

## Configure the app

In the app: **Settings → Server**.

```
Sync URL           https://sync.example.com
Routing URL        https://routing.example.com
Search server URL  https://geocoder.example.com   (only if you installed the geocoder)
CF Access ID / Secret     (only for option 1 above; the same pair for all three)
Invite code               (on the sign-in screen, first time only)
```

With the one-hostname layout above, all three URLs are the same address —
`https://detour.example.com`. The convoy relay has no Settings field; it
is baked at build time from `local.properties`, where a single

```properties
server.url=https://detour.example.com
```

supplies all four (`sync.url`, `routing.url`, `geocoder.url` and `live.url`
still override it individually if you kept a hostname per service). See
`routingCfg()`/`serviceUrl()` in `app/build.gradle.kts`.

Then sign in. The app refuses to sync until you do — an un-upgraded phone
silently stops syncing rather than erroring.

---

## Verify

```bash
bash server/verify.sh                       # sync only
INVITE=<code> bash server/verify.sh --routing   # both
```

It creates three throwaway accounts, exercises isolation between users, the
friends privacy guarantee, fog-sharing in both directions, idempotent merging
and the brute-force lockout, then deletes the accounts. `ALL PASS` is the only
acceptable result.

The installer copies it to `/usr/local/bin/detour-verify.sh`.

---

## Choosing an OSM region

`--region` takes a [Geofabrik](https://download.geofabrik.de/) path such as
`europe/belgium`, `europe/netherlands`, `north-america/us/colorado`, or a full
URL to any `.osm.pbf`.

| Extract | Download | RAM for import | Import time |
|---|---|---|---|
| `europe/andorra` | 3 MB | 2 GB | seconds |
| `europe/belgium` | 500 MB | 4 GB | ~10 min |
| `europe/benelux` | 1.5 GB | 6 GB | ~20 min |
| a whole large country | 3 GB+ | 8 GB+ | 40 min+ |

The installer sizes the Java heap at ~70% of available RAM and gives an LXC
8 GB by default. Bigger regions need more; routing quality does not improve
with a bigger extract, so take the smallest one that covers where you ride.

To change region later, re-run with a new `--region`, or edit
`datareader.file` in `/opt/graphhopper/data/config.yml` and delete the graph
(below).

---

## Routing profiles: moto, car, bike

GraphHopper comes up with three profiles, all defined by the installer in
`/opt/graphhopper/data/config.yml`:

- **`moto`** — curvy-road weighting. It leans on GraphHopper's built-in
  `curvature` encoded value, a per-edge sinuosity score. Edges run
  junction-to-junction, so a turn at an intersection is structurally excluded
  from "curviness" — it can never be scored as a curvy road, unlike a
  heading-change heuristic which would count it. Motorways and residential
  streets are penalized (multiplied down), near-straight edges are penalized
  more as `curvature` climbs toward 1.0, on a four-step ladder between 0.85
  and 1.0 — that band is where nearly every road lands. No CH (contraction
  hierarchies) profile — `round_trip` and the app's per-query custom models
  both need flexible routing, which CH doesn't support.

  The weighting only biases GraphHopper's search; which loop you actually get
  is decided in the app, which rolls several seeds per spin and keeps the one
  whose polyline spends the most length in 25–300 m bends
  (`Curviness.routeScore`). That is why these multipliers are deliberately mild: `moto`
  also routes plain A-to-B, and a profile tuned hard enough to guarantee a
  curvy loop would send every ordinary ride the scenic way round.

  **Changing these multipliers needs a re-import, not just a restart.**
  GraphHopper stores each profile alongside the graph and validates it at
  load; an edited `custom_model` makes it refuse to start with
  `IllegalStateException: Profile 'moto' does not match.` (verified on
  GraphHopper 12.0). Delete the graph directory so the next start rebuilds
  it — and make sure you delete the one actually in use, see the
  `graph.location` warning below. Edit the ladder and restart on its own and
  the routing server goes into a crash loop until the config is reverted.
- **`car`** — fastest route, motorways allowed. The app can also POST a
  `custom_model` on this profile (e.g. to avoid motorways) — that only works
  because `car` also has no CH profile.
- **`bike`** — cycling, using `bike_average_speed`/`bike_priority`. Needs
  cycleways and paths in the import, which the routing-only config used to
  exclude — the installer's `import.osm.ignored_highways` only drops
  `footway,pedestrian,steps`, keeping cycleways and paths in the graph.

Do not replace the curvature-based weighting with a heading-change heuristic
— the junction exclusion is the whole point, and it only falls out of using
edges the way GraphHopper already segments them.

---

## Choosing the geocoder's country index

`--geo-country be` (or `nl`, `de`, …) picks a **single-country prebuilt
Photon index** from GraphHopper's public mirror — no local build step, just a
download and extract (~1–2 GB, a couple of minutes). There is no prebuilt
multi-country index (e.g. a Benelux-wide one); covering more than one
country means building Photon from a Nominatim database yourself, which is
out of scope for the installer.

The Photon **jar version is pinned** (`PHOTON_VERSION` in `install.sh`) to
match the index's search-engine format — the two are not independently
upgradable. The mirror's by-country-code dumps are still the legacy
embedded-Elasticsearch line (ES 5.6 / Lucene 6.2), not the OpenSearch line the
1.x jars read, so the pin is **0.4.4** — the last ES-based release — running on
a **Java 11** image (0.4.4's Netty breaks on 21, and the ES 5.6 node needs 11+
class support). A "data dir empty" log means the jar is too new for the
extract; an "incompatible version" log means the dump's schema moved and the
pin needs to follow it.

`photon-refresh.timer` re-downloads the country's newest index monthly, the
same way `graphhopper-refresh.timer` refreshes the routing extract.

---

## Maintenance

**Backups.** Nightly, automatic, 14 days, in `/var/backups/detour`. They
use SQLite's backup API, not `cp` — a plain copy of a live WAL database can be
torn — and each one is checked with `PRAGMA integrity_check`. Run one now:

```bash
/usr/local/bin/detour-backup.sh
```

To restore, stop the service and put the file back:

```bash
systemctl stop detour-sync
cp /var/backups/detour/detour-2026-07-10.db \
   /var/lib/detour-sync/detour.db
rm -f /var/lib/detour-sync/detour.db-wal \
      /var/lib/detour-sync/detour.db-shm
chown detour-sync: /var/lib/detour-sync/detour.db
systemctl start detour-sync
```

If the server lives in an LXC, the container-local backup dies with the
container. Add it to your hypervisor's backup job as well.

**Accounts from the command line.** Everything here is also in the [manager
dashboard](#the-manager-dashboard); the CLI is what you use to create the first
admin, or to get back in if you have locked yourself out. Run them as the
service user so they write the same database:

```bash
sudo -u detour-sync DATA_DIR=/var/lib/detour-sync \
  python3 /opt/detour-sync/sync_server.py --make-admin NAME
```

| Flag | Does |
|---|---|
| `--make-admin NAME` / `--drop-admin NAME` | Access to `/admin` |
| `--set-password NAME` | Prompts for a new password (blank generates one). The way back in when the only admin has forgotten theirs |
| `--revoke-tokens NAME` | Sign an account out everywhere |
| `--api-key NAME [LABEL]` / `--revoke-keys NAME` | Read-only `/ha/*` keys |
| `--backfill-points NAME` | Re-unpack traces into track points |

**OSM refresh.** `graphhopper-refresh.timer` re-downloads the extract monthly
and re-imports. Routing is down for the duration of the import. Force it now:

```bash
systemctl start graphhopper-refresh.service
journalctl -u graphhopper-refresh -f
```

**Forcing a re-import** (after editing profiles or encoded values):

```bash
cd /opt/graphhopper
docker compose down
rm -rf data/graph-cache        # the graph, not the .pbf
docker compose up -d && docker compose logs -f
```

---

## Troubleshooting

**GraphHopper never becomes ready / the container keeps restarting.**
Almost always the import ran out of memory. Raise the heap in
`/opt/graphhopper/docker-compose.yml` (`JAVA_OPTS: "-Xmx8g -Xms1g"`), give the
LXC more RAM, or use a smaller extract. `docker compose logs --tail 50` will
show the `OutOfMemoryError`.

**My config changes to `graph.location` do nothing.**
They do something, but not what you would expect. The image's entrypoint always
appends `-Ddw.graphhopper.graph.location=<--graph-cache value>`, defaulting to
`/data/default-gh`, and a Dropwizard `-Ddw.` property can only *override a key
that already exists* in the YAML. So:

- key present in `config.yml` → the flag wins, YAML value ignored;
- key absent → the flag is silently dropped, and the graph lands in
  `/data/<extract-name>-gh`.

The installer keeps `graph.location: /data/graph-cache` in the YAML *and*
passes `--graph-cache /data/graph-cache` in `command:`. Keep them in sync, or
you will delete a directory that is not the one being used and wonder why your
re-import silently reused the old graph.

**`curl … | bash` fails with 404.** The repository is private, or the branch
does not exist. Clone it and run the script from the checkout — it copies the
files it needs directly and never touches the network for them.

**Docker will not start inside the LXC.** It needs `nesting=1,keyctl=1`. The
installer sets both when it creates the container; if you made the container
yourself, add them (`pct set <id> --features nesting=1,keyctl=1`) and reboot it.

**`POST /auth/register` returns 429.** Ten failed auth attempts from one IP in
five minutes locks that IP out. A wrong invite code counts as a failure.
Successful logins never consume the budget. Wait five minutes, or restart the
service to clear the in-memory counter.

**The app syncs nothing and reports no error.** It refuses to sync until you
sign in. Check Settings → Server.

---

## Upgrading a pre-rename (`maproulette-sync`) install

Before the rename to Detour the server installed itself as `maproulette-sync`,
with its database at `/var/lib/maproulette-sync/maproulette.db`. Re-running the
installer on such a host takes it over: it stops and disables the old service,
copies the database to `/var/lib/detour-sync/detour.db`, and carries across
`invite.conf` and `mail.conf`, so the invite code and SMTP password keep
working. It asks first, unless you passed `--yes`.

```bash
bash server/install.sh --sync
```

Nothing is deleted. `/opt/maproulette-sync`, `/var/lib/maproulette-sync` and
`/var/backups/maproulette` stay where they are, so the way back is
`systemctl enable --now maproulette-sync` (stop `detour-sync` first — they want
the same port). Delete them once the new service has run for a few days.

Two cases it refuses to guess at:

- **Both databases already exist.** It leaves both alone and says so — that
  usually means an earlier re-run stood up an empty `detour-sync` beside the
  real one. Stop `detour-sync`, copy `maproulette.db` over `detour.db`
  yourself, `chown detour-sync:`, start it again.
- **You declined the prompt.** `detour-sync` will then fail to bind port 8790
  while the old service holds it.

The database schema is unchanged by the rename; the new server migrates older
schemas on start regardless.

---

## Migrating from the old single-user server

Earlier versions stored `trips.json` and `traces.jsonl` with no accounts. To
adopt that data, register your account, then attribute the files to it:

```bash
systemctl stop detour-sync
sudo -u detour-sync DATA_DIR=/var/lib/detour-sync \
  python3 /opt/detour-sync/sync_server.py --import-legacy <username>
systemctl start detour-sync
```

It uses `INSERT OR IGNORE`, so running it twice is harmless, and it leaves the
old files where they are. Delete them once the app has synced successfully.

---

## API reference

Everything except `/health` and `/auth/*` needs `Authorization: Bearer <token>`.
Errors are `{"error": "..."}` with a real status code, and the app shows that
string to the user verbatim — so keep any you add plain.

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/health` | — | `ok` |
| POST | `/auth/register` | `{username, password, invite?, email?}` | `{token, username}` |
| POST | `/auth/login` | `{username, password}` | `{token, username}` |
| POST | `/auth/logout` | — | `{}` |
| POST | `/auth/forgot` | `{username}` (or an email) | `{}` — always, mails a link if it can |
| POST | `/auth/reset` | `{token, password}` | `{username}` |
| GET | `/me` | — | `{username, email, stats, badges}` |
| POST | `/sync` | `{trips, traces, badges, stats, shareFog?, deletedTrips?}` | merged `{trips, traces, badges}` |
| GET | `/friends` | — | `{friends, incoming, outgoing}` |
| POST | `/friends/request` | `{username}` | `{status}` |
| POST | `/friends/respond` | `{username, accept}` | `{status}` |
| POST | `/friends/remove` | `{username}` | `{}` |
| GET | `/friends/stats` | — | `[{username, stats, badges}]` |
| GET | `/friends/fog` | — | `{sharing, traces}` |

The `/ha/*` endpoints are the exception: read-only, and authenticated with a
dashboard API key (`?key=` or `X-API-Key`) instead of a login token, so a Home
Assistant config never holds credentials that could write anything.

| Method | Path | Returns |
|---|---|---|
| GET | `/ha/stats?key=` | `{stats, badges, rideCount, lastRideMs, traceSegments}` |
| GET | `/ha/rides?key=[&limit=]` | `{rides: [{startMs, mode, distanceKm, topSpeedKmh, maxLeanDeg, …}]}` (newest first, max 200) |
| GET | `/ha/ride.geojson?key=&start=` | GeoJSON, one Feature per segment |
| GET | `/ha/ride.html?key=[&start=]` | Leaflet page, path coloured by lean |

Mint a key with `sync_server.py --api-key <username> <label>`; revoke every key
for a user with `--revoke-keys <username>`. The manager dashboard does both
without shell access. A ready-made Home Assistant package
(totals, badges, last-ride and recent-rides sensors) lives in
[`homeassistant/`](homeassistant/README.md).

Merging is idempotent. Trips deduplicate on `(user, startTimeMs)`, traces on
`(user, sha256(line))`, badges keep the **earliest** earned date so a reinstall
cannot push it forward. `stats` and `shareFog` are only touched when the key is
present, so an older client cannot blank a user's stats or silently change
their sharing.

The `/admin/*` endpoints are a third case again: a browser session cookie, held
only by accounts with the admin flag. They are the [manager
dashboard's](#the-manager-dashboard) own API, not something the app calls, and
every mutating one also needs the `X-CSRF-Token` header the login handed back.

Passwords are PBKDF2-HMAC-SHA256, 210,000 rounds, per-user salt. Tokens are 32
random bytes stored only as a SHA-256 hash, so a database leak hands over no
live sessions; a token idle for `TOKEN_MAX_IDLE_DAYS` (90) is rejected and
pruned. Password reset links are hashed the same way, single use, and expire in
an hour — and redeeming one signs the account out everywhere, because "someone
else has my phone" is the other reason people reset a password.

# Running Detour for a group of friends

This guide is for one person who wants a Detour server for their riding group
and is not a sysadmin. You rent a small server, run three commands, and send
each friend an invite. Friends, convoys, circles and sync then run on your
server, and nobody can sign up without an invite from you.

Everything here uses `./detour`, the small script in this directory. The full
reference for the stack, every layer and every setting is
[README.md](README.md); you only need it to go beyond this guide.

## What you need

- **A server (VPS)** with Linux, about **2 GB of RAM**, and a public IPv4
  address. That is enough without in-app routing. With routing
  (`--routing benelux`, see [Routing](#routing-off-by-default)) it needs
  about **10 GB of RAM**, and the first start spends about 25 minutes
  building the road graph.
- **A domain name** pointing at that server: one DNS `A` record, for example
  `detour.example.com` → the server's IP address. A free dynamic-DNS name works
  too, as long as it points at the server.
- **Ports 80 and 443 open** to the internet. Caddy, the web server in front,
  uses them to get a free HTTPS certificate from Let's Encrypt and to serve
  the app.

## Install

**1. Install Docker** with the Compose plugin. Docker's own convenience
script is the short way on a fresh Debian or Ubuntu server:

```bash
curl -fsSL https://get.docker.com | sudo sh
docker compose version
```

`docker compose version` must say **2.24.4 or newer**; the HTTPS layer needs
it. You also need `git` and `openssl` (`sudo apt install git openssl` if
they are missing). Run the commands below as a user in the `docker` group,
or put `sudo` in front of each.

**2. Get the files.** Clone the repository and go to this directory:

```bash
git clone --depth 1 https://github.com/Detour-app/Detour.git
cd Detour/docker/prod
```

Every command from here on runs in `docker/prod/`.

**3. Write the configuration.** Use your own domain:

```bash
./detour setup --host detour.example.com --email you@example.com
```

`--email` is optional; Let's Encrypt writes to it when a certificate is
about to expire. Setup writes `.env` with generated passwords and prints the
Keycloak administrator password once. Keep `.env` as it is, `KC_ADMIN_*`
included: `./detour` signs in to Keycloak with those values, and every
command fails without them.

Running setup a second time refuses to overwrite `.env`. Don't use `--force`
on a server that already ran, because it replaces the database passwords and
the existing databases won't accept the new ones.

**4. Start it:**

```bash
docker compose up -d
```

The first start downloads the images and takes a minute or two. Then check it
from any computer:

```bash
curl https://detour.example.com/api/capabilities
```

The answer is a short JSON document whose `idp.issuer` is
`https://detour.example.com/auth/realms/detour`. If `curl` complains about
the certificate, give Caddy another minute and check that DNS points at this
server (`docker compose logs caddy` says why a certificate failed).

## Which ports are open

Only **80 and 443**, plus **SSH (22)** so you can log in. The API, Keycloak
and the databases listen on the server's loopback address only. If your
provider has a firewall, open those three and nothing else. With `ufw`:

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
```

Docker publishes container ports through its own firewall rules, so `ufw` does
not see them. That is why the stack binds everything except Caddy to loopback.

**The Keycloak admin console** is not reachable from the internet:
`https://<host>/auth/admin` answers 403 on purpose. You rarely need it, since
`./detour` covers inviting and removing riders. When you do, open an SSH
tunnel from your own computer:

```bash
ssh -L 7580:127.0.0.1:7580 you@detour.example.com
```

Leave that open and browse to `http://127.0.0.1:7580/auth/admin`. The user is
`admin` and the password is `KC_ADMIN_PASSWORD` in `.env`.

## Invite riders

```bash
./detour invite
```

This prints a message to send to one rider, with the server address, a
throwaway username like `join-3f9a`, and a one-time code. Each invite is for
one person; run it again for the next one.

What the rider does:

1. Install Detour and open **Settings → Servers & sync**. Type the server
   address, `https://detour.example.com`.
2. Go to **Friends** and tap **Sign in**. Use the `join-…` username and the
   code from the invite.
3. Keycloak asks them to pick their own username (3 to 24 letters, digits,
   dots, dashes or underscores), then their own password. From then on they
   sign in with those, and the invite name and code stop working.
4. They can now add each other as friends on the **Friends** screen.

Managing riders afterwards:

| Command | What it does |
| --- | --- |
| `./detour invites` | Lists invites nobody has used yet, half-finished ones (a username was picked but no password), and members still named `join-…`. |
| `./detour revoke join-3f9a` | Cancels an invite nobody has used yet. |
| `./detour disable <user>` | Removes a member: they can't sign in, and every session they have is logged out. Their data stays. |
| `./detour enable <user>` | Lets a disabled member back in. |
| `./detour reset <user>` | Gives a rider who forgot their password a new one-time code, and logs out their sessions. Prints a message to send them. |
| `./detour delete <user>` | Deletes the rider's Keycloak account. |

**When a rider deletes their account in the app**, the app removes their
data from your server, but their login stays in Keycloak, because the kit has
no hook between the two. Run `./detour delete <their username>` to remove it.

A rider who stopped halfway through the first sign-in shows up in
`./detour invites` as "waiting for a password" under the name they picked.
They sign in with that name and the code from the invite, and finish.

## Routing: off by default

Without routing, the server does sign-in, sync, friends, convoys and circles.
Riders don't see **Navigate in app**; they hand a destination to Google Maps or
another navigation app as before, and spin distances are straight-line.

**Turning routing on** costs RAM: about 10 GB for `benelux` (the README's
[Regions](README.md#regions) table lists the others), and about 25 minutes the
first time it builds the road graph. The easy way is setup with `--routing`,
which writes a fresh `.env`. On a server that already runs, edit `.env` by hand
instead, because `setup --force` would replace the database passwords:

1. Add `docker-compose.routing.yml` to the `COMPOSE_FILE` line, before
   `docker-compose.proxy.yml`:

   ```properties
   COMPOSE_FILE=docker-compose.yml:docker-compose.idp.yml:docker-compose.group.yml:docker-compose.routing.yml:docker-compose.proxy.yml:docker-compose.caddy.yml
   ```

2. Set `OSM_REGION=benelux` (or another region from
   [`config/regions.env`](config/regions.env)), and `GRAPHHOPPER_HEAP` to
   match it.
3. Set `ROUTING_BASE_URL=https://detour.example.com`. Without it the server
   doesn't tell the app about routing, and riders still get no **Navigate in
   app**.
4. `docker compose up -d`. `docker compose logs -f graphhopper` shows the
   build.

**Address search** works without anything from you: the app uses the public
search server at `photon.komoot.io`. A rider who turned off **Fall back to
public search** in Settings has no address search on your server. To run
search on your own server, add `docker-compose.search.yml` to `COMPOSE_FILE`
the same way (before `docker-compose.proxy.yml`), set `PHOTON_REGION` to a
country code such as `be`, and set `GEOCODER_BASE_URL=https://detour.example.com`.

## Upgrading and backups

Upgrade with `docker compose pull` and then `docker compose up -d`. The
README's [Upgrading](README.md#upgrading) section covers what a particular
release needs on top; read it before you pull.

Back up the two databases. Losing either can't be undone: `db` holds every
trip and friendship, `keycloak-db` holds every account.

```bash
docker compose exec -T db pg_dump -U detour detour > detour-db.sql
docker compose exec -T keycloak-db pg_dump -U keycloak keycloak > keycloak-db.sql
```

Copy both files, and `.env`, somewhere off the server. `.env` holds the
passwords; keep the copy as private as the server itself. The README's
[Backups](README.md#backups) section lists the one other volume worth keeping.

## Privacy

You run this server, so you are the one who holds your riders' data: their
accounts, the trips they sync, their friends, convoys and circles. It stays on
your machine; nothing is sent to the Detour project. Tell your riders who runs
the server. The app's own policy, including what it sends to other services
like map tiles and public search, is [docs/privacy.html](../../docs/privacy.html).

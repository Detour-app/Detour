# Task: expose the convoy live relay through the tunnel

For whichever Claude instance runs on the home server. This is a scoped,
self-contained task — you shouldn't need anything outside this file and the
repo to complete it.

## Context

The app just gained a "convoys" feature: live friend location on the map
plus push-to-talk, gated by mutual friendship + an accepted convoy invite.
It needs a new always-on WebSocket listener next to the existing sync
server — `server/sync/sync_server.py` now starts a second listener on
`LIVE_PORT` (default `8990`) whenever the optional `python3-websockets`
package is importable. Everything it relays (location, PTT audio) is
in-memory only, never written to SQLite, same privacy posture as the rest of
the server.

`server/install.sh` and `server/INSTALL.md` were updated to provision and
document this (see the "convoy live relay" mentions in both), but **the
installer does not — and cannot — touch your Cloudflare Tunnel/Access
config**, same as it never has for the sync/routing/geocoder hostnames
either. That's the part you're doing here.

Per prior session notes: cloudflared runs in its own LXC (105 as of
2026-07), separate from the service containers — verify this is still the
topology rather than assuming it; it may have changed.

## What "done" looks like

1. The sync server on this host is running the updated code, with the live
   relay actually listening on `LIVE_PORT`.
2. A new public hostname (e.g. `live.<yourdomain>`) reaches
   `http://<sync-host>:8990` through the tunnel.
3. That hostname sits behind the **same** Access service token as the
   sync/routing/geocoder hostnames already do.
4. You can prove the WS relay is reachable *with* the token and rejected
   *without* it — see verification section.

## Steps

### 1. Update and restart the sync server

```bash
# on the host running detour-sync
cd <wherever the repo/install source lives>
git pull   # or however this host tracks the repo — check first
bash server/install.sh   # re-running is safe; see INSTALL.md
```

Re-running `install.sh` is idempotent (documented behavior — it won't touch
your database or the existing invite code) and is what installs
`python3-websockets` and adds `Environment=LIVE_PORT=8990` to the
`detour-sync` systemd unit. If this host doesn't use `install.sh` for
its updates (e.g. it deploys `sync_server.py` some other way), do the
equivalent by hand:

```bash
apt-get install -y python3-websockets
# add to the systemd unit (systemctl edit detour-sync, or the unit file directly):
#   Environment=LIVE_PORT=8990
systemctl daemon-reload
systemctl restart detour-sync
```

Confirm it came up:

```bash
journalctl -u detour-sync -n 30 --no-pager
# expect a line: "detour-live (convoy relay) on 127.0.0.1:8990"
ss -ltnp | grep 8990   # or: python3 -c "import socket; socket.create_connection(('127.0.0.1',8990),1)"
```

If the journal instead says `live convoy relay disabled: run pip install
websockets to enable it`, the package didn't install — fix that before
moving on to the tunnel.

### 2. Add a Cloudflare hostname for the relay

First figure out which kind of tunnel this actually is — don't assume:

```bash
cloudflared tunnel list
# and check for a locally-managed config:
cat /etc/cloudflared/config.yml 2>/dev/null || cat ~/.cloudflared/config.yml 2>/dev/null
```

**If it's locally-managed** (a `config.yml` with an `ingress:` list exists):
add an entry pointing at the sync host's `8990`, following the exact shape
of the existing sync entry (same `service:` scheme, same hostname pattern).
If cloudflared runs in a different container than the sync server (per the
LXC-105 note above), use the sync host's LAN address, not `localhost` —
same as the existing sync/routing entries already have to.

```yaml
ingress:
  # ... existing entries (sync, routing, geocoder) above this ...
  - hostname: live.<yourdomain>
    service: http://<sync-host-lan-ip-or-localhost>:8990
  # existing catch-all 404 entry stays last
```

Then:

```bash
cloudflared tunnel route dns <tunnel-name-or-id> live.<yourdomain>
systemctl restart cloudflared   # or the container-appropriate equivalent
```

**If it's remotely-managed** (dashboard-configured, no useful local
`config.yml`): use `cloudflared tunnel token` / the Zero Trust API, or note
in your final report that this step needs a human in the dashboard
(Zero Trust → Networks → Tunnels → this tunnel → Public Hostname → Add a
public hostname), pointed at the same service address, and stop there —
don't guess at dashboard clicks blind.

### 3. Add an Access application for the new hostname

Same policy as every other hostname this server exposes: Action = **Service
Auth**, one policy including the **same service token** already used for
sync/routing/geocoder. Reuse it — don't mint a new one; the app sends one
token to all of these.

If the tunnel is remotely-managed and you couldn't do step 2 yourself,
this also needs the same human-in-the-dashboard caveat.

### 4. Verify

```bash
# blocked without the token — expect the request to fail/hang or 403,
# never a clean WS handshake
curl -sS -o /dev/null -w '%{http_code}\n' https://live.<yourdomain>/

# a real WS handshake needs a WS client, not curl. Quick python check
# (adjust CF headers if this host uses Access):
python3 - <<'EOF'
import asyncio, websockets
async def main():
    try:
        async with websockets.connect(
            "wss://live.<yourdomain>",
            additional_headers={
                "CF-Access-Client-Id": "<id>",
                "CF-Access-Client-Secret": "<secret>",
            },
        ) as ws:
            print("connected OK — send a bad join, expect an error reply")
            await ws.send('{"type":"join","convoyId":999999}')
            print(await asyncio.wait_for(ws.recv(), timeout=5))
    except Exception as e:
        print("FAILED:", e)
asyncio.run(main())
EOF
```

Expect: `{"type": "error", "message": "not a member of that convoy"}` for a
made-up convoy id (proves the socket authenticated — auth happens before
that check) — or `{"type": "error", "message": "missing bearer token"}` /
similar if you deliberately omit the `Authorization` header, to prove it's
gated at all. If you don't have a live bearer token handy, register a
throwaway test account first (`POST /auth/register`, same as any client) or
just confirm the Access layer alone (step 4's first `curl`) and leave the
authenticated check for whoever tests from the phone.

## Report back

State plainly: whether the relay is running, whether the hostname exists,
whether Access is in front of it, and whether you completed step 2/3
yourself or hit the remotely-managed-tunnel wall and need a human. Don't
mark this done if you had to guess at any credential or skip verification.

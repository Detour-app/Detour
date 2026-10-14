#!/usr/bin/env python3
"""A scripted second rider for the local rig (see README, "Convoys").

    convoy-rider.py route FROM_LAT,FROM_LON TO_LAT,TO_LON [--profile moto] [--kmh 50]
    convoy-rider.py setup
    convoy-rider.py run gap|break [--t0 EPOCH]

`route` asks the rig's GraphHopper for a route and writes it twice: route.json,
which `run` walks, and route.txt, one `lon lat` per second with a standstill
tail, which the replay scripts feed to the phone. The phone must navigate that
same route, so give it the same destination from the same start.

`setup` has `rider` (the account the phone is signed in as) host a convoy named
"Rig convoy" and `rider2` join it by invite link. Safe to repeat.

`run` connects as `rider2` to the real /api/live socket and plays a scenario on
the wall clock, starting at --t0 (epoch seconds; default now). To line it up
with a replay, take the epoch right after the replay starts:

  gap    along the route: rides with the host, falls behind, catches up,
         stops, catches up again, then goes silent. Fires, in order, "fell
         behind" (~90 s), its clear (~140 s), "stopped" (~190 s), "fell behind"
         (~205 s), its clear (~230 s), "signal lost" (~360 s) on a host that
         navigates the route from its start at 50 km/h.
  break  parked 150 m along the route: sends a break request at 15 s and, as a
         non-host, a regroup pin at 60 s (the relay must drop it). Prints every
         frame it receives, so the host's own Break and Regroup taps show up.

Needs `pip install websockets` (15.x). Everything else is the standard library.
Work files go in $RIG_CONVOY_DIR (default: <tmp>/detour-rig-convoy).
"""
import argparse, asyncio, json, math, os, sys, tempfile, time
import urllib.parse, urllib.request

API, KC, GH = "http://localhost:7500", "http://localhost:7580", "http://127.0.0.1:7510"
WORK = os.environ.get("RIG_CONVOY_DIR") or os.path.join(tempfile.gettempdir(), "detour-rig-convoy")
CONVOY_NAME = "Rig convoy"


def hav(a, b):
    p1, p2 = math.radians(a[1]), math.radians(b[1])
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(b[0] - a[0]) / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))


def token(user):
    body = urllib.parse.urlencode(dict(grant_type="password", client_id="detour-app", username=user, password="detour-dev")).encode()
    try:
        return json.load(urllib.request.urlopen(f"{KC}/realms/detour/protocol/openid-connect/token", body))["access_token"]
    except Exception as e:
        sys.exit(f"no token for {user}: {e}\nIs the rig up and has tools/dev-rig/auth-up.sh run?")


def call(user, method, path, body=None):
    req = urllib.request.Request(API + path, method=method, headers={"Authorization": "Bearer " + token(user)})
    if body is not None:
        req.data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req) as r:
        raw = r.read()
    return json.loads(raw) if raw else None


def cmd_route(a):
    (flat, flon), (tlat, tlon) = (map(float, p.split(",")) for p in (a.origin, a.dest))
    url = f"{GH}/route?point={flat},{flon}&point={tlat},{tlon}&profile={a.profile}&points_encoded=false"
    path = json.load(urllib.request.urlopen(url))["paths"][0]
    pts = path["points"]["coordinates"]
    cum = [0.0]
    for i in range(1, len(pts)):
        cum.append(cum[-1] + hav(pts[i - 1], pts[i]))
    step, out, d, j = a.kmh / 3.6, [pts[0]], a.kmh / 3.6, 1  # one point per second
    while d < cum[-1]:
        while cum[j] < d:
            j += 1
        f = (d - cum[j - 1]) / (cum[j] - cum[j - 1])
        out.append([pts[j - 1][0] + (pts[j][0] - pts[j - 1][0]) * f, pts[j - 1][1] + (pts[j][1] - pts[j - 1][1]) * f])
        d += step
    out.append(pts[-1])
    os.makedirs(WORK, exist_ok=True)
    json.dump(out, open(f"{WORK}/route.json", "w"))
    with open(f"{WORK}/route.txt", "w") as f:
        f.writelines(f"{p[0]:.6f} {p[1]:.6f}\n" for p in out + [out[-1]] * 30)
    print(f"{cum[-1]:.0f} m, {len(out)} s at {a.kmh:g} km/h -> {WORK}/route.txt (replay) and route.json")


def cmd_setup(_):
    mine = call("rider", "GET", "/api/convoys")
    convoy = next((c for c in mine if c["name"] == CONVOY_NAME), None) or call("rider", "POST", "/api/convoys", {"name": CONVOY_NAME})
    if not any(m["username"] == "rider2" for m in convoy["members"]):
        link = call("rider", "POST", f"/api/convoys/{convoy['id']}/invite-link")
        call("rider2", "POST", "/api/convoys/join", {"token": link["token"]})
    os.makedirs(WORK, exist_ok=True)
    open(f"{WORK}/convoy.id", "w").write(convoy["id"])
    print(f"convoy {convoy['id']}: rider hosts, rider2 joined. In the app: Social > Friends > {CONVOY_NAME} > Go live.")


GAP = [(20, 50), (110, 25), (170, 75), (205, 0), (250, 100), (330, 50), (385, None)]  # (until s, km/h; None = silent)


async def cmd_run(a):
    import websockets
    route = json.load(open(f"{WORK}/route.json"))
    convoy = open(f"{WORK}/convoy.id").read().strip()
    cum = [0.0]
    for i in range(1, len(route)):
        cum.append(cum[-1] + hav(route[i - 1], route[i]))

    def at(s):
        s, j = max(0.0, min(s, cum[-1])), 1
        while cum[j] < s:
            j += 1
        f = (s - cum[j - 1]) / max(cum[j] - cum[j - 1], 1e-9)
        return route[j - 1][0] + (route[j][0] - route[j - 1][0]) * f, route[j - 1][1] + (route[j][1] - route[j - 1][1]) * f

    t0 = a.t0 or time.time()
    async with websockets.connect("ws://localhost:7500/api/live", additional_headers={"Authorization": "Bearer " + token("rider2")}) as ws:
        send = lambda d: ws.send(json.dumps(d))
        await send({"type": "join", "groupId": convoy})

        async def reader():
            async for m in ws:
                if json.loads(m).get("type") != "positions":
                    print(f"[{time.time() - t0:6.1f}s] <- {m[:200]}", flush=True)
        task = asyncio.create_task(reader())
        while time.time() < t0:
            await asyncio.sleep(0.05)
        s, last, sent = 0.0, time.time(), set()
        while True:
            now, t = time.time(), time.time() - t0
            if a.scenario == "break":
                if t > 150:
                    break
                lon, lat = at(150.0)
                await send({"type": "location", "lat": lat, "lon": lon, "speedKmh": 0, "ts": int(now * 1000)})
                for key, at_s, frame in (("br", 15, {"type": "break_request", "groupId": convoy, "lat": lat, "lon": lon}),
                                         ("rp", 60, {"type": "regroup_pin", "groupId": convoy, "lat": lat, "lon": lon, "label": "non-host"})):
                    if t > at_s and key not in sent:
                        sent.add(key)
                        await send(frame)
                        print(f"[{t:6.1f}s] -> {frame['type']}", flush=True)
            else:
                seg = next((x for x in GAP if t < x[0]), None)
                if seg is None:
                    break
                dt, last = now - last, now
                if seg[1] is not None:
                    s += seg[1] / 3.6 * dt
                    lon, lat = at(s)
                    await send({"type": "location", "lat": lat, "lon": lon, "speedKmh": seg[1], "ts": int(now * 1000)})
                if int(t) % 10 == 0 and abs(t - round(t)) < 0.55:
                    print(f"[{t:6.1f}s] along={s:7.1f} m speed={seg[1]}", flush=True)
            await asyncio.sleep(1.0)
        task.cancel()


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = p.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("route"); r.add_argument("origin"); r.add_argument("dest")
    r.add_argument("--profile", default="moto"); r.add_argument("--kmh", type=float, default=50.0)
    sub.add_parser("setup")
    u = sub.add_parser("run"); u.add_argument("scenario", choices=["gap", "break"]); u.add_argument("--t0", type=float)
    a = p.parse_args()
    if a.cmd == "run":
        asyncio.run(cmd_run(a))
    else:
        {"route": cmd_route, "setup": cmd_setup}[a.cmd](a)


if __name__ == "__main__":
    main()

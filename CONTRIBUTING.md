# Contributing

## Prerequisites

- JDK 17
- Android SDK 35 (compile/target), min SDK 26
- Python 3.8+ if you're touching the sync server (stdlib only, no dependencies
  to install)

## Building

Both Gradle modules build from the repo root:

```bash
./gradlew assembleDebug          # phone + watch debug APKs
./gradlew :app:assembleDebug     # phone only
./gradlew :wear:assembleDebug    # watch only
```

Phone APK lands in `app/build/outputs/apk/debug/app-debug.apk`, watch APK in
`wear/build/outputs/apk/debug/wear-debug.apk`. `assembleRelease` also works
locally without any signing environment set — you'll get an unsigned,
minified release build; see `README.md` for how CI produces a signed one.

No server URLs, API keys, or Cloudflare Access secrets are required to build.
The app takes all of that at runtime (Settings), or from `local.properties`
for a personal local build — see the `routingCfg()` helper in
`app/build.gradle.kts` for the property/env-var names it reads. If your
services sit behind one path-routed hostname (see `server/INSTALL.md`), a
single `server.url` covers sync, routing, the geocoder and the convoy relay;
the per-service keys override it where they're set.

## Running the sync server locally

```bash
DATA_DIR=/tmp/mrtest python3 server/sync/sync_server.py
```

Stdlib only — no virtualenv, no pip install. Point `SYNC_URL` in the app's
Settings (or `local.properties`/CI env) at `http://<your-machine>:8790` to
test against it. See `server/INSTALL.md` for the full self-hosting picture
(GraphHopper routing, the Photon geocoder, exposing any of it safely).

`python3 -m py_compile server/sync/sync_server.py` is the fast sanity check
for any change to the server — no imports fail, syntax is valid.

### `verify.sh`

`server/verify.sh` is the server's test suite: it creates a few throwaway
accounts against a *running* server, exercises the things that must never
regress — per-user isolation, the friends privacy boundary (totals/badges
only, never trips or traces), idempotent sync merging, and the brute-force
login lockout — then deletes the accounts it made. Run it against your local
instance:

```bash
bash server/verify.sh                     # sync only
INVITE=<code> bash server/verify.sh --routing   # also check GraphHopper
```

`ALL PASS` is the only acceptable result. If you change anything in
`sync_server.py` that touches auth, sync merging, or friends, run this before
opening a PR.

## Pull requests

- **One topic per PR.** A security fix and a UI tweak are two PRs, even if
  they're both small.
- **The build must pass.** CI builds the phone and watch release APKs and
  bundles on every change — a red build blocks review, don't ask for an
  exception. A push to `main` additionally signs them, publishes a GitHub
  release, and uploads to Play's internal track (see
  [docs/RELEASING.md](docs/RELEASING.md)); PRs stop at the build.
- If you touched the sync server, run `verify.sh` against a local instance
  and mention the result in the PR description.
- If you touched Android security- or privacy-relevant code (BLE, backup
  rules, credential storage), say so explicitly — those get a closer look.

## Code style

Comments in this repo explain **why**, not what — a line like
`// retry once, the board occasionally drops the first write after reconnect`
is worth its keep; `// increment the counter` is not. This is a deliberate
house style, not incidental: when you add code, add the reasoning behind it,
not a restatement of it. When you touch existing code, keep the comments
that are still true — don't delete or "clean up" a why-comment just because
the line next to it changed, unless the reasoning itself is now wrong.

Beyond that: match whatever the surrounding file already does (naming,
structure, how state is held) rather than introducing a new pattern for one
change.

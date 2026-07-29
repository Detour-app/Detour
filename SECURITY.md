# Security Policy

## Reporting a vulnerability

Please report security issues privately rather than opening a public issue —
this is a small personal project without a bug bounty, but a public issue
about, say, a BLE injection path or an auth bypass gives anyone running the
app a window before a fix ships.

Email: TODO(owner): confirm contact

Include what you found, how to reproduce it, and what you think the impact
is. A fix or a workaround will follow at whatever pace a personal project
allows — this isn't a company with an SLA, but reports are taken seriously.

## Scope

In scope:

- **The sync server** (`server/sync/sync_server.py`) — authentication, token
  handling, per-user data isolation, the friends privacy boundary (friends
  may see totals/badges, never trips or traces), rate limiting, SQL
  injection, anything that could leak or corrupt another user's data.
- **The BLE surface** (`app/src/main/java/com/jellemax/maproulette/ble/`) —
  the GATT peripheral that talks to an external nav display. Known and
  accepted: characteristics are unencrypted and unbonded, so anyone in BLE
  range while the feature is on could read nav/music state or write fake
  telemetry — the threat model is intentionally narrow (someone in BLE range
  of a moving vehicle), and fixing it properly needs pairing support on the
  ESP32 firmware side. Reports about within-range sniffing/injection are
  still welcome, especially if they identify a fix that doesn't require a
  firmware change.
- **App data handling** — anything backed up off-device that shouldn't be
  (see `app/src/main/res/xml/backup_rules.xml` /
  `data_extraction_rules.xml`), credentials stored in a readable place, or
  data sent somewhere the user didn't opt into (see README's "What leaves
  your device").

Out of scope:

- The routing server (GraphHopper) and geocoder (Photon) themselves — these
  are third-party software; report vulnerabilities in them upstream. Their
  *deployment* (exposed with no auth, e.g.) is in scope here.
- Physical access to an unlocked phone.
- Denial of service against a self-hosted server you don't own.

## Supported versions

This is a personal project with one active line of development — the latest
commit on `main` is the only version that gets fixes. There is no LTS
branch.

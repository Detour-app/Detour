# Problem-report filer

`file_reports.py` turns the problem reports riders send (#811) into public
GitHub issues. The backend stores each report privately (BACKEND_SPEC §15.11).
The loop host runs this script on a timer. It lists the reports nobody has
filed yet and strips everything that could identify a rider. It opens one issue
per new crash, or adds a `+1` to the open issue with the same crash signature.
Then it marks the report filed. Standard library only, plus the `gh` CLI signed
in to the repo.

## Usage

```sh
python3 file_reports.py --dry-run   # print what it would file; writes nothing
python3 file_reports.py             # file them
```

`--env` points at a different credentials file (default
`/root/.config/detour/loop.env`). `--repo owner/name` files somewhere other than
the checkout's repo. The exit code is 1 when any report failed. The others are
still filed, and the failed ones are retried on the next run.

Tests, against a fake `gh` and HTTP layer (no server, no GitHub):

```sh
python3 -m unittest tools/problem-reports/test_file_reports.py
```

## Credentials

`loop.env` holds four lines. It is never in the repo:

```sh
DETOUR_API=https://detour.example.org
KEYCLOAK_TOKEN_URL=https://auth.example.org/realms/detour/protocol/openid-connect/token
LOOP_CLIENT_ID=detour-loop
LOOP_CLIENT_SECRET=...
```

Variables already set in the environment win over the file.

The client is a Keycloak **confidential** client, signed in with
`client_credentials`. To create it in the realm:

1. *Clients → Create client*, ID `detour-loop`. Turn on *Client
   authentication* and *Service accounts roles*, and turn every other flow off.
2. *Service accounts roles → Assign role*: the `detour-admin` realm role.
3. *Client scopes → detour-loop-dedicated → Add mapper → Audience*: included
   client audience `detour-api`, added to the access token. The API refuses a
   token without `aud=detour-api` (BACKEND_SPEC §4.2).
4. *Credentials*: copy the secret into `LOOP_CLIENT_SECRET`.

**`detour-admin` can also delete accounts** (BACKEND_SPEC §13). Keep
`loop.env` readable by root only (`chmod 600`), and rotate the secret in
Keycloak if the loop host is ever compromised.

## What gets published

Only the kind, app version, platform, OS, device model, the redacted stack and
note, and the report id. **The event log is never published.**

- **Stack:** only exception class names and `at …` frames. Every message is
  dropped, the first line's and each `Caused by:`'s alike. So is any other
  line: a message can carry an address, a place name or a URL that no regex
  would catch.
- **Note and stack:** these are removed:
  - coordinate-shaped numbers (4+ decimals) and `lat,lon` pairs
  - email addresses, `Bearer …`, JWT-shaped strings and long hex or base64 tokens
  - every username and email on the server (`GET /api/admin/accounts`),
    case-insensitively, as whole words
- **Device fields:** anyone can send any string, so these are cut to a safe
  character set and 80 characters, then redacted the same way.
- The note and the stack sit in a code block that nothing in them can close. A
  note can't `@mention` anyone, link anywhere or plant a marker.

## How issues are chosen

- **Signature:** the sha1 of the top 5 `com.jellemax.detour` frames, or the top
  5 frames when none are ours. Line numbers and native addresses are stripped
  first. It goes at the top of the issue body as
  `<!-- problem-report:sig <hash> -->`.
- **A signed-in report with a stack** goes onto the open issue with the same
  signature as a `+1 (<version>, <device>) · report <id>` comment. With no
  match, it opens a new issue labelled `bug` + `p1-next`.
- **A report with no stack frames** always opens its own issue, labelled `bug` +
  `needs-triage`.
- **An anonymous report** (`reporter: null`) always opens its own issue,
  labelled `bug` + `needs-triage`, under an *Unauthenticated report* heading.
  Anyone can send one. It carries no signature, so a later crash never lands on
  it. It never counts toward escalation and never changes a label.
- **Escalation:** each body and `+1` carries
  `<!-- problem-report:reporter <12 hex> -->`. That value is the sha1 of the
  server's per-account HMAC plus the signature. Salting with the signature means
  nobody can link one rider's reports across issues. When 3 distinct signed-in
  reporters share an issue, `p1-next` is swapped for `p0-now`. A priority the
  owner set by hand is left alone.
- **Retry safety:** each body and `+1` also carries
  `<!-- problem-report:id <uuid> -->`. A run that opened an issue or posted a
  `+1` but died before marking the report filed only marks it on the next run.
  It doesn't post again.

Markers count only at the very top of a body or comment. Report text further
down can't be mistaken for one.

Markers count only in issues and comments written by the account `gh` is signed
in as (`gh api user`). The repo is public: a stranger's comment carrying
reporter markers can't push an issue to `p0-now`, and a stranger's issue
carrying a signature or report id can't take over a crash or get a report
marked filed against it.

## Wiring it into the loop host

The loop host's units are local, not in the repo. Add these two:

```ini
# /etc/systemd/system/detour-problem-reports.service
[Unit]
Description=File new Detour problem reports as redacted issues

[Service]
Type=oneshot
WorkingDirectory=/root/detour-loop
Environment=HOME=/root
ExecStart=/usr/bin/python3 /root/detour-loop/tools/problem-reports/file_reports.py
```

```ini
# /etc/systemd/system/detour-problem-reports.timer
[Unit]
Description=File new Detour problem reports every 30 minutes

[Timer]
OnBootSec=5min
OnUnitActiveSec=30min
Persistent=true

[Install]
WantedBy=timers.target
```

```sh
systemctl daemon-reload
systemctl start detour-problem-reports.service   # once, then check: journalctl -u detour-problem-reports
systemctl enable --now detour-problem-reports.timer
```

Run `--dry-run` by hand first and read what it prints.

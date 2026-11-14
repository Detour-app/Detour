# Invite-only group realm

`group-realm.json` is the realm a group server imports on first start, through
`docker-compose.group.yml` (on top of `docker-compose.idp.yml`). It ships no
users and no self-registration: an administrator hands each rider an invite, a
throwaway username and a temporary password, and the rider picks their own
handle and password on first sign-in. Riders give a username and a password,
nothing else; email, first and last name are not asked for.

This file records the invite mechanism as it was run, and the `kcadm.sh`
commands that drove it. `./detour` (run from `docker/prod/`) scripts them.

## What was run

Keycloak 26.7.5 (the release tarball; the image tag `quay.io/keycloak/keycloak:26.7`
tracks 26.7.x) in `start-dev` on loopback, with this realm imported
through `--import-realm`. The sign-ins went through `detour-app`'s
authorization endpoint with PKCE S256 and `redirect_uri=detour://auth/callback`,
posting each Keycloak form in turn, and exchanged the returned code for a token.
Not run: the Docker image, Postgres, a reverse proxy, a phone.

## Commands

Inside the stack, `kcadm.sh` runs in the Keycloak container against its own
port, signed in as the bootstrap administrator from `KC_ADMIN_*` in `.env`, or one
you created in the master realm (README, "The realm"):

```sh
docker compose exec keycloak /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080 --realm master \
  --user <your administrator>   # prompts for the password
```

With the Caddy layer Keycloak serves under `/auth`, so use
`--server http://localhost:8080/auth`; without `/auth` this login returns
`404 Not Found` (run on Keycloak 26.7.5 with `--http-relative-path=/auth`).

The remaining commands are written as `kcadm.sh …`; prefix them with
`docker compose exec keycloak /opt/keycloak/bin/`.

**Create an invite** (step 1):

```sh
kcadm.sh create users -r detour -s username=join-ab12 -s enabled=true \
  -s 'requiredActions=["UPDATE_PASSWORD","UPDATE_PROFILE"]' -i
kcadm.sh set-password -r detour --username join-ab12 \
  --new-password '<temporary password>' --temporary
```

`create … -i` prints the new user's id. `--temporary` adds `UPDATE_PASSWORD`
again, which is harmless.

**List invites not yet redeemed** (step 4): Keycloak has no query on required
actions, so list users and filter:

```sh
kcadm.sh get users -r detour --fields id,username,requiredActions \
  | jq '[.[] | select(.requiredActions | length > 0)]'
```

The admin API returns the first 100 users by default; a server with more needs
paging.

**Revoke an invite**: `kcadm.sh delete users/<id> -r detour`.

## Result

**Step 2, first sign-in.** `join-ab12` + the temporary password, through
`detour-app`. Keycloak 26.7 asks for the **profile first, then the password**,
whatever order `requiredActions` was given in:

1. *Update Account Information*: one field, `username`, editable, pre-filled
   with `join-ab12`. No email, first name or last name field.
2. *Update password*: `password-new`, `password-confirm`, and a "sign out other
   devices" checkbox.
3. Redirect to `detour://auth/callback?code=…`; the code exchanges for a token
   with `preferred_username = lucas.r`.

The username form enforces the backend's handle rule
(`DetourLimits.UsernamePattern`, 3–24 of `[A-Za-z0-9_.-]`): `ab` gets "Length
must be between 3 and 24.", `bad name!` gets "Use letters, digits, dots, dashes
or underscores only.", and a taken name gets "Username already exists."; the
form stays until the answer is valid.

**Step 3, after redemption.** `join-ab12` + the temporary password: "Invalid
username or password." `join-ab12` + the new password: the same. `lucas.r` +
the new password: straight to the app, no forms. `kcadm.sh get users` shows
`lucas.r` with `requiredActions: []`.

**Step 4, half-finished.** Because the profile comes first, the half-finished
state is "username chosen, password not set", not the other way round. A rider
who renamed `join-cd34` to `mia.k` and closed the browser on the password form
leaves:

- `mia.k` with `requiredActions: ["UPDATE_PASSWORD"]`; `join-cd34` no longer
  exists.
- Next sign-in as `join-cd34` + temporary password: "Invalid username or
  password." The invite name is gone the moment the profile form is accepted.
- Next sign-in as `mia.k` + the **temporary** password: straight to *Update
  password*, then the app.

So an unredeemed invite is a user with any required action left;
`UPDATE_PROFILE` still there means the invite name is still the username,
`UPDATE_PASSWORD` alone means the rider picked a name and needs the temporary
password with that name. An administrator who lost track can look the user up
by id and tell them their own chosen name.

## Import notes

- The file inside the container must be named `<realm>-realm.json`. Keycloak
  26.7 stops on start with "File name / realm name mismatch. group-realm.json,
  contains realm detour. File name should be detour-realm.json", so the overlay
  mounts `group-realm.json` as `/opt/keycloak/data/import/detour-realm.json`.
  `scripts/group-realm.test.sh` checks this.
- An import is skipped when the realm already exists ("Strategy:
  IGNORE_EXISTING"), so restarts are safe and edits to the file after the first
  start do nothing.

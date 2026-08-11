# Why the realm looks like this

`detour-realm.json` carries no comments — Keycloak's importer rejects any field it
does not recognise, including `_comment` keys, and fails the whole boot. The
reasoning lives here instead.

Each setting below replaces something the Python sync server implemented by
hand. The mechanism changes; the rule must not.

## Registration fails closed

`registrationAllowed: false`.

The old server refused registration unless explicitly opened, precisely so that
someone running it with no configuration did not end up with an open front door.
Keycloak defaults the same way here. Opening it is a deliberate act; until then
accounts are created from the admin console.

The old invite-code system has no direct equivalent and is not reimplemented in
this file. If invites come back, they are domain state in the backend gating a
registration flow — not a Keycloak feature.

## Brute-force protection

`bruteForceProtected`, `failureFactor: 10`, waits escalating to 15 minutes.

This is one half of the old per-IP throttle: it caps guesses against a single
account. The other half — capping request volume from one address — is the API's
own `anonymous` rate-limit policy, because Keycloak never sees requests that do
not reach it.

Ten failures matches the old `AUTH_MAX_ATTEMPTS`. `permanentLockout` is off: a
rider locking themselves out permanently by fat-fingering a password is a worse
outcome than a slow attacker.

## Token lifetimes

`accessTokenLifespan: 900` (15 min), session idle and max both 90 days.

The old bearer tokens were rejected and pruned after 90 days idle. The refresh
token now carries that horizon, so a stolen phone stops working on the same
schedule it always did. The access token is short because it is the thing that
travels on every request.

`revokeRefreshToken` with `refreshTokenMaxReuse: 0` makes refresh tokens
single-use: replaying one that has already been exchanged invalidates the
session, which is how a stolen token gets noticed.

## Password reset

`resetPasswordAllowed: true`, `actionTokenGeneratedByUserLifespan: 3600`.

The old server kept exactly one live reset link per account and expired it after
an hour. Keycloak invalidates the previous action token when a new one is
issued, which is the same promise — a second "forgot password" tap cannot leave
two ways in.

A reset also ends existing sessions, which is what the old implementation did
explicitly, because a reset is also the answer to "someone else has my phone".

## Password policy

`length(8)` matches the old 8–200 range. `notUsername` and `passwordHistory(3)`
are additions — cheap, and neither is something the old server could express.

## Roles

Two realm roles. `detour-user` is attached to `default-roles-detour`, so every
account gets it without anyone remembering to.

`detour-admin` replaces the old `is_admin` column. It grants account management
and nothing else: an administrator still cannot read anyone's rides, and that is
enforced by the API not having such an endpoint rather than by this role's
absence of a permission.

## Clients

**`detour-app`** — public, PKCE with S256, authorization-code flow only. A
native app cannot keep a secret, so it does not get one. `directAccessGrants` is
off: the password grant exists only to make `curl` convenient and is a standing
invitation to ship a password form in a client that should be using a browser.

Turn it on in the admin console when you need a token by hand; do not commit it
on.

**`detour-api`** — bearer-only. It is never logged into; it exists so tokens
have an audience to name, and so the API can reject a token minted for something
else. The audience mapper on `detour-app` is what puts `detour-api` in the `aud`
claim.

## The dev rider

`rider` / `detour-dev`, holding both roles.

Import runs once, on an empty Keycloak. A deployment provisioned any other way
never sees this file, so the account cannot leak into one. It is still the first
thing to delete if this file is ever used as the basis for a real realm.

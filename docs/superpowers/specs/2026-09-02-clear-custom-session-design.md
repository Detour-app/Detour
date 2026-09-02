# Clearing the custom server must drop the session too

Issue: #115. Branch: `fix/clear-custom-session`.

## The defect

`RoutingServer` has three paths that can change which realm this device signs in
to. Two of them drop the session when that happens and one does not.

| Path | Compares the effective issuer? | Calls `Auth.clear()`? |
|---|---|---|
| `save()` (`RoutingServer.kt:249`) | yes, via `issuerAfterSave` | yes |
| `rememberDiscoveredIssuer()` (`:301`) | yes, via `issuer(custom, …)` | yes |
| `clearCustom()` (`:340`) | **no** | **no** |

`clearCustom()` is `prefs(PREFS).clear()`, which drops both `idp_issuer` and
`idp_issuer_discovered` in one wipe, and then returns. The stored session
survives it.

The reasoning for clearing is already written down at `save()`'s call site and
is not restated here: tokens are minted by one realm and meaningless to
another, and a refresh token presented to a realm that did not mint it reads as
a replay rather than as a mistake.

### The sequence

With a rider on a custom server and no typed issuer:

1. The API server states realm `G` via the capability probe; `rememberDiscoveredIssuer`
   stores it and the rider signs in against it.
2. The rider taps **Remove custom server**, or imports a config file whose
   `routingUrl` is blank.
3. `prefs(PREFS).clear()` drops both issuer keys.
4. The effective issuer becomes `BuildDefaults.idpIssuer`, or blank. The
   session survives.
5. The next token refresh goes to a realm that did not mint the token it presents.

### Scope

The defect class predates capability discovery — `clearCustom()` already
dropped a *typed* `idp_issuer` the same way. What #106 changed is that it now
drops a second issuer key, and that it sits between the two functions which do
apply the rule, which makes the omission read as intentional.

`clearCustom()` lives in `shared/`, so one fix covers all four call sites:
`app/.../ui/SettingsScreen.kt:1308` and `:1327`,
`app/.../data/ConfigFile.kt:50` (blank-`routingUrl` import), and
`iosApp/Detour/SettingsScreen.swift:146`.

## Security grounding

Checked against the OWASP corpus (ASVS 5.0.0, cheat sheets `20260805`) rather
than from recall, because "clear the session" has a second dimension that is
easy to miss — *what else* counts as session state.

- **ASVS 5.0.0 V7.4.1 (L1)** — when session termination is triggered, the
  application must disallow any further use of the session. `Auth.sessionEpoch`
  is this codebase's mechanism for that, and `Auth.clear()` bumps it.
- **ASVS 5.0.0 V14.3.1 (L1)** — authenticated data must be cleared from client
  storage when the session is terminated, not just the credential.

The second is the dimension worth naming, and the codebase already satisfies
it: `Auth.clear()` blanks the session *and* resets `FriendsStore`,
`ConvoysStore`, `CirclesStore`, `FriendFog` and `AccountScope`, with a doc
comment explaining that those singletons cache another rider's data and have no
lifecycle of their own.

**So this change must not write any clearing logic of its own.** The control
exists and is more thorough than a fresh one would be; the defect is one path
not calling it. That is the whole fix.

`Capabilities.acceptable()` / `vettedIssuer()` already cover
**ASVS 5.0.0 V10.5.3 (L2)** (reject issuer metadata that does not match what
the client expects). Unchanged here, noted so the next reader does not go
looking for it.

## Approaches considered

**A — compare inline inside `clearCustom()`.** Read `loadCustom()` and
`discoveredIssuer()`, wipe, compare, clear. Smallest diff. Rejected: the
comparison would sit behind `prefs`, which no unit test can reach. The reason
`issuerAfterSave` and `vettedIssuer` exist as separate `internal` functions at
all is that this file deliberately extracts each decision from its I/O so the
decision stays assertable. Inlining here would be the one path with no test,
which is how it got into this state.

**B — extract a pure predicate and call the existing `Auth.clear()`.**
Chosen. Mirrors what the siblings already do and lands the decision somewhere a
`commonTest` can assert.

**C — delegate to `save(ServerConfig(enabled = true))`.** Reuses the rule
wholesale. Rejected: `save()` writes `saved = true` and does not remove the two
Cloudflare keys from the secure store, so it does not mean "remove the custom
server". Changing `clearCustom`'s contract to inherit the rule trades a bug for
a worse one.

## The change

### 1. A pure predicate, in `RoutingServer.kt`

Mirroring `issuerAfterSave`'s shape and its reason for existing:

```kotlin
/**
 * Whether clearing the custom server changes which realm this device signs
 * in to, given the [previous] config and the stored [discovered] issuer.
 *
 * Extracted from [clearCustom] for the same reason [issuerAfterSave] is
 * extracted from [save]: the clear itself calls [Auth.clear] behind `prefs`
 * and is unreachable from a unit test, but the comparison that drives it is
 * the part worth protecting.
 *
 * Both arguments go, so what is left is the baked default — which is why the
 * "after" side takes no config and no discovered value.
 */
internal fun clearDropsSession(previous: ServerConfig?, discovered: String): Boolean =
    issuer(null, "") != issuer(previous, discovered)
```

### 2. `clearCustom()` applies it

```kotlin
fun clearCustom() {
    // Read before the wipe: both inputs live in the prefs this is about to
    // clear.
    if (clearDropsSession(loadCustom(), discoveredIssuer())) Auth.clear()

    prefs(PREFS).clear()
    securePrefs().apply {
        CredentialMigration.SERVER_GROUP.keys.forEach { remove(it.name) }
    }
}
```

Ordering is load-bearing twice over, and both reasons are already established in
this file:

- The two inputs must be read **before** `prefs(PREFS).clear()`, since that is
  the store they come from.
- `Auth.clear()` goes **above** the wipe, matching the discipline `save()`
  documents for its own eviction: each `put`/`remove` is its own async commit on
  Android, so a process death mid-way must not be able to leave a cleared config
  paired with a live session. The reverse order is the unsafe one.

### 3. Tests

In `shared/src/commonTest/.../ServerResolutionTest.kt`, alongside
`changingTheServerAddressDiscardsTheDiscoveredIssuer`, using the existing
`noBakedDefaults()` seam (`BuildDefaults.configure()`):

1. **A discovered issuer is dropped by the clear** — no baked default, previous
   config with a discovered realm → `true`.
2. **A typed issuer is dropped by the clear** — the pre-#106 form of the same
   defect; `ServerConfig(idpIssuer = …)`, blank discovered → `true`.
3. **Clearing back to the same baked realm does not drop the session** — baked
   default equal to the discovered value → `false`. This is the existing rule on
   `Auth.sessionEpoch` (a change that leaves the effective issuer alone must not
   clear) and it has to survive.
4. **No custom server and nothing discovered** → `false`. The idempotent case:
   `clearCustom()` on an already-default install must be a no-op.

Test 3 is the one that stops the fix from being written as an unconditional
`Auth.clear()`, which would sign riders out for no reason.

## Verification

- `:shared:testDebugUnitTest` — the new cases and no regression.
- The iOS caller is source-compatible (no signature change), and `ios.yml`
  builds it on the free `macos-15` runner if `shared/**` is touched, which it is.

## Not in scope

- Whether `clearCustom()` should also drop *non-session* local state. It does
  not today, `Auth.clear()` covers the per-account stores, and widening this is
  a separate question.
- #25's identity-keying problem, which touches the same `Auth` file and is
  independent of this.

## Versioning

`versionName` `1.95.0` → `1.95.1`. A fix with no behaviour or API break, per
`CLAUDE.md`.

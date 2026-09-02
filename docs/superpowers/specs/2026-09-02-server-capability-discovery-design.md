# Server capability discovery, and the sign-in realm as its first capability

Implements [#106](https://github.com/Detour-app/Detour/issues/106). Pointing the app at a
self-hosted deployment currently takes two addresses the rider has to get right
independently, and the second one is a value the first one already knows.

Scope is wider than #106 as filed in one direction and narrower in another, both recorded
under [Decisions](#decisions): the endpoint is a general **capability document** rather than a
single-purpose config route, because a client that outruns a self-hosted server will need this
more than once; and the client-side **gating framework** that document invites is explicitly
not built here, because designing one against a single consumer produces the wrong one.

## The problem, as the code has it

Two addresses, resolved by different rules:

- **Server URL** — `RoutingServer.apiBase()`, which everything under `/api` falls back to.
- **Sign-in realm URL** — `ServerConfig.idpIssuer`. `RoutingServer.issuer()`
  (`shared/src/commonMain/kotlin/com/jellemax/detour/data/RoutingServer.kt:124-134`) picks
  `idpIssuer` then `BuildDefaults.idpIssuer` and deliberately does *not* fall through to
  `url`, with a comment explaining why: a realm URL is never the API base, and letting it
  fall through aims the token exchange at a host with no discovery document.

That exclusion is correct and stays. Its consequence is that `idpIssuer` is the one field
with no default and no way to infer it — while the API server has been configured with the
same value all along. `IdpSettings.Authority`
(`backend/Detour/Detour.Api/Configuration/ApiConfiguration.cs:35`) is `required`, and
`AuthenticationInstaller.cs:23,35` uses it as both `options.Authority` and `ValidIssuer`,
exact rather than prefix. So the rider is asked to retype, character-exact, a string the
server they just pointed at could state.

The cost is visible in the code that exists to apologise for it. The Android settings copy
(`app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt:1226-1234`) spends five lines
warning that the field has no default and that getting it wrong signs the device out. Four
separate dead ends say the same thing a different way:
`app/.../ui/FriendsScreen.kt:156` and `:180`, `iosApp/Detour/SignIn.swift:66`,
`iosApp/Detour/FriendsScreen.swift:396`.

## What ships

### The endpoint

`GET /api/capabilities`, unauthenticated, on the anonymous rate-limit bucket
`docs/BACKEND_SPEC.md` §15.2 already defines for tokenless endpoints. Same trust level as
`/api/health`, which §15.4 documents as unauthenticated on purpose and which
`backend/Detour/Detour.InfraTests/Api/AuthenticationTests.cs:43` already reaches with no
token.

```json
{
  "schema": 1,
  "features": ["idp-discovery"],
  "idp": { "issuer": "https://idp.example/realms/detour" }
}
```

`idp.issuer` is `IdpSettings.Authority` verbatim — the same string the backend pins as
`ValidIssuer`. The server therefore cannot advertise an issuer whose tokens it would then
refuse, which removes a whole class of misconfiguration rather than reporting it.

Two rules make the document usable by clients both newer and older than the server, and both
belong in `BACKEND_SPEC.md` rather than in a comment:

- **Unknown is ignored.** A client skips feature strings and fields it does not know. An old
  app against a new server reads what it recognises and behaves as it did.
- **`schema` bumps only on a breaking change to an existing field.** Adding a feature string
  or a new field is additive and does not bump it. A client seeing a `schema` higher than it
  knows still reads the fields it recognises rather than refusing the document.

**This endpoint is a version-fingerprinting surface.** That is accepted: the realm URL is
already public — riders type it, browsers display it — and a self-hosted open-source server's
version is discoverable regardless. The constraint that follows is a content rule, written
down here because it is the kind of thing that erodes silently: the document carries feature
names and the values a client needs to configure itself against, and nothing else. No
dependency versions, no build strings, no counts, no dependency health. `/api/health` is
where operational detail already lives, and it stays there.

### Resolution in `shared/`

A new `shared/src/commonMain/kotlin/com/jellemax/detour/data/Capabilities.kt`, rather than
more weight on `RoutingServer.kt` (504 lines) or `Auth.kt` (476):

```kotlin
internal object Capabilities {
    /** null on 404, 5xx, timeout, or a body that does not parse. */
    suspend fun fetch(apiBase: String): ServerCapabilities?

    /** Pure: whether a discovered issuer may be used at all. Scheme rules only. */
    fun acceptable(issuer: String): Boolean
}
```

`RoutingServer.issuer()` gains one candidate in the middle of the existing `pick` chain:

```kotlin
fun issuer(custom: ServerConfig?): String =
    pick(custom?.idpIssuer ?: "", discoveredIssuer(), BuildDefaults.idpIssuer)
```

`ServerConfig.idpIssuer` keeps exactly its current meaning — the rider's typed value. The
discovered value lives in a new `idp_issuer_discovered` key in the existing `routing_server`
preference bag, written only by discovery.

That single line is why `Auth` needs no plumbing. `Auth.endpoint()` (`Auth.kt:93-99`) already
builds `$issuer/protocol/openid-connect/$name` from `RoutingServer.issuer(loadCustom())`, so
the token exchange and the token refresh pick up the discovered issuer for free.

### When the issuer is read

```
Sign in tapped:
  typed issuer?   -> use it, no network at all
  otherwise       -> GET /api/capabilities, every time
     issuer back  -> acceptable()? differs from stored? Auth.clear(); store; use
     fetch failed -> stored value, else blank -> actionable error

Auth.refresh():
  stored value only, never probes
```

Every interactive sign-in fetches the issuer fresh. The stored copy is **not** the source of
truth for a new sign-in; it exists because `Auth.refresh()` runs on cold start, possibly
offline, and `Auth.endpoint()` throws `AuthException("No identity provider configured")` with
nothing stored. A signed-in rider with no network has to be able to refresh a token.

This is what makes a moved realm a non-event. A cached issuer used at sign-in could point at
a realm that has since moved, and the failure surfaces as a 404 on the authorize URL with no
lever the rider can pull. Probing at every interactive sign-in means the value was fetched
seconds before it was used. Drift clears the session at sign-in time only — harmless, because
the rider is signing in anyway — and never while the app sits idle.

The stored value is dropped when the resolved `apiBase` changes, since it belonged to the old
server.

### `Oidc` and the compiler as the enforcement

```kotlin
val configured: Boolean get() =
    issuer().isNotBlank() || RoutingServer.apiBase(RoutingServer.loadCustom()).isNotBlank()

suspend fun resolveIssuer(): String
```

`configured` becomes optimistic — true when an issuer is known *or* an API base exists that a
probe might answer. Without this, a fresh install with only a Server URL filled in reports
`configured == false`, the Sign in control never appears, and the probe that would have fixed
it never fires. The old-server case then surfaces as an actionable message at tap time
instead of a silently absent button, which is a strict improvement on today's four dead ends.

`begin(entropy, issuer)` is promoted from `internal` to public. **The single-argument
`begin(entropy)` overload is deleted.** That is the point, not a side effect: left in place, a
caller that skips `resolveIssuer()` still compiles and silently never discovers anything.
Two callers exist — `app/.../auth/AuthBrowser.kt` and `iosApp/Detour/SignIn.swift` — so
making the compiler route both through the resolver is cheap and permanent.

`rememberDiscovered()` goes through the same rule `RoutingServer.save()` already applies
(`RoutingServer.kt:163-170`): an effective issuer that differs from the one in use calls
`Auth.clear()`. Tokens are minted by one realm and meaningless to another, and a refresh
presented to the wrong realm reads as a replay rather than as a mistake.

### Security controls

Retrieved from ASVS 5.0.0 and the OWASP Cheat Sheet Series (pack version 20260805) rather
than recalled.

| Control | Citation |
|---|---|
| The discovered issuer becomes the pinned expected issuer; every later comparison is exact, never a prefix | `ASVS 5.0.0 V10.5.3`, in spirit — see below |
| The ID token's `iss` must equal the pinned issuer before a session is established | `ASVS 5.0.0 V10.2.2` (partial) |
| A discovered issuer is refused unless it is HTTPS, or loopback over cleartext; userinfo and hostless authorities are refused on both | RFC 8414 §2, `OAuth2_Cheat_Sheet#other-recommendations` item 19 |
| An issuer change clears the session | existing rule, `RoutingServer.kt:163-170` |

**`V10.5.3` is not satisfied so much as made inapplicable, and that is the honest reading.**
The requirement is that a client reject authorization-server metadata whose issuer "does not
exactly match the **pre-configured** issuer URL expected by the client". This feature removes
the pre-configuration — that is its entire purpose. What survives is the *pinning* half: once
discovered and stored, the value is compared exactly and never as a prefix. What does not
survive is the part that gave the comparison its authority, namely that a human chose the
expected value out of band.

**So `Capabilities.acceptable()` inherits the whole job `V10.5.3` had assigned to
pre-configuration**, and is the only substantive control on a server-supplied issuer. In
particular the ID-token `iss` check cannot stand in for it: that compares `iss` against the
discovered value itself, so a hostile realm echoing what it advertised passes. Treat anything
`acceptable()` admits as trusted from that point on, and change it with that in mind. This is
why it refuses userinfo in the authority (`http://localhost:8080@evil.example` has host
`evil.example`, and truncating at the first colon would read the attacker's credentials as the
hostname) and refuses a hostless `https://`.

**The trust delta is real and worth naming.** Today a human pre-configures the issuer. After
this change the server names it, so a compromised API server — which already receives the
rider's tokens — can also choose the page the rider types their **password** into. Token
theft becomes credential theft. The mitigations are that the rider chose to trust that server
with their data in the first place, that both platforms' sign-in browsers display the host,
and that the controls above bound what a wrong answer can do. Note the second mitigation holds
by grace of the browser rather than by anything in this codebase — Custom Tabs and
`ASWebAuthenticationSession` strip userinfo and show the real origin — which is a further
reason `acceptable()` refuses that shape itself rather than relying on the display.

**`V10.2.2` is partially met, deliberately.** The requirement asks a client that can interact
with more than one authorization server to defend against mix-up attacks, "for example, it
could require that the authorization server return the 'iss' parameter value and validate it
in the authorization response and the token response". This change implements the token-response
leg only. The `openid` scope is already requested (`Oidc.kt:102`, `scope` = `openid profile
email`), so the token response carries an `id_token`; its `iss` is compared for exact equality
with the pinned issuer in `Auth.exchangeCode`, between the `post` and the
`store(response, establishesSession = true)` on `Auth.kt:186`.

The authorization-response leg is out of scope because it would depend on the rider's Keycloak
emitting `iss` on the callback. An older realm would then break sign-in, and tolerating the
parameter's absence is the bypass — it would introduce a second server-version compatibility
problem, on the realm this time, to close a hole the first one leaves open anyway. This
belongs in the pull request description, not in a source comment.

**Neither the scheme check nor the `iss` check has anything to extend.** `grep` across
`shared/src/commonMain` finds no HTTPS-scheme guard and no `iss` validation. `Oidc.isCallback`
(`Oidc.kt:124-125`) is the only exact-match guard in the flow and is the pattern to imitate —
it matches the whole redirect URI rather than a prefix, for reasons its own doc gives.

Note that `Auth.usernameFrom` and its sibling (`Auth.kt:454-458`) decode token payloads
*without* verifying signatures, on the documented grounds that "the API is the party that has
to verify it, and does". That rationale survives this change — the backend's `ValidIssuer` is
still exact — but it is no longer the only thing standing between a wrong realm and a
session, which is what the `iss` check above adds.

### The localhost carve-out

`BuildDefaults.idpIssuer` documents `http://localhost:7580/realms/detour` as the dev value
(`BuildDefaults.kt:34-39`). `Capabilities.acceptable()` refuses a non-HTTPS issuer except that
documented loopback shape, because a plain-HTTP realm anywhere else is an invitation to swap
the signing keys in transit — which `IdpSettings.RequireHttpsMetadata`'s own doc comment
already says on the server side.

**The requirement HTTPS-or-nothing comes from is RFC 8414 §2**, verified verbatim: an issuer
identifier "is a URL that uses the `https` scheme and has no query or fragment components"
(the same rule appears in OIDC Discovery 1.0 §3). That is worth citing precisely rather than
by feel, because it also settles a case that would otherwise look merely fail-closed: an
issuer carrying a query component is refused *by definition*, not as a judgement call.

**The reason for the loopback carve-out is that the traffic never leaves the device**, so there
is no on-path attacker to defend against. RFC 8252 §8.3 states exactly that rationale —
"acceptable for loopback interface redirect URIs as the HTTP request never leaves the device"
— so the argument has a standards basis by analogy, though only by analogy: §7.3 and §8.3
govern a native client's own loopback **redirect URI**, not an authorization server reachable
over cleartext, and nothing in RFC 8252 requires TLS on an authorization server's endpoints.

Two things this replaces, both of which were wrong in an earlier draft of this document and are
recorded here because a security rationale that was confidently wrong once will be trusted
again. RFC 8252 §8.6 was cited as requiring TLS on AS endpoints; §8.6 is "Client
Impersonation" and says nothing about transport. And this section claimed there was no
standards carve-out to lean on at all, which understated §8.3. The device-local argument was
always the right one — it is self-limiting, where an appeal to a redirect-URI carve-out is
not, since a future reader cannot stretch "never leaves the device" to cover a non-loopback
host.

The set is deliberately narrower than "loopback": `http://[::1]`, `http://127.0.0.2`,
`http://127.1` and the integer-collapsed forms are all refused even though each is genuinely
loopback. Fail-closed is the safe direction, only the `localhost` form is documented, and a
loopback issuer on a real phone points at the phone rather than at anyone's dev machine.
Widening it needs a better reason than symmetry.

### User-facing copy

Both settings screens keep the **Sign-in realm URL** field and mark it deprecated, replacing
the five-line footgun warning that exists only because the field had no default:

> Deprecated — newer servers tell the app which realm to use. Leave empty unless your server
> has not been updated.

Android: `app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt:1219-1225`.
iOS: `iosApp/Detour/SettingsScreen.swift:139`.

The four dead ends (`FriendsScreen.kt:156`, `FriendsScreen.kt:180`, `SignIn.swift:66`,
`FriendsScreen.swift:396`) and the sign-in path gain the old-server message, which is
actionable where the current wording is not:

> Your server did not say which realm to sign in to. Update the server, or set the realm URL
> in Settings.

## Compatibility

The async case is the reason this design looks the way it does. Somebody hosting their own
server updates it when they get to it, which may be months after the app updates, and there is
no coordination point between the two.

| App | Server | Realm field | Result |
|---|---|---|---|
| new | new | blank | probes, signs in — the point of #106 |
| new | new | set | typed value wins, no probe at all |
| new | **old** | blank | 404 → actionable message, retried on every attempt |
| new | **old** | set | unchanged from today |
| old | new | either | reads what it recognises, ignores the rest |
| new | new, realm since moved | blank | picked up on the next sign-in, session cleared, no rider action |
| new, offline | — | blank | stored value keeps refresh alive; a *new* sign-in needs the API |

Row three is the one that matters. A 404 is not cached: the rider updates their server
whenever they get to it, taps Sign in, and it works — no app update, no cache to clear, no
failure state to undo. Row five is why the two document rules above are load-bearing rather
than decorative.

No wire-format break. Existing installs keep the value they typed and keep using it.

## Testing

`commonTest` cannot reach `prefs` — `RoutingServer.loadCustom()` needs a Context that does not
exist in a unit test, which is why `Oidc.begin(entropy, issuer)` is `internal` today. The pure
resolution logic therefore takes its inputs as arguments, and the same trick applies to the new
code.

Extending `ServerResolutionTest` and `OidcTest`:

- discovered issuer used when nothing is typed
- a typed issuer overrides a discovered one
- discovery fails and nothing is typed → blank, today's behaviour
- an issuer change clears the session
- a non-HTTPS discovered issuer is refused
- the documented localhost dev issuer is allowed
- an ID token whose `iss` does not match the pinned issuer refuses the sign-in
- a change of server address drops the stored discovered issuer

`Detour.InfraTests`: `/api/capabilities` is reachable with no token and returns the configured
authority, mirroring `AuthenticationTests.cs:43`.

## Decisions

**A capability document, not `/api/config`.** A single-purpose route would have been smaller.
But a client that ships ahead of a self-hosted server will need to ask "does this deployment
support X" more than once, and the wire format is the expensive part to change later. Shipping
the schema-versioned document now, with the issuer as its first entry, costs little and settles
that format while there is exactly one consumer to migrate.

**The gating framework is a follow-up, not part of this.** The obvious next step is a
`Capabilities.has()` query, a documented fallback policy (hide the control, render it
differently, degrade the behaviour), guidance in `CONTRIBUTING.md`, and a skill. It is not in
this branch: designing that ergonomics against a single consumer is how the wrong ergonomics
gets fixed in place. It gets designed against two or three real consumers instead. #106 does
its one check by hand.

**Annotation-based gating is not available on iOS, and the follow-up issue records that.**
Kotlin annotations are not exported to Swift in any gating-capable form, and Kotlin/Native has
no reflection, so nothing can read an annotation at runtime to decide whether a function is
live. A compiler plugin could gate at build time, but a capability is a property of the server
the rider chose at runtime, so build time is the wrong seam regardless. Runtime capability
gating in this project has to be an explicit query at the call site, in Kotlin and Swift both.

**Probe every interactive sign-in; store only for refresh.** A cache-first design saves one
small GET and pays for it with a class of failure that has no rider-visible lever: a stored
issuer whose realm has moved produces a 404 on the authorize URL. A TTL narrows that window
without closing it, and buys a scheduler on both platforms plus a path that can sign somebody
out while the app is idle. Probing at the moment of use closes it outright, and the stored copy
still does the one job that genuinely needs persistence — letting `Auth.refresh()` build a URL
offline.

**The deprecated field keeps no removal version.** It stays, still wins over discovery, and
says it is deprecated. Whether the deployment it exists for — API unreachable but realm
reachable — is real enough to keep supporting is a question the issue tracker can answer later.
Naming a removal version now would be committing to delete the only fallback on no evidence.

**The single-argument `Oidc.begin` is deleted rather than kept for compatibility.** Keeping it
would leave a compiling path that skips discovery entirely. There are two callers; the
compiler pointing at both is worth more than the source compatibility.

## Scope

| Area | Change |
|---|---|
| `backend/` | `Controllers/CapabilitiesController.cs`, `Contracts/CapabilityContracts.cs`, one infra test |
| `docs/` | `BACKEND_SPEC.md` capabilities section (schema rules, content rule), `backend/INSTALL.md` note beside `Idp:Authority` |
| `shared/` | new `Capabilities.kt`; edits to `RoutingServer.kt`, `Oidc.kt`, `Auth.kt` |
| Android | `SettingsScreen.kt`, `FriendsScreen.kt`, `AuthBrowser.kt` |
| iOS | `SettingsScreen.swift`, `FriendsScreen.swift`, `SignIn.swift` |
| Tests | `ServerResolutionTest.kt`, `OidcTest.kt`, one `Detour.InfraTests` case |

`versionName` `1.93.2` → `1.94.0`: a backward-compatible feature, per `CLAUDE.md`.
`versionCode` is CI-stamped and untouched.

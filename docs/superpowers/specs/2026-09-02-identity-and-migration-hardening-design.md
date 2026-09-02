# Identity fallback and migration completion

Issues: #25 (partly), #132. Branch: `fix/identity-username-fallback`.

Two changes that both protect *which rider a piece of data belongs to*. They are unrelated in
code and grouped only because they are small and land together.

## Part 1 — #132: `migrateOnce()` must not return before migration completes

### The defect, and why it is worse than the issue says

`CredentialMigration.migrateOnce()` guards with a compare-and-swap:

```kotlin
fun migrateOnce() {
    if (!migratedOnce.compareAndSet(false, true)) return
    migrateGroup(prefs("settings"), SESSION_GROUP)
    migrateGroup(prefs(RoutingServer.PREFS), SERVER_GROUP)
}
```

Exactly one caller runs the migration — but the loser **returns immediately**, while the
winner is still copying. #132 recorded that as a latent nicety. It is not.

`Settings.init()` calls `migrateOnce()` and then reads the secure store on the very next lines:

```kotlin
CredentialMigration.migrateOnce()
val persistedKey = secure.string("auth_scope_key")
val storedKey = AccountScope.keyAtLaunch(
    storedKey = persistedKey,
    refreshToken = secure.string("refresh_token"),
    accessToken = secure.string("access_token"),
    username = secure.string("auth_username"),
)
```

Its own comment states the dependency:

> Before the key is read below, not after it. `access_token`, `refresh_token` and
> `auth_username` are all in `CredentialMigration.SESSION_GROUP`, so on an install still
> holding plaintext credentials they live in the `settings` bag until this runs — reading them
> earlier would derive a key from three empty strings on precisely the installs the derivation
> exists for.

So the sequence is:

1. `RoutingServer.loadCustom()` (from an IO coroutine — `Api.kt`, `Geocoder.kt`, `SyncClient.kt`)
   wins the CAS and begins migrating.
2. `Settings.init()` loses, returns immediately, and reads the secure store.
3. On a pre-migration upgrade install the values are not there yet, so all three read empty.
4. `AccountScope.keyAtLaunch` derives a key from nothing and the install lands on `_local`.

That is the failure `AccountScope`'s own doc calls #73: `accounts/_local` holds this rider's
whole history unclaimed, "and the next account to sign in adopts it, renders A's trips,
traces, badges and saved places as their own, and uploads them into their own server account
on the first sync."

Reachable because ordering is not guaranteed: `initSharedCore` documents that a Service may
start the process without `Settings.init()` running first, which is exactly why both call
sites call `migrateOnce()` and neither can be dropped.

**Severity is therefore data attribution on the upgrade path, not tidiness.** That is what
justifies the cost below.

### Why the cheap fixes do not work

- **Let both callers migrate.** `step` cannot run twice per process per group: two concurrent
  calls can have one arm the marker while the other deletes plaintext it has not copied. That
  is the defect found on hardware that per-group markers exist to prevent.
- **Spin-wait on a second atomic.** The winner's work includes a Keystore read measured at
  1.6–1.8 s, so the loser would busy-wait for seconds, and a winner that throws leaves it
  spinning forever.
- **`kotlinx.coroutines.sync.Mutex`.** In `commonMain` (coroutines is a dependency), but
  suspending; both call sites are non-suspending and `runBlocking` is not in `commonMain`.

### The change

Add a lock to `Platform.kt`. Its doc opens "The three things the shared core needs from
whatever OS it is running on" — this makes it four, deliberately, and the doc says why.

```kotlin
/**
 * Mutual exclusion, for the one thing in the shared core that needs it.
 * ...
 */
expect class PlatformLock() {
    fun <T> withLock(block: () -> T): T
}
```

`androidMain`: a `ReentrantLock`. `iosMain`: an `NSLock`.

`migrateOnce()` becomes:

```kotlin
private val lock = PlatformLock()
private var migrated = false

fun migrateOnce() = lock.withLock {
    if (migrated) return@withLock
    migrated = true
    migrateGroup(prefs("settings"), SESSION_GROUP)
    migrateGroup(prefs(RoutingServer.PREFS), SERVER_GROUP)
}
```

The `AtomicBoolean` goes: the lock provides both the mutual exclusion and the completion
barrier the CAS could not, and a plain `var` inside the lock is correct. That also drops the
`@OptIn(ExperimentalAtomicApi::class)` that #43 introduced.

**The cost, stated rather than hidden:** the loser now blocks until the winner finishes, up to
the ~1.6–1.8 s Keystore read, and on the upgrade path that can be the main thread. That is the
point — a rider waiting is correct where a rider silently adopting the wrong account bucket is
not — and it happens once per process, only on installs that still hold plaintext.

No re-entrancy risk: `migrateGroup` reaches `prefs`, `securePrefs` and `groupHasPlaintext`,
none of which call `migrateOnce`.

## Part 2 — #25, items 3 and 4 only

### Premise, re-checked

#25 was filed before several things landed. Two of its claims are now false and the plan must
not repeat them:

| Claim | Verdict |
|---|---|
| "A grep for the `sub` claim across clients returns **zero hits**" | **REFUTED** — `Auth.subjectFrom()` (`Auth.kt:530`) reads it; `AccountScope.keyFrom()` uses it |
| "the resulting `id_token` is currently discarded" | **REFUTED** — `Auth.idTokenIssuer()` (`Auth.kt:509`) reads it |
| The silent `ifBlank` fallback | **HOLDS** — `Auth.kt:447` |
| 5 client identity comparisons on username | **HOLDS** |
| Server relationships keyed on username | **HOLDS** |
| `editUsernameAllowed` undocumented for prod | **HOLDS** — 0 mentions in `docker/prod/README.md` |

### Scope: items 3 and 4 here, items 1 and 2 split out

The issue's directions 1 and 2 — re-keying relationships on `sub` server-side, and the client
reading `sub` for identity comparisons — are one change, not two: the five client comparison
sites compare against payload fields (`member.username`, `friend.username`, `place.owner`), so
there is no client-only version. The server must return an id first. That is a wire-protocol
change plus a data migration and belongs in its own issue.

Items 3 and 4 are self-contained and are the sharp edge.

### Item 3 — the silent stale fallback

```kotlin
val username = usernameFrom(access).ifBlank { Settings.authUsername.value }
```

`usernameFrom` splits the **access token**, base64-decodes the payload and reads
`preferred_username`. Access tokens are the resource server's artifact — RFC 9068 and the
OAuth 2.0 BCP both say clients should treat them as opaque — so their shape is not a contract
this client can rely on. Things that make it return blank, none of them code changes here: an
opaque or encrypted access token, a Keycloak version that changes the layout, a client scope
that drops the claim.

When it does, the value does not go blank — it keeps whatever was there before. **On an
account switch the second rider carries the first rider's username**, and every one of the
five comparisons then evaluates against the wrong identity, including the ownership check at
`CirclesScreen.kt:541`.

**The fix:** fall back only when the token belongs to the same account that is already stored.
`AccountScope.keyFrom(subject, username)` is `subject.ifEmpty { username }` hashed — so when
the subject is present the username is not an input, and `keyFrom(subject, "")` can be compared
against the persisted `auth_scope_key` to answer "same rider?" without storing the subject
itself.

```kotlin
val subject = subjectFrom(access)
val parsed = usernameFrom(access)
val username = parsed.ifBlank { carriedUsername(subject, secure.string("auth_scope_key")) }
```

where the decision is extracted as a pure, testable function:

```kotlin
/**
 * The stored username, but only when [subject] identifies the same rider the
 * stored bucket belongs to. Blank otherwise — a blank name is a visible bug,
 * carrying the previous rider's name into a new session is a silent one.
 */
internal fun carriedUsername(subject: String, storedScopeKey: String, stored: String): String
```

Returning blank rather than throwing is deliberate: a session that cannot name its rider is
degraded, not invalid, and refusing to store the token would sign out a rider whose realm
merely stopped sending a claim. What it must not do is name them as somebody else.

### Item 4 — document the load-bearing realm setting

`editUsernameAllowed: false` (`docker/dev/config/keycloak/detour-realm.json:12`) is what keeps
the username stable enough to key on today. `docker/prod/README.md`'s realm checklist covers
roles, both clients, a username policy and an administrator, and never mentions it — and
`docker/prod` imports no realm, so production's is created by hand. Add it to that checklist,
saying what breaks if it is on.

Keycloak's own default is `false`, so a hand-created realm inherits the safe value; this is
documentation of an existing dependency, not a change of behaviour.

## Testing

`commonTest`, both parts:

- `carriedUsername` — same subject as the stored key returns the stored name; a *different*
  subject returns blank (the account-switch case); a blank subject with a stored key returns
  blank; no stored key returns blank.
- `migrateOnce` — the existing `CredentialMigrationTest` must stay green. The lock is not
  directly assertable in `commonTest` (no threads there), so its correctness rests on review
  plus the two `actual` implementations being one-liners over `ReentrantLock` and `NSLock`.

Honest limit: the concurrency fix is not covered by a test that fails without it. An Android
instrumentation test with two threads could cover it; that is out of scope here and named as a
follow-up.

## Not in scope

- #25 items 1 and 2 — server re-keying on `sub` and the client comparisons that depend on it.
  Split into its own issue.
- #131 — see the branch's PR description; it needs a Fold 3, not code.

## Versioning

`versionName` `1.97.0` → `1.97.1`. Both parts are fixes with no behaviour or API break.

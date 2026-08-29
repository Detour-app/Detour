# Account-scoped local stores

Closes #73. The first slice after the four-slice iOS parity stack, and stacked on it — the
`Auth.clear()` choke point and the `sessionEpoch` machinery this builds on only exist there.

## The defect, in one line

`shared/src/commonMain/kotlin/com/jellemax/detour/data/Files.kt:17`

```kotlin
internal fun appFile(name: String): Path = appFilesDir() / name
```

Fixed names, no account dimension. Every rider who signs in on a device shares one set of files,
and `SyncClient.sync()` then faithfully uploads whatever is on disk to whoever is currently signed
in — which is exactly wrong once the data on disk belongs to somebody else. A second rider's first
sync uploads the first rider's entire ride history, fog traces, badges and saved places (which
include home and work addresses) into their own server account, and the merge renders them back as
their own. Silently: no prompt, no warning, nothing in either rider's UI that says it happened.

`Auth.clear()` is documented as the choke point for account-scoped state and resets only in-memory
singletons. No file store consults `authUsername` — `grep -rl authUsername shared/src/commonMain/`
returns `Auth.kt` and `Settings.kt` and nothing else.

## What makes this tractable

**`appFile` is the only path constructor.** 29 call sites, all inside
`shared/src/commonMain/kotlin/com/jellemax/detour/data/`, all going through that one function.
Nothing in `app/`, `iosApp/` or `wear/` builds a store path itself. So the account dimension can be
introduced in one place rather than at every store.

**The stores split cleanly on caching**, which decides how much a session switch has to do:

| Store | Holds state across a session? |
|---|---|
| `TripStore.load()`, `Badges.load()`, `TraceStore.loadAll()`, `RecentSearchStore.load()` | **No** — read-through, hit the file every call |
| `SavedPlaces` | Yes — `_places` StateFlow behind a `loaded` latch (`:26-28`) |
| `Routes` | Yes — `_routes` StateFlow behind a `loaded` latch (`:108-110`) |
| `Coverage` | Yes — `@Volatile cache` and `misses` (`:118`, `:129`) |

Read-through stores are correct for free once the path moves. Only three need invalidating.

**A stable key already exists and is already parsed.** `Auth.usernameFrom` (`Auth.kt:375`)
base64-decodes the access token's payload to read `preferred_username`. The `sub` claim is in the
same object, so keying on it costs one sibling function, not a new dependency or a second request.

## Decisions taken

Three were put to the user before designing, because each has a defensible alternative:

**The key is `sha256(sub)` truncated to 16 hex characters.** Stable across a server-side username
change, safe as a filename whatever the IdP issues, and — because the directory name ends up inside
a Google Drive backup — it puts no rider identifier there. The cost is real and is paid in §"What
this makes worse": `run-as … ls files/accounts/` shows opaque hashes.

**Signed-out data lives in `accounts/_local/`, adopted by the first account to sign in.** After any
account has owned data on the device, later sign-ins get their own empty bucket and `_local` is left
alone. This is what makes an existing install's upgrade uneventful: your rides are still yours.

**Cloud backup covers the whole `accounts` subtree.** Android's `<include>` cannot enumerate dynamic
per-account directories, so preserving the current exact-path scope would require a second scoping
mechanism (an owner sidecar) alongside the directory one. Broadening instead is a deliberate scope
change and an improvement: `detour-adb` currently records that badges, saved places, routes and
coverage have **no cloud copy at all**, and this gives them one.

## Design

### 1. Split the path layer, and force the choice at every call site

`appFile` is **renamed** `deviceFile`, and a new `accountFile` resolves under `accounts/<key>/`.
All 29 call sites are edited, each becoming an explicit decision about which scope it wants.

The rename is the point. An unqualified `appFile` that silently means device-scoped is the precise
shape of this defect, and leaving that name in place invites the next store to inherit it — the same
way every store already here did. The extra churn buys a compile error instead of a silent default.

| Scope | Files |
|---|---|
| Account | `trips.json`, `deleted_trips.json`, `edited_modes.json`, `traces.jsonl`, `badges.json`, `saved_places.json`, `routes.json`, `municipalities.json` |
| Device | `recent_searches.json` |

`recent_searches.json` is the one deliberate exception. It is a geocoder convenience cache rather
than ride data, and keeping it at the root keeps it out of cloud backup — search history including
typed addresses is the one thing here worth not putting in Google Drive. It is a smaller leak
between riders on a shared device, accepted knowingly rather than overlooked; see §"What this makes
worse".

### 2. The account key

A new `AccountScope` object in `commonMain` owns the key, adoption and migration, so `Files.kt`
stays what its own doc says it is: whole-file operations over okio, no policy.

- `internal fun subjectFrom(accessToken: String): String` beside `usernameFrom`, same decode.
- `sha256().hex().take(16)` over okio, the same primitive `Oidc.kt` already uses for PKCE S256.
- Persisted in secure prefs alongside the session and read back on cold start, exactly as
  `authUsername` is (`Settings.kt:185`).
- If `sub` is absent, fall back to hashing `preferred_username`.
- **If both are blank, `sync()` refuses.** A sync that does not happen is recoverable; a sync that
  puts one rider's rides into another's account is not. The refusal **throws**, which is
  `sync()`'s existing contract rather than an exception to it: it is already
  `@Throws(Exception::class)` and returns a bare `SyncResult` with no failure channel, and every
  caller — `SettingsScreen`, `HistoryScreen`, `TripTrackingService` via `syncQuietly` — already
  catches. This is the one place in this core that reports failure by throwing; the stores' "return
  a `String?`, never throw" contract from slice B applies to rider-initiated actions with a message
  to show, which this is not.

### 3. Session switch

`Auth.clear()` and `Auth.store()` already bump `sessionEpoch` and reset the slice-B stores. This
extends that same call with the three caching stores plus one version bump:

- `SavedPlaces` — drop `_places`, clear `loaded`
- `Routes` — drop `_routes`, clear `loaded`
- `Coverage` — null `cache`, empty `misses`
- `TraceStore` — bump `_version` so the fog layer redraws against the new account's traces

No new mechanism. The read-through stores need nothing.

### 4. Migration and adoption

One rule each, both idempotent:

**Migration.** Any account-scoped file still at the root moves into `accounts/_local/`, one file at
a time via `atomicMove`. The condition is **per-file, not per-run** — no marker, no "have I migrated
yet" flag — so a partial run simply finishes on the next launch. Runs eagerly from
`Settings.init()`, before any store reads (see §6).

**Adoption.** An account signing in when `accounts/` contains *only* `_local` renames it to that
account's key. "Has any account ever owned data here" needs no new persisted state — it is whether
`accounts/` holds a non-`_local` entry.

The two compose: an existing signed-in install migrates to `_local` and immediately adopts, so the
rider sees no change at all. An existing signed-out install migrates to `_local` and adopts whenever
they first sign in.

> **Correction, found in review after implementation.** The first of those two sentences asserted a
> composition that nothing implemented, and it is the majority upgrade path. Adoption was reachable
> only from `exchangeCode`, which an already-signed-in install never calls. The consequence was not
> cosmetic: cold start 1 migrates the files into `_local` and everything still displays; a routine
> token refresh then persists the real key without moving the scope or adopting; cold start 2 points
> at a bucket that has never existed, and the rider's history, fog, badges, saved places and saved
> routes all read as empty. Writing into the new bucket then makes `adopt` refuse to claim `_local`
> from then on, so the split is **permanent**, and `routes.json` never syncs to the server, so saved
> routes have no recovery path at all.
>
> The fix is a reconciliation in `Settings.init()`: after `migrate()`, if a key is stored, `adopt`
> with it before pointing the scope at it. `adopt` is already idempotent and already no-ops once any
> non-anonymous bucket exists, so it is safe on every other path — including a normal sign-in, which
> still adopts through `Auth.store`.
>
> Worth naming as a pattern rather than a one-off: this section described **what the two rules do
> when composed**, and I read that description as evidence the composition existed. It did not. A
> spec sentence in the present tense is a claim that needs a call site, exactly like the KDoc in
> Task 2 that asserted a `Settings.init()` call before one existed.

### 5. Backup, docs and skills

Both XMLs move to `<include domain="file" path="accounts"/>`, with their comments rewritten to state
what changed and why — those comments currently reason carefully about what should and should not
reach Google Drive, and would otherwise describe a policy that no longer holds.

Also stale the moment the paths move, and updated here:

- `docs/DEBUG_INTENTS.md` — the `run-as … cat > files/trips.json` seeding recipe
- `.claude/skills/detour-trip-data/` — the file table **and** `scripts/check-preconditions.sh`,
  which asserts `TraceStore` still writes `traces.jsonl` and will fail loudly, which is what it is
  for
- `.claude/skills/detour-adb/` — the `filesDir` table and its `run-as` examples
- `.claude/skills/detour-gps-replay/` — its `run-as … cat files/trips.json` verification step

### 6. Error handling

Migration runs **eagerly at `Settings.init()`**, before any store reads, so there is no window in
which a store looks for a file that has not moved yet and therefore **no dual-path read fallback**.

> **Correction, made while planning.** This section first specified a read path that checks the
> account directory and falls back to the root for any file not yet moved. That was solving a
> problem the ordering removes. Worse, a fallback is permanent by nature — every read pays it
> forever to cover a window that lasts one launch — and it is exactly the kind of complexity that
> outlives the reason for it. Eager migration is simpler and self-healing.

A failed move leaves the source file in place, because `atomicMove` is atomic per file, so the next
launch retries it. The cost of a partial failure is that some rides are briefly invisible, not that
any are destroyed, and the retry is unconditional rather than needing a marker.

`CancellationException` rethrown ahead of every generic catch, as everywhere else in this core.

## Tests

`commonTest`, plain `kotlin.test`.

> **Correction.** This first claimed a fake `FileSystem` was already the established pattern, citing
> `Platform.kt:46`. Both halves were wrong: the line is `:64`, and while its doc does say "a fake in
> tests", **nothing in the repo has ever supplied one** — `actual val fileSystem` is
> `FileSystem.SYSTEM` in `Platform.android.kt:58` and `Platform.ios.kt:77`, there is no test actual,
> and `okio-fakefilesystem` is not a dependency. The comment was aspirational and I read it as
> established practice.

The real precedent is better suited anyway: `CredentialMigrationTest` covers a migration by
**pushing the store in as a parameter** — `CredentialMigration.step(plain: Prefs, secure: Prefs,
group: SecretGroup)` is the pure seam, `migrateOnce()` the ambient wrapper that supplies the real
ones. This copies that shape exactly: `AccountScope.migrate(fs: FileSystem, root: Path)` is pure and
testable, `migrateOnce()` supplies `fileSystem` and `appFilesDir()`.

That needs `com.squareup.okio:okio-fakefilesystem:3.9.0` in `commonTest` — a test-only dependency on
okio's own fake, at the version `commonMain` already pins. Preferred over a new `commonMain`
interface for file operations: `detour-shared-core` §4 records that `commonMain` has exactly one
interface (`Prefs`) and that adding a second needs an argument of its own, which this does not have.
It also makes `Platform.kt:64`'s claim true for the first time.

- Key derivation: `sub` present; `sub` absent and `preferred_username` present; both blank.
- `sync()` refuses when the key is blank, and the refusal is returned rather than thrown.
- Migration moves every account-scoped file and leaves `recent_searches.json` at the root.
- Migration is idempotent: running it twice is the same as running it once.
- Migration resumes: with two of five files already moved, the rest move and the moved ones are
  untouched.
- Adoption renames `_local` on a first sign-in, and does **not** fire when a non-`_local` directory
  already exists.
- A second account gets an empty bucket.
- The read-path fallback: a file not yet moved is still found at the root while `accounts/` exists,
  and stops being consulted once it has moved.
- The regression itself: after rider B signs in, rider A's files are neither read nor written.
- The three caching stores drop their state on a session change, and the read-through stores return
  the new account's contents without one.

## Verification, and its limits

- `:shared:testDebugUnitTest` and `:app:testDebugUnitTest` in the devcontainer, plus
  `:shared:compileCommonMainKotlinMetadata`.
- **CI compiles the Swift** — `ios.yml` runs `xcodegen` and two `xcodebuild` passes on macOS
  runners. This was misread as unavailable throughout the four parity slices; it is not.
- **The migration cannot be exercised end-to-end here.** It needs an install with real data at the
  old paths, and the one device available is signed out with a server that has no identity provider.
  The fake-`FileSystem` tests are the coverage of record, and the on-device path is unverified.
- **Never `pm clear` or `adb uninstall` to produce a clean migration state.** Both destroy trip
  history that has no cloud copy. A throwaway AVD is the route; `detour-adb` says why at length.

## What this makes worse, deliberately

Three costs, none of them free:

1. **Opaque directory names.** `run-as … ls files/accounts/` shows `a3f1c8e29b4d7061`, not a rider.
   Reading one rider's data over adb now needs the hash, which means signing in and reading the
   stored key. The skills are updated to say so rather than leaving the next reader to work it out.
2. **A broader cloud backup.** Badges, saved places, routes and coverage gain a Google Drive copy
   they did not have. That is an improvement for restore and an expansion of what leaves the device.
3. **`recent_searches.json` still leaks between riders on a shared device.** Chosen over putting
   typed addresses into cloud backup. It is the smaller of two leaks, not the absence of one.

## Version

`1.83.0` → **1.84.0**. The on-disk layout changes and the migration is one-way, which `CLAUDE.md`'s
table would send to major — but no rider loses data, the migration is automatic and silent, and the
downgrade path that a major bump protects is not one an Android install takes in practice. Recorded
here so the reasoning is visible rather than inferred from the number.

## Follow-ups this creates

1. Prefs are not scoped. `Settings.lastSyncMs` and the per-circle `notifyArrivals` /
   `lastSeenEventTsMs` keys still span accounts. Smaller blast radius than files — no upload path —
   but the same class of defect, and worth its own pass.
2. `_local` is never garbage-collected. A device that has seen an account keeps its anonymous bucket
   forever, invisible in the UI. Deleting it needs a rider-facing decision this slice does not make.
3. #74 (`place_event` frames never parse) and #75 (iOS wall-clock fix age) remain open and are
   unaffected by this.

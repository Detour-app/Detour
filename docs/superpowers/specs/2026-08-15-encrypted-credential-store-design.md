# Encrypted credential store — design

Closes [#26](https://github.com/maxke24/Detour/issues/26) in part: the storage port and the
Android implementation. The iOS Keychain half is deliberately a follow-up.

Session tokens and the Cloudflare Access service token are stored in plaintext, in
`MODE_PRIVATE` SharedPreferences on Android and `NSUserDefaults` on iOS. There is no
encryption anywhere in the tree.

## Preconditions

Run before writing the plan. If any fails, the spec is **stale** — establish whether the
assertion or the code is wrong before adapting. Every value below was produced by running the
command.

```sh
# --- inverted: these assert the gap is still open, and flip when the work lands ---

# 1. No encryption anywhere                                                     -> 0
git grep -lniE 'EncryptedSharedPreferences|AndroidKeyStore|kSecClass|SecItemAdd' -- '*.kt' | wc -l

# 2. Prefs is still an expect class, not an interface                           -> 1
grep -c '^expect class Prefs' shared/src/commonMain/kotlin/com/jellemax/detour/data/Platform.kt

# 3. commonMain still has zero non-sealed interfaces (the house pattern)         -> 0
grep -rl 'interface ' shared/src/commonMain | while read -r f; do \
  grep -h 'interface ' "$f" | grep -qv 'sealed interface ' && echo "$f"; done | wc -l

# --- stable: these must hold before and after ---

# 4. Platform.kt is the only file with expects, and declares exactly four        -> 1, 4
git grep -l '^expect ' -- 'shared/src/commonMain/**' | wc -l
grep -c '^expect ' shared/src/commonMain/kotlin/com/jellemax/detour/data/Platform.kt

# 5. Every prefs() consumer — five call sites across three stores                -> 5
git grep -c '\bprefs(' -- '*.kt' | grep -v Platform | awk -F: '{s+=$2} END {print s}'

# 6. minSdk, which decides the StrongBox guard                                   -> 26
grep -oP 'minSdk = \K[0-9]+' app/build.gradle.kts | head -1

# 7. The shared-core skill's seven assertions still hold                         -> exit 0
.claude/skills/detour-shared-core/scripts/check-preconditions.sh
```

Assertion 3 is the one this work deliberately breaks. `detour-shared-core` asserts commonMain
has **zero** interfaces, and its body calls 33 `object` singletons the house pattern. Adding
the first interface is a documented pattern break, and the skill must be updated in the same
PR — see *Skill correction* below.

## What #26 gets wrong

#26 proposes backing the sensitive store with `EncryptedSharedPreferences`. That API is
**deprecated**. From the official release notes for `androidx.security:security-crypto`
**1.1.0-beta01, 4 June 2025**:

> Deprecated all APIs in favour of existing platform APIs and direct use of Android Keystore.

This is good news rather than an obstacle. Google's replacement — Keystore directly — is what
`Mobile_Application_Security_Cheat_Sheet#android` asks for anyway, and it means **no new
dependency on either platform**. The `androidx.security-crypto` artifact never enters the
build.

## What the guidance actually requires

Retrieved from the OWASP knowledge base rather than recalled. ASVS 5.0.0, Cheat Sheets
`20260724` (`7d1c2d3`).

> **`Mobile_Application_Security_Cheat_Sheet#android`:** "Avoid storing sensitive data in
> SharedPreferences." · "Use Android Keystore with hardware backing (TEE or StrongBox) to
> securely store cryptographic keys." · "Generate keys with hardware backing by specifying
> `.setIsStrongBoxBacked(true)` … (Android 9+)." · "Fall back to regular hardware-backed
> keystore if Strongbox isn't available."

> **`#1-data-encryption`:** "Use platform APIs for encryption. Do not attempt to implement
> your own encryption algorithms." · "Leverage hardware-based security features when
> available."

> **ASVS 5.0.0 V13.3.3 (L3):** Verify that all cryptographic operations are performed using
> an isolated security module (such as a vault or hardware security module).

Android Keystore with TEE/StrongBox *is* that isolated module, so V13.3.3 is reachable here
even though it is an L3 requirement.

**Two citations to avoid.** #26 cites **V14.3.3**, which is scoped to *browser* storage
("localStorage, sessionStorage, IndexedDB, or cookies") and does not literally cover
`SharedPreferences`; the issue body has been corrected. And the OAuth requirements that
surface on this topic — **V10.4.5, V10.4.8, V10.4.9** — are *authorization server*
requirements, i.e. Keycloak's, not this client's. Do not claim them.

The KB carries no guidance on Keychain accessibility classes, so any `kSecAttrAccessible*`
choice in the iOS follow-up comes from Apple's documentation, not from OWASP. Say so there.

## Design

### 1. The port

`expect class Prefs` becomes `interface Prefs` in commonMain, with the same ten methods and
no signature change, so no call site moves.

```kotlin
interface Prefs {
    fun string(key: String, def: String = ""): String
    fun bool(key: String, def: Boolean): Boolean
    fun float(key: String, def: Float): Float
    fun long(key: String, def: Long): Long
    fun put(key: String, value: String)
    fun put(key: String, value: Boolean)
    fun put(key: String, value: Float)
    fun put(key: String, value: Long)
    fun remove(key: String)
    fun clear()
}

expect fun prefs(name: String): Prefs   // unchanged
expect fun securePrefs(): Prefs         // new: the one encrypted bag
```

**The `expect` count stays at four** — `prefs`, `securePrefs`, `appFilesDir`, `fileSystem` —
because `expect class Prefs` becomes an interface. `Platform.kt:11-14` cares about the size of
the platform surface, and this does not grow it.

`securePrefs()` takes no name because there is exactly one secure bag. A name parameter would
be a second way to say the same thing.

### 2. Which values move

Six, from two stores into one:

| Value | From | Why |
|---|---|---|
| `access_token` | `settings` | bearer credential |
| `refresh_token` | `settings` | long-lived credential |
| `access_token_expires_at` | `settings` | session state; kept with the tokens so the session is atomic |
| `auth_username` | `settings` | as above |
| `clientId` | `routing_server` | half of the CF Access service token |
| `clientSecret` | `routing_server` | the credential #7 was about |

`auth_username` and `clientId` are not secrets. They move because a session split across two
stores is harder to reason about than one that is not, and both are tiny. Everything a
developer actually reads while debugging — theme, units, fog radius, zoom, map icon, the
notification preferences — stays in plaintext `settings.xml` and `circle_notify.xml`. That is
#26's inspectability argument, and it is preserved by moving six values rather than by
forking the implementation per build type.

`RoutingServer.clearCustom()` (`RoutingServer.kt:99`) calls `clear()` on the whole store. Once
the two CF fields live elsewhere it must clear both, or "Remove custom server" leaves the
credential behind.

### 3. Android implementation

AES/GCM/NoPadding, 256-bit key in `AndroidKeyStore`, ciphertext Base64'd into an ordinary
app-private SharedPreferences file. The file being unencrypted is fine — the values in it are
not.

```kotlin
KeyGenParameterSpec.Builder(ALIAS, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
    .setBlockModes(BLOCK_MODE_GCM)
    .setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
    .setKeySize(256)
    // API 28+. Throws StrongBoxUnavailableException at generation on a device without
    // one, which is caught and retried without it — the cheat sheet's "fall back to
    // regular hardware-backed keystore".
    .setIsStrongBoxBacked(true)
```

**Do not supply the IV.** `setRandomizedEncryptionRequired` defaults to true, so passing an
IV to `Cipher.init(ENCRYPT_MODE, …)` throws. Let the Cipher generate one, read it back from
`cipher.iv`, and store `IV‖ciphertext`. Decryption passes it back as
`GCMParameterSpec(128, iv)`. This is the detail most likely to compile and fail at runtime.

**No `setUserAuthenticationRequired`.** The cheat sheet suggests it "for sensitive
operations", but this app reads tokens from a foreground service and a background sync while
the screen is off. Requiring user presence would break trip recording. Recorded here so the
omission reads as a decision rather than an oversight.

**All four types are stored as strings.** `put(key, 42L)` encrypts `"42"`; `long(key, def)`
decrypts and parses, returning `def` if the parse fails. One code path, and the type map
stays in the interface where it already is.

### 4. Failure must be soft

A decrypt failure returns the default. It never throws.

The key can genuinely disappear: a device restore, an app-data clear, a Keystore fault, or a
key invalidated by a lock-screen change. If any of those propagated out of `Settings.init()`,
the app would crash on launch and stay crashed — the failure is persistent, so a restart does
not help. Returning "no token" instead means the user signs in again, which is the correct
degradation and the one the OIDC flow already handles from cold.

This is the single most important non-obvious constraint in the design and it earns its own
test.

### 5. Migration

Two phases, so the fallback is never destroyed before the replacement is proven readable.

**First run after the update.** Copy the six values from the plaintext stores into the secure
store and write a marker. **Leave the originals untouched.** Reads continue to work from
either store.

**Every later run.** If the marker reads back successfully from the secure store, decryption
works — delete the six originals. If the marker cannot be read while the plaintext values are
still present, fall back to plaintext and retry the copy.

```
run 1:  plaintext ──copy──> secure + marker        (originals kept)
run 2+: marker readable?  yes -> delete originals
                          no  -> read plaintext, retry copy
```

Idempotent, and safe to interrupt at any point: a half-finished copy is re-run, and nothing is
deleted until a full round-trip through the cipher has been demonstrated on that device.

### 6. Backup rules

The secure store must be excluded from **both** `<cloud-backup>` and `<device-transfer>` in
`app/src/main/res/xml/data_extraction_rules.xml`, and from `backup_rules.xml`.

Keystore keys never leave the device, so a restored or transferred copy is undecryptable. With
soft failure that degrades to "not signed in" rather than a crash — which is the intended
outcome for a new device anyway, and is exactly the argument #26 makes: nothing that needs
encrypting also needs to be portable.

`settings.xml` and `routing_server.xml` keep their current rules. Once the six values leave
them, what remains is ordinary preferences and a server URL.

## Skill correction

`.claude/skills/detour-shared-core/` asserts commonMain has zero non-sealed interfaces, and
its §4 table lists "Interfaces / DI — **Zero interfaces, 33 `object` singletons**" with the
advice "match the pattern". That stops being true here.

The change is licensed by the repo's own guide rather than by this spec. `CONTRIBUTING.md:40`:

> A port earns an interface when it has more than one implementation.

This port has three — plain Android, encrypted Android, plain iOS — so it meets the stated
bar. The skill's §2 test 2 says the same thing, and its script header (`:33-34`) already
cites `CONTRIBUTING.md:40` as the authority.

Update the assertion to expect one interface, and the §4 row to name which one and what
earned it, so the next person finds a precedent with a reason attached rather than a rule
that was quietly dropped. Zero was never the principle — "more than one implementation" was,
and zero was simply the count until now.

## Verification

**Unit.** `SecureStoreMigrationTest` in `commonTest`, over a pure migration function that
takes two `Prefs` and a marker key. The interface is what makes this possible — a fake `Prefs`
is now writable in `commonTest`, which it was not when `Prefs` was an `expect class`. Cases:
first run copies and keeps originals; second run with a readable marker deletes them; an
unreadable marker keeps them and retries; a partial copy completes on the next run; running
twice changes nothing.

**Device.** The crypto path cannot be unit-tested here — `androidUnitTest` has no Keystore,
and there is no instrumented test source set. On a device: sign in, confirm `secure.xml`
contains no readable token; restart, confirm the session survives; confirm the originals are
gone on the second run; clear the Keystore alias and confirm the app degrades to signed-out
rather than crashing.

**CI.** `:shared:compileCommonMainKotlinMetadata` is the check that catches `java.*` leaking
into commonMain and is path-gated on `shared/**`, which this changes. Run it explicitly.

## Out of scope

- **iOS Keychain.** `iosMain` keeps `NSUserDefaults` behind the same interface this PR
  introduces, which is what makes the follow-up self-contained. It cannot be verified here —
  no Swift compiler on this host, and `iosApp/` has no test target — and shipping a
  security-critical implementation on a green compile and a reading is how the phone and car
  drifted apart. Follow-up issue, with the accessibility-class question named.
- **Making `Settings` testable.** `Settings.store = prefs("settings")` still resolves through
  an `expect`, so `commonTest` still cannot fake it. The interface is a precondition for
  fixing that; an injectable factory is the change, and it is not this one.
- **Encrypting `trips.json` / `traces.jsonl`.** #26's own follow-on, and the one case that
  genuinely needs key migration — for account-less users that data never syncs and so is
  irreplaceable.
- **QR config transfer**, #26's other follow-on. Needs a new dependency and a camera
  permission on both platforms.
- **Whether the client should hold a CF service token at all** — [#24](https://github.com/maxke24/Detour/issues/24).

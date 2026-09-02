# Clear the session when the custom server is removed — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `RoutingServer.clearCustom()` drop the session when removing the custom server changes which realm this device signs in to — the rule its two sibling mutation paths already apply.

**Architecture:** Extract the decision as a pure `internal` predicate (`clearDropsSession`) so a `commonTest` can assert it, then call the existing `Auth.clear()` from `clearCustom()` when it returns true. No new clearing logic: `Auth.clear()` already blanks the session and resets the per-account stores.

**Tech Stack:** Kotlin Multiplatform (`shared/`, `commonMain` + `commonTest`), `kotlin.test`, Gradle. All Gradle commands run through `devcontainer-exec`.

**Spec:** `docs/superpowers/specs/2026-09-02-clear-custom-session-design.md`

**Working directory:** `/home/andre/Projects/Detour/.claude/worktrees/fix-clear-custom-session`

---

## Background the implementer needs

`RoutingServer` (`shared/src/commonMain/kotlin/com/jellemax/detour/data/RoutingServer.kt`) resolves the "effective issuer" — the OIDC realm this device signs in to — from three sources in precedence order, via `issuer(custom, discovered)`:

1. `custom?.idpIssuer` — an address the rider typed
2. `discovered` — the realm the API server stated during its capability probe
3. `BuildDefaults.idpIssuer` — baked in at build time

Tokens minted by one realm are meaningless to another, so whenever a mutation changes the effective issuer, the session must be dropped. `save()` and `rememberDiscoveredIssuer()` both do this. `clearCustom()` does not — it calls `prefs(PREFS).clear()`, which wipes sources 1 and 2 in one go, leaving the effective issuer as the baked default while the session survives.

Two existing helpers matter:

- `issuer(custom: ServerConfig?, discovered: String): String` — `internal`, pure, already exists.
- `issuerAfterSave(...)` — `internal`, pure, the same extraction this plan mirrors. It exists because `Auth.clear()` sits behind `prefs`, which no unit test can reach, so the *decision* is extracted and the side effect is not tested.

`BuildDefaults.configure()` resets all baked defaults to blank, which is what the test helper `noBakedDefaults()` in `ServerResolutionTest.kt` calls.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `shared/src/commonMain/kotlin/com/jellemax/detour/data/RoutingServer.kt` | Modify (`:340-346`) | Add `clearDropsSession`; call it from `clearCustom()` |
| `shared/src/commonTest/kotlin/com/jellemax/detour/data/ServerResolutionTest.kt` | Modify (append tests) | Assert the four decision cases |
| `app/build.gradle.kts` | Modify (`:80`) | `versionName` patch bump |

No new files. No signature changes, so the iOS caller (`iosApp/Detour/SettingsScreen.swift:146`) and the three Android callers need no edit.

---

### Task 1: The decision predicate, test-first

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/RoutingServer.kt`
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/data/ServerResolutionTest.kt`

- [ ] **Step 1: Write the failing tests**

Append these four tests inside the existing `ServerResolutionTest` class in `shared/src/commonTest/kotlin/com/jellemax/detour/data/ServerResolutionTest.kt`, immediately after `keepingTheServerAddressKeepsTheDiscoveredIssuer`. `noBakedDefaults()` is an existing private helper in that class — do not redefine it.

```kotlin
    @Test
    fun removingTheCustomServerDropsASessionBoundToADiscoveredRealm() {
        // The #106 shape: the rider never typed an issuer, so the realm they
        // signed in against came from the server they are now removing.
        noBakedDefaults()
        assertTrue(
            RoutingServer.clearDropsSession(
                previous = ServerConfig(url = "https://mine.example", enabled = true),
                discovered = "https://mine.example/realms/detour",
            ),
        )
    }

    @Test
    fun removingTheCustomServerDropsASessionBoundToATypedRealm() {
        // The pre-#106 form of the same defect: clearCustom() already dropped a
        // typed idp_issuer without dropping the session it was bound to.
        noBakedDefaults()
        assertTrue(
            RoutingServer.clearDropsSession(
                previous = ServerConfig(
                    url = "https://mine.example",
                    idpIssuer = "https://typed.example/realms/detour",
                    enabled = true,
                ),
                discovered = "",
            ),
        )
    }

    @Test
    fun removingTheCustomServerKeepsASessionTheBakedRealmAlreadyMinted() {
        // The rule recorded on Auth.sessionEpoch: a change that leaves the
        // effective issuer alone must not sign the rider out. Written as a test
        // so the fix cannot be simplified into an unconditional Auth.clear().
        BuildDefaults.configure(idpIssuer = "https://same.example/realms/detour")
        assertFalse(
            RoutingServer.clearDropsSession(
                previous = ServerConfig(url = "https://mine.example", enabled = true),
                discovered = "https://same.example/realms/detour",
            ),
        )
    }

    @Test
    fun removingNothingDropsNothing() {
        // clearCustom() on an install that never had a custom server is a no-op,
        // and must stay one — the settings screen can reach it in that state.
        noBakedDefaults()
        assertFalse(RoutingServer.clearDropsSession(previous = null, discovered = ""))
    }
```

Add whichever of these imports the file does not already have, at the top of the file with the other imports:

```kotlin
import kotlin.test.assertFalse
import kotlin.test.assertTrue
```

Check first with `grep -n "^import" shared/src/commonTest/kotlin/com/jellemax/detour/data/ServerResolutionTest.kt` — `kotlin.test.Test` and `kotlin.test.assertEquals` are already there. Do not add a duplicate import; Kotlin will not compile with one.

- [ ] **Step 2: Run the tests to verify they fail**

Run from the worktree root:

```bash
devcontainer-exec ./gradlew :shared:testDebugUnitTest --console=plain
```

Expected: FAIL at compile time with `unresolved reference: clearDropsSession`. That is the correct failure — the function does not exist yet. If it fails for any other reason, stop and report it rather than proceeding.

- [ ] **Step 3: Write the predicate**

In `shared/src/commonMain/kotlin/com/jellemax/detour/data/RoutingServer.kt`, insert this immediately after the existing `issuerAfterSave` function (which ends with the line `): String = issuer(config, if (serverChanged(config, previous)) "" else discovered)`) and before the `serverChanged` doc comment:

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
     * The "after" side takes neither a config nor a discovered value because
     * [clearCustom] drops both — what survives it is the baked default.
     */
    internal fun clearDropsSession(previous: ServerConfig?, discovered: String): Boolean =
        issuer(null, "") != issuer(previous, discovered)
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
devcontainer-exec ./gradlew :shared:testDebugUnitTest --console=plain
```

Expected: `BUILD SUCCESSFUL`. All four new tests pass and no existing test regresses.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/RoutingServer.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/ServerResolutionTest.kt
git commit -m "Extract the clear-custom-server session decision as a pure predicate (#115)"
```

---

### Task 2: Apply the predicate in `clearCustom()`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/RoutingServer.kt:340-346`

There is no test step here, and that is deliberate rather than an omission: `clearCustom()` reaches `prefs`, which needs a platform Context that no `commonTest` can construct. Task 1 is where the behaviour is asserted. This task wires the asserted decision to the existing side effect.

- [ ] **Step 1: Replace the body of `clearCustom()`**

The current function reads:

```kotlin
    /** Clearing the secure store wholesale would take the session with it, so the two
     *  Cloudflare keys are removed by name. */
    fun clearCustom() {
        prefs(PREFS).clear()
        securePrefs().apply {
            CredentialMigration.SERVER_GROUP.keys.forEach { remove(it.name) }
        }
    }
```

Replace it with:

```kotlin
    /** Clearing the secure store wholesale would take the session with it, so the two
     *  Cloudflare keys are removed by name. */
    fun clearCustom() {
        // Read before the wipe: both inputs live in the prefs this is about to
        // clear. And cleared above it, not below, matching the discipline [save]
        // documents for its own eviction — each put/remove is its own async
        // commit on Android, so a process death between them must not be able to
        // leave a cleared config paired with a live session.
        //
        // The rule is [save]'s: tokens are minted by one realm and meaningless to
        // another, and a refresh presented to the wrong realm reads as a replay
        // rather than as a mistake. Dropping the custom server is a realm change
        // whenever what it resolved to differs from the baked default.
        if (clearDropsSession(loadCustom(), discoveredIssuer())) Auth.clear()

        prefs(PREFS).clear()
        securePrefs().apply {
            CredentialMigration.SERVER_GROUP.keys.forEach { remove(it.name) }
        }
    }
```

- [ ] **Step 2: Verify the whole shared module still builds and passes**

```bash
devcontainer-exec ./gradlew :shared:testDebugUnitTest --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Update the stale call-site list on `Auth.clear`**

`Auth.clear()`'s doc comment in `shared/src/commonMain/kotlin/com/jellemax/detour/data/Auth.kt` says it is called from three places and names them. It was already one short (`rememberDiscoveredIssuer`), and this task adds another. Find the phrase `because [clear] is called from three places — [signOut], a 401 in` and replace that sentence's list so it reads:

```
     *  because [clear] is called from every path that ends a session —
     *  [signOut], a 401 in [Api], and the three realm changes in
     *  `RoutingServer` (`save`, `rememberDiscoveredIssuer`, `clearCustom`) —
     *  and a screen
```

Keep the rest of the sentence (`that forgets to reset on any one of them is a screen that leaks another rider's data.`) exactly as it is. Preserve the leading ` *  ` comment prefix on every line.

- [ ] **Step 4: Verify the comment edit did not break the build**

```bash
devcontainer-exec ./gradlew :shared:testDebugUnitTest --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/RoutingServer.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/Auth.kt
git commit -m "Drop the session when removing the custom server changes the realm (#115)"
```

---

### Task 3: Version bump

**Files:**
- Modify: `app/build.gradle.kts:80`

`CLAUDE.md` requires a semver check before anything lands on `main`. This is a fix with no behaviour or API break, so it is a patch bump. Do not touch `versionCode` — CI stamps it from the run number.

- [ ] **Step 1: Bump `versionName`**

Change line 80 of `app/build.gradle.kts` from:

```kotlin
        versionName = "1.95.0"
```

to:

```kotlin
        versionName = "1.95.1"
```

- [ ] **Step 2: Verify nothing else claims that version**

```bash
grep -rn "1\.95\.0" --include="*.kts" --include="*.kt" --include="*.toml" . | grep -v "/build/"
```

Expected: no output. If anything else pins `1.95.0`, report it rather than editing it — it may be a deliberate floor.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "Bump versionName to 1.95.1"
```

---

### Task 4: Full verification

**Files:** none modified.

- [ ] **Step 1: Run the Android unit tests the pull_request build actually runs**

`.claude/c7/github-workflow.yml` records the three steps a PR executes. Run the test one here:

```bash
devcontainer-exec ./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest --console=plain
```

Expected: `BUILD SUCCESSFUL`. This catches an app-module consumer broken by the shared change.

- [ ] **Step 2: Type-check the common source set against the common intersection**

This is the first check `ios.yml` runs, and it catches a `java.*` import that would only fail on the macOS runner minutes later. Cheap here.

```bash
devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Confirm the diff is only what the plan describes**

```bash
git diff origin/main --stat
```

Expected exactly six files: the spec, this plan, `RoutingServer.kt`, `ServerResolutionTest.kt`, `Auth.kt`, `app/build.gradle.kts`. Report anything else.

- [ ] **Step 4: Report**

State the test counts and the pass/fail result. Do not claim success without pasting the `BUILD SUCCESSFUL` line from Steps 1 and 2.

---

## Notes for the implementer

- **Do not write a new session-clearing routine.** `Auth.clear()` already blanks the session and resets `FriendsStore`, `ConvoysStore`, `CirclesStore`, `FriendFog` and `AccountScope`. Anything less would be a regression; anything more belongs in a different change.
- **Do not make `clearCustom()` call `Auth.clear()` unconditionally.** Task 1's third test exists to stop that. It would sign out every rider whose custom server resolved to the same realm as the baked default.
- **Do not change any call site.** No signature changes; all four callers are unaffected.
- All Gradle commands go through `devcontainer-exec` from the worktree root. File reads, edits and `git` stay on the host.

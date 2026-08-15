# Secure Credential Fields Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the unmasked Cloudflare Access client-secret field with a reusable masked component, and add a guard so the next credential field cannot repeat the mistake.

**Architecture:** Two composables in `app/.../ui/SecureFields.kt` over three `internal` pure
config functions. The pure functions carry the security decision and are unit-tested under
plain JUnit4; the composables are thin shells verified by compiler and device. A shell script
in the `detour-compose-state-hazards` skill fails CI if a raw `OutlinedTextField` is given a
secret-ish label.

**Tech Stack:** Kotlin, Jetpack Compose (BOM `2024.09.02` → Foundation 1.7), Material3,
JUnit4, bash, GitHub Actions.

Spec: [`../specs/2026-08-15-secure-credential-fields-design.md`](../specs/2026-08-15-secure-credential-fields-design.md).
Closes [#7](https://github.com/maxke24/Detour/issues/7).

## Global Constraints

- **All Gradle runs happen in the devcontainer.** Never on the host — the host JDK is 26 with
  no Android SDK. Prefix: `docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard`.
  Always the numeric uid:gid, never `-u dev` — a username pins the uid but not the gid and
  leaves root-owned files behind.
- **Never run a bare `./gradlew build`.** Name the task.
- **Never uninstall, clear data on, or revoke permissions from the app on the device.** A
  previous session destroyed a user's login and four saved traces working around a blocked
  `pm revoke`. `adb install -r` only.
- **No new dependencies.** `material-icons-extended` and `compose-bom:2024.09.02` are already
  declared; nothing else may be added.
- **No `Co-Authored-By` or `Claude-Session` trailer** on any commit. Conventional-commits
  style, subject + optional body, no trailers.
- **Package is `com.jellemax.detour.ui`** for every new Kotlin file, so no call site needs an
  import edit.
- **`KeyboardCapitalization.None` is a no-op** at the `EditorInfo` level (`Unspecified`
  already resolves to `None`). It is written for documented intent. Do not claim in a commit
  message or comment that it changes behaviour.
- **`autoCorrectEnabled = false` is the setting that does real work** — a null default
  resolves to `true`, so these fields are autocorrected today.

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/jellemax/detour/ui/SecureFields.kt` | **Create.** The three pure config functions and the two public composables. One file: they are one unit of meaning and total well under 200 lines. |
| `app/src/test/java/com/jellemax/detour/ui/SecureFieldsTest.kt` | **Create.** Covers the pure config only — the composables are unreachable from plain JUnit4. |
| `app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt` | **Modify** `ServerSection`, lines 1142–1160. |
| `.claude/skills/detour-compose-state-hazards/scripts/check-secret-fields.sh` | **Create.** The regression guard. |
| `.claude/skills/detour-compose-state-hazards/SKILL.md` | **Modify.** A section pointing at the guard. |
| `.github/workflows/build.yml` | **Modify.** One step before `Run unit tests`. |

---

### Task 1: The pure config functions

**Files:**
- Create: `app/src/main/java/com/jellemax/detour/ui/SecureFields.kt`
- Test: `app/src/test/java/com/jellemax/detour/ui/SecureFieldsTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces, all `internal`, used by Task 2 and Task 3:
  - `credentialKeyboardOptions(keyboardType: KeyboardType): KeyboardOptions`
  - `secretKeyboardOptions(): KeyboardOptions`
  - `secretMask(revealed: Boolean): VisualTransformation`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/jellemax/detour/ui/SecureFieldsTest.kt`:

```kotlin
package com.jellemax.detour.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Covers the config behind [SecretTextField] and [CredentialTextField]. The composables
 * themselves are out of reach here - app/ has plain JUnit4 and no Robolectric, no
 * compose-ui-test and no androidTest source set - so the security decision is extracted
 * into these functions and the shell around them is checked on a device.
 *
 * These types were measured to construct and evaluate on the JVM stub with no Android
 * framework before this test was written; without that, the config would have had to
 * return a framework-free type instead.
 */
class SecureFieldsTest {

    @Test
    fun aSecretFieldAsksForThePasswordKeyboard() {
        // KeyboardType.Password is the whole IME-cache protection: it maps to
        // TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD, which is what stops a
        // compliant IME learning the secret. ASVS 5.0.0 V6.2.6.
        assertEquals(KeyboardType.Password, secretKeyboardOptions().keyboardType)
    }

    @Test
    fun neitherFieldAutocorrects() {
        // Null resolves to true, so a field that says nothing IS autocorrected.
        // This is the one option here that changes EditorInfo.
        assertEquals(false, secretKeyboardOptions().autoCorrectEnabled)
        assertEquals(false, credentialKeyboardOptions(KeyboardType.Ascii).autoCorrectEnabled)
    }

    @Test
    fun neitherFieldRequestsCapitalisation() {
        // A no-op against the platform default, asserted so the intent survives an edit.
        assertEquals(KeyboardCapitalization.None, secretKeyboardOptions().capitalization)
        assertEquals(
            KeyboardCapitalization.None,
            credentialKeyboardOptions(KeyboardType.Uri).capitalization,
        )
    }

    @Test
    fun aCredentialFieldPassesItsKeyboardTypeThrough() {
        assertEquals(KeyboardType.Uri, credentialKeyboardOptions(KeyboardType.Uri).keyboardType)
        assertEquals(KeyboardType.Ascii, credentialKeyboardOptions(KeyboardType.Ascii).keyboardType)
    }

    @Test
    fun aHiddenSecretIsMasked() {
        val masked = secretMask(revealed = false).filter(AnnotatedString("hunter2"))
        assertEquals("•••••••", masked.text.text)
        assertNotEquals("hunter2", masked.text.text)
    }

    @Test
    fun aRevealedSecretShowsItsText() {
        val shown = secretMask(revealed = true).filter(AnnotatedString("hunter2"))
        assertEquals("hunter2", shown.text.text)
    }

    @Test
    fun revealingDoesNotReachTheKeyboard() {
        // The defect this guards: if revealing also swapped the keyboard back to Text,
        // checking your own typing would start feeding the IME cache - #7 reached by a
        // different route. `revealed` is structurally absent from secretKeyboardOptions'
        // signature, and this asserts the masking function is the only thing it feeds.
        assertNotEquals(
            secretMask(revealed = false).filter(AnnotatedString("abc")).text.text,
            secretMask(revealed = true).filter(AnnotatedString("abc")).text.text,
        )
        assertEquals(KeyboardType.Password, secretKeyboardOptions().keyboardType)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.ui.SecureFieldsTest"
```

Expected: **FAIL** at compilation — `Unresolved reference: secretKeyboardOptions` (and the
other two). A compile failure is the correct red here; the functions do not exist yet.

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/main/java/com/jellemax/detour/ui/SecureFields.kt`:

```kotlin
package com.jellemax.detour.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Input fields for credentials, and the config behind them.
 *
 * Compose does not infer any of this from [PasswordVisualTransformation]: that only affects
 * what is drawn. Without [KeyboardOptions] a field ships as ordinary prose, so a typed
 * secret can land in the keyboard's personalised learning dictionary - which several
 * third-party IMEs sync off-device. Masking hides the secret from someone looking over your
 * shoulder; it does not change what the keyboard does with the keystrokes.
 *
 * What each setting is actually worth, measured against Foundation 1.7 and AOSP's
 * `EditorInfo.update()` rather than assumed:
 *
 *  - [KeyboardType.Password] -> `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD`. This is
 *    the entire IME-cache protection.
 *  - `autoCorrectEnabled = false` clears `TYPE_TEXT_FLAG_AUTO_CORRECT`, which a null default
 *    *sets*. Real: these fields are autocorrected today.
 *  - [KeyboardCapitalization.None] does nothing. `Unspecified` already resolves to `None`,
 *    which ORs no flag. Written for intent, not effect.
 *
 * `IME_FLAG_NO_PERSONALIZED_LEARNING` would be the belt-and-braces version and is not
 * reachable: `PlatformImeOptions` carries only `privateImeOptions: String?`, the flag is an
 * Int on `EditorInfo.imeOptions`, and Compose never sets it.
 *
 * Requirements: ASVS 5.0.0 V6.2.6 (mask, with a permitted temporary reveal) and V6.2.7
 * (paste and password managers must work). CWE-549.
 */

/**
 * Keyboard config for any credential: no autocorrect, no capitalisation, and a caller-chosen
 * [keyboardType]. Not masked - use [secretKeyboardOptions] for something that is secret.
 */
internal fun credentialKeyboardOptions(keyboardType: KeyboardType) = KeyboardOptions(
    keyboardType = keyboardType,
    autoCorrectEnabled = false,
    capitalization = KeyboardCapitalization.None,
)

/** Keyboard config for a secret: [credentialKeyboardOptions] plus the password IME. */
internal fun secretKeyboardOptions() = credentialKeyboardOptions(KeyboardType.Password)

/**
 * The mask, and the only thing a reveal toggle is allowed to change. Deliberately not a
 * parameter of [secretKeyboardOptions]: a revealed secret must still refuse the IME's
 * learning dictionary.
 */
internal fun secretMask(revealed: Boolean): VisualTransformation =
    if (revealed) VisualTransformation.None else PasswordVisualTransformation()
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :app:testDebugUnitTest --tests "com.jellemax.detour.ui.SecureFieldsTest"
```

Expected: `BUILD SUCCESSFUL`. Confirm all seven ran, rather than trusting the exit code:

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-com.jellemax.detour.ui.SecureFieldsTest.xml
```

Expected: `tests="7" skipped="0" failures="0" errors="0"`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/SecureFields.kt \
        app/src/test/java/com/jellemax/detour/ui/SecureFieldsTest.kt
git commit -m "feat(ui): add the keyboard and masking config for credential fields

Pure functions, so the security decision is testable under plain JUnit4 -
app/ has no Robolectric and no compose-ui-test, so a composable cannot be
asserted on. Seven tests, including one that pins the mask as the only thing
a reveal is allowed to change: a revealed secret must still refuse the IME's
learning dictionary."
```

---

### Task 2: The two composables

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/SecureFields.kt` (append)

**Interfaces:**
- Consumes: `credentialKeyboardOptions`, `secretKeyboardOptions`, `secretMask` from Task 1.
- Produces, used by Task 3:
  - `SecretTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier)`
  - `CredentialTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, keyboardType: KeyboardType = KeyboardType.Ascii, placeholder: String? = null)`

There is no unit test in this task and that is not an oversight — see the file structure
note. The gates are the compiler here and the device in Task 5.

- [ ] **Step 1: Append the imports**

Add to the existing import block in `SecureFields.kt`, keeping it alphabetically ordered:

```kotlin
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
```

- [ ] **Step 2: Append the autofill modifier**

```kotlin
/**
 * Offers this field to the platform autofill service, so a password manager can fill it.
 *
 * ASVS 5.0.0 V6.2.7 (L1) requires that "paste functionality, browser password helpers, and
 * external password managers are permitted", which makes this a requirement rather than a
 * convenience. It also cuts the other way from the usual instinct: suppressing autofill on a
 * credential field is exactly what breaks password managers, so the HTML5 cheat sheet's
 * `autocomplete="off"` advice is deliberately *not* followed here.
 *
 * This is the pre-1.8 autofill API. `Modifier.semantics { contentType = ContentType.Password }`
 * would be tidier but arrived in Compose 1.8; this project is on BOM 2024.09.02.
 * [LocalAutofill] is null on platforms without a service, hence the safe call.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Modifier.autofillPassword(onFill: (String) -> Unit): Modifier {
    val autofill = LocalAutofill.current
    // The node is remembered, so without this the first onFill would be captured forever and
    // an autofilled value would be written through a stale lambda. See
    // .claude/skills/detour-compose-state-hazards/ section 2.
    val currentOnFill by rememberUpdatedState(onFill)
    val node = remember { AutofillNode(autofillTypes = listOf(AutofillType.Password)) { currentOnFill(it) } }
    LocalAutofillTree.current += node
    return this
        .onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged { state ->
            autofill?.run {
                if (state.isFocused) requestAutofillForNode(node) else cancelAutofillForNode(node)
            }
        }
}
```

- [ ] **Step 3: Append the two composables**

```kotlin
/**
 * A masked field for something secret, with a reveal toggle that re-hides itself.
 *
 * There is deliberately no `visualTransformation` parameter: no argument you can pass this
 * composable produces an unmasked field. That is the whole reason it exists rather than a
 * pair of helper functions - #7 happened because one line was omitted from an
 * `OutlinedTextField`, and an omission is exactly what a helper cannot prevent.
 *
 * The reveal is sanctioned rather than tolerated: ASVS 5.0.0 V6.2.6 says applications "may
 * allow the user to temporarily view the entire masked password". It re-hides on focus loss
 * so a revealed secret cannot survive into a screenshot, a recent-apps thumbnail, or the
 * next visit to the screen.
 */
@Composable
fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = secretMask(revealed),
        keyboardOptions = secretKeyboardOptions(),
        trailingIcon = {
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (revealed) "Hide secret" else "Show secret",
                )
            }
        },
        // hasFocus, NOT isFocused. The reveal button is an IconButton inside this field's
        // own focus subtree, so tapping it takes focus off the text field itself; keyed on
        // isFocused the secret would re-hide on the very tap meant to show it and the
        // toggle would look broken. This compiles clean either way - only a device shows it.
        modifier = modifier
            .autofillPassword(onValueChange)
            .onFocusChanged { if (!it.hasFocus) revealed = false },
    )
}

/**
 * A field for a credential that is not secret - a client id, a server URL.
 *
 * Not masked, because these are not secrets and hiding them only makes them hard to check.
 * What it does carry is the input-hint half: no autocorrect, no capitalisation, and a
 * caller-chosen [keyboardType]. iOS already sets all three on its counterparts
 * (`iosApp/Detour/SettingsScreen.swift:125-131`); Android set none of them.
 */
@Composable
fun CredentialTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Ascii,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { text -> { Text(text) } },
        singleLine = true,
        keyboardOptions = credentialKeyboardOptions(keyboardType),
        modifier = modifier,
    )
}
```

`fillMaxWidth` is intentionally not baked in — the caller supplies layout, matching
`DisabledFeatureNotice` and the rest of `ui/`.

- [ ] **Step 4: Verify it compiles and the Task 1 tests still pass**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.jellemax.detour.ui.SecureFieldsTest"
```

Expected: `BUILD SUCCESSFUL`.

If `AutofillNode`'s constructor is rejected, its parameter order in this Compose version is
`AutofillNode(autofillTypes, boundingBox, onFill)` with `onFill` last — the trailing-lambda
form above is written for that. Do not switch to `Modifier.composed {}`; it is deprecated in
1.7 and the `@Composable` extension above is the replacement.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/SecureFields.kt
git commit -m "feat(ui): add SecretTextField and CredentialTextField

SecretTextField takes no visualTransformation parameter, so no argument
produces an unmasked field - #7 was an omission, and an omission is what a
helper function cannot prevent.

The reveal toggle keys its auto-hide on hasFocus rather than isFocused: the
eye button sits inside the field's own focus subtree, so isFocused would
re-hide the secret on the very tap meant to reveal it.

Autofill is wired because ASVS 5.0.0 V6.2.7 requires password managers work,
using the pre-1.8 AutofillNode API - ContentType semantics needs Compose 1.8
and this project is on BOM 2024.09.02."
```

---

### Task 3: Convert the three call sites

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt:1142-1160`

**Interfaces:**
- Consumes: `SecretTextField`, `CredentialTextField` from Task 2.
- Produces: nothing.

- [ ] **Step 1: Confirm the target is still what the plan expects**

```bash
sed -n '1142,1160p' app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt | grep -c 'OutlinedTextField('
```

Expected: `3`. If not, stop — `ServerSection` has moved and this task needs rewriting
against the current file rather than adapting blindly.

- [ ] **Step 2: Replace the three fields**

Replace exactly this block:

```kotlin
        OutlinedTextField(
            value = url, onValueChange = { url = it; saved = false },
            label = { Text("Server URL") },
            placeholder = { Text("https://…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = clientId, onValueChange = { clientId = it; saved = false },
            label = { Text("CF Access Client Id (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = clientSecret, onValueChange = { clientSecret = it; saved = false },
            label = { Text("CF Access Client Secret (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
```

with:

```kotlin
        CredentialTextField(
            value = url, onValueChange = { url = it; saved = false },
            label = "Server URL",
            keyboardType = KeyboardType.Uri,
            placeholder = "https://…",
            modifier = Modifier.fillMaxWidth(),
        )
        CredentialTextField(
            value = clientId, onValueChange = { clientId = it; saved = false },
            label = "CF Access Client Id (optional)",
            modifier = Modifier.fillMaxWidth(),
        )
        SecretTextField(
            value = clientSecret, onValueChange = { clientSecret = it; saved = false },
            label = "CF Access Client Secret (optional)",
            modifier = Modifier.fillMaxWidth(),
        )
```

Note `label` is now a `String`, not a `{ Text(...) }` lambda.

- [ ] **Step 3: Add the one needed import**

Add to `SettingsScreen.kt`'s import block, in alphabetical position:

```kotlin
import androidx.compose.ui.text.input.KeyboardType
```

`SecretTextField` and `CredentialTextField` need no import — same package.

- [ ] **Step 4: Verify**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`. Then confirm the field is genuinely masked now — this is the
spec's inverted precondition 1 flipping, which is success:

```bash
grep -c 'visualTransformation' app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt   # 0 - it moved into SecureFields
grep -c 'SecretTextField' app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt        # 1
grep -c 'CredentialTextField' app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt    # 2
```

Also confirm nothing else in the file changed:

```bash
git diff --stat app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt
```

Expected: roughly `+18 −20`. A much larger diff means the editor reformatted the file —
revert and redo, since a move plus a reformat in one commit is unreviewable.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt
git commit -m "fix(settings): mask the Cloudflare Access client secret

Closes the live half of #7. The field rendered the secret in plaintext and,
having no keyboardOptions, also fed it to the IME's learning dictionary -
worse than the two FriendsScreen fields the issue was named after, which at
least masked on screen. iOS has used SecureField here all along.

The two fields beside it lose autocorrect and the URL field gains the URI
keyboard, matching what iOS already sets on the same three."
```

---

### Task 4: The regression guard

**Files:**
- Create: `.claude/skills/detour-compose-state-hazards/scripts/check-secret-fields.sh`
- Modify: `.claude/skills/detour-compose-state-hazards/SKILL.md`
- Modify: `.github/workflows/build.yml`

**Interfaces:**
- Consumes: the call sites from Task 3 (the script must pass against them).
- Produces: nothing consumed by later tasks.

The component only protects people who know it exists. #7 happened because one line was
omitted; nothing stops the next field omitting it too. This is what makes the fix durable.

- [ ] **Step 1: Write the guard**

Create `.claude/skills/detour-compose-state-hazards/scripts/check-secret-fields.sh`:

```bash
#!/usr/bin/env bash
#
# Fail if a raw text field is given a secret-ish label.
#
# Why this exists: #7 was one missing line on an OutlinedTextField - no mask, no
# keyboardOptions - and nothing in the toolchain noticed. There is no Robolectric, no
# compose-ui-test and no androidTest source set in this repo, so a credential field that
# leaks to the IME cache compiles clean, passes every test, and ships. SecretTextField
# makes the right thing easy; this makes the wrong thing loud.
#
# Matches a raw OutlinedTextField/BasicTextField/TextField constructor whose next few lines
# mention a secret-ish word. SecretTextField and CredentialTextField do not match the
# constructor pattern, so a converted call site is silent.
#
# Read-only: greps the working tree. Exit 0 clean, 1 on a finding, 2 on misuse.
set -euo pipefail

if [ "$#" -gt 0 ]; then
    echo "usage: $(basename "$0")            # no arguments; run from anywhere" >&2
    exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$ROOT"

# The component itself necessarily wraps a raw OutlinedTextField.
SELF="app/src/main/java/com/jellemax/detour/ui/SecureFields.kt"
SECRET_RE='secret|password|passphrase|token|api[-_ ]?key|credential'
WINDOW=8

hits=""
while IFS= read -r f; do
    [ "$f" = "$SELF" ] && continue
    found=$(awk -v file="$f" -v re="$SECRET_RE" -v w="$WINDOW" '
        /OutlinedTextField\(|BasicTextField\(|[^A-Za-z]TextField\(/ {
            if (collecting && tolower(buf) ~ re) printf "%s:%d\n", file, start
            start = NR; buf = $0; collecting = 1; next
        }
        collecting {
            buf = buf "\n" $0
            if (NR - start >= w) {
                if (tolower(buf) ~ re) printf "%s:%d\n", file, start
                collecting = 0
            }
        }
        END { if (collecting && tolower(buf) ~ re) printf "%s:%d\n", file, start }
    ' "$f") || true
    [ -n "$found" ] && hits="$hits$found"$'\n'
done < <(git ls-files '*.kt')

hits=$(printf '%s' "$hits" | sed '/^$/d')

if [ -n "$hits" ]; then
    echo "A raw text field is collecting something secret-ish:" >&2
    printf '%s\n' "$hits" >&2
    cat >&2 <<'EOF'

Use SecretTextField (masked, password IME, reveal toggle, autofill) or
CredentialTextField (not masked, but no autocorrect and a chosen keyboard type),
both in app/src/main/java/com/jellemax/detour/ui/SecureFields.kt.

Without keyboardOptions a field ships as ordinary prose: predictive text runs over
the value and it can land in the keyboard's personalised learning dictionary, which
several third-party IMEs sync off-device. PasswordVisualTransformation alone does
not change this - it only affects what is drawn.

See ASVS 5.0.0 V6.2.6 and V6.2.7, CWE-549, and issue #7.
EOF
    exit 1
fi

echo "OK: no raw text field is labelled as collecting a secret."
```

- [ ] **Step 2: Make it executable and prove it catches the real defect**

```bash
chmod +x .claude/skills/detour-compose-state-hazards/scripts/check-secret-fields.sh
```

Run it against the code as it stands now (post-Task-3). Expected: **exit 0**, `OK: ...`.

```bash
.claude/skills/detour-compose-state-hazards/scripts/check-secret-fields.sh; echo "exit=$?"
```

This awk was run against the tree at `7904319` while the plan was written: it reported
exactly `app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt:1155` and nothing else —
no false positive on the three username fields, on "Convoy name", or on any other field in
`RoutesScreen`, `CirclesScreen` or `SavedPlacesScreen`. So it is known to work; if it
misbehaves for you, something in the surrounding code changed.

Now prove it is not vacuous — a guard that cannot fail is decoration. Reintroduce #7
temporarily and confirm it fires:

The working tree is clean at this point (Task 3 committed), so this just restores the
pre-fix file from the previous commit — `HEAD~1` here is Task 2's commit, where
`SettingsScreen.kt` is still unconverted.

```bash
git checkout HEAD~1 -- app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt
.claude/skills/detour-compose-state-hazards/scripts/check-secret-fields.sh; echo "exit=$?"
```

Expected: **exit 1**, naming `app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt:1155`.
If it exits 0 here, the guard is broken — fix the awk window or regex before continuing.

Restore:

```bash
git checkout HEAD -- app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt
.claude/skills/detour-compose-state-hazards/scripts/check-secret-fields.sh; echo "exit=$?"
```

Expected: back to **exit 0**.

- [ ] **Step 3: Document it in the skill**

Append to `.claude/skills/detour-compose-state-hazards/SKILL.md`, immediately before the
final `## Related` section:

```markdown
## 7. Credential fields need `keyboardOptions`, not just a mask

`PasswordVisualTransformation` only changes what is *drawn*. Compose infers nothing about
the IME from it, so a field with a mask and no `keyboardOptions` still ships as
`KeyboardType.Text` with autocorrect on: predictive text runs over the value and it can
land in the keyboard's personalised learning dictionary, which several third-party IMEs
sync off-device. This is what #7 was.

Do not hand-roll it. Use, from `app/src/main/java/com/jellemax/detour/ui/SecureFields.kt`:

- **`SecretTextField`** — masked, `KeyboardType.Password`, a reveal toggle that re-hides on
  focus loss, and autofill. It has no `visualTransformation` parameter on purpose: no
  argument produces an unmasked field.
- **`CredentialTextField`** — not masked, for a client id or a server URL, but with
  autocorrect off and a caller-chosen keyboard type.

Two things that are easy to get backwards:

- **Auto-hide keys on `hasFocus`, not `isFocused`.** The reveal button is an `IconButton`
  inside the field's own focus subtree, so tapping it moves focus off the text field.
  Keyed on `isFocused`, the secret re-hides on the very tap meant to reveal it.
- **Do not suppress autofill.** The instinct to opt a credential field out of everything is
  right for autocorrect and capitalisation and wrong here: ASVS 5.0.0 V6.2.7 (L1) requires
  that paste and external password managers work, and suppressing autofill is precisely
  what breaks them.

`scripts/check-secret-fields.sh` fails if a raw `OutlinedTextField`, `TextField` or
`BasicTextField` is given a secret-ish label. It runs in CI, before the unit tests.
```

- [ ] **Step 4: Wire it into CI**

In `.github/workflows/build.yml`, insert immediately **before** the existing
`- name: Run unit tests` step (and after the `Stamp version codes` step):

```yaml
      # #7 was one missing line on an OutlinedTextField, and nothing in the
      # toolchain noticed: there is no Robolectric, no compose-ui-test and no
      # androidTest source set here, so a credential field that leaks to the
      # keyboard cache compiles clean and ships. Cheap grep, run before the
      # tests so it fails in seconds.
      - name: Check credential fields use the secure components
        run: .claude/skills/detour-compose-state-hazards/scripts/check-secret-fields.sh
```

- [ ] **Step 5: Verify the workflow is valid YAML and the step is where it should be**

```bash
python3 -c "import yaml,sys; d=yaml.safe_load(open('.github/workflows/build.yml')); \
names=[s.get('name','<uses>') for s in d['jobs']['build']['steps']]; \
print('\n'.join(names)); \
i=names.index('Check credential fields use the secure components'); \
j=names.index('Run unit tests'); \
assert i < j, 'guard must run before the tests'; print('\nOK: guard at %d, tests at %d' % (i, j))"
```

Expected: the step list, then `OK: guard at <i>, tests at <j>` with `i < j`.

- [ ] **Step 6: Commit**

```bash
git add .claude/skills/detour-compose-state-hazards/scripts/check-secret-fields.sh \
        .claude/skills/detour-compose-state-hazards/SKILL.md \
        .github/workflows/build.yml
git commit -m "ci: fail the build when a raw text field collects a secret

SecretTextField makes the right thing easy; this makes the wrong thing loud.
#7 was one omitted line that compiled clean, passed every test and shipped,
because there is no Robolectric, no compose-ui-test and no androidTest source
set for anything to catch it.

Verified non-vacuous: reverting SettingsScreen to its pre-fix state makes the
script exit 1 naming the field, and restoring it returns exit 0."
```

---

### Task 5: Device desk-check

**Files:** none.

**Interfaces:**
- Consumes: everything above.
- Produces: the recorded observations for the PR description.

This is the only gate for the two things no unit test in this repo can reach: the
`hasFocus`/`isFocused` hazard, and the V6.2.7 paste requirement.

- [ ] **Step 1: Confirm the device and read the package identity**

```bash
adb devices
```

Expected: `RFCT42HS9WY   device`. If it says `unauthorized`, stop and ask — do not work
around it.

Read `.claude/skills/detour-adb/SKILL.md` for the package identity before installing. The
Kotlin namespace (`com.jellemax.detour`) is **not** the applicationId, and the debug build
has its own suffix. Do not guess it.

- [ ] **Step 2: Build and install**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`-r` reinstalls in place. **Never `adb uninstall`** — it destroys the user's login and saved
traces.

- [ ] **Step 3: Observe, and write down what you see**

Navigate: **Settings → Server**. Then check each, recording the actual observation rather
than "looks fine":

1. **Masked at rest** — type into *CF Access Client Secret*. Expected: `•` glyphs, not
   characters.
2. **No suggestion strip** — while typing in it, compare against the *Server URL* field.
   Expected: the keyboard offers no predictive-text row over the secret.
3. **Reveal works** — tap the eye. Expected: the text becomes readable and the icon changes
   to a struck-through eye.
4. **Reveal survives the tap** — this is the `hasFocus` hazard. Expected: it *stays*
   revealed. If it snaps back to dots the instant you lift your finger, `onFocusChanged` is
   keyed on `isFocused` and must be changed to `hasFocus`.
5. **Auto-hide on focus loss** — tap into *Server URL*. Expected: the secret returns to `•`.
6. **Paste works** — copy any text, long-press the secret field, paste. Expected: it pastes.
   ASVS 5.0.0 V6.2.7 makes this a requirement, not a nicety.
7. **Save round-trips** — enter a URL and a secret, tap *Save server*, leave Settings,
   return. Expected: the values are still there and the secret is masked again.

- [ ] **Step 4: Record the result**

Append the seven observations to the PR description draft. If any of 1–7 fails, fix it and
re-run the whole list — a partial re-check is how a regression gets through.

- [ ] **Step 5: Full verification sweep, then push**

```bash
docker exec -u 1000:1000 -w /workspaces/Detour recursing_volhard \
  ./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
.claude/skills/detour-compose-state-hazards/scripts/check-secret-fields.sh
.claude/skills/detour-compose-state-hazards/scripts/check-preconditions.sh
```

All three must pass. The third is the pre-existing skill precondition script — if this work
invalidated one of its assertions, that is a finding to resolve, not to skip.

```bash
git push -u origin fix/secure-credential-fields
```

---

## Self-Review

**Spec coverage**

| Spec section | Task |
|---|---|
| Preconditions | Task 3 Step 1 re-checks assertion 4; Task 3 Step 4 confirms assertion 1 flips |
| Public API — `SecretTextField`, `CredentialTextField` | Task 2 |
| Pure core — three `internal` functions | Task 1 |
| Behaviour — masking, reveal, auto-hide, autofill, paste | Task 2 (build), Task 5 (observe) |
| Hazard — `hasFocus` not `isFocused` | Task 2 Step 3 comment, Task 5 Step 3 item 4 |
| Hazard — revealing must not change the keyboard | Task 1 test `revealingDoesNotReachTheKeyboard` |
| Verification — unit | Task 1 Step 4 |
| Verification — guard | Task 4 Steps 2 and 5 |
| Verification — device | Task 5 |
| The guard script | Task 4 |
| Compliance statement | carried in the KDoc of `SecureFields.kt` (Task 1 Step 3) and the SKILL.md section (Task 4 Step 3) |
| Deferred: username fields, #26, #24, `IME_FLAG_NO_PERSONALIZED_LEARNING`, `FLAG_SECURE` | out of scope by design; no task, correctly |

**Placeholder scan:** none. Every code step carries complete code; every command carries its
expected output.

**Type consistency:** `credentialKeyboardOptions(KeyboardType)`, `secretKeyboardOptions()`
and `secretMask(Boolean)` are defined in Task 1 and used with those exact signatures in
Tasks 1 and 2. `SecretTextField` and `CredentialTextField` are defined in Task 2 with the
parameter names `value`, `onValueChange`, `label`, `modifier`, `keyboardType`, `placeholder`
and called with those exact names in Task 3. `label` is a `String` in both the definition
and the call sites.

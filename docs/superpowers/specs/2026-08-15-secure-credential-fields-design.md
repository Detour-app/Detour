# Secure credential fields — design

Closes [#7](https://github.com/maxke24/Detour/issues/7).

Three of #7's four sites are already gone. What remains is the worst of them, plus a wider
drift the issue did not catch. This spec covers a reusable component for both, and states
which OWASP requirements it satisfies and which it deliberately does not.

## Preconditions

Run these before writing the plan. If any fails, the spec is **stale** — establish whether
the assertion or the code is wrong before adapting anything. Every value below was produced
by running the command, not inferred from the shape of the code.

**Assertions 1–4 are inverted**: they assert the gap this spec exists to close is *still
open*, so they are expected to fail once the work lands. That is success, not drift.
Assertions 5–8 describe the environment and must hold throughout.

```sh
# --- inverted: these assert the gap is still open, and flip when the work lands ---

# 1. The secret field is unmasked: no visual transformation in SettingsScreen  -> 0
grep -c 'visualTransformation' app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt

# 2. No password masking anywhere in Kotlin (files containing it)              -> 0
git grep -c 'PasswordVisualTransformation' -- '*.kt' | wc -l

# 3. No password keyboard type anywhere in Kotlin (files containing it)        -> 0
git grep -c 'KeyboardType.Password' -- '*.kt' | wc -l

# 4. ServerSection still has three raw fields                                  -> 3
sed -n '1142,1160p' app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt \
  | grep -c 'OutlinedTextField('

# --- stable: these must hold before and after ---

# 5. Compose BOM pins Foundation 1.7 (no ContentType autofill semantics)       -> 1
grep -c 'compose-bom:2024.09.02' app/build.gradle.kts

# 6. The eye icons are available without a new dependency                      -> 1
grep -c 'material-icons-extended' app/build.gradle.kts

# 7. app/ has plain JUnit4 and nothing else — no Robolectric, no ui-test       -> 0
grep -cE 'robolectric|compose.ui:ui-test|androidTestImplementation' app/build.gradle.kts

# 8. iOS already masks its counterpart, so this is Android-only drift          -> 1
grep -c 'SecureField' iosApp/Detour/SettingsScreen.swift
```

## Why this is smaller than #7 implies

#7 named four sites. Verified on `7904319`:

| Site | State |
|---|---|
| `FriendsScreen.kt:173` — sign-in password | **gone.** The Keycloak migration (#19) replaced the in-app form with a browser OAuth leg; the app never sees a password. |
| `FriendsScreen.kt:346` — password reset dialog | **gone.** The realm mails a reset link. |
| `server/sync/sync_server.py:5995` — admin dialog | **gone.** The Python server was deleted; the .NET backend serves no HTML forms. |
| `SettingsScreen.kt:1155` — CF Access Client Secret | **live, and worse than the two it outlived.** |

The survivor has neither `visualTransformation` nor `keyboardOptions`, so the secret renders
in plaintext *and* feeds the IME's learning dictionary. The two fields #7 was named after at
least masked on screen.

The drift is also wider than the secret. iOS sets `.keyboardType(.URL)`,
`.textInputAutocapitalization(.never)` and `.autocorrectionDisabled()` across that section
(`iosApp/Detour/SettingsScreen.swift:125-131`); Android sets none of them on any of the three
fields.

### What each option actually does, measured

Stated precisely, because two of these are worth less than they look and an earlier draft of
this spec overclaimed. From Compose Foundation 1.7's `KeyboardOptions.kt` and AOSP's
`EditorInfo.update()`:

```kotlin
private val autoCorrectOrDefault: Boolean get() = autoCorrectEnabled ?: true
private val capitalizationOrDefault: KeyboardCapitalization
    get() = capitalization.takeUnless { it == KeyboardCapitalization.Unspecified }
        ?: KeyboardCapitalization.None
```

| Setting | Effect on `EditorInfo` | Worth |
|---|---|---|
| `keyboardType = Password` | `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD` | **real** — this is the whole IME-cache protection |
| `autoCorrectEnabled = false` | clears `TYPE_TEXT_FLAG_AUTO_CORRECT`, which a null default **sets** | **real** — the field is autocorrected today |
| `capitalization = None` | nothing. `Unspecified` already resolves to `None`, which hits the `else` branch and ORs no flag | **no-op**, kept as documented intent |
| `keyboardType = Uri` | `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_URI` | ergonomic, not a security control |

So Android is **not** silently uppercasing the client ID — Compose never requests
capitalisation, and an earlier draft claiming the saved value is corrupted was wrong. The
genuine defect in that section is the unmasked secret; the genuine improvement to the other
two fields is turning autocorrect off and giving the URL field the right keyboard.

## Requirements this is built against

Retrieved from the OWASP knowledge base rather than recalled. ASVS 5.0.0, Cheat Sheets
`20260724` (`7d1c2d3`), CWE 4.20.

> **ASVS 5.0.0 V6.2.6 (L1):** Verify that password input fields use type=password to mask the
> entry. Applications may allow the user to temporarily view the entire masked password, or
> the last typed character of the password.

The second sentence is what sanctions the reveal toggle. Linked to **CWE-549 (Missing
Password Field Masking)** and **NIST SP 800-63B 5.1.1.2**.

> **ASVS 5.0.0 V6.2.7 (L1):** Verify that "paste" functionality, browser password helpers, and
> external password managers are permitted.

This is why autofill is mandatory rather than a nicety, and it adds a constraint that is easy
to miss: **paste must keep working.**

> **`Mobile_Application_Security_Cheat_Sheet#1-ui-data-masking`:** Mask sensitive information
> on UI fields to prevent shoulder surfing.

> **`HTML5_Security_Cheat_Sheet#credential-and-personally-identifiable-information-pii-input-hints`:**
> credential inputs should set `spellcheck="false"`, `autocorrect="off"`, `autocapitalize="off"`
> — and `autocomplete="off"`.

**One conflict, resolved deliberately.** That last attribute opposes V6.2.7: suppressing
autocomplete is exactly what breaks password managers. The requirement wins over the cheat
sheet. Blanket "opt out of everything" is the wrong instinct on the autofill axis and the
right one on the autocorrect/autocapitalise/spellcheck axes, and this component splits them
accordingly.

## Design

### Files

| File | Change |
|---|---|
| `app/src/main/java/com/jellemax/detour/ui/SecureFields.kt` | new — the component |
| `app/src/test/java/com/jellemax/detour/ui/SecureFieldsTest.kt` | new — the pure config, under plain JUnit4 |
| `app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt` | three call sites in `ServerSection` |
| `.claude/skills/detour-compose-state-hazards/scripts/check-secret-fields.sh` | new — the regression guard |
| `.claude/skills/detour-compose-state-hazards/SKILL.md` | a section pointing at the guard |
| `.github/workflows/build.yml` | one step, so the guard actually runs |

`ui/` is the right package: it already holds the app's shared presentational pieces
(`GlassSurface.kt`, `DisabledFeature.kt`), and staying in-package means no import edits at any
call site. This does **not** belong in `shared/` — Compose UI is Android-only here; iOS is
SwiftUI and already correct.

`detour-compose-state-hazards` is the right home for the guard. Its stated remit is the bug
classes that compile clean and fail in the field, which is what a raw secret field is.

### Public API

```kotlin
@Composable
fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
)

@Composable
fun CredentialTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Ascii,
    placeholder: String? = null,
)
```

Both are `singleLine = true`. Neither takes a `visualTransformation` parameter, and that is
the point: there is no argument you can pass `SecretTextField` that yields an unmasked field.
The two alternatives considered — a single composable with a `kind` parameter, and exporting
only `KeyboardOptions` factories — were rejected because both leave the #7 mistake one
omission away. A `kind = Opaque` on a secret compiles; a forgotten `visualTransformation`
compiles.

### Pure core

```kotlin
internal fun credentialKeyboardOptions(keyboardType: KeyboardType) = KeyboardOptions(
    keyboardType = keyboardType,
    autoCorrectEnabled = false,
    capitalization = KeyboardCapitalization.None,
)

internal fun secretKeyboardOptions() = credentialKeyboardOptions(KeyboardType.Password)

internal fun secretMask(revealed: Boolean): VisualTransformation =
    if (revealed) VisualTransformation.None else PasswordVisualTransformation()
```

`internal`, because the tests live in the same module and nothing outside it should reach
these.

### Behaviour

- **Masking** — `PasswordVisualTransformation`, whose default glyph is `•` (measured, not
  assumed).
- **Reveal** — trailing `IconButton`, `Icons.Filled.Visibility` / `Icons.Filled.VisibilityOff`,
  with content descriptions "Show secret" / "Hide secret". Sanctioned by V6.2.6.
- **Auto-hide** — `Modifier.onFocusChanged { if (!it.hasFocus) revealed = false }`, so a
  revealed secret cannot survive into a screenshot, a recent-apps thumbnail, or the next visit
  to the screen.
- **Autofill** — a private `Modifier.autofill(...)` extension over `LocalAutofill` +
  `AutofillNode(AutofillType.Password)`, `@OptIn(ExperimentalComposeUiApi::class)`, null-safe
  because `LocalAutofill.current` can be null. Compose 1.7's autofill is best-effort about
  triggering the *save* prompt specifically; the fill path is the one that matters here.
- **Paste** — nothing suppresses it. `OutlinedTextField` allows paste by default and this
  component adds no knob that would change that. Stated because V6.2.7 makes it a requirement,
  so a future change that breaks it is a regression rather than a preference.

### Hazards this must handle

**`hasFocus`, not `isFocused`.** The reveal eye is an `IconButton` in the `trailingIcon` slot,
so tapping it moves focus off the text field itself. A naive
`onFocusChanged { if (!it.isFocused) revealed = false }` re-hides the instant the user taps
reveal, and the toggle appears dead. `hasFocus` covers descendants, so focus landing on the
icon inside the field's own subtree still counts as being in the field. This compiles clean
either way and is only observable on a device.

**Revealing must not change the keyboard.** `secretMask` is the only thing `revealed` feeds.
If revealing also swapped `KeyboardType.Password` for `Text`, the field would start feeding
the IME cache the moment a user checked their typing — the exact defect #7 is about, reached
by a different route. This gets its own named test.

## Verification

The repo has plain JUnit4 and nothing else — no Robolectric, no `compose-ui-test`, no
`androidTest` source set. A `@Composable` cannot be asserted on. So the decision is extracted
and tested, and the shell is desk-checked, which is how the rest of this codebase already
works.

A throwaway probe confirmed the pure functions are testable at all: `KeyboardOptions`,
`KeyboardType`, `KeyboardCapitalization`, `PasswordVisualTransformation` and
`VisualTransformation.None` all construct **and evaluate** on the JVM stub with no Robolectric
(`tests="2" failures="0"`). Without that result the config functions would have needed to
return a framework-free type for the composable to map over.

1. **Unit** — `:app:testDebugUnitTest`. `SecureFieldsTest` asserts: the secret field uses
   `KeyboardType.Password`; neither field autocapitalises or autocorrects; `keyboardType`
   passes through on the credential field; and revealing flips the mask while leaving the
   keyboard options identical.
2. **Guard** — `check-secret-fields.sh` locally and in CI.
3. **Device** — on a connected handset: masking, no suggestion strip while typing, reveal
   works, reveal auto-hides on focus loss, paste works, and the Client Id no longer
   autocapitalises. The `hasFocus` hazard and the paste requirement are observable **only**
   here.

### The guard

`check-secret-fields.sh` exits 1, printing `file:line`, when an `OutlinedTextField`,
`TextField` or `BasicTextField` call site carries a label or placeholder matching
`secret|password|token|api[-_ ]?key|credential`, excluding `SecureFields.kt` itself.

The component only protects people who know it exists. #7 happened because a field was added
without one line; nothing stops the next field from doing the same. The guard is what makes
this fix durable rather than a one-time correction, and it is why the CI step is part of the
work rather than a nicety.

## Compliance

**Satisfied**

| Requirement | How |
|---|---|
| ASVS 5.0.0 V6.2.6 (L1) | `PasswordVisualTransformation` masks; the reveal toggle is the temporary view the requirement permits |
| ASVS 5.0.0 V6.2.7 (L1) | `AutofillType.Password` node; paste left untouched |
| CWE-549 | the unmasked field at `SettingsScreen.kt:1155` is the instance being closed |
| `Mobile_App_Security#1-ui-data-masking` | masked by default, revealed only on deliberate action |
| `HTML5_Security#...input-hints` | `autoCorrectEnabled = false` on both fields, which is the one setting of the three that changes `EditorInfo`. `capitalization = None` is carried as documented intent and is a no-op. **Partial** — it covers the section's three fields, not the three username fields, which are deferred above. |

**Not satisfied, deliberately**

| Gap | Why not here |
|---|---|
| Plaintext at rest — the secret persists to `routing_server.xml` via `MODE_PRIVATE` SharedPreferences (`RoutingServer.kt:95`), against `Mobile_App_Security#android`'s "avoid storing sensitive data in SharedPreferences" | Tracked in **#26**, which wants a `Prefs` interface first. Touches `shared/`'s expect/actual, needs an iOS Keychain half and a migration path. Not a UI change. |
| The client holds a shared perimeter credential at all | Tracked in **#24** — a design question, not a defect to fix in passing. |
| `IME_FLAG_NO_PERSONALIZED_LEARNING` not set explicitly | Unreachable from Compose 1.7: `PlatformImeOptions` carries only `privateImeOptions: String?`, and the flag is an Int on `EditorInfo.imeOptions`. AOSP's `EditorInfo.update()` sets only `IME_FLAG_FORCE_ASCII`, `IME_FLAG_NO_ENTER_ACTION` and `IME_FLAG_NO_FULLSCREEN`. The protection in practice is `TYPE_TEXT_VARIATION_PASSWORD`, which mainstream IMEs honour by convention rather than contract. Reaching the flag needs an `AndroidView`-hosted `EditText`, discarding Material3 and Compose state ergonomics for one field. |
| `FLAG_SECURE` / screenshot suppression | Nothing in ASVS or the cheat sheets requires it, and it is a window-level flag with app-global effect. Declined on evidence, not omission. |

**Already satisfied elsewhere, and not to be re-litigated here:** cloud-backup exposure.
`backup_rules.xml` and `data_extraction_rules.xml` both exclude `routing_server.xml` by name,
each with its reasoning written into the file. `Mobile_App_Security#android`'s "disable backup
mode" concern is closed by allowlist rather than by `allowBackup="false"`.

## Out of scope

- iOS. `SettingsScreen.swift:131` already uses `SecureField`, and the other two fields already
  carry the autocapitalisation and autocorrect opt-outs. There is nothing to converge.
- A Compose BOM upgrade.
- **The three username fields** — `FriendsScreen.kt:437` ("Their username"),
  `FriendsScreen.kt:737` and `CirclesScreen.kt:646` ("Friend's username"). All three are raw
  `OutlinedTextField`s and all three are autocorrected today.

  The cheat sheet already cited here names them:
  `HTML5_Security_Cheat_Sheet#...input-hints` covers "login credentials (**username**,
  password)". So `CredentialTextField` would fit them, and it is three one-line call-site
  changes to a component this spec builds anyway.

  They are excluded because the case for them is hygiene alone. Checked before deciding:
  `UserRepository.GetByUsernameAsync` queries a **`citext`** column, so lookup is already
  case-insensitive at the database and no capitalisation or autocorrect artefact can break an
  invite. A username is also not a secret — it is displayed in the friends list. Worth a
  follow-up issue, not worth widening a security fix.

  `FriendsScreen.kt:714` ("Convoy name") is a display name, not a credential, and legitimately
  wants ordinary keyboard behaviour. It should not be converted.
- `FriendsScreen.kt`'s sign-in path. It holds no password field — only a button that opens the
  browser leg.

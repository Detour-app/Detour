# Android + Compose packaging precedent — external research

Compiled 2026-09-09. Research task, external sources only; no repository files were
changed as part of this research. This document is a full record of what was fetched —
fuller than the compact summary given in conversation — so a future reader can judge
staleness and verify claims independently.

Refs/commits actually fetched, for staleness tracking:

- `android/nowinandroid` — branch `main`, commit `12f80da6518e161ed16a06a68e71fb8a873576d6`,
  queried 2026-09-09 via `gh api "repos/android/nowinandroid/git/trees/main?recursive=1"`.
- `android/compose-samples` — branch `main`, queried 2026-09-09 via
  `gh api "repos/android/compose-samples/git/trees/main?recursive=1"` (exact commit hash
  not separately recorded by the sub-agent; treat as "main tip on 2026-09-09").
- `chrisbanes/tivi` — branch `main`, queried 2026-09-09 via
  `gh api "repos/chrisbanes/tivi/git/trees/main?recursive=1"` (exact commit hash not
  separately recorded; treat as "main tip on 2026-09-09").
- `duckduckgo/Android` — branch `main`, queried 2026-09-09 via
  `gh api "repos/duckduckgo/Android/git/trees/main?recursive=1"` (exact commit hash not
  separately recorded; treat as "main tip on 2026-09-09").
- `androidx` (Material3 source) — `androidx-main` branch, googlesource.com mirror,
  fetched 2026-09-09, URLs below carry the ref in the path (`refs/heads/androidx-main`),
  so they will follow the branch forward rather than being commit-pinned.
- developer.android.com pages — fetched live 2026-09-09; Google edits these pages
  periodically without versioning, so treat quotes as "true as of this date."

Everything below either carries a verbatim quote + URL, or is marked **unverified**.

---

## 1. Google's official current guidance — feature or layer?

### 1a. developer.android.com/topic/architecture ("Guide to app architecture")

URL: https://developer.android.com/topic/architecture

Verbatim: "Considering common architectural principles, design each application with at
least two layers: UI layer... Data layer..." and "The role of the UI layer (or
presentation layer) is to display the application data on screen."

Interpretation: this is a **layer-based** split at the top architectural level (UI layer
vs. data layer, optionally a domain layer). The page does not address how to organize
*within* the UI layer (by feature vs. by component type) — silent on that finer-grained
question.

### 1b. developer.android.com/topic/architecture/recommendations ("Layered architecture" section)

URL: https://developer.android.com/topic/architecture/recommendations

Verbatim: "In small apps, you can choose to place data layer types in a `data` package or
module." and "In small apps, you can choose to place data layer types in a `ui` package
or module." [sic — the second sentence is reproduced exactly as it appears on Google's
own live page; it is very likely a copy-paste typo on Google's side, since it clearly
intends to say UI-layer types go in a `ui` package, mirroring the sentence about data.
This was verified via two independent fetches of the raw page, so it's not a fetch
artifact — it is Google's own text as of 2026-09-09.]

Interpretation: confirms the same **coarse split by layer** (`ui` package, `data`
package). Says nothing about feature-vs-component-type organization inside `ui`.

### 1c. developer.android.com/topic/modularization ("Guide to Android app modularization")

URL: https://developer.android.com/topic/modularization

No verbatim statement was found on this page contrasting feature vs. layer directly; it
is conceptual (benefits/pitfalls of modularization in general) and points readers to
`/topic/modularization/patterns` for structural guidance.

### 1d. developer.android.com/topic/modularization/patterns

URL: https://developer.android.com/topic/modularization/patterns

Verbatim (feature definition): "A feature is an isolated part of an app's functionality
that usually corresponds to a screen or series of closely related screens... If your app
has a bottom bar navigation, it's likely that each destination is a feature."

Verbatim (shared UI module): "UI module: If you use custom UI elements or elaborate
branding in your app, you should consider encapsulating your widget collection into a
module for all the features to reuse... you can avoid a painful refactor when a rebrand
happens."

Verbatim (module API surface): "Expose as little as possible: The public interface of a
module should be minimal and expose only the essentials."

Interpretation: this is the clearest statement of Google's actual position, and it is
**hybrid, explicitly**: feature modules organized one-per-screen-or-closely-related-
screen-group, *plus* a separate shared "UI module" (widget collection) for cross-feature
reuse. Google does not say the shared UI module's internals must nest by component
variant — the only stated constraint on that module is that its public surface should be
minimal ("expose as little as possible").

### 1e. Compose layering / API guidelines

URLs checked:
- https://developer.android.com/develop/ui/compose/layering
- https://github.com/androidx/androidx/blob/androidx-main/compose/docs/compose-api-guidelines.md

Both fetched successfully (200 OK). Neither page contains any sentence about
file/package organization, one-composable-per-file conventions, or nesting by component
variant. The `compose-api-guidelines.md` document's actual section list is: Kotlin style,
naming composables, stable types, Compose UI API structure (elements/layouts/modifiers),
and API design patterns (stateless/hoisted state) — there is no file-organization section
in this document at all (confirmed by reading the full header/section listing, not just
a keyword miss).

**Unverified / silent**: whether Google has any official position on package
organization *within* a Compose UI module below the module level. Not found in either
Compose-specific doc checked.

### Bottom line for §1

Google's official current guidance is **layer-based only at the top architectural
split** (UI layer vs. data layer — §1a/§1b), and **feature-based for screens/modules**
with a small, separately-scoped shared UI/design-system module for cross-cutting
components (§1d). Nowhere in the fetched official pages does Google state or imply that
UI code should be organized by nesting per component-type/variant (e.g., "all buttons
together, all cards together, further split by variant"); the only "by type" statement
found is the coarse `ui` package vs. `data` package split, plus the singular shared
"UI module" for widgets. The Compose-specific docs checked (layering page, API
guidelines) say nothing about file/package organization at all.

---

## 2. Now in Android (android/nowinandroid) — actual structure

Repo: https://github.com/android/nowinandroid — branch `main`, commit
`12f80da6518e161ed16a06a68e71fb8a873576d6`, queried 2026-09-09 via
`gh api "repos/android/nowinandroid/git/trees/main?recursive=1" --jq '.tree[].path'`.

**This is a plain single-platform Android/Compose app, not Kotlin Multiplatform** — the
full recursive tree listing contains zero paths under any `commonMain` source set.
Anything in training data suggesting NIA is a KMP sample should be treated as stale/wrong
relative to this commit.

Also note a structural drift worth flagging for anyone comparing against older
descriptions of NIA: `feature/*` modules are now split into separate `api` + `impl`
Gradle modules (not one flat module per feature, as in older versions of this sample).

### 2a. Complete top-level module list

`core/`: `analytics`, `common`, `data`, `data-test`, `database`, `datastore`,
`datastore-proto`, `datastore-test`, `designsystem`, `domain`, `model`, `navigation`,
`network`, `notifications`, `screenshot-testing`, `testing`, `ui`

`feature/`: `bookmarks`, `foryou`, `interests`, `search`, `settings`, `topic` — each
(except `settings`) split into `feature/<name>/api` and `feature/<name>/impl` Gradle
modules; `feature/settings` has only an `impl` module (no `api`).

`sync/`: `sync-test`, `work`

Other top-level: `app`, `app-nia-catalog` (a design-system catalog/showcase app),
`benchmarks`, `build-logic/convention`, `lint`, `ui-test-hilt-manifest`, `docs/`.

### 2b. Full `core/designsystem/.../component/` file listing

Base path: `core/designsystem/src/main/kotlin/com/google/samples/apps/nowinandroid/core/designsystem/`

`component/` (flat, one file per component/component-family — no per-component or
per-variant subpackages):
- `component/Background.kt`
- `component/Button.kt`
- `component/Chip.kt`
- `component/DynamicAsyncImage.kt`
- `component/IconButton.kt`
- `component/LoadingWheel.kt`
- `component/Navigation.kt`
- `component/Tabs.kt`
- `component/Tag.kt`
- `component/TopAppBar.kt`
- `component/ViewToggle.kt`

`component/scrollbar/` — the **one** exception to flatness, a small dedicated
subpackage:
- `component/scrollbar/AppScrollbars.kt`
- `component/scrollbar/LazyScrollbarUtilities.kt`
- `component/scrollbar/Scrollbar.kt`
- `component/scrollbar/ScrollbarExt.kt`
- `component/scrollbar/ThumbExt.kt`

Sibling packages under the same `designsystem` root:
- `icon/NiaIcons.kt`
- `theme/Background.kt`
- `theme/Color.kt`
- `theme/Gradient.kt`
- `theme/Theme.kt`
- `theme/Tint.kt`
- `theme/Type.kt`

So: components are flat files inside `component/` (e.g. there is no
`component/button/NiaButton.kt`); the only nesting anywhere in designsystem is the
5-file `scrollbar` cluster, which groups a component *family with internal
helper/extension files*, not variants of one component.

### 2c. Full `core/ui/` file listing

Base path: `core/ui/src/main/kotlin/com/google/samples/apps/nowinandroid/core/ui/`

Fully flat, no subpackages at all:
- `AnalyticsExtensions.kt`
- `DevicePreviews.kt`
- `FollowableTopicPreviewParameterProvider.kt`
- `InterestsItem.kt`
- `JankStatsExtensions.kt`
- `LocalTimeZone.kt`
- `NewsFeed.kt`
- `NewsResourceCard.kt`
- `NewsResourceCardList.kt`
- `UserNewsResourcePreviewParameterProvider.kt`

### 2d. Verbatim `core:designsystem` vs `core:ui` distinction

The per-module `README.md` files (`core/designsystem/README.md`, `core/ui/README.md`)
are auto-generated and contain only a Mermaid dependency graph — no prose distinction.
The actual stated distinction lives in `docs/ModularizationLearningJourney.md`:

URL: https://github.com/android/nowinandroid/blob/main/docs/ModularizationLearningJourney.md

Verbatim — **core:designsystem**: "Design system which includes Core UI components
(many of which are customized Material 3 components), app theme and icons. The design
system can be viewed by running the `app-nia-catalog` run configuration." (examples
given: `NiaIcons`, `NiaButton`, `NiaTheme`)

Verbatim — **core:ui**: "Composite UI components and resources used by feature modules,
such as the news feed. Unlike the `designsystem` module, it is dependent on the data
layer since it renders models, like news resources." (examples given: `NewsFeed`,
`NewsResourceCardExpanded`)

The same document also states a general placement rule (relevant to this repo's own
`base/` proposal): "If a class is needed only by one feature module, it should remain
within that module. If not, it should be placed into an appropriate core module."

### 2e. `feature/foryou` listing (representative feature module)

`feature/foryou/api/src/main/kotlin/com/google/samples/apps/nowinandroid/feature/foryou/api/`:
- `navigation/ForYouNavKey.kt` (just the nav-key contract — this is the entire `api`
  module's content)

`feature/foryou/impl/src/main/kotlin/com/google/samples/apps/nowinandroid/feature/foryou/impl/`:
- `ForYouScreen.kt`
- `ForYouViewModel.kt`
- `OnboardingUiState.kt`
- `navigation/ForYouEntryProvider.kt`

Screen, ViewModel and UI-state file all sit **flat together in one `impl` package** —
only `navigation/` gets its own subpackage. The same flat pattern repeats across
bookmarks/interests/search/topic. `feature/settings` (no `api` module) is simply
`impl/SettingsDialog.kt` + `impl/SettingsViewModel.kt`.

---

## 3. Other OSS Compose apps — actual layouts

### 3a. android/compose-samples

Repo: https://github.com/android/compose-samples — branch `main`, queried 2026-09-09
via `gh api "repos/android/compose-samples/git/trees/main?recursive=1" --jq '.tree[].path'`.

**Jetsnack** — root `Jetsnack/app/src/main/java/com/example/jetsnack/ui/`

`ui/components/` (flat, 12 files, zero subfolders):
- `Button.kt`
- `Card.kt`
- `Divider.kt`
- `Filters.kt`
- `Gradient.kt`
- `GradientTintedIconButton.kt`
- `Grid.kt`
- `QuantitySelector.kt`
- `Scaffold.kt`
- `Snackbar.kt`
- `Snacks.kt`
- `Surface.kt`

`ui/theme/` (flat): `Color.kt`, `Shape.kt`, `Theme.kt`, `Type.kt`

Screens organized by feature, as sibling packages of `components`/`theme`:
`ui/home/`, `ui/home/cart/`, `ui/home/search/`, `ui/snackdetail/`, `ui/navigation/`.

**Reply** — root `Reply/app/src/main/java/com/example/reply/ui/`

`ui/components/` (flat, 4 files): `ReplyAppBars.kt`, `ReplyEmailListItem.kt`,
`ReplyEmailThreadItem.kt`, `ReplyProfileImage.kt`

`ui/theme/` (flat): `Color.kt`, `Shapes.kt`, `Theme.kt`, `Type.kt`

No per-screen feature packages at all in Reply — screens (`ReplyListContent.kt`,
`EmptyComingSoon.kt`) sit directly in `ui/` (small app, no need felt for feature
packaging).

**JetNews** — root `JetNews/app/src/main/java/com/example/jetnews/ui/`

`ui/components/` (flat, only 2 files): `AppNavRail.kt`, `JetnewsSnackbarHost.kt`

`ui/theme/` (flat)

Screens organized by feature: `ui/home/`, `ui/interests/`, `ui/post/`, `ui/navigation/`.

**Verdict for all three (Jetsnack, Reply, JetNews): flat-by-file.** A grep across the
whole compose-samples tree for a two-level nesting pattern
(`/(button|card|icon|component)s?/[^/]+/`) returned **no matches** — no
`components/button/` style folder exists anywhere in this repo.

### 3b. chrisbanes/tivi

Repo: https://github.com/chrisbanes/tivi — branch `main`, queried 2026-09-09 via
`gh api "repos/chrisbanes/tivi/git/trees/main?recursive=1" --jq '.tree[].path'`.

Design-system module: `common/ui/compose/src/{commonMain,androidMain,iosMain,jvmMain}/kotlin/app/tivi/common/compose/`

`.../compose/ui/` subpackage (flat, one level, no per-component subfolders):
`AppBar.kt`, `Backdrop.kt`, `BackdropCard.kt`, `Empty.kt`, `Icon.kt`,
`LoadingButton.kt`, `PosterCard.kt`, `RefreshButton.kt`, `SearchTextField.kt`,
`SortChip.kt`, `Surface.kt`, `UserProfileButton.kt`, and others in the same flat
bucket.

`.../compose/theme/` subpackage (flat): `Color.kt`, `Platform.kt`, `Shape.kt`,
`Theme.kt`, `Type.kt`

Note this module is itself a KMP module (has `commonMain`/`androidMain`/`iosMain`/
`jvmMain` source sets) — Tivi does share Compose UI across platforms via Compose
Multiplatform, unlike Detour, where iOS is native SwiftUI. This is a structural
difference worth keeping in mind when using Tivi as precedent for §5 (KMP placement).

Per-screen UI in Tivi is organized as **separate Gradle modules per feature**, not
subpackages of one module: `ui/discover/`, `ui/library/`, `ui/search/`,
`ui/settings/`, `ui/account/`, `ui/popular/`, `ui/recommended/`, `ui/anticipated/`,
`ui/episode/{details,track}/`, `ui/show/{details,seasons}/`, `ui/root/`,
`ui/developer/{log,notifications,settings}/`. Each contains Kotlin under
`app.tivi.home.<feature>`, e.g. `ui/discover/src/commonMain/kotlin/app/tivi/home/discover/Discover.kt`,
`DiscoverPresenter.kt`, `DiscoverUiState.kt` — screen, presenter and state file sit
flat together in one package, mirroring the NIA `feature/foryou/impl` pattern.

**Verdict: flat-by-file** for the shared UI/design-system module (single `ui/` bucket,
no per-component or per-variant subfolders).

### 3c. duckduckgo/Android

Repo: https://github.com/duckduckgo/Android — branch `main`, queried 2026-09-09 via
`gh api "repos/duckduckgo/Android/git/trees/main?recursive=1" --jq '.tree[].path'`.

Real Compose UI confirmed: 65 `.kt` files found under `/compose/` paths in the tree,
alongside a broader legacy View-based `common/ui/view/` tree (DDG is mid-migration
from Views to Compose).

Dedicated design-system module:
`android-design-system/design-system/src/main/java/com/duckduckgo/common/ui/compose/`

Subpackages, one per component **type** (never per variant):

`button/` — full file listing (10 files, all flat siblings, no further subfolders):
- `DaxButton.kt`
- `DaxPrimaryButton.kt`
- `DaxSecondaryButton.kt`
- `DaxOutlinedButton.kt`
- `DaxGhostButton.kt`
- `DaxIconButton.kt`
- `DaxDestructivePrimaryButton.kt`
- `DaxDestructiveGhostButton.kt`
- `DaxDestructiveGhostAltButton.kt`
- `DaxDestructiveGhostSecondaryButton.kt`

Other type-folders present at the same nesting level (one folder per component type,
each internally flat): `cards/`, `checkbox/`, `dialog/`, `divider/`, `listitem/`,
`message/` (with one deeper `message/remote/` for remote-message content subtypes —
a content category, not a button-style variant), `panel/`, `pill/`, `progress/`,
`radiobutton/`, `sheets/`, `snackbar/`, `switch/`, `template/`, `text/`, `textfield/`,
`theme/`, `tools/`, `appbars/`, `layout/`.

A legacy View-based mirror exists too, same pattern: `android-design-system/design-system/.../view/button/DaxButtonPrimary.kt`,
`DaxButtonSecondary.kt`, `DaxButtonBrand.kt`, `DaxButtonDestructive.kt` — variant
expressed in the filename, not a subfolder, in the View-based code too.

**Verdict: nested-by-component-type (one level only), never per-variant.** A grep for
`/(button|card|icon|component)s?/[^/]+/` across the whole DDG tree found no true
two-level variant nesting — the one hit (`message/remote/`) is a content category, not
a component-style variant.

### 3d. Summary across §3

Across all apps investigated (Jetsnack, Reply, JetNews, Tivi, DuckDuckGo), **nobody
nests one subpackage per component variant.** The observed spectrum is:

1. Flat single bucket, no subfolders (Jetsnack, Reply, JetNews, Tivi) — variant is a
   different composable name inside the same flat package.
2. Flat-per-component-type folder, variant baked into the filename (DuckDuckGo) —
   `button/DaxButtonPrimary.kt`, `button/DaxButtonSecondary.kt`, never
   `button/primary/`, `button/secondary/`.

Per-screen UI is always feature-packaged (subpackage in the samples/DDG, separate
Gradle module in Tivi) — except Reply, which is small enough to have skipped
screen-level packaging entirely.

---

## 4. Material3 as the counter-example (design-system component nesting)

URL (Button.kt source): https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Button.kt

`Button.kt` is **1939 lines**, a single flat file, in the flat `androidx.compose.material3`
package (no subpackage). It contains all of the following button-family composables —
**10 `@Composable` functions total** (5 variants × 2 overloads each: one taking an
`onClick`/content lambda pair, one taking additional parameters):

- `Button`
- `ElevatedButton`
- `FilledTonalButton`
- `OutlinedButton`
- `TextButton`

Variants are distinguished entirely by **composable function name + parameters/
defaults**, not by any folder structure.

URL (directory listing): https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/

This directory listing confirms the pattern holds repo-wide for Material3: every
component (`AlertDialog.kt`, `AppBar.kt`, `Badge.kt`, `Card.kt`, `Chip.kt`,
`IconButton.kt`, etc.) is a single flat file directly under the flat
`androidx.compose.material3` package. **No per-component or per-variant subpackages
exist anywhere in the library.**

Supplementary (general, non-file-organization) guidance:
https://developer.android.com/develop/ui/compose/designsystems/custom suggests
creating "a new package called `components`" and adding button components to it — i.e.
a package per component *type*, not per variant. Cash App's Redwood (a multiplatform
Compose design-system generator) write-up, https://code.cash.app/native-ui-and-multiplatform-compose-with-redwood,
was checked and surfaced no package-structure detail supporting per-variant nesting
either.

---

## 5. Per-variant-nesting verdict — explicit negative

**Claim being tested**: is `base/button/{confirm,deny,download,progress}`,
`base/modal/{confirm,acknowledge,info}` (one filesystem package per component
*variant*) precedented anywhere in real, sizeable Compose codebases?

**Everything checked to establish this**:
- Material3 (`androidx.compose.material3`) — flat package, `Button.kt` holds 5
  variants × 2 overloads in one 1939-line file. See §4.
- Now in Android `core/designsystem/component/` — flat, one file per component
  family; the only subpackage (`scrollbar/`) groups helper files for one component,
  not variants of it. See §2b.
- Jetsnack `ui/components/` — flat, 12 files, no subfolders. See §3a.
- Reply `ui/components/` — flat, 4 files, no subfolders. See §3a.
- JetNews `ui/components/` — flat, 2 files, no subfolders. See §3a.
- Tivi `common/compose/ui/` — flat, no subfolders. See §3b.
- DuckDuckGo Android `design-system/.../compose/button/` — one folder per component
  type, but **flat within it**: 10 button-variant files as siblings, not further
  nested. See §3c.
- General Compose design-system guidance (developer.android.com custom-design-systems
  page) and a design-systems-focused blog piece (designsystemscollective.com) — both
  describe component-type packages (`components/`), neither describes per-variant
  folders.
- A broad web search for real-world Compose design systems using folder-per-variant
  nesting turned up **no examples** (Twitter/X, Airbnb/Showkase-catalogued systems,
  Cash App Redwood were all considered; none showed this pattern).

**Conclusion**: no precedent was found, in any source checked, for one filesystem
package per component variant. The proposed structure directly contradicts the
observed convention everywhere: Material3, Now in Android, Jetsnack, Reply, JetNews,
Tivi, and DuckDuckGo all keep variants as sibling files (or overloads inside one file)
within a single component-type folder or package, never a further subfolder per
variant.

---

## 6. KMP-specific: where should design-system/base components live?

Detour's shape for comparison: a `shared/` commonMain module, an `app/` Android
module (Compose UI), an Android Auto surface, and `iosApp/` using **native SwiftUI**
— i.e., unlike Tivi (§3b), Compose UI itself is not shared cross-platform in this
project; only business/domain logic is shared via expect/actual.

- Kotlin's official recommended-project-structure documentation describes the
  standard `commonMain`/`androidMain`/`iosMain` split and the `expect`/`actual`
  mechanism generically (commonMain = platform-independent code; platform source
  sets = platform-specific implementations of `expect` declarations). It does not
  contain a UI-specific rule.
  URL: https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html

- The clearest applicable principle found, **surfaced via web search and not
  independently confirmed against the primary source page** (see honesty marker
  below): "If some targets implement native UI, it's a good idea to separate common
  code into `sharedLogic` and `sharedUI` modules, so that app modules with native UI
  don't need to depend on Compose Multiplatform to use shared code." This matches
  Detour's shape (iOS = SwiftUI, not Compose Multiplatform) closely, and if accurate
  would argue for keeping a Compose-based `base/` design-system package out of
  `shared/`.

  **Honesty marker, verbatim as required**: this sentence is labeled **reasonably-
  supported inference, NOT a quoted rule** — the underlying architectural claim
  (sharedLogic vs. sharedUI module separation) is consistent with the officially
  documented recommended project structure page above, but I was **not able to
  independently WebFetch-confirm the exact source page containing this exact
  sentence**, and could not pin it to a verified kotlinlang.org or jetbrains.com URL.
  Treat the precise wording and its attribution as **unverified**.

- A secondary, separately-sourced framing (also from web search, same caveat
  applies): "KMP's core value proposition is sharing business logic: the code that
  doesn't touch pixels. Authentication flows, API clients, data validation, caching
  strategies, domain rules, and state management can all live in shared Kotlin
  modules," recommending teams start with shared business logic + fully native UI
  per platform, adding Compose Multiplatform only when a shared-UI codebase is a
  deliberate choice.
  URL: https://batteriesincluded.io/insights/kotlin-multiplatform-and-compose-multiplatform
  (this is a third-party blog, not an official Kotlin/JetBrains source — weight
  accordingly.)

**No direct official source was found** stating explicitly, in those words, that
"Compose design-system components must never live in commonMain when the other
platform uses native SwiftUI." Neither kotlinlang.org's multiplatform docs nor
jetbrains.com/help/kotlin-multiplatform-dev were found to address this exact scenario
head-on in the pages checked. **This specific claim is UNVERIFIED — no direct official
guidance found.** The closest supporting inference is the sharedLogic/sharedUI
module-separation principle above, which argues for keeping Compose UI (including a
`base/` design-system package) out of a module that a SwiftUI-only iOS target would
otherwise have to depend on — but this is inference from an unverified secondary
source, not a citable rule.

---

## Overall net read

Google and every real-world example surveyed support feature-first screen packages
plus a *shallow* shared UI/design-system area — never nested deeper than one level
(component type), and never one folder per variant. The proposed
`base/button/{confirm,deny,download,progress}`, `base/modal/{confirm,acknowledge,info}`
pattern has no precedent in Material3, Now in Android, or any of the five other apps
checked (§5), and contradicts the observed convention of siblings-in-one-folder or
overloads-in-one-file for variants. The KMP module-placement question (`shared/` vs.
`app/` for the design-system package) has no direct Google/JetBrains ruling found for
this exact SwiftUI+Compose split (§6); the closest applicable principle points toward
keeping such a package in `app/`, not `shared/`, but that pointer is flagged as
unverified inference, not a citable rule.

# Is a deeply nested package tree idiomatic Kotlin, or Java habit? — research

Date: 2026-09-09

## Sources: fetched live vs. searched

Fetched live (full page or full source file read, not summarized from search snippets):

- https://kotlinlang.org/docs/coding-conventions.html (WebFetch)
- https://developer.android.com/kotlin/style-guide (WebFetch)
- https://kotlinlang.org/docs/visibility-modifiers.html (WebFetch)
- https://detekt.dev/docs/rules/naming/ (WebFetch, for PackageNaming description before source was pulled)
- https://phauer.com/2020/package-by-feature/ (WebFetch)
- https://developer.android.com/topic/modularization/patterns (WebFetch)
- https://discuss.kotlinlang.org/t/kotlin-to-support-package-protected-visibility/1544 (WebFetch)
- Raw rule source pulled directly from GitHub via `curl`/`gh api` (ground truth, not paraphrase):
  - `ktlint-ruleset-standard/.../rules/PackageNameRule.kt` — https://github.com/ktlint/ktlint
  - `ktlint-ruleset-standard/.../rules/FilenameRule.kt` — https://github.com/ktlint/ktlint
  - `detekt-rules-naming/.../PackageNaming.kt` — https://github.com/detekt/detekt
  - `detekt-rules-naming/.../MatchingDeclarationName.kt` — https://github.com/detekt/detekt
  - `detekt-rules-complexity/.../TooManyFunctions.kt` — https://github.com/detekt/detekt

Searched only (used for orientation / triangulation, not quoted as primary evidence unless noted):

- WebSearch: "ktlint package-name rule filename rule 2026"
- WebSearch: "detekt MatchingDeclarationName TooManyFunctions rule default style ruleset"
- WebSearch: "Kotlin Compose package per component internal visibility module encapsulation feature module"
- WebSearch: "Android package by feature vs package by layer Compose large team module extraction internal visibility"
- WebSearch: "pinterest ktlint PackageNameRule.kt github source" (led to discovering the repo move)

**Repo move note:** ktlint's repository and package namespace moved. It is no longer `pinterest/ktlint` / `com.pinterest.ktlint.*` in the current `master` branch — it is now `ktlint/ktlint` on GitHub, with rule classes under `io.github.ktlint.core.ruleset.standard.rules`. Older docs/URLs (e.g. `pinterest.github.io/ktlint/...`, `github.com/pinterest/ktlint/...`) 404'd during this research; the working source tree is at `github.com/ktlint/ktlint`, path `ktlint-ruleset-standard/src/main/kotlin/io/github/ktlint/core/ruleset/standard/rules/`.

Attempted but unreachable (404, noted rather than guessed): `pinterest.github.io/ktlint/0.49.1/rules/standard/`, `pinterest.github.io/ktlint/1.5.0/rules/standard/`, `pinterest.github.io/ktlint/rules/standard/`, `blog.sourced-bvba.be/article/2018/05/14/clean-architecture-kotlin-visibility/`.

---

## 1. kotlinlang.org coding conventions — verbatim

Source: https://kotlinlang.org/docs/coding-conventions.html

**Directory structure** (section "Directory structure"):

> "In pure Kotlin projects, the recommended directory structure follows the package structure with the common root package omitted. For example, if all the code in the project is in the `org.example.kotlin` package and its subpackages, files with the `org.example.kotlin` package should be placed directly under the source root, and files in `org.example.kotlin.network.socket` should be in the `network/socket` subdirectory of the source root."

> "On JVM: In projects where Kotlin is used together with Java, Kotlin source files should reside in the same source root as the Java source files, and follow the same directory structure: each file should be stored in the directory corresponding to each package statement."

This is presented as a *recommendation* about where files live relative to package names already chosen — it says nothing about how many packages to create or how deep to nest them. It does not argue for one-package-per-component.

**Multiple declarations per file** (section "Source file organization"):

> "Placing multiple declarations (classes, top-level functions or properties) in the same Kotlin source file is encouraged as long as these declarations are closely related to each other semantically, and the file size remains reasonable (not exceeding a few hundred lines)."

**File naming** (section "Source file names"):

> "If a Kotlin file contains a single class or interface (potentially with related top-level declarations), its name should be the same as the name of the class, with the `.kt` extension appended. It applies to all types of classes and interfaces. If a file contains multiple classes, or only top-level declarations, choose a name describing what the file contains, and name the file accordingly. Use [upper camel case](https://en.wikipedia.org/wiki/Camel_case), where the first letter of each word is capitalized. For example, `ProcessDeclarations.kt`."

**Package naming / casing** (section "Naming rules"):

> "Names of packages are always lowercase and do not use underscores (`org.example.project`). Using multi-word names is generally discouraged, but if you do need to use multiple words, you can either just concatenate them together or use camel case (`org.example.myProject`)."

This is the load-bearing quote for the proposal's camelCase segments (`userCard`): Kotlin's own convention explicitly sanctions camel case as one of the two acceptable ways to spell a multi-word package segment.

**"Avoid creating files just to hold extensions"** (section "Source file organization"):

> "In particular, when defining extension functions for a class which are relevant for all clients of this class, put them in the same file with the class itself. When defining extension functions that make sense only for a specific client, put them next to the code of that client. Avoid creating files just to hold all extensions of some class."

This is a direct, on-point rebuttal of the instinct behind the proposal: don't fragment things into their own file/package just because they're "about" one class or concept — put them where they're used, and only split when there's a real semantic reason.

---

## 2. The Java-vs-Kotlin calculus

**Why Java habit produces deep trees:** Java requires (mechanically, via the compiler) that a public top-level type live in its own file named after that type, and gives no language mechanism for grouping several related-but-distinct types except a shared directory (package). So when a Java codebase has several small, related concepts — five button variants, two modal variants, a card, two HUD readouts — the *only* available grouping/namespacing tool is the package, and the habit of "one destination folder per concept" becomes the only lever for organizing anything smaller than a full class.

**What removes the constraint in Kotlin:** two facts, both cited above from https://kotlinlang.org/docs/coding-conventions.html:
1. "Placing multiple declarations ... in the same Kotlin source file is encouraged as long as these declarations are closely related ... and the file size remains reasonable."
2. File name need not equal type name — a file's name only has to "describe what the file contains" when it holds more than one declaration.

Because Kotlin lets several `@Composable` functions/classes live in one file, and lets that file be named for the *category* rather than for one member of the category, the grouping unit that Java expresses as a **directory** becomes, in Kotlin, a **file**. The package tree no longer needs a leaf per component to keep things organized — the file does that job one level up.

**Concrete comparison for the proposal:**

Proposed tree (Java-habit shape):
```
ui/base/button/confirm/     <- ConfirmButton
ui/base/button/deny/
ui/base/button/download/
ui/base/button/progress/
ui/base/modal/confirm/
ui/base/modal/acknowledge/
ui/base/card/userCard/
ui/base/hud/speed/
ui/base/hud/avg/
```
That's **9 leaf directories** (9 distinct packages), each presumably holding at least one file — i.e. on the order of 9-15 files spread across 9 packages, for what is functionally: 4 button variants, 2 modal variants, 1 card, 2 HUD readouts.

Idiomatic-Kotlin shape, grouping by semantic category instead of by variant:
```
ui/base/button/Buttons.kt      <- ConfirmButton, DenyButton, DownloadButton, ProgressButton
ui/base/modal/Modals.kt        <- ConfirmModal, AcknowledgeModal
ui/base/card/UserCard.kt
ui/base/hud/Hud.kt             <- SpeedHud, AvgHud
```
That's **4 files across 4 packages** (or fewer, if button/modal/hud are judged closely related enough to live in one `ui/base` package directly) — no per-variant subpackage at all. The button and modal files could split into two files each (e.g. `Buttons.kt` / `ProgressButton.kt` if progress genuinely has independent, non-trivial state) without ever creating a directory per variant.

So: Java habit → ~9 directories / ~9-15 files. Idiomatic Kotlin → ~3-4 files / ~3-4 packages (one per functional category, not per component, and never per variant).

---

## 3. What ktlint / detekt / Android's style guide actually enforce

All rule text below is quoted from the actual rule source files (pulled live via `curl`/`gh api`, not from documentation summaries), except the Android style guide quotes which come from the live-fetched HTML page.

### ktlint `package-name`

Source: `ktlint-ruleset-standard/src/main/kotlin/io/github/ktlint/core/ruleset/standard/rules/PackageNameRule.kt`, repo https://github.com/ktlint/ktlint

```kotlin
/**
 * https://kotlinlang.org/docs/coding-conventions.html#naming-rules
 */
@SinceKtlint("0.25", STABLE)
public class PackageNameRule : StandardRule("package-name") {
    override fun beforeVisitChildNodes(
        node: ASTNode,
        emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision,
    ) {
        node
            .takeIf { node.elementType == PACKAGE_DIRECTIVE }
            ?.firstChildNode
            ?.nextCodeSibling
            ?.takeIf { it.elementType == DOT_QUALIFIED_EXPRESSION || it.elementType == REFERENCE_EXPRESSION }
            ?.let { expression ->
                if (expression.text.contains('_')) {
                    emit(expression.startOffset, "Package name must not contain underscore", false)
                } else if (!expression.text.matches(VALID_PACKAGE_NAME_REGEXP)) {
                    emit(expression.startOffset, "Package name contains a disallowed character", false)
                }
            }
    }

    private companion object {
        val VALID_PACKAGE_NAME_REGEXP =
            "[a-z][a-zA-Z\\d]*(\\.[a-z][a-zA-Z\\d]*)*"
                .regExIgnoringDiacriticsAndStrokesOnLetters()
    }
}
```

Rule id: `package-name`. Default regex, exactly as fetched: `[a-z][a-zA-Z\d]*(\.[a-z][a-zA-Z\d]*)*`. Error messages: `"Package name must not contain underscore"` and `"Package name contains a disallowed character"`.

**Does it accept `userCard`?** Yes. Each segment must start with a lowercase letter (`[a-z]`), then may contain any mix of upper/lowercase letters and digits (`[a-zA-Z\d]*`). `userCard` — lowercase `u` then `serCard` (letters, including uppercase `C`) — matches cleanly. This rule would not flag the proposal's camelCase segments.

### ktlint `filename`

Source: `ktlint-ruleset-standard/src/main/kotlin/io/github/ktlint/core/ruleset/standard/rules/FilenameRule.kt`, repo https://github.com/ktlint/ktlint

```kotlin
/**
 * [Kotlin lang documentation](https://kotlinlang.org/docs/coding-conventions.html#source-file-names):
 * If a Kotlin file contains a single class (potentially with related top-level declarations), its name should be
 * the same as the name of the class, with the `.kt` extension appended. If a file contains multiple classes,
 * or only top-level declarations, choose a name describing what the file contains, and name the file accordingly.
 * Use upper camel case with an uppercase first letter (also known as Pascal case),
 * for example, `ProcessDeclarations.kt`.
 *
 * According to issue https://youtrack.jetbrains.com/issue/KTIJ-21897/Kotlin-coding-convention-file-naming-for-class,
 * "class" above should be read as any type of class (data class, enum class, sealed class) and interfaces.
 *
 * A strict implementation of guideline above had unwanted consequences:
 *   - If the file contains a single top level private class, it does not make sense to force the name of file to be
 *     identical to that class.
 *   - If the file contains a coherent set of functions and one of those function returns an instance of a public class
 *     which happens to be the only top level class in that file, it might not always be best to force the file to be
 *     named after that class.
 *   - Existing functionality regarding files containing a single top level object/typealias was lost.
 *
 * Exceptions to this rule:
 * - file without `.kt` extension
 * - file with name `package.kt`
 */
@SinceKtlint("0.23", SinceKtlint.Status.STABLE)
public class FilenameRule : StandardRule("filename") {
```

Rule id: `filename`. Behaviour, from the logic in the file: if there is exactly one non-private top-level class and no unrelated top-level declaration, the file must be named after that class; otherwise (multiple classes, a lone top-level object/typealias, or a mix of top-level functions/properties) the file only has to be **PascalCase** describing its contents — there is no requirement to match any single declaration's name. This is the rule that makes `Buttons.kt` holding four button composables a fully compliant file.

**Does it police directory-vs-package match?** No. Nothing in this rule (or anywhere else in ktlint's standard rule set, per the search/fetch above) inspects the file's directory path against its package statement.

### detekt `PackageNaming`

Source: `detekt-rules-naming/src/main/kotlin/dev/detekt/rules/naming/PackageNaming.kt`, repo https://github.com/detekt/detekt

```kotlin
/**
 * Reports package names that do not follow the specified naming convention.
 */
@ActiveByDefault(since = "1.0.0")
@Alias("PackageName")
class PackageNaming(config: Config) :
    Rule(config, "Package names should follow the naming convention set in detekt's configuration.") {

    @Configuration("naming pattern")
    private val packagePattern: Regex by config("""[a-z]+(\.[a-z][A-Za-z0-9]*)*""") { it.toRegex() }

    override fun visitPackageDirective(directive: KtPackageDirective) {
        val name = directive.qualifiedName
        if (name.isNotEmpty() && !name.matches(packagePattern)) {
            report(
                Finding(
                    Entity.from(directive),
                    message = "Package name should match the pattern: $packagePattern"
                )
            )
        }
    }
}
```

Rule id: `PackageNaming` (naming ruleset), active by default since 1.0.0. Default regex, exactly as fetched: `[a-z]+(\.[a-z][A-Za-z0-9]*)*`.

**Does it accept `userCard`?** Yes, for the same shape of reason as ktlint: the first segment must be all-lowercase letters, but every subsequent dot-separated segment is `[a-z][A-Za-z0-9]*` — starts lowercase, then may contain uppercase letters and digits. `userCard` as a non-first segment matches.

### detekt `MatchingDeclarationName`

Source: `detekt-rules-naming/src/main/kotlin/dev/detekt/rules/naming/MatchingDeclarationName.kt`, repo https://github.com/detekt/detekt

```kotlin
/**
 * "If a Kotlin file contains a single non-private class (potentially with related top-level declarations),
 * its name should be the same as the name of the class, with the .kt extension appended.
 * If a file contains multiple classes, or only top-level declarations,
 * choose a name describing what the file contains, and name the file accordingly.
 * Use camel humps with an uppercase first letter (e.g. ProcessDeclarations.kt).
 *
 * The name of the file should describe what the code in the file does.
 * Therefore, you should avoid using meaningless words such as "Util" in file names." - Official Kotlin Style Guide
 *
 * More information at: https://kotlinlang.org/docs/coding-conventions.html
 *
 * <noncompliant>
 *
 * class Foo // FooUtils.kt
 *
 * fun Bar.toFoo(): Foo = ...
 * fun Foo.toBar(): Bar = ...
 *
 * </noncompliant>
 *
 * <compliant>
 *
 * class Foo { // Foo.kt
 *     fun stuff() = 42
 */
@ActiveByDefault(since = "1.0.0")
```

Active by default since 1.0.0. This rule quotes the official Kotlin style guide verbatim in its own KDoc and enforces exactly that: a single non-private class needs a matching file name; a file with multiple classes or only top-level declarations just needs a descriptive name. Nothing here penalizes grouping several related declarations into one file — it only fires when a *single* class sits in a misnamed file.

### detekt `TooManyFunctions`

Source: `detekt-rules-complexity/src/main/kotlin/dev/detekt/rules/complexity/TooManyFunctions.kt`, repo https://github.com/detekt/detekt

```kotlin
/**
 * This rule reports files, classes, interfaces, objects and enums which contain too many functions.
 * Each element can be configured with different thresholds.
 *
 * Too many functions indicate a violation of the single responsibility principle. Prefer extracting functionality
 * which clearly belongs together in separate parts of the code.
 */
@ActiveByDefault(since = "1.0.0")
class TooManyFunctions(config: Config) :
    Rule(
        config,
        "Too many functions inside a/an file/class/object/interface always indicate a violation of " +
            "the single responsibility principle. Maybe the file/class/object/interface wants to manage too " +
            "many things at once. Extract functionality which clearly belongs together."
    ) {

    @Configuration("The maximum allowed functions per file")
    private val allowedFunctionsPerFile: Int by config(DEFAULT_THRESHOLD)

    @Configuration("The maximum allowed functions per class")
    private val allowedFunctionsPerClass: Int by config(DEFAULT_THRESHOLD)

    @Configuration("The maximum allowed functions per interface")
    private val allowedFunctionsPerInterface: Int by config(DEFAULT_THRESHOLD)

    @Configuration("The maximum allowed function per object")
    private val allowedFunctionsPerObject: Int by config(DEFAULT_THRESHOLD)

    @Configuration("The maximum allowed functions in enums")
    private val allowedFunctionsPerEnum: Int by config(DEFAULT_THRESHOLD)
```

and, further down in the same file:

```kotlin
const val DEFAULT_THRESHOLD = 11
```

Rule id: `TooManyFunctions` (complexity ruleset), active by default since 1.0.0. Default threshold: **11**, applied identically to `allowedFunctionsPerFile`, `allowedFunctionsPerClass`, `allowedFunctionsPerInterface`, `allowedFunctionsPerObject`, `allowedFunctionsPerEnum` — i.e. a *file* is allowed up to 11 top-level functions by default, same budget as a class. This is a per-file/per-class function-count budget; it argues for splitting a file once it's doing too much, not for giving every component its own package.

### detekt `MaxLineLength`

Source: https://detekt.dev/docs/rules/style/ (style ruleset)

> "This rule reports lines of code which exceed a defined maximum line length. Long lines might be hard to read on smaller screens or printouts. Additionally, having a maximum line length in the codebase will help make the code more uniform."

Default: `maxLineLength` = 120. Again a size/readability budget on individual lines, unrelated to package depth.

### Summary: what these tools do and don't police

None of `package-name`, `filename`, `PackageNaming`, `MatchingDeclarationName`, `TooManyFunctions`, or `MaxLineLength` — the full set of naming/size rules checked for this research — say anything about how many packages a module should have, how deep they should nest, or how many components may share a package. **No rule in ktlint's or detekt's rule sets polices package depth or component-per-package granularity.** The only two things actually enforced are (a) package/file *name spelling* (casing, underscores, PascalCase) and (b) per-file/per-class *size* (function count, line length) — both satisfied by grouping several small composables into one reasonably-sized, well-named file.

### Android's Kotlin style guide

Source: https://developer.android.com/kotlin/style-guide

**Source Files → Naming:**

> "If a source file contains only a single top-level class, the file name should reflect the case-sensitive name plus the `.kt` extension. Otherwise, if a source file contains multiple top-level declarations, choose a name that describes the contents of the file, apply PascalCase (camelCase is acceptable if the filename is plural), and append the `.kt` extension."

with the worked examples:

```kotlin
// MyClass.kt
class MyClass { }

// Bar.kt
class Bar { }
fun Runnable.toBar(): Bar = Bar()

// Map.kt
fun <T, O> Set<T>.map(func: (T) -> O): List<O> = emptyList()
fun <T, O> List<T>.map(func: (T) -> O): List<O> = emptyList()

// extensions.kt
fun MyClass.process() = { /* ... */ }
fun MyResult.print() = { /* ... */ }
```

**Package Names:**

> "Package names are all lowercase, with consecutive words simply concatenated together (no underscores)."

```kotlin
// Okay
package com.example.deepspace

// WRONG!
package com.example.deepSpace

// WRONG!
package com.example.deep_space
```

**Structure:**

> "A `.kt` file comprises the following, in order:
>
> - Copyright and/or license header (optional)
> - File-level annotations
> - Package statement
> - Import statements
> - Top-level declarations
>
> Exactly one blank line separates each of these sections."

> "The contents of a file should be focused on a single theme. Examples of this would be a single public type or a set of extension functions performing the same operation on multiple receiver types. Unrelated declarations should be separated into their own files and public declarations within a single file should be minimized."

This last quote is itself evidence against per-variant packages: the unit Android's own guide asks you to keep focused is the *file* ("a single theme"), not the package.

---

## 4. A disagreement, called out rather than smoothed over

Four sources, four different answers on whether a camelCase package segment (like the proposal's `userCard`) is acceptable:

1. kotlinlang.org coding conventions (https://kotlinlang.org/docs/coding-conventions.html), section "Naming rules": *"Using multi-word names is generally discouraged, but if you do need to use multiple words, you can either just concatenate them together or use camel case (`org.example.myProject`)."* — **camelCase explicitly allowed.**
2. ktlint `package-name` rule (https://github.com/ktlint/ktlint, `PackageNameRule.kt`): regex `[a-z][a-zA-Z\d]*(\.[a-z][a-zA-Z\d]*)*` — **camelCase segments match and pass.**
3. detekt `PackageNaming` rule (https://github.com/detekt/detekt, `PackageNaming.kt`): default regex `[a-z]+(\.[a-z][A-Za-z0-9]*)*` — **camelCase segments in non-first position match and pass.**
4. Android's Kotlin style guide (https://developer.android.com/kotlin/style-guide), section "Package Names": *"Package names are all lowercase, with consecutive words simply concatenated together (no underscores)"*, with `package com.example.deepSpace` marked **"WRONG!"** in a worked example — **camelCase explicitly rejected.**

These four do not agree. JetBrains' own language convention and both of the two static-analysis tools that would actually run against this code (ktlint, detekt) treat camelCase package segments as fine; Google's Android-specific style guide treats the identical construct as an error. Practically: **no linter configured for this repo would catch a `userCard`-style segment** — the tool defaults (ktlint, detekt) accept it, and Android's stricter rule is not itself a lint rule anyone runs, only prose guidance in a document. So the casing question is real but moot for tooling purposes; it's a style call, not something CI would flag either way.

---

## 5. `internal` is module-scoped, not package-scoped — verified

Source: https://kotlinlang.org/docs/visibility-modifiers.html

> "The `internal` visibility modifier means that the member is visible within the same module. More specifically, a module is a set of Kotlin files compiled together, for example:
> - An IntelliJ IDEA module.
> - A Maven project.
> - A Gradle source set (with the exception that the `test` source set can access the internal declarations of `main`)."

> "If you mark it as `internal`, it will be visible everywhere in the same [module](#modules)."

> "`internal` means that any client inside this module who sees the declaring class sees its `internal` members."

There is no package-private visibility level in Kotlin at all. This was raised as a feature request and deliberately rejected by the language team. From the discussion thread (https://discuss.kotlinlang.org/t/kotlin-to-support-package-protected-visibility/1544):

Developers asking for it argued, among other things, that a *"complex independent component made of 10 classes only one of which had public access"* should be able to keep the other 9 hidden without exposing them to the whole module, that whitebox unit testing wants class-internal access without full module exposure, and that `internal` is *"basically the same as `public`"* for most single-module projects. JetBrains' own reply (contributor `yole`) rejected package-protected visibility on the grounds that it *"does not provide any real encapsulation"* — since any other class placed in the same package could access those "protected" members too, package boundaries were judged too weak a security boundary to bother implementing, and `internal` (module-scoped) was kept as the only boundary stronger than `public`.

**Consequence for the proposal:** splitting `ConfirmButton` into its own package (`ui/base/button/confirm/`) buys it **no additional encapsulation** over `DenyButton` sitting in the same file/package, because both are visible at the same granularity (module) regardless of which package they're declared in. The one justification that would make one-package-per-component worth the ceremony — "packages control what's exposed" — does not hold in Kotlin.

---

## 6. Counter-argument, at full strength

### Package-by-feature (phauer)

Source: https://phauer.com/2020/package-by-feature/

Strongest case for finer packaging, in the author's own words:

> "we only have to keep the current package in mind" (contrasted with needing to understand the whole codebase to change anything, quoting Sandi Metz: *"I felt like I had to understand everything in order to help with anything."*)

> "Changes in the `productManagement` will never break the `exportProduct` code and vice versa. They can evolve independently."

> "KISS > DRY" — preferring duplication across self-contained feature packages over shared generic abstractions that couple unrelated features together, quoting Metz again: *"Prefer duplication over the wrong abstraction."*

But the author explicitly gates this by project size — his size caveat, verbatim in substance from the fetch: for small-to-midsize projects he says *"I like to avoid defining rules that may add more ceremony than value,"* and only for **larger codebases** does he recommend defining *"more rules about the subpackage structure"* and introducing formal module/component boundaries with explicit `api`/`internal` surfaces (citing Tom Hombergs' approach to modular monoliths). He does not claim package-by-feature is universally correct at every scale — the finer the packaging, the more it costs, and that cost is only worth paying once the codebase (or the team touching it) is large enough that the cost of *not* having boundaries exceeds it.

### Anticipated module extraction (Android modularization docs)

Source: https://developer.android.com/topic/modularization/patterns

> "Data sources should only be accessible by repositories from the same module. They remain hidden to the outside. You can enforce this by using Kotlin's `private` or `internal` visibility keyword."

> "The public interface of a module should be minimal and expose only the essentials. It shouldn't leak any implementation details outside. Scope everything to the smallest extent possible. Use Kotlin's `private` or `internal` visibility scope to make the declarations module-private."

> "If two modules heavily rely on knowledge of each other, it may be a good sign that they should actually act as one system. Conversely, if two parts of a module don't interact with each other often, they should probably be separate modules."

Note that every one of these quotes is written in terms of **modules** (Gradle build modules), not packages — consistent with §5: the actual encapsulation boundary Android's own architecture guidance relies on is the module, and package structure is only worth pre-shaping when it is standing in for a module boundary that's genuininely coming.

### The two conditions under which deep nesting would actually pay off

Drawing directly from the two sources above: fine-grained packaging is worth its ceremony cost when **either**

1. the codebase or the team touching a given area is large enough that package-level isolation reduces real cognitive load and merge conflicts (phauer's size-gated case), **or**
2. a given package is a genuine, near-term candidate for extraction into its own Gradle module — i.e. someone actually intends to draw a build-graph boundary there, not just a filesystem boundary (Android modularization docs' case) — since only a module boundary gets real `internal` enforcement (§5).

Neither condition is met by a single-team Compose UI component library where "internal" already means "module-wide" either way and no extraction is planned.

---

## 7. Repo facts (measured, not hypothetical)

Verified by another agent against this repository, folding the abstract argument into this codebase's actual shape:

- `ui/` is **59 files / 18,033 lines**.
- **65 top-level `internal` declarations** inside `ui/` are already read across package boundaries — from `map/`, `car/`, and the test source set — today. This is a direct, empirical confirmation of §5: those 65 declarations are already relying on `internal`'s module-wide reach, not on any package boundary, to be visible where they're used. If package boundaries provided real encapsulation, this cross-package internal access from `map/` and `car/` either couldn't happen or would already be a violation somebody had flagged — it isn't, because `internal` was never a package-scoped guarantee to begin with.
- Bearing on the "would this become a Gradle module" test (§6, condition 2): **no package anywhere in this repo is nested at any depth** — every existing package in this codebase is flat. And `shared/.../data`, at **64 flat files**, is *larger* than the 59-file `ui/` under discussion, and it too has stayed flat. Nothing in this codebase's own precedent treats "many files" as a trigger for nesting, and no part of `ui/` is a stated candidate for module extraction.

---

## 8. Verdict

The balance of evidence — Kotlin's own coding conventions (§1), the mechanical Java-vs-Kotlin difference in what a "file" can hold (§2), what ktlint and detekt actually enforce by rule id and regex (§3), Android's own style guide read in full (§3), the confirmed module-scoping of `internal` (§5), and the two real (but here unmet) conditions under which finer packaging pays for itself (§6) — points the same direction:

**One level of category nesting, not one package per component and never one package per component variant.** Concretely, for `ui/base`: a handful of category-scoped files or at most category-scoped packages (`button/`, `modal/`, `card/`, `hud/`), each holding the small number of closely-related composables in that category in one reasonably-sized file (well under detekt's `TooManyFunctions` default of 11 functions per file and under 120-character-line churn from `MaxLineLength`), named for the category rather than for a single variant. No package per component, and no package per component *variant* (`confirm/`, `deny/`, `download/`, `progress/` as siblings under `button/`).

Reasoning, restated compactly:
- Kotlin explicitly encourages multiple related declarations per file (§1) and explicitly warns against creating files (let alone packages) "just to hold" narrowly-scoped material (§1) — the proposal's per-variant packages are exactly the pattern this warns against.
- No linting tool this repo would plausibly run enforces package depth or component-per-package granularity (§3) — the only enforced budgets are file/class size and name spelling, both satisfied by category-grouped files.
- The one substantive justification for finer packages — using packages to control what's exposed — does not exist in Kotlin: `internal` is module-scoped (§5), confirmed both by the language docs and by this repo's own measured cross-package `internal` usage from `map/` and `car/` into `ui/` (§7). Fragmenting `ui/base` into nine leaf packages would not make any of those 65 already-cross-package-visible declarations any more private.
- The two conditions that would make deep nesting worth its cost — team/codebase scale (phauer, §6) or genuine near-term module extraction (Android modularization docs, §6) — are both absent here: this is a single-team, single-module UI layer with no stated modularization plan, and the repo's own precedent (an even-larger 64-file flat `shared/.../data`, and zero nested packages anywhere else) treats file count alone as no reason to nest.

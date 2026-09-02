# Server Capability Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The API server states which sign-in realm to use on an unauthenticated `/api/capabilities` endpoint, and the app asks it at sign-in instead of making the rider type the issuer a second time.

**Architecture:** A schema-versioned capability document on the backend (`Idp:Authority` verbatim). In `shared/`, a new `Capabilities` object splits the pure parts (parse, scheme check, precedence) from the one I/O call, because `commonTest` cannot test a network call at all. `RoutingServer.issuer()` gains a discovered candidate between the typed value and the baked default, so `Auth.endpoint()` picks it up with no plumbing. `Oidc` gains a `suspend resolveIssuer()` that probes on every interactive sign-in; the stored copy exists only so `Auth.refresh()` can build a URL offline.

**Tech Stack:** ASP.NET Core (net10.0, MVC controllers, xUnit + AwesomeAssertions + Testcontainers), Kotlin Multiplatform (`commonMain`/`commonTest`, `kotlin.test`, ktor client, okio), Jetpack Compose, SwiftUI.

**Spec:** `docs/superpowers/specs/2026-09-02-server-capability-discovery-design.md`
**Issue:** [#106](https://github.com/Detour-app/Detour/issues/106). Follow-up: [#113](https://github.com/Detour-app/Detour/issues/113).

---

## Environment

This repository's toolchain lives in a devcontainer. **Every `dotnet` and `./gradlew` command below must be prefixed with `devcontainer-exec`.** File edits and all `git` commands run on the host.

```bash
devcontainer-exec dotnet build backend/Detour.slnx
devcontainer-exec ./gradlew :shared:testDebugUnitTest
```

If nothing is running, stop and ask the user to start their devcontainer. Never run `devcontainer up` or `docker start`.

**iOS cannot be compiled here.** Tasks 12 and 13 edit Swift that no local command can type-check; `.github/workflows/ios.yml` is the gate. Make those edits carefully and say in the commit that they are CI-verified, not locally verified.

---

## File Structure

| File | Responsibility |
|---|---|
| `backend/Detour/Detour.Api/Contracts/CapabilityContracts.cs` | **Create.** The response records. |
| `backend/Detour/Detour.Api/Controllers/CapabilitiesController.cs` | **Create.** One anonymous GET returning the configured authority. |
| `backend/Detour/Detour.InfraTests/Api/CapabilitiesTests.cs` | **Create.** Reachable without a token; states the configured authority. |
| `backend/Detour/Detour.InfraTests/Api/DetourApiFactory.cs:26` | **Modify.** `private const string Issuer` → `public const`, so a test can assert against it instead of duplicating the literal. |
| `docs/BACKEND_SPEC.md` | **Modify.** New §15.5 with the two document rules and the content rule. |
| `backend/INSTALL.md` | **Modify.** Note under `Idp:Authority` that the API advertises it. |
| `shared/src/commonMain/kotlin/com/jellemax/detour/data/Capabilities.kt` | **Create.** `ServerCapabilities`, `parse`, `acceptable`, `preferredDiscovered` (all pure) and `fetch` (the only I/O). |
| `shared/src/commonMain/kotlin/com/jellemax/detour/data/RoutingServer.kt` | **Modify.** Discovered-issuer storage, two-argument `issuer()`, `headers` → `internal accessHeaders`, `save()` invalidation. |
| `shared/src/commonMain/kotlin/com/jellemax/detour/data/Oidc.kt` | **Modify.** `configured`, `hasApiServer`, `resolveIssuer()`; delete single-argument `begin`, publish the two-argument one. |
| `shared/src/commonMain/kotlin/com/jellemax/detour/data/Auth.kt` | **Modify.** `idTokenIssuer()` plus the pinned-issuer check in `exchangeCode`. |
| `shared/src/commonTest/kotlin/com/jellemax/detour/data/CapabilitiesTest.kt` | **Create.** Parse, scheme, precedence. |
| `shared/src/commonTest/kotlin/com/jellemax/detour/data/ServerResolutionTest.kt` | **Modify.** Move issuer assertions to the two-argument overload; add discovered-value cases. |
| `shared/src/commonTest/kotlin/com/jellemax/detour/data/AuthIssuerTest.kt` | **Create.** `idTokenIssuer` over real JWT shapes. |
| `app/src/main/java/com/jellemax/detour/auth/AuthBrowser.kt` | **Modify.** `start` becomes `suspend`; new `NoRealmAdvertised` failure. |
| `app/src/main/java/com/jellemax/detour/ui/FriendsScreen.kt` | **Modify.** Coroutine scope for the click; new message. |
| `app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt` | **Modify.** Deprecation copy. |
| `iosApp/Detour/SignIn.swift` | **Modify.** `resolveIssuer()` before `begin`; new message. |
| `iosApp/Detour/SettingsScreen.swift` | **Modify.** Deprecation copy. |
| `iosApp/Detour/FriendsScreen.swift` | **Modify.** New message in the `else` branch. |
| `app/build.gradle.kts:80` | **Modify.** `versionName` → one minor above whatever `main` holds; read the file. |

Two decisions locked here rather than left to the implementer:

- **`Capabilities.fetch` takes its headers as a parameter.** CF Access service-token headers are built by hand in three places already (`Api.kt:58`, `Geocoder.kt:73`, `RoutingServer.kt:199`). A probe without them receives the Access login page instead of JSON. Rather than add a fourth copy, `RoutingServer.headers` becomes `internal accessHeaders` and is reused. The three pre-existing copies are left alone — out of scope for this issue.
- **`RoutingServer.issuer` keeps a pure overload.** Reading the discovered value means touching `prefs`, which `commonTest` cannot do (`OidcTest.kt:19-21` documents why). So the precedence order lives in `issuer(custom, discovered)`, and the single-argument version is the thin prefs-reading wrapper. This mirrors `Oidc.begin(entropy, issuer)`.
- **`ServerCapabilities.features` is parsed but not consumed, on purpose.** Nothing in this change reads it — feature *querying* is #113's job. It is kept because it is part of the wire contract this change is settling, and a parse that dropped it would be lossy for the next consumer. Do not delete it as dead code; `CapabilitiesTest` asserts it.

---

## Task 1: Backend capability contract and controller

**Files:**
- Create: `backend/Detour/Detour.Api/Contracts/CapabilityContracts.cs`
- Create: `backend/Detour/Detour.Api/Controllers/CapabilitiesController.cs`
- Modify: `backend/Detour/Detour.InfraTests/Api/DetourApiFactory.cs:26`
- Test: `backend/Detour/Detour.InfraTests/Api/CapabilitiesTests.cs`

- [ ] **Step 1: Make the test factory's issuer readable**

`DetourApiFactory.cs:26` currently reads:

```csharp
    private const string Issuer = "https://test-issuer.detour.invalid/realms/detour";
```

Change to:

```csharp
    // Public so a test can assert that the API advertises exactly what it was
    // configured with, rather than repeating the literal and drifting from it.
    public const string Issuer = "https://test-issuer.detour.invalid/realms/detour";
```

- [ ] **Step 2: Write the failing test**

Create `backend/Detour/Detour.InfraTests/Api/CapabilitiesTests.cs`:

```csharp
using System.Net;
using System.Net.Http.Json;
using Detour.InfraTests.Database;

namespace Detour.InfraTests.Api;

[Collection(PostgresCollection.Name)]
public class CapabilitiesTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task Capabilities_are_reachable_without_a_token()
    {
        var response = await _factory.CreateClient().GetAsync("/api/capabilities");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
    }

    [Fact]
    public async Task Capabilities_state_the_configured_authority_verbatim()
    {
        var payload = await _factory.CreateClient()
            .GetFromJsonAsync<CapabilitiesPayload>("/api/capabilities");

        payload.Should().NotBeNull();
        // Exactly, not merely a prefix: the app pins this value and the token
        // pipeline compares `iss` against it with string equality.
        payload!.Idp.Issuer.Should().Be(DetourApiFactory.Issuer);
    }

    [Fact]
    public async Task Capabilities_announce_the_idp_discovery_feature()
    {
        var payload = await _factory.CreateClient()
            .GetFromJsonAsync<CapabilitiesPayload>("/api/capabilities");

        payload!.Schema.Should().Be(1);
        payload.Features.Should().Contain("idp-discovery",
            "the app skips features it does not know, so the name is the contract");
    }

    private sealed record CapabilitiesPayload(
        int Schema,
        IReadOnlyList<string> Features,
        IdpPayload Idp);

    private sealed record IdpPayload(string Issuer);
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `devcontainer-exec dotnet test backend/Detour/Detour.InfraTests --filter CapabilitiesTests`
Expected: FAIL — compile error on `DetourApiFactory.Issuer` is already fixed by Step 1, so the failure is all three tests returning 404 (`StatusCode` is `NotFound`, and `GetFromJsonAsync` throws).

Note: these tests start a real Postgres container via Testcontainers, so the first run pulls `postgres:18-alpine`.

- [ ] **Step 4: Write the contract**

Create `backend/Detour/Detour.Api/Contracts/CapabilityContracts.cs`:

```csharp
using System.ComponentModel.DataAnnotations;

namespace Detour.Api.Contracts;

/// <summary>
/// What this deployment can do, for a client that may be newer or older than it.
///
/// Two rules govern this document and both are load-bearing rather than
/// decorative, because a self-hoster updates on their own schedule and there is
/// no coordination point with the app: a client ignores feature strings and
/// fields it does not know, and <see cref="Schema"/> is bumped only when an
/// existing field changes meaning — never for an addition.
///
/// It is also a version-fingerprinting surface, which is accepted because a
/// self-hosted open-source server's version is discoverable anyway. What follows
/// from that is a content rule: feature names and values a client needs to
/// configure itself, and nothing else. No dependency versions, no build strings,
/// no counts. Operational detail belongs on <c>/api/health</c>.
/// </summary>
public record CapabilitiesResponse(
    [Required] int Schema,
    [Required] IReadOnlyList<string> Features,
    [Required] IdpCapabilityResponse Idp);

/// <summary>
/// Where riders sign in. <see cref="Issuer"/> is <c>Idp:Authority</c> verbatim —
/// the same string the token pipeline requires as <c>iss</c>, exactly and not as
/// a prefix. Stating it unchanged is what makes it impossible for this server to
/// advertise a realm whose tokens it would then refuse.
/// </summary>
public record IdpCapabilityResponse([Required] string Issuer);
```

- [ ] **Step 5: Write the controller**

Create `backend/Detour/Detour.Api/Controllers/CapabilitiesController.cs`:

```csharp
using Detour.Api.Configuration;
using Detour.Api.Contracts;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.Extensions.Options;
using Shared.Api.RateLimiting;

namespace Detour.Api.Controllers;

/// <summary>
/// What this deployment supports, for a client configuring itself against it.
///
/// Unauthenticated on purpose, and for the same reason <c>/api/health</c> is: a
/// caller needs this *before* it can obtain a token, since one of the things it
/// answers is which realm mints them. Nothing here is a secret — the realm
/// address is typed by riders and displayed by their browsers.
/// </summary>
[ApiController]
[Route("api/[controller]")]
[Produces("application/json")]
[AllowAnonymous]
[EnableRateLimiting(RateLimitPolicies.Anonymous)]
public class CapabilitiesController(IOptions<IdpSettings> idp) : ControllerBase
{
    /// <summary>Feature names this deployment answers for.</summary>
    private static readonly string[] Features = ["idp-discovery"];

    [HttpGet]
    [EndpointSummary("What this deployment supports.")]
    [EndpointDescription(
        "Unauthenticated, because a client needs the realm address before it can "
        + "hold a token. Clients ignore unknown feature names and fields; the schema "
        + "number changes only when an existing field does.")]
    [ProducesResponseType<CapabilitiesResponse>(StatusCodes.Status200OK)]
    public ActionResult<CapabilitiesResponse> Get() =>
        Ok(new CapabilitiesResponse(
            Schema: 1,
            Features: Features,
            Idp: new IdpCapabilityResponse(idp.Value.Authority)));
}
```

Two notes for the implementer, both verified in the tree rather than assumed:

- `Startup.cs:72` sets `options.LowercaseUrls = true`, so `[Route("api/[controller]")]` resolves to `/api/capabilities`.
- There is no fallback authorization policy anywhere in the backend, so a controller without `[Authorize]` is already anonymous. `[AllowAnonymous]` is here to match `HealthController.cs:11` and to say so out loud.
- `[EnableRateLimiting(RateLimitPolicies.Anonymous)]` is the **first** use of that policy in the codebase — it is registered at `RateLimitingExtensions.cs:74` and consumed nowhere. Its budget is 20 tokens replenishing 10 per 60s per IP (`RateLimitSettings.cs:42-47`), which suits a sign-in-time probe.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `devcontainer-exec dotnet test backend/Detour/Detour.InfraTests --filter CapabilitiesTests`
Expected: PASS, 3 tests.

- [ ] **Step 7: Verify the build and formatting are clean**

Run: `devcontainer-exec dotnet build backend/Detour.slnx`
Expected: Build succeeded, 0 errors.

Run: `devcontainer-exec dotnet format style backend/Detour.slnx --severity info --verify-no-changes --exclude '**/Migrations/**'`
Expected: exit 0, no output. If it reports changes, run it without `--verify-no-changes` and commit the result.

- [ ] **Step 8: Commit**

```bash
git add backend/Detour/Detour.Api/Contracts/CapabilityContracts.cs \
        backend/Detour/Detour.Api/Controllers/CapabilitiesController.cs \
        backend/Detour/Detour.InfraTests/Api/CapabilitiesTests.cs \
        backend/Detour/Detour.InfraTests/Api/DetourApiFactory.cs
git commit -m "feat(api): advertise the configured realm on /api/capabilities

A self-hosted deployment configures Idp:Authority and pins it as ValidIssuer,
then asks the rider to retype the same string in the app. This states it
instead, unauthenticated for the same reason /api/health is: a client needs the
realm address before it can hold a token.

Schema-versioned and additive by rule, because a self-hoster updates on their
own schedule: clients ignore unknown feature names and fields, and the schema
number moves only when an existing field changes meaning.

First consumer of RateLimitPolicies.Anonymous, which was registered and unwired.

Claude-Session: https://claude.ai/code/session_01Gjxrtw5G2FQWgkFsdz7Vwi"
```

---

## Task 2: Document the endpoint

**Files:**
- Modify: `docs/BACKEND_SPEC.md` (after §15.4, which ends at line 503)
- Modify: `backend/INSTALL.md` (the `Idp:Authority` row in the Configuration table)

- [ ] **Step 1: Add §15.5 to `docs/BACKEND_SPEC.md`**

Insert immediately after the §15.4 Health paragraph (which ends `...Redis is degraded-only.`) and before `## 16. Limits and defaults`:

```markdown
### 15.5 Capabilities

`/api/capabilities` is unauthenticated, for the same reason `/api/health` is: a
client needs it *before* it can hold a token, because one of the things it
answers is which realm mints them.

```json
{
  "schema": 1,
  "features": ["idp-discovery"],
  "idp": { "issuer": "https://idp.example/realms/detour" }
}
```

`idp.issuer` is `Idp:Authority` verbatim — the same string §4.2 requires as `iss`,
exactly and not as a prefix. Stating it unchanged is what makes it impossible for
a deployment to advertise a realm whose tokens it would then refuse.

Two rules make the document usable by clients both newer and older than the
server, which matters because anyone self-hosting updates on their own schedule
and there is no coordination point with the app:

- **Unknown is ignored.** A client skips feature strings and fields it does not
  know. An old app against a new server reads what it recognises and behaves as
  it did.
- **`schema` bumps only on a breaking change to an existing field.** Adding a
  feature string or a field is additive and does not bump it. A client seeing a
  `schema` higher than it knows still reads the fields it recognises rather than
  refusing the document.

It is a version-fingerprinting surface. That is accepted — the realm address is
already public, and a self-hosted open-source server's version is discoverable
regardless — and what follows from it is a content rule: this document carries
feature names and the values a client needs to configure itself against, and
nothing else. No dependency versions, no build strings, no counts, no dependency
health. §15.4 is where operational detail lives, and it stays there.
```

- [ ] **Step 2: Add the note to `backend/INSTALL.md`**

The Configuration table's `Idp:Authority` row currently reads:

```markdown
| `Idp:Authority` | The exact `iss` claim to require, e.g. `https://idp.example/realms/detour`. Exact, not a prefix. |
```

Replace with:

```markdown
| `Idp:Authority` | The exact `iss` claim to require, e.g. `https://idp.example/realms/detour`. Exact, not a prefix. The API also states this value on the unauthenticated `/api/capabilities`, so riders configuring the app against your deployment do not have to retype it — set it correctly and they get it for free. |
```

- [ ] **Step 3: Verify the fenced block nests correctly**

The §15.5 text contains a fenced JSON block inside a markdown block in this plan. In the actual file it must be a plain ```` ```json ```` fence. Run:

`grep -n '```' docs/BACKEND_SPEC.md | sed -n '/15/,$p' | head -20`

Expected: an even number of fence markers around the new section, with the JSON fence opening and closing inside §15.5.

- [ ] **Step 4: Commit**

```bash
git add docs/BACKEND_SPEC.md backend/INSTALL.md
git commit -m "docs(api): specify /api/capabilities and its compatibility rules

The two document rules are the load-bearing part: a self-hoster updates when
they get to it, so unknown fields and feature names must be ignored rather than
refused, and the schema number must move only when an existing field changes
meaning. Also records the content rule that keeps a fingerprinting surface from
growing into a deployment report.

Claude-Session: https://claude.ai/code/session_01Gjxrtw5G2FQWgkFsdz7Vwi"
```

---

## Task 3: The pure half of `Capabilities`

Everything in this task is a pure function, which is the point: `Http.client` is private with no injection seam and there is no `ktor-client-mock` dependency, so a network call cannot be tested in `commonTest` at all (`AuthRetry.kt:32` says so in a comment). The parse, the scheme rule and the precedence rule are therefore split out and tested; only `fetch` in Task 5 is untested I/O.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Capabilities.kt`
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/data/CapabilitiesTest.kt`

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/jellemax/detour/data/CapabilitiesTest.kt`:

```kotlin
package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three decisions in issuer discovery that can be made without a network.
 *
 * They are separated from the fetch deliberately: `Http`'s client is private
 * with no injection seam and there is no `MockEngine` in this source set, so a
 * real request cannot be exercised here. Splitting the judgement out is the
 * same move `Auth.store(tokenResponse: String, …)` and
 * `Auth.tokenFailureMessage(code, body)` already make.
 */
class CapabilitiesTest {

    private val document = """
        {"schema":1,"features":["idp-discovery"],
         "idp":{"issuer":"https://idp.example/realms/detour"}}
    """.trimIndent()

    @Test
    fun aCapabilityDocumentYieldsItsSchemaFeaturesAndIssuer() {
        val caps = Capabilities.parse(document)
        assertEquals(1, caps?.schema)
        assertEquals(listOf("idp-discovery"), caps?.features)
        assertEquals("https://idp.example/realms/detour", caps?.idpIssuer)
    }

    @Test
    fun theIssuerIsNormalisedTheSameWayASavedOneIs() {
        // RoutingServer.pick trims and strips a trailing slash. A discovered
        // value that skipped that would compare unequal to the identical typed
        // one, and the ID token's `iss` carries no trailing slash — so a
        // mismatch here would refuse a sign-in that is actually correct.
        val caps = Capabilities.parse(
            """{"schema":1,"features":[],"idp":{"issuer":" https://idp.example/realms/detour/ "}}"""
        )
        assertEquals("https://idp.example/realms/detour", caps?.idpIssuer)
    }

    @Test
    fun anUnknownFeatureOrFieldIsCarriedOrIgnoredButNeverRefused() {
        // The compatibility rule that lets an old app read a new server's
        // document. A higher schema is still read, not rejected.
        val caps = Capabilities.parse(
            """{"schema":7,"features":["idp-discovery","something-new"],
                "idp":{"issuer":"https://idp.example/realms/detour"},
                "somethingElse":{"nested":true}}"""
        )
        assertEquals(7, caps?.schema)
        assertEquals(listOf("idp-discovery", "something-new"), caps?.features)
        assertEquals("https://idp.example/realms/detour", caps?.idpIssuer)
    }

    @Test
    fun aBodyThatIsNotACapabilityDocumentIsNotOne() {
        // An access gateway's HTML sign-in page is the common case, and a proxy
        // answering `{}` is the quiet one. Both must read as "no answer" rather
        // than as a document with a blank issuer.
        assertNull(Capabilities.parse("<html>Sign in to continue</html>"))
        assertNull(Capabilities.parse("{}"))
        assertNull(Capabilities.parse(""))
    }

    @Test
    fun aDocumentWithNoIssuerParsesButOffersNothing() {
        // A server that has the endpoint but states no realm. Distinct from an
        // unparseable body: the document is real, it just cannot help.
        val caps = Capabilities.parse("""{"schema":1,"features":[]}""")
        assertEquals(1, caps?.schema)
        assertEquals("", caps?.idpIssuer)
    }

    @Test
    fun onlyAnHttpsIssuerIsAcceptable() {
        assertTrue(Capabilities.acceptable("https://idp.example/realms/detour"))
        assertFalse(Capabilities.acceptable("http://idp.example/realms/detour"))
        assertFalse(Capabilities.acceptable(""))
        assertFalse(Capabilities.acceptable("ftp://idp.example/realms/detour"))
    }

    @Test
    fun loopbackOverPlainHttpStaysAcceptableForTheDevStack() {
        // BuildDefaults.idpIssuer documents http://localhost:7580/realms/detour
        // as the dev value, and OAuth guidance carves out loopback for native
        // clients. Any port, because a dev stack picks its own.
        assertTrue(Capabilities.acceptable("http://localhost:7580/realms/detour"))
        assertTrue(Capabilities.acceptable("http://127.0.0.1:8080/realms/detour"))
        // Not a carve-out for anything that merely mentions localhost.
        assertFalse(Capabilities.acceptable("http://localhost.evil.example/realms/detour"))
    }

    @Test
    fun aFreshlyFetchedIssuerBeatsTheStoredOne() {
        assertEquals(
            "https://new.example/realms/detour",
            Capabilities.preferredDiscovered(
                fetched = "https://new.example/realms/detour",
                stored = "https://old.example/realms/detour",
            ),
        )
    }

    @Test
    fun theStoredIssuerCarriesTheProbeThatFailed() {
        // This is what keeps a signed-in rider working offline: the fetch
        // returned nothing, and the value from the last successful probe is
        // still the right answer.
        assertEquals(
            "https://old.example/realms/detour",
            Capabilities.preferredDiscovered(fetched = "", stored = "https://old.example/realms/detour"),
        )
    }

    @Test
    fun anUnacceptableFetchedIssuerDoesNotDisplaceAGoodStoredOne() {
        // A server that starts answering with a plain-HTTP realm must not be
        // able to downgrade a rider who already had an HTTPS one.
        assertEquals(
            "https://old.example/realms/detour",
            Capabilities.preferredDiscovered(
                fetched = "http://idp.example/realms/detour",
                stored = "https://old.example/realms/detour",
            ),
        )
    }

    @Test
    fun nothingFetchedAndNothingStoredIsBlank() {
        assertEquals("", Capabilities.preferredDiscovered(fetched = "", stored = ""))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*CapabilitiesTest*'`
Expected: FAIL to compile — `Unresolved reference: Capabilities`.

- [ ] **Step 3: Write the pure implementation**

Create `shared/src/commonMain/kotlin/com/jellemax/detour/data/Capabilities.kt`:

```kotlin
package com.jellemax.detour.data

/**
 * What one deployment says it can do.
 *
 * [schema] and [features] are read even when this client does not recognise
 * their values, because the server's own rule is that unknown is ignored rather
 * than refused — see `docs/BACKEND_SPEC.md` §15.5. That is what lets an app
 * older than the server it is pointed at keep working.
 */
internal data class ServerCapabilities(
    val schema: Int,
    val features: List<String>,
    /** Blank when the server has the endpoint but names no realm. */
    val idpIssuer: String,
)

/**
 * Asking a deployment which realm to sign in against, instead of the rider
 * typing an address their server already knows.
 *
 * The split here is forced and worth stating: [parse], [acceptable] and
 * [preferredDiscovered] are pure and covered by `CapabilitiesTest`, while
 * [fetch] is not covered at all. `Http`'s client is private with no injection
 * seam and this source set has no `MockEngine` — see the same note on
 * `AuthRetry.kt`. So every decision lives in a function that takes its inputs
 * as arguments, and the I/O is a thin wrapper with no judgement in it.
 */
internal object Capabilities {

    /**
     * Reads a capability document, or null when the body is not one.
     *
     * Null covers two real cases that must not be confused with a document
     * naming no realm: an access gateway's HTML sign-in page, and a proxy
     * answering `{}`. Both would otherwise read as "the server told us the
     * realm is blank", which is a different fact with a different message.
     */
    fun parse(body: String): ServerCapabilities? {
        val o = runCatching { jsonObjectOf(body) }.getOrNull() ?: return null
        // Absent or zero means this is not a capability document. A real one
        // always names its schema, and the server never emits 0.
        val schema = o.optInt("schema")
        if (schema < 1) return null
        val features = (o.optArray("features") ?: JsonArrayEmpty)
            .let { a -> a.indices.map { a.optString(it) } }
        return ServerCapabilities(
            schema = schema,
            features = features,
            // Normalised exactly as RoutingServer.pick normalises a typed
            // address. Without this a discovered issuer and the identical typed
            // one compare unequal, and the ID token's `iss` — which carries no
            // trailing slash — would refuse a sign-in that is correct.
            idpIssuer = o.optObject("idp")?.optString("issuer").orEmpty().trim().trimEnd('/'),
        )
    }

    /**
     * Whether a discovered issuer may be used at all.
     *
     * HTTPS or nothing: this string becomes the page a rider types their
     * password into and the token endpoint the authorization code is sent to,
     * and over plain HTTP the realm's signing keys can be swapped in transit —
     * which is what `IdpSettings.RequireHttpsMetadata` says on the server side.
     *
     * This is the *only* substantive control on a server-supplied issuer. The
     * ID-token `iss` check in Task 6 cannot stand in for it: that compares
     * `iss` against this very value, so a hostile realm that echoes what it
     * advertised passes. Anything this function accepts is trusted from here on.
     */
    fun acceptable(issuer: String): Boolean {
        val scheme = when {
            issuer.startsWith("https://") -> "https://"
            issuer.startsWith("http://") -> "http://"
            // Case-sensitive, so `HTTPS://` is refused. Fail-closed and
            // deliberate: a realm whose issuer is spelled that way already
            // fails the backend's own exact `iss` comparison.
            else -> return false
        }
        val authority = issuer.removePrefix(scheme).substringBefore('/')
        // Userinfo is what turns a host check into a host *prefix* check:
        // `localhost:8080@evil.example` has userinfo `localhost:8080` and host
        // `evil.example`, so truncating at the first colon reads an attacker's
        // credentials as the hostname. No OIDC issuer identifier carries
        // userinfo, so refusing the shape outright is both correct and simpler
        // than parsing it properly.
        if ('@' in authority) return false
        if (authority.any { it.isWhitespace() || it.isISOControl() }) return false
        // A prefix check would accept a bare `https://`, which reaches
        // Auth.endpoint() as `https:///protocol/...` and fails as a malformed
        // URL instead of as "no realm advertised".
        val host = authority.substringBefore(':')
        if (host.isEmpty()) return false
        // Loopback over cleartext is the one carve-out, and the reason is that
        // the traffic never leaves the device, so there is no on-path attacker
        // to defend against. `BuildDefaults.idpIssuer` documents
        // http://localhost:7580/realms/detour as the dev value.
        //
        // Deliberately narrower than the full loopback set: `[::1]`,
        // `127.0.0.2`, `127.1` and the integer-collapsed forms are all refused.
        // That is the safe direction, and widening it needs a reason better
        // than symmetry.
        return scheme == "https://" || host == "localhost" || host == "127.0.0.1"
    }

    /**
     * Which discovered issuer to use: the one just fetched, or the one kept
     * from the last successful probe.
     *
     * The stored value is not a cache in front of the fetch — the fetch always
     * runs at an interactive sign-in. It is what [Auth.refresh] falls back to on
     * a cold start with no network, and what carries a probe that failed. An
     * unacceptable fetched value loses to a good stored one, so a server that
     * starts answering with a plain-HTTP realm cannot downgrade a rider who
     * already had an HTTPS one.
     */
    fun preferredDiscovered(fetched: String, stored: String): String = when {
        fetched.isNotBlank() && acceptable(fetched) -> fetched
        // Vetted again on the way out, not only on the way in. The store is
        // written by one caller and only ever with a value that passed this
        // check, but nothing on the [Auth.refresh] path re-vets it and the store
        // survives until the API address changes — so a value written under
        // looser rules would otherwise outlive the tightening.
        stored.isNotBlank() && acceptable(stored) -> stored
        else -> ""
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*CapabilitiesTest*'`
Expected: PASS, 11 tests.

- [ ] **Step 5: Verify nothing leaked into `commonMain` that only Android has**

Run: `devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL. This is the check that catches a `java.*` import that would compile on Android and fail on the iOS targets, which cannot be built here.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/Capabilities.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/CapabilitiesTest.kt
git commit -m "feat(shared): parse a server capability document and vet its issuer

The pure half of issuer discovery: reading the document, deciding whether a
discovered issuer may be used, and deciding whether a fresh one displaces the
stored one. Split out because commonTest cannot exercise a network call at all
-- Http's client is private with no injection seam and there is no MockEngine
in the source set -- so every judgement takes its inputs as arguments.

An unparseable body and a document naming no realm are deliberately different
answers: an access gateway's HTML sign-in page must not read as 'the server
says the realm is blank'.

HTTPS or loopback only. This string becomes the page a rider types a password
into, and over plain HTTP the realm's signing keys can be swapped in transit.

Claude-Session: https://claude.ai/code/session_01Gjxrtw5G2FQWgkFsdz7Vwi"
```

---

## Task 4: Store the discovered issuer in `RoutingServer`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/RoutingServer.kt:124-134` (`issuer`), `:163-184` (`save`), `:196-203` (`headers`)
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/data/ServerResolutionTest.kt`

- [ ] **Step 1: Write the failing tests**

In `shared/src/commonTest/kotlin/com/jellemax/detour/data/ServerResolutionTest.kt`, the four existing assertions on `RoutingServer.issuer(...)` must move to the two-argument overload, because the one-argument version will read `prefs` and `prefs` throws in a JVM unit test. Replace these four test functions entirely:

```kotlin
    @Test
    fun theIssuerNeverFallsBackToTheGeneralServerAddress() {
        // A realm URL is never the API base, so a saved server with no issuer
        // must leave sign-in unconfigured rather than aim it at the API host.
        noBakedDefaults()
        val c = ServerConfig(url = "https://all.example", enabled = true)
        assertEquals("", RoutingServer.issuer(c, discovered = ""))
    }

    @Test
    fun theIssuerStillPrefersTheSavedValueOverTheBakedOne() {
        BuildDefaults.configure(idpIssuer = "https://baked-idp.example/realms/detour")
        assertEquals(
            "https://idp.example/realms/detour",
            RoutingServer.issuer(split(), discovered = ""),
        )
        assertEquals(
            "https://baked-idp.example/realms/detour",
            RoutingServer.issuer(
                ServerConfig(url = "https://all.example", enabled = true),
                discovered = "",
            ),
        )
    }

    @Test
    fun aTypedIssuerBeatsADiscoveredOne() {
        // The rule the deprecation copy promises: the field still wins. A rider
        // who typed an address is overruling the server on purpose, and
        // silently ignoring that is worse than the problem discovery solves.
        noBakedDefaults()
        assertEquals(
            "https://idp.example/realms/detour",
            RoutingServer.issuer(split(), discovered = "https://discovered.example/realms/detour"),
        )
    }

    @Test
    fun aDiscoveredIssuerBeatsTheBakedDefault() {
        // A rider pointing at their own server should sign in to their own
        // realm, not the realm this build happened to be compiled against.
        BuildDefaults.configure(idpIssuer = "https://baked-idp.example/realms/detour")
        val c = ServerConfig(url = "https://all.example", enabled = true)
        assertEquals(
            "https://discovered.example/realms/detour",
            RoutingServer.issuer(c, discovered = "https://discovered.example/realms/detour"),
        )
    }

    @Test
    fun aDiscoveredIssuerIsUsedWhenNothingElseIsConfigured() {
        noBakedDefaults()
        val c = ServerConfig(url = "https://all.example", enabled = true)
        assertEquals(
            "https://discovered.example/realms/detour",
            RoutingServer.issuer(c, discovered = "https://discovered.example/realms/detour"),
        )
    }
```

Then fix the two remaining one-argument uses. In `trailingSlashesAndSurroundingSpaceAreStrippedSoPathsDoNotDoubleUp`, change:

```kotlin
        assertEquals("https://idp.example/realms/detour", RoutingServer.issuer(c))
```

to:

```kotlin
        assertEquals("https://idp.example/realms/detour", RoutingServer.issuer(c, discovered = ""))
```

And in `nothingConfiguredAnywhereResolvesToBlank`, change:

```kotlin
        assertEquals("", RoutingServer.issuer(null))
```

to:

```kotlin
        assertEquals("", RoutingServer.issuer(null, discovered = ""))
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*ServerResolutionTest*'`
Expected: FAIL to compile — `Too many arguments for public final fun issuer(custom: ServerConfig?): String`.

- [ ] **Step 3: Split `issuer` into a pure overload and a prefs-reading wrapper**

In `RoutingServer.kt`, the current declaration at `:133-134` is:

```kotlin
    fun issuer(custom: ServerConfig?): String =
        pick(custom?.idpIssuer ?: "", BuildDefaults.idpIssuer)
```

Replace it with:

```kotlin
    fun issuer(custom: ServerConfig?): String = issuer(custom, discoveredIssuer())

    /**
     * `internal` with the discovered value passed in, for the same reason
     * [Oidc.begin] has an overload taking the issuer: reading it means touching
     * `prefs`, and `prefs` reaches a Context that does not exist in a unit test.
     * The precedence order lives here so a test can assert it.
     *
     * [discovered] sits between the typed value and the baked one on purpose. A
     * rider who typed an address is overruling their server deliberately and
     * keeps winning; a rider who pointed at their own server should reach their
     * own realm rather than whichever one this build was compiled against.
     */
    internal fun issuer(custom: ServerConfig?, discovered: String): String =
        pick(custom?.idpIssuer ?: "", discovered, BuildDefaults.idpIssuer)

    /**
     * What is actually on disk, unvetted. Only [discoveredIssuer] and
     * [rememberDiscoveredIssuer] may call this — the first to vet it, the second
     * to evict it. Everything else must read through [discoveredIssuer].
     */
    private fun storedIssuerRaw(): String = prefs(PREFS).string(KEY_DISCOVERED_ISSUER)

    /**
     * The realm the API server last stated, or blank. See [rememberDiscoveredIssuer].
     *
     * Vetted on **read**, not only on write, and that placement is load-bearing
     * rather than belt-and-braces. This is the single read point for the stored
     * issuer, and [Auth.refresh] reaches it through [Auth.endpoint] on a cold
     * start without going anywhere near [Capabilities.preferredDiscovered],
     * which runs only at an interactive sign-in. Vetting at the sign-in read
     * alone would leave a value written by an older, looser build receiving a
     * refresh token on every launch, forever.
     */
    internal fun discoveredIssuer(): String =
        storedIssuerRaw().takeIf { Capabilities.acceptable(it) } ?: ""
```

Add the key constant next to `PREFS` at `:69`:

```kotlin
    /**
     * The realm the API server stated on its last successful probe.
     *
     * Not part of [ServerConfig], which stays the rider's own input. This is
     * *not* a cache in front of the probe — an interactive sign-in always asks
     * the server again, so a realm that moved cannot produce a 404 on an
     * authorize URL built from a stale value. What it is for is [Auth.refresh],
     * which runs on a cold start that may have no network and still has to
     * build a token endpoint from something.
     */
    private const val KEY_DISCOVERED_ISSUER = "idp_issuer_discovered"
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*ServerResolutionTest*'`
Expected: PASS, 10 tests — the four base-address/normalisation tests already there, plus the six issuer tests.

- [ ] **Step 5: Write the failing test for save-time invalidation**

The spec requires `commonTest` coverage of "issuer-change-clears-session", and the clear itself is unreachable from a unit test — it calls `Auth.clear()` behind `prefs`. So the *decision* that drives it is extracted, and that is what gets tested. Append to `ServerResolutionTest.kt`:

```kotlin
    @Test
    fun changingTheServerAddressDiscardsTheDiscoveredIssuer() {
        // The discovered value belongs to the server that stated it. Carried
        // across to a new address it would aim sign-in at the old deployment's
        // realm, which is the failure this whole feature exists to remove.
        noBakedDefaults()
        val before = ServerConfig(url = "https://old.example", enabled = true)
        val after = ServerConfig(url = "https://new.example", enabled = true)
        assertEquals(
            "",
            RoutingServer.issuerAfterSave(
                config = after,
                previous = before,
                discovered = "https://discovered.example/realms/detour",
            ),
        )
    }

    @Test
    fun keepingTheServerAddressKeepsTheDiscoveredIssuer() {
        // The rule this protects is the existing one recorded on
        // Auth.sessionEpoch: a server switch that leaves the effective issuer
        // alone must not drop the session. Editing an unrelated field is that
        // case, and it has to survive.
        noBakedDefaults()
        val before = ServerConfig(url = "https://same.example", enabled = true)
        val after = ServerConfig(
            url = "https://same.example",
            geocoderUrl = "https://search.example",
            enabled = true,
        )
        val discovered = "https://discovered.example/realms/detour"
        assertEquals(
            discovered,
            RoutingServer.issuerAfterSave(after, before, discovered),
        )
        // Same value before and after, so save() finds nothing to clear.
        assertEquals(
            RoutingServer.issuer(before, discovered),
            RoutingServer.issuerAfterSave(after, before, discovered),
        )
    }

    @Test
    fun anUnacceptableStoredIssuerIsNeverTheEffectiveOne() {
        // The refresh path reads the stored issuer without going through
        // Capabilities.preferredDiscovered, so the vet has to sit on the read
        // itself. Asserted through the pure overload, since discoveredIssuer()
        // touches prefs: what this pins is that an unacceptable value passed as
        // the discovered candidate still loses to nothing at all.
        noBakedDefaults()
        val c = ServerConfig(url = "https://all.example", enabled = true)
        assertEquals("", RoutingServer.issuer(c, discovered = ""))
        // And the value the vet exists to catch, had it reached this far.
        assertEquals(
            "http://localhost:8080@evil.example/realms/detour",
            RoutingServer.issuer(c, discovered = "http://localhost:8080@evil.example/realms/detour"),
        )
        // ^ deliberately NOT filtered here: issuer() composes candidates and does
        // not judge them. The filtering is discoveredIssuer()'s job, and putting
        // it in both places would hide which one is the control.
    }

    @Test
    fun changingServersWhileAnIssuerWasTypedChangesNothing() {
        // A rider who typed a realm is not affected by a server change: the
        // typed value outranks the discovered one, so the effective issuer is
        // the same before and after and the session survives.
        noBakedDefaults()
        val typed = "https://idp.example/realms/detour"
        val before = ServerConfig(url = "https://old.example", idpIssuer = typed, enabled = true)
        val after = ServerConfig(url = "https://new.example", idpIssuer = typed, enabled = true)
        assertEquals(
            typed,
            RoutingServer.issuerAfterSave(after, before, "https://discovered.example/realms/detour"),
        )
    }
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*ServerResolutionTest*'`
Expected: FAIL to compile — `Unresolved reference: issuerAfterSave`.

- [ ] **Step 7: Add `issuerAfterSave`, `rememberDiscoveredIssuer` and the `save()` invalidation**

Still in `RoutingServer.kt`, add next to the `issuer` overloads:

```kotlin
    /**
     * The effective issuer a [save] of [config] would leave behind, given the
     * currently stored [discovered] value and the [previous] config.
     *
     * Extracted from [save] so it can be asserted: the clear itself calls
     * [Auth.clear] behind `prefs` and is unreachable from a unit test, but the
     * comparison that drives it is the part worth protecting.
     *
     * The rule is that a new API address discards the discovered issuer, since
     * it belonged to the server that stated it. Carried across it would aim
     * sign-in at the old deployment's realm.
     */
    internal fun issuerAfterSave(
        config: ServerConfig,
        previous: ServerConfig?,
        discovered: String,
    ): String = issuer(config, if (apiBase(config) != apiBase(previous)) "" else discovered)
```

Then replace the body of `save` (currently `:163-184`) with:

```kotlin
    fun save(config: ServerConfig) {
        val previous = loadCustom()
        val discovered = discoveredIssuer()
        val serverChanged = apiBase(config) != apiBase(previous)

        // Tokens are minted by one realm and meaningless to another, and a
        // refresh presented to the wrong realm reads as a replay rather than as
        // a mistake. Compared on the *effective* issuer, which is why the
        // discarded discovered value is folded in through [issuerAfterSave]: a
        // rider whose only issuer was discovered, changing servers, is changing
        // realms. A server switch that leaves the effective issuer alone still
        // does not clear — see the note on [Auth.sessionEpoch].
        if (issuerAfterSave(config, previous, discovered) != issuer(previous, discovered)) {
            Auth.clear()
        }

        prefs(PREFS).apply {
            put("saved", true)
            put("url", config.url.trim())
            put("api_url", config.apiUrl.trim())
            put("routing_url", config.routingUrl.trim())
            put("geocoder_url", config.geocoderUrl.trim())
            put("idp_issuer", config.idpIssuer.trim())
            if (serverChanged) remove(KEY_DISCOVERED_ISSUER)
        }
        securePrefs().apply {
            put("clientId", config.clientId.trim())
            put("clientSecret", config.clientSecret.trim())
        }
    }

    /**
     * Records the realm the API server just stated, dropping the session if
     * that changes which realm this device signs in to.
     *
     * The clear goes through the same rule [save] applies, and for the same
     * reason: a refresh token presented to a realm that did not mint it reads
     * as a replay. Cheap to call with an unchanged value, which is the common
     * case, since every interactive sign-in probes.
     */
    internal fun rememberDiscoveredIssuer(discovered: String) {
        val previous = discoveredIssuer()
        val custom = loadCustom()

        // A blank argument evicts rather than returning early. Blank means the
        // probe found nothing usable, and that includes the case where what was
        // already stored is no longer acceptable — so returning early here would
        // strand exactly the value the caller just refused. Compared on the raw
        // read, because a value the vetted read already filters to blank still
        // occupies the key and should still go.
        if (discovered.isBlank()) {
            if (storedIssuerRaw().isNotEmpty()) {
                if (issuer(custom, "") != issuer(custom, previous)) Auth.clear()
                prefs(PREFS).remove(KEY_DISCOVERED_ISSUER)
            }
            return
        }

        if (discovered == previous) return
        if (issuer(custom, discovered) != issuer(custom, previous)) Auth.clear()
        prefs(PREFS).put(KEY_DISCOVERED_ISSUER, discovered)
    }
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*ServerResolutionTest*'`
Expected: PASS, 14 tests.

- [ ] **Step 9: Consolidate the URL normalisation**

Task 3's review flagged this and it comes due here, because this task owns `RoutingServer.kt`. The rule `.trim().trimEnd('/')` now lives in `Capabilities.parse`, in `RoutingServer.pick` (`:107-108`), and Task 6 adds a third copy in `Auth.idTokenIssuer`. A sign-in refusal depends on all three agreeing — if one drifts, a discovered issuer stops comparing equal to an identical typed one and a correct sign-in is refused. Three copies is where documentary coupling becomes structural coupling.

Add to `Capabilities.kt`, beside the functions that already use it:

```kotlin
/**
 * One address, normalised for comparison: trimmed, with trailing slashes gone.
 *
 * Extracted because three call sites depend on agreeing exactly —
 * [Capabilities.parse], [RoutingServer.pick] and [Auth.idTokenIssuer] — and a
 * sign-in is refused when they disagree: a realm emitting a trailing slash in
 * `iss` would fail a comparison that is actually a match. Documentary agreement
 * was enough at two copies and is not at three.
 */
internal fun normalisedAddress(raw: String): String = raw.trim().trimEnd('/')
```

Then have `RoutingServer.pick` call it, replacing its own `?.trim()?.trimEnd('/')`. Keep `pick`'s existing doc comment about why the trailing slash matters for Photon's `/api/?q=` — that reasoning is specific to `pick` and is not duplicated by the new function's doc.

Do not change `Capabilities.parse` in this task beyond having it call the shared function; its behaviour is unchanged either way.

**Two comments in `Capabilities.kt` go stale the moment this task lands, and must be updated in it.** Both currently assert something this task makes false:

- `preferredDiscovered`'s KDoc says "nothing on the `Auth.refresh()` path re-vets it … so a value written under looser rules would otherwise outlive the tightening". After this task, `discoveredIssuer()` vets on read, so the refresh path *is* covered. Rewrite it to say what stays true: the re-vet here is now redundant with the read-time vet and kept as a local guarantee, so the function is correct read in isolation.
- `parse`'s inline comment says the issuer is "normalised exactly as `RoutingServer.pick` normalises a typed address". Once both call `normalisedAddress`, say that instead — a shared function is a stronger statement than a claim of agreement, and it is the reason the claim can be dropped.

A comment asserting an invariant that has since moved is worse than no comment, because it tells a reader the guarantee is somewhere it is not.

- [ ] **Step 10: Make the Cloudflare Access headers reusable**

`Capabilities.fetch` (Task 5) needs the same service-token headers every other client sends, or an Access-fronted deployment answers the probe with its login page. The headers are built by hand in three places already (`Api.kt:58`, `Geocoder.kt:73`, and here); rather than add a fourth, promote this one. At `RoutingServer.kt:196`, change:

```kotlin
    /** Cloudflare Access service-token headers, absent when not behind Access. */
    private fun headers(config: ServerConfig): Map<String, String> = buildMap {
```

to:

```kotlin
    /**
     * Cloudflare Access service-token headers, absent when not behind Access.
     *
     * `internal` rather than private because the capability probe needs the
     * same ones: without them an Access-fronted deployment answers the probe
     * with its own sign-in page, and the app would read that as "this server
     * has no capability endpoint".
     */
    internal fun accessHeaders(config: ServerConfig): Map<String, String> = buildMap {
```

Then update its two call sites in the same file — `fetchRoute` and `snapToRoad` — from `headers(config)` to `accessHeaders(config)`. Find them with:

`grep -n 'headers(config)' shared/src/commonMain/kotlin/com/jellemax/detour/data/RoutingServer.kt`

Leave `Api.kt` and `Geocoder.kt` alone. Their duplication predates this change and consolidating it is not this issue's job.

- [ ] **Step 11: Run the full shared suite and the metadata check**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest`
Expected: PASS, all tests. Nothing outside `ServerResolutionTest` should change behaviour.

Run: `devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 12: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/Capabilities.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/RoutingServer.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/ServerResolutionTest.kt
git commit -m "feat(shared): resolve the issuer through a discovered candidate

issuer() gains a candidate between the typed value and the baked default, which
is the whole of the plumbing: Auth.endpoint() already resolves through it, so
the token exchange and the refresh pick up a discovered realm for free.

The precedence order moves into an internal overload taking the discovered
value, because reading it touches prefs and prefs reaches a Context no unit test
has. Same reason Oidc.begin has an issuer overload.

save() drops the stored value when the API address changes, since it belonged to
the old server, and both it and rememberDiscoveredIssuer clear the session on a
change of effective issuer -- a refresh token presented to a realm that did not
mint it reads as a replay.

RoutingServer.headers becomes internal accessHeaders so the probe can send the
Cloudflare Access service token. Without it an Access-fronted deployment answers
with its sign-in page and the app reads that as 'no capability endpoint'.

Claude-Session: https://claude.ai/code/session_01Gjxrtw5G2FQWgkFsdz7Vwi"
```

---

## Task 5: Fetch the document

No test. This is the one function in the feature that cannot have one, for the reason recorded in Task 3, and it is deliberately thin enough that reading it is the review.

**A known limitation, surfaced by Task 1's code review and deliberately not fixed here.** Collapsing every non-answer to null means a `429` from the anonymous rate limiter is indistinguishable from "this server predates the endpoint", so a throttled probe reports "your server did not say which realm" — a configuration error for a transient condition. The limiter's budget is 20 tokens replenishing 10 per 60s **per client IP**, so behind CGNAT or a shared hotspot on a group ride, every rider on that address shares it. The rejection handler already sets `Retry-After` (`RateLimitingExtensions.cs:158-162`) and nothing reads it.

It stays unfixed because the harm is narrow: a returning rider falls back to the stored issuer and signs in normally, so only a *first* sign-in on a saturated shared address is affected, and telling that rider to try again needs a reason channel `resolveIssuer(): String` does not have. Distinguishing it means either a result type or an out-of-band signal, which is more API surface than the case earns today. Do not add it in this task. If it needs solving, it is its own issue.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Capabilities.kt`

- [ ] **Step 1: Add `fetch` to the `Capabilities` object**

Append inside `internal object Capabilities`, after `preferredDiscovered`:

```kotlin
    /**
     * Asks one deployment what it supports, or null when it does not say.
     *
     * Null covers every way that can happen and does not distinguish them,
     * because the caller's next move is the same for all of them: a server
     * predating this endpoint answers 404, an unreachable one throws, and a
     * gateway in front of it may answer with HTML. All three mean "no issuer
     * from this server right now", and the stored value is what carries the
     * gap.
     *
     * There is no test for this function. `Http`'s client is private with no
     * injection seam and this source set has no `MockEngine`, so it is kept as
     * close to no logic as it can be — every decision it would otherwise make
     * lives in [parse], [acceptable] and [preferredDiscovered], which are
     * covered.
     */
    suspend fun fetch(apiBase: String, headers: Map<String, String>): ServerCapabilities? {
        if (apiBase.isBlank()) return null
        val body = try {
            // Shorter than the 30s default: this runs between a rider tapping
            // Sign in and the browser opening, and a server that is not going
            // to answer should not hold that gap open.
            Http.get("$apiBase/api/capabilities", headers, readTimeoutMs = 10_000)
        } catch (e: Exception) {
            // Broad on purpose, and it must stay broad: this is reached from a
            // function Swift calls, where an escaping exception terminates the
            // process rather than arriving as an error. Nothing about a failed
            // probe is worth that.
            return null
        }
        return parse(body)
    }
```

- [ ] **Step 2: Verify it compiles on the common intersection**

Run: `devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the shared suite to confirm nothing regressed**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest`
Expected: PASS, all tests.

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/Capabilities.kt
git commit -m "feat(shared): fetch a deployment's capability document

The only untestable function in the feature, so it holds no judgement: a 404
from an old server, an unreachable host and a gateway answering HTML all return
null, because the caller's next move is the same for all three. The catch is
broad and has to stay broad -- this is reached from a function Swift calls,
where an escaping exception terminates the process.

Claude-Session: https://claude.ai/code/session_01Gjxrtw5G2FQWgkFsdz7Vwi"
```

---

## Task 6: Check the ID token's issuer (ASVS V10.2.2)

Discovering the issuer at runtime makes this app a client that can interact with more than one authorization server, which is the precondition for a mix-up attack. `ASVS 5.0.0 V10.2.2` asks such a client for a defence and names validating `iss` in the authorization response and the token response as the example. This implements the token-response leg.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Auth.kt:169-186` (`exchangeCode`), and a new helper next to `usernameFrom` at `:454`
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/data/AuthIssuerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/jellemax/detour/data/AuthIssuerTest.kt`:

```kotlin
package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import okio.ByteString.Companion.encodeUtf8

/**
 * Reading the `iss` a realm signed into an ID token.
 *
 * This is the client half of ASVS 5.0.0 V10.2.2. It matters more now than it
 * did: the issuer used to be typed by a person, and is now stated by the API
 * server, so "the token came back from the realm we asked" stops being true by
 * construction and has to be checked.
 *
 * Only the extraction is covered here. The comparison in `exchangeCode` reads
 * the pinned issuer through `RoutingServer.loadCustom()`, which reaches `prefs`
 * and therefore a Context no unit test has — the same limit `OidcTest` records.
 */
class AuthIssuerTest {

    /** A JWT is header.payload.signature, each base64url and unpadded. */
    private fun jwt(payload: String): String {
        val part = payload.encodeUtf8().base64Url().trimEnd('=')
        val header = """{"alg":"RS256","typ":"JWT"}""".encodeUtf8().base64Url().trimEnd('=')
        return "$header.$part.signature-not-verified-here"
    }

    private fun tokenResponse(idToken: String) =
        """{"access_token":"a.b.c","refresh_token":"r","expires_in":300,"id_token":"$idToken"}"""

    @Test
    fun theIssuerIsReadOutOfTheIdToken() {
        val response = tokenResponse(
            jwt("""{"iss":"https://idp.example/realms/detour","sub":"abc"}""")
        )
        assertEquals("https://idp.example/realms/detour", Auth.idTokenIssuer(response))
    }

    @Test
    fun aResponseWithNoIdTokenYieldsBlank() {
        // Blank is refused by the caller rather than treated as a match. OIDC
        // core requires an ID token whenever the `openid` scope was requested,
        // and Oidc.begin always requests it — so a response without one is a
        // realm not doing what was asked, not an older realm being tolerant of.
        assertEquals("", Auth.idTokenIssuer("""{"access_token":"a.b.c","expires_in":300}"""))
    }

    @Test
    fun aMalformedIdTokenYieldsBlankRatherThanThrowing() {
        // Reached at the very end of a sign-in, where a throw is the one
        // failure a rider cannot retry past. Every shape below has to come back
        // as a value.
        assertEquals("", Auth.idTokenIssuer(tokenResponse("not-a-jwt")))
        assertEquals("", Auth.idTokenIssuer(tokenResponse("only.two")))
        assertEquals("", Auth.idTokenIssuer(tokenResponse("a.!!!not-base64!!!.c")))
        assertEquals("", Auth.idTokenIssuer("<html>gateway</html>"))
        assertEquals("", Auth.idTokenIssuer(""))
    }

    @Test
    fun anIdTokenCarryingNoIssuerYieldsBlank() {
        assertEquals("", Auth.idTokenIssuer(tokenResponse(jwt("""{"sub":"abc"}"""))))
    }

    @Test
    fun theIssuerIsComparedWithoutATrailingSlash() {
        // Capabilities.parse and RoutingServer.pick both strip one, so the
        // pinned value never has it. A realm that puts one in its `iss` would
        // otherwise fail a comparison that is actually a match.
        val response = tokenResponse(
            jwt("""{"iss":"https://idp.example/realms/detour/","sub":"abc"}""")
        )
        assertEquals("https://idp.example/realms/detour", Auth.idTokenIssuer(response))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*AuthIssuerTest*'`
Expected: FAIL to compile — `Unresolved reference: idTokenIssuer`.

- [ ] **Step 3: Add the helper**

In `Auth.kt`, immediately after `usernameFrom` (which ends at `:458`), add:

```kotlin
    /**
     * The `iss` the realm signed into the ID token, or `""` when the response
     * carries none or it does not parse.
     *
     * Read unverified, exactly as [usernameFrom] and [subjectFrom] are, and for
     * a narrower purpose than it might look: this is not standing in for
     * signature validation, which the API performs and which is what actually
     * protects the session. It answers one question — did this token come back
     * from the realm we sent the rider to — and that question is only worth
     * asking because the issuer is now stated by the API server rather than
     * typed by a person. See ASVS 5.0.0 V10.2.2.
     *
     * Normalised like every other issuer in this codebase, so a realm that
     * emits a trailing slash does not fail a comparison that is a match.
     */
    internal fun idTokenIssuer(tokenResponse: String): String {
        val idToken = runCatching { jsonObjectOf(tokenResponse).optString("id_token") }
            .getOrDefault("")
        val payload = idToken.split(".").getOrNull(1) ?: return ""
        val json = payload.decodeBase64()?.utf8() ?: return ""
        return normalisedAddress(
            runCatching { jsonObjectOf(json).optString("iss") }.getOrDefault("")
        )
    }
```

`decodeBase64()` is already imported in this file for `usernameFrom`; confirm with `grep -n 'decodeBase64' shared/src/commonMain/kotlin/com/jellemax/detour/data/Auth.kt` and add `import okio.ByteString.Companion.decodeBase64` only if it is missing.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*AuthIssuerTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Enforce it in `exchangeCode`**

In `Auth.kt`, `exchangeCode` currently ends (`:186`):

```kotlin
        store(response, establishesSession = true)
    }
```

Replace those two lines with:

```kotlin
        // The realm that answered must be the realm we pinned. Checked here and
        // not in [store] because [refresh] also calls store, and a refresh
        // response carries no ID token.
        //
        // ASVS 5.0.0 V10.2.2: discovering the issuer at runtime makes this a
        // client that can interact with more than one authorization server,
        // which is what a mix-up attack needs. Only the token-response leg is
        // implemented; the authorization-response `iss` (RFC 9207) is not,
        // because requiring it would break an older Keycloak and tolerating its
        // absence is the bypass. That gap is recorded in the pull request, not
        // closed here.
        val pinned = RoutingServer.issuer(RoutingServer.loadCustom())
        val signed = idTokenIssuer(response)
        if (signed != pinned) {
            throw AuthException(
                if (signed.isBlank()) {
                    "The realm returned no usable ID token, so this sign-in " +
                        "could not be verified. Try again."
                } else {
                    "Sign-in came back from a different realm than the one " +
                        "configured, so it was refused. Check the sign-in realm " +
                        "under Settings."
                }
            )
        }
        store(response, establishesSession = true)
    }
```

- [ ] **Step 6: Correct the `sessionEpoch` parenthetical this feature invalidated**

Task 4's review found this, and Task 6 owns `Auth.kt`. The doc on `Auth.sessionEpoch`
(`Auth.kt:68-71`) says:

> (A server switch that keeps the same issuer does *not* bump this — [RoutingServer.save] only
> calls [clear] on an issuer change, see its own doc — but that is still the same rider, so it
> is not a case this guard needs to catch.)

That is no longer true for a rider whose only issuer was discovered. `save()` is not
suspending and cannot probe, so it discards the discovered value on a server change and the
effective issuer moves — bumping the epoch even when the new server would have advertised the
identical realm. Failing closed is the right trade (a needless re-sign-in beats a refresh that
reads as a replay), so change the comment, not the behaviour. Add a clause:

```kotlin
     * it is not a case this guard needs to catch.) One exception since realm
     * discovery: a rider whose issuer came from the server, not from the
     * settings field, does bump this on a server change, because [save] cannot
     * probe the new server to find out whether it names the same realm. Failing
     * closed is deliberate — a needless re-sign-in beats a refresh token
     * presented to a realm that did not mint it.
```

- [ ] **Step 7: Run the full shared suite**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest`
Expected: PASS, all tests.

Run: `devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/Auth.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/data/AuthIssuerTest.kt
git commit -m "feat(shared): require the ID token's iss to match the pinned issuer

Discovering the issuer at runtime makes this a client that can interact with
more than one authorization server, which is the precondition for a mix-up
attack. ASVS 5.0.0 V10.2.2 asks such a client for a defence; this is the
token-response leg of the example it gives.

'The token came back from the realm we asked' used to be true by construction,
because a person typed the issuer. It is not any more, so it is checked.

The authorization-response leg (RFC 9207 iss) is deliberately absent: requiring
it would break an older Keycloak and tolerating its absence is the bypass. The
gap is recorded in the PR description rather than in a comment.

Checked in exchangeCode rather than store(), because refresh() also calls store
and a refresh response carries no ID token.

Claude-Session: https://claude.ai/code/session_01Gjxrtw5G2FQWgkFsdz7Vwi"
```

---

## Task 7: `Oidc.resolveIssuer()` and the optimistic gate

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Oidc.kt:61-83`

- [ ] **Step 1: Replace `configured`, `issuer()` and the single-argument `begin`**

`Oidc.kt:61-83` currently reads:

```kotlin
    /** Whether signing in is possible at all — false when no realm is
     *  configured, which is how a build shipping no baked issuer behaves until
     *  the rider sets one under Settings. */
    val configured: Boolean get() = issuer().isNotBlank()

    /** Resolved rather than read off [BuildDefaults]: a store build ships no
     *  baked issuer, so the saved one is the only one there will ever be. */
    private fun issuer(): String = RoutingServer.issuer(RoutingServer.loadCustom())
```

followed by the doc comment and:

```kotlin
    fun begin(entropy: ByteArray): String = begin(entropy, issuer())
```

Replace all of it — from `val configured` through the single-argument `begin` — with:

```kotlin
    /**
     * Whether signing in is worth offering.
     *
     * Optimistic, and it has to be: a realm may be known only after asking the
     * API server, and asking needs a network call this non-suspending property
     * cannot make. Reporting `false` for a fresh install that has a server
     * address would hide the Sign in control, and hiding it means the probe that
     * would have found the realm never runs.
     *
     * So the honest reading is "there is either a realm or somewhere to ask",
     * and a rider whose server turns out not to answer learns that from
     * [resolveIssuer] at tap time — which is a message they can act on, unlike
     * a button that was never drawn.
     */
    val configured: Boolean get() = issuer().isNotBlank() || hasApiServer

    /**
     * Whether there is an API server a probe could ask.
     *
     * Exposed so the two platforms can tell "your server did not name a realm"
     * from "nothing is configured at all". Both surface as a blank issuer, and
     * they need different messages: one says update your server, the other says
     * fill in a field.
     */
    val hasApiServer: Boolean
        get() = RoutingServer.apiBase(RoutingServer.loadCustom()).isNotBlank()

    /** Resolved rather than read off [BuildDefaults]: a store build ships no
     *  baked issuer, so the saved one is the only one there will ever be. */
    private fun issuer(): String = RoutingServer.issuer(RoutingServer.loadCustom())

    /**
     * The realm to sign in to, asking the API server when the rider has not
     * named one. Blank when there is no realm to be had.
     *
     * A typed issuer short-circuits the network entirely — the field still
     * wins, which is what its deprecation copy promises.
     *
     * Otherwise this probes on **every** interactive sign-in rather than
     * trusting what it stored. That is the point rather than an oversight: a
     * stored issuer whose realm has since moved produces a 404 on the authorize
     * URL, and the rider has no lever to pull. Fetching at the moment of use
     * means the value is seconds old. The stored copy is still written, because
     * [Auth.refresh] runs on a cold start that may have no network and still
     * has to build a token endpoint from something.
     *
     * `@Throws(Exception::class)`: called from Swift, where an unannotated
     * escaping exception terminates the process rather than arriving as an
     * error. See the doc on [SyncClient.sync] for the full reasoning.
     */
    @Throws(Exception::class)
    suspend fun resolveIssuer(): String {
        val custom = RoutingServer.loadCustom()
        val typed = custom?.idpIssuer.orEmpty()
        if (typed.isNotBlank()) return RoutingServer.issuer(custom)

        val fetched = Capabilities
            .fetch(RoutingServer.apiBase(custom), custom.accessHeaders())
            ?.idpIssuer
            .orEmpty()
        val discovered = Capabilities.preferredDiscovered(
            fetched = fetched,
            stored = RoutingServer.discoveredIssuer(),
        )
        // Writes only on a change, and clears the session when the change is a
        // change of realm — see [RoutingServer.rememberDiscoveredIssuer].
        RoutingServer.rememberDiscoveredIssuer(discovered)
        return RoutingServer.issuer(custom, discovered)
    }
```

- [ ] **Step 2: Publish the two-argument `begin`**

At `Oidc.kt:85` (after the edit above, its line number will have moved; find it with `grep -n 'internal fun begin'`), change:

```kotlin
    /** `internal` so a test can supply an issuer without going near `prefs` —
     *  `RoutingServer.loadCustom()` reaches a Context that does not exist in a
     *  unit test. Same reason [Auth.tokenFailureMessage] is internal. */
    internal fun begin(entropy: ByteArray, issuer: String): String {
```

to:

```kotlin
    /**
     * Public, and the only way in: the issuer is now something a caller has to
     * go and get, so it is an argument rather than something read from here.
     *
     * The single-argument overload this replaced is deleted deliberately. Left
     * in place it would be a path that compiles, skips [resolveIssuer] and
     * silently never discovers a realm — and there are two callers, so making
     * the compiler point at both of them is cheap.
     *
     * Taking the issuer as a parameter is also what lets a test supply one
     * without going near `prefs`, which reaches a Context no unit test has.
     * Same reason [Auth.tokenFailureMessage] is internal.
     */
    fun begin(entropy: ByteArray, issuer: String): String {
```

- [ ] **Step 3: Verify the shared module still compiles and the existing tests hold**

Run: `devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata`
Expected: BUILD SUCCESSFUL.

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest`
Expected: PASS. `OidcTest`'s ~16 `Oidc.begin(entropy(), issuer)` calls all use the two-argument overload, so none of them change.

- [ ] **Step 4: Confirm the Android app is now the only thing broken**

Run: `devcontainer-exec ./gradlew :app:compileDebugKotlin`
Expected: FAIL, exactly one error — `AuthBrowser.kt:65`, no single-argument `begin`. That failure is Task 8's starting point. If anything else fails, stop and report it rather than working around it.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/Oidc.kt
git commit -m "feat(shared): ask the server which realm to sign in to

resolveIssuer() probes on every interactive sign-in rather than trusting a
stored value. A stored issuer whose realm has moved produces a 404 on the
authorize URL with no lever the rider can pull; fetching at the moment of use
means the value is seconds old. A typed issuer short-circuits the network
entirely -- the field still wins.

configured becomes optimistic, because a non-suspending property cannot make a
network call and reporting false would hide the button whose tap is what runs
the probe. hasApiServer lets each platform tell 'your server did not name a
realm' from 'nothing is configured'.

The single-argument begin() is deleted rather than kept: left in place it is a
path that compiles and silently never discovers anything. Two callers, so the
compiler pointing at both is worth more than source compatibility.

This commit breaks :app on purpose; the Android caller is next.

Claude-Session: https://claude.ai/code/session_01Gjxrtw5G2FQWgkFsdz7Vwi"
```

---

## Task 8: Android — `AuthBrowser` becomes suspending

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/auth/AuthBrowser.kt:22-95`

- [ ] **Step 1: Add the new failure case**

`AuthBrowser.kt:50-55` declares `NotConfigured`. Immediately after it, inside the same `StartFailure` sealed interface, add:

```kotlin
        /** There is an API server, but it did not name a realm — either it
         *  predates the capability endpoint or it was unreachable. Separate
         *  from [NotConfigured] because the rider's next move is different:
         *  update the server, rather than fill in a field. */
        data object NoRealmAdvertised : StartFailure
```

- [ ] **Step 2: Make `start` suspending and resolve the issuer first**

Replace `AuthBrowser.kt:58-69` — the doc comment through the blank-authorize check — with:

```kotlin
    /**
     * Opens the realm's login page. Returns `null` on success, or the reason
     * it did not open, so the caller can report the actual cause instead of
     * defaulting every failure to "no browser available".
     *
     * `suspend` because the realm may have to be asked for: a deployment states
     * its own issuer, and finding out is a request. Must still be called from
     * the main thread — [Oidc]'s parked verifier and state are guarded by a
     * single-thread contract, not a lock, and the caller's
     * `rememberCoroutineScope()` satisfies it.
     */
    suspend fun start(context: Context): StartFailure? {
        // Before the entropy, deliberately: a refused start should not have
        // drawn from the CSPRNG, and this is the failure most likely to happen
        // on a self-hosted deployment.
        val issuer = Oidc.resolveIssuer()
        if (issuer.isBlank()) {
            return if (Oidc.hasApiServer) StartFailure.NoRealmAdvertised
            else StartFailure.NotConfigured
        }

        val entropy = ByteArray(Oidc.ENTROPY_BYTES).also { SecureRandom().nextBytes(it) }
        val authorize = Oidc.begin(entropy, issuer)
        // Blank here is now only "entropy too short", since the issuer was
        // checked above. begin() already dropped anything it parked in that
        // case, so there is nothing to abandon.
        if (authorize.isBlank()) return StartFailure.NotConfigured
```

Leave the rest of the function — the `toHttpUrlOrNull` check, the `launchUrl` call and its `catch` — exactly as it is.

- [ ] **Step 3: Update the `configured` doc**

`AuthBrowser.kt:24-27` reads:

```kotlin
    /** Whether signing in is possible at all — false when no realm is
     *  configured, which is how a build with no secrets behaves until the rider
     *  sets one under Settings. */
    val configured: Boolean get() = Oidc.configured
```

Replace with:

```kotlin
    /** Whether signing in is worth offering — see [Oidc.configured], which is
     *  optimistic now: true when there is a realm *or* a server that might name
     *  one. A deployment that turns out not to answer surfaces as
     *  [StartFailure.NoRealmAdvertised] at tap time rather than as a missing
     *  button. */
    val configured: Boolean get() = Oidc.configured

    /** Whether there is a server a probe could ask; see [Oidc.hasApiServer]. */
    val hasApiServer: Boolean get() = Oidc.hasApiServer
```

- [ ] **Step 4: Verify only the caller is left broken**

Run: `devcontainer-exec ./gradlew :app:compileDebugKotlin`
Expected: FAIL — `FriendsScreen.kt:170`, a suspend function called from a non-suspend context, plus a non-exhaustive `when` missing `NoRealmAdvertised`. Both are Task 9.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/auth/AuthBrowser.kt
git commit -m "feat(android): resolve the realm before opening the browser

start() becomes suspend because the realm may have to be asked for. The resolve
runs before the entropy draw: a start that is going to be refused should not
have taken from the CSPRNG, and on a self-hosted deployment this is the most
likely refusal.

NoRealmAdvertised is separate from NotConfigured because the rider's next move
differs -- update the server, rather than fill in a field.

Still main-thread only: Oidc's parked verifier and state are guarded by a
single-thread contract rather than a lock.

Claude-Session: https://claude.ai/code/session_01Gjxrtw5G2FQWgkFsdz7Vwi"
```

---

## Task 9: Android — the sign-in button and its messages

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/FriendsScreen.kt:141-196`

- [ ] **Step 1: Add the scope and rewrite the gate and the click**

Replace `FriendsScreen.kt:141-190` — from `private fun SignInSection() {` through the closing `}` of the `Button` — with:

```kotlin
private fun SignInSection() {
    val context = LocalContext.current
    // Sign-in is a suspending call now: the realm may have to be asked for.
    // This scope is the composition's, so the launched body runs on the main
    // thread — which is what Oidc's single-thread contract requires of
    // begin()/abandon(). Do not move this to Dispatchers.IO.
    val scope = rememberCoroutineScope()
    // Set by MainActivity when a redirect fails to become a session; consumed
    // here because this is the screen the rider is looking at.
    val error by PendingSignIn.error.collectAsStateWithLifecycle()
    val busy by PendingSignIn.busy.collectAsStateWithLifecycle()

    Text(
        "Sign in to sync your rides and compare stats with friends. " +
            "Your trips and explored map stay private — friends only ever see " +
            "totals and badges.",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (!AuthBrowser.configured) {
        Text(
            "No server or sign-in realm is configured, so there is nobody to " +
                "sign in to. Set your server address under Settings → Servers & sync.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
    Button(
        onClick = {
            PendingSignIn.clear()
            // begin() is reached from inside this launch, so PendingSignIn.begin()
            // marks the screen busy for the probe as well as the browser trip:
            // on a slow server the resolve is the part the rider waits through.
            PendingSignIn.begin()
            scope.launch {
                val failure = AuthBrowser.start(context)
                when (failure) {
                    // The browser is open; MainActivity's redirect handler owns
                    // the rest, including clearing busy.
                    null -> {}
                    AuthBrowser.StartFailure.InvalidRealmUrl -> PendingSignIn.fail(
                        "The sign-in realm address is not a valid URL. Check it " +
                            "under Settings → Servers & sync."
                    )
                    AuthBrowser.StartFailure.NoBrowserAvailable ->
                        PendingSignIn.fail("No browser available to sign in with.")
                    AuthBrowser.StartFailure.NoRealmAdvertised ->
                        PendingSignIn.fail(
                            "Your server did not say which realm to sign in to. " +
                                "Update the server, or set the sign-in realm URL " +
                                "under Settings → Servers & sync."
                        )
                    AuthBrowser.StartFailure.NotConfigured ->
                        PendingSignIn.fail(
                            "No identity provider is configured. Set the sign-in " +
                                "realm URL under Settings → Servers & sync."
                        )
                }
            }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Text("Sign in")
    }
```

Leave the trailing "Opens your browser…" `Text` and the closing brace as they are.

- [ ] **Step 2: Check that `PendingSignIn.begin()` before a failure is safe**

`PendingSignIn.fail(...)` must clear `busy`, or a refused start leaves the button disabled for good. Read it:

`grep -n 'fun begin\|fun fail\|fun clear\|fun succeed' -A6 app/src/main/java/com/jellemax/detour/ui/PendingSignIn.kt`

If `fail` does not clear `busy`, do **not** patch `PendingSignIn` — instead drop the `PendingSignIn.begin()` line added in Step 1 and leave busy-marking to `MainActivity` as it was before. Report which of the two you did.

- [ ] **Step 3: Add the imports**

`rememberCoroutineScope` and `launch` are needed. Check what is already there:

`grep -n 'rememberCoroutineScope\|kotlinx.coroutines.launch' app/src/main/java/com/jellemax/detour/ui/FriendsScreen.kt`

`FriendsScreen.kt:201` already calls `rememberCoroutineScope()` in `FriendsSection`, so both imports are likely present. Add whichever is missing:

```kotlin
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
```

- [ ] **Step 4: Verify the Android app compiles**

Run: `devcontainer-exec ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the Android unit tests**

Run: `devcontainer-exec ./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/FriendsScreen.kt
git commit -m "feat(android): report a server that names no realm

The Sign in click launches into the composition's scope, which keeps the call
on the main thread -- Oidc guards its parked verifier with a single-thread
contract rather than a lock, so this must not move to Dispatchers.IO.

The gate's message changes with configured becoming optimistic: it now fires
only when there is neither a realm nor a server, so it points at the server
address rather than at the realm field. A server that does not name a realm
gets its own message, which is the one a self-hoster on an older build will
actually see.

Claude-Session: https://claude.ai/code/session_01Gjxrtw5G2FQWgkFsdz7Vwi"
```

---

## Task 10: Android — deprecate the realm field

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt:1219-1234`

- [ ] **Step 1: Replace the field label and its explanatory text**

`SettingsScreen.kt:1219-1234` currently reads:

```kotlin
        CredentialTextField(
            value = idpIssuer, onValueChange = { idpIssuer = it; saved = false },
            label = "Sign-in realm URL",
            keyboardType = KeyboardType.Uri,
            placeholder = "https://idp.example.com/realms/detour",
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Your identity provider's realm, which issues the tokens the API " +
                "trusts. It has no default from the server address above — a realm " +
                "URL is never the same host — so signing in stays off until it is " +
                "filled in. Changing it signs this device out: tokens from one " +
                "realm mean nothing to another.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
```

Replace with:

```kotlin
        CredentialTextField(
            value = idpIssuer, onValueChange = { idpIssuer = it; saved = false },
            label = "Sign-in realm URL (deprecated)",
            keyboardType = KeyboardType.Uri,
            placeholder = "https://idp.example.com/realms/detour",
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Deprecated — newer servers tell the app which realm to use, so " +
                "leave this empty unless your server has not been updated. " +
                "Anything typed here still wins over what the server says. " +
                "Changing it signs this device out: tokens from one realm mean " +
                "nothing to another.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
```

The removed sentence — "It has no default from the server address above" — is now false, which is the point of the change. The sentence about signing out survives because it is still true.

- [ ] **Step 2: Verify it compiles**

Run: `devcontainer-exec ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt
git commit -m "feat(android): mark the sign-in realm field deprecated

The five lines of warning under this field existed because it had no default
and no way to infer one. It has one now, so the copy says what the field is
for -- a server that has not been updated yet -- and that typing something
still overrides the server.

No removal version is named. Whether the deployment this field exists for is
real enough to keep supporting is a question for the tracker, and naming a
version now would commit to deleting the only fallback on no evidence.

Claude-Session: https://claude.ai/code/session_01Gjxrtw5G2FQWgkFsdz7Vwi"
```

---

## Task 11: iOS — resolve before `begin`

**Cannot be compiled here.** Xcode is not available in this container and `shared`'s iOS targets cannot be built on Linux. `.github/workflows/ios.yml` is the gate. Make the edit exactly as written and say in the commit that it is CI-verified.

**Files:**
- Modify: `iosApp/Detour/SignIn.swift:26-28`, `:34-69`

- [ ] **Step 1: Add `hasApiServer` alongside `configured`**

`SignIn.swift:26-28` reads:

```swift
    /// False when no realm is configured, which is how a build shipping no
    /// baked issuer behaves until one is set under Settings.
    var configured: Bool { Oidc.shared.configured }
```

Replace with:

```swift
    /// True when there is a realm *or* a server that might name one — see
    /// `Oidc.configured`, which is optimistic now. A server that turns out not
    /// to name one surfaces as a message when Sign in is tapped, rather than as
    /// a button that was never drawn.
    var configured: Bool { Oidc.shared.configured }

    /// Whether there is a server a probe could ask. Distinguishes "your server
    /// did not say" from "nothing is configured at all".
    var hasApiServer: Bool { Oidc.shared.hasApiServer }
```

- [ ] **Step 2: Resolve the issuer before drawing entropy**

`SignIn.swift:39-69` currently draws entropy, then calls `begin`, then checks for blank. Replace from `guard let secureEntropy = entropy() else {` through the closing `}` of the `guard !authorize.isEmpty else {` block with:

```swift
        // Before the entropy, deliberately: a sign-in that is going to be
        // refused should not have drawn from the CSPRNG, and on a self-hosted
        // deployment this is the refusal most likely to happen.
        //
        // `try?` rather than `try`: resolveIssuer is @Throws so a failure
        // arrives as an error instead of terminating the process, and there is
        // nothing to tell the rider about a failed probe that the blank-issuer
        // message below does not already say better.
        let issuer = (try? await Oidc.shared.resolveIssuer()) ?? ""
        guard !issuer.isEmpty else {
            error = hasApiServer
                ? "Your server did not say which realm to sign in to. Update "
                    + "the server, or set the sign-in realm under Settings → Own server."
                : "No server or sign-in realm is configured, so there is nobody "
                    + "to sign in to. Set your server address under Settings → Own server."
            return
        }

        guard let secureEntropy = entropy() else {
            // SecRandomCopyBytes failing is undocumented on iOS, but checked
            // rather than trusted — see entropy()'s doc. Reported distinctly
            // from the realm failures above: proceeding with begin() here would
            // refuse on length and report a realm problem that did not happen.
            error = "Could not generate a secure sign-in request. Please try again."
            return
        }

        // Runs on every path below, including the one where `session.start()`
        // returns false — on the MainActor, after `present(_:)`'s completion
        // handler (if it ran at all) has already returned. Deliberately not
        // done from inside that handler: see the comment there. Do not move
        // this back into the handler.
        defer { session = nil }

        let authorize = Oidc.shared.begin(entropy: secureEntropy, issuer: issuer)
        guard !authorize.isEmpty else {
            // begin() returns blank rather than throwing: it is not a suspend
            // function, and a throw out of one of those terminates this process
            // instead of arriving as an error. The issuer is non-blank by this
            // point and secureEntropy is always full length (entropy()
            // guarantees it), so this is unreachable in practice — kept because
            // a silent empty URL would be worse than a redundant check.
            error = "Could not start sign-in. Please try again."
            return
        }
```

Note the ordering change: `defer { session = nil }` must stay after the entropy guard and before `begin`, exactly as above. Its own comment explains why it is where it is; do not move it above the new issuer guard, because the new guard returns before any session exists.

- [ ] **Step 3: Confirm the Swift call shape against an existing one**

`begin` gains a second argument, and Kotlin/Native requires named arguments in Swift. The call is `Oidc.shared.begin(entropy: secureEntropy, issuer: issuer)`. Cross-check the pattern against a two-argument shared call already in the tree:

`grep -rn 'RoutingServer.shared.route\|FriendsStore.shared.respond' iosApp/Detour/ | head -3`

- [ ] **Step 4: Verify by inspection, then commit**

There is no local command that type-checks this. Re-read the edited function start to finish and confirm: every `guard` returns, `defer { session = nil }` sits above `begin`, and no `try` is unaccompanied by `?` or a `do`/`catch`.

```bash
git add iosApp/Detour/SignIn.swift
git commit -m "feat(ios): resolve the realm before opening the browser

resolveIssuer() runs before the entropy draw, for the same reason as on
Android: a sign-in that will be refused should not have taken from the CSPRNG.
try? rather than try -- the shared function is @Throws so a failed probe
arrives as an error, and the blank-issuer message says more than the error
would.

The blank case now splits in two on hasApiServer: a server that named no realm
gets 'update your server', and a device with nothing configured gets 'set your
server address'.

Not compiled locally -- Xcode is unavailable in this container and shared's iOS
targets cannot be built on Linux. Verified by the iOS workflow.

Claude-Session: https://claude.ai/code/session_01Gjxrtw5G2FQWgkFsdz7Vwi"
```

---

## Task 12: iOS — the two remaining copy sites

**Cannot be compiled here.** Same caveat as Task 11.

**Files:**
- Modify: `iosApp/Detour/FriendsScreen.swift:394-401`
- Modify: `iosApp/Detour/SettingsScreen.swift:139-142`, `:152-159`

- [ ] **Step 1: Update the `SignInForm` dead end**

`FriendsScreen.swift:394-401` reads:

```swift
                } else {
                    Text("""
                        No identity provider is configured, so there is nobody to \
                        sign in to. Set the sign-in realm under Settings → Own server.
                        """)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
```

Replace with:

```swift
                } else {
                    // Reached only when there is neither a realm nor a server
                    // to ask for one — `signIn.configured` is optimistic now.
                    // A server that has an address but names no realm keeps the
                    // button and reports it on tap, which is actionable where a
                    // missing button is not.
                    Text("""
                        No server or sign-in realm is configured, so there is nobody \
                        to sign in to. Set your server address under Settings → Own server.
                        """)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
```

- [ ] **Step 2: Deprecate the realm field**

`SettingsScreen.swift:139-142` reads:

```swift
            TextField("https://your.realm/realms/detour", text: $idpIssuer)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
```

Replace with:

```swift
            TextField("Sign-in realm (deprecated)", text: $idpIssuer)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
```

- [ ] **Step 3: Update the section footer**

`SettingsScreen.swift:152-159` reads:

```swift
        } footer: {
            Text("""
                One address reaches routing, search, sync and convoys — the tunnel \
                routes by path. Leave blank to use the built-in defaults. The realm \
                address is separate and never derived from the others: it is where \
                signing in happens.
                """)
        }
```

Replace with:

```swift
        } footer: {
            Text("""
                One address reaches routing, search, sync and convoys — the tunnel \
                routes by path. Leave blank to use the built-in defaults. The realm \
                field is deprecated: newer servers tell the app which realm to use, \
                so leave it empty unless your server has not been updated. Anything \
                typed there still wins over what the server says.
                """)
        }
```

- [ ] **Step 4: Verify by inspection, then commit**

Re-read both edits. Confirm the Swift multi-line string continuations (`\` at end of line) are preserved and every `"""` is balanced.

```bash
git add iosApp/Detour/FriendsScreen.swift iosApp/Detour/SettingsScreen.swift
git commit -m "feat(ios): mark the realm field deprecated and fix the dead end

The footer said the realm address is 'never derived from the others', which
stops being true with this change. It now says the field is for a server that
has not been updated yet, and that typing something still overrides.

The SignInForm else-branch is reached only when there is neither a realm nor a
server, so it points at the server address rather than the realm.

Not compiled locally -- verified by the iOS workflow.

Claude-Session: https://claude.ai/code/session_01Gjxrtw5G2FQWgkFsdz7Vwi"
```

---

## Task 13: Version bump and full verification

**Files:**
- Modify: `app/build.gradle.kts:80`

- [ ] **Step 1: Bump `versionName`**

`app/build.gradle.kts:80` reads:

```kotlin
        versionName = "1.93.1"
```

Change to:

```kotlin
        versionName = "1.94.0"
```

A backward-compatible feature, per `CLAUDE.md`'s table. Do not touch `versionCode` — it is CI-stamped from the run number.

**Do not trust any version number written in this plan. Read the file.** This moved three times while the branch was open: `1.93.1` at planning time, `1.93.2` when #112 merged, `1.94.0` when #114 merged — and #114 claimed the very number this task had already written, so `git rebase` dropped this task's commit as "patch contents already upstream" and the branch silently had no bump at all. The target is *one minor above whatever `main` currently holds*, because this is a backward-compatible feature. Compute it at the moment you make the commit; a literal recorded here is wrong by the time anyone reads it.

- [ ] **Step 2: Run every check this machine can run**

```bash
devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata
devcontainer-exec ./gradlew :shared:testDebugUnitTest :app:testDebugUnitTest
devcontainer-exec ./gradlew :app:assembleDebug :wear:assembleDebug
devcontainer-exec dotnet build backend/Detour.slnx
devcontainer-exec dotnet format style backend/Detour.slnx --severity info --verify-no-changes --exclude '**/Migrations/**'
devcontainer-exec dotnet test backend/Detour/Detour.Domain.Tests
devcontainer-exec dotnet test backend/Detour/Detour.InfraTests
```

Expected: all green. `:wear:assembleDebug` is included because the watch module shares `:shared`, even though it has no auth code.

- [ ] **Step 3: Confirm the deleted overload has no callers left**

```bash
grep -rn 'Oidc.begin(' app/src iosApp shared/src wear/src | grep -v 'issuer'
```

Expected: no output. Any hit is a call site still using the deleted single-argument form.

- [ ] **Step 4: Confirm no stray reference to the old endpoint name**

The spec went through a `/api/config` phase before settling on `/api/capabilities`.

```bash
grep -rn 'api/config' --include='*.kt' --include='*.swift' --include='*.cs' --include='*.md' . | grep -v '.claude/'
```

Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore(release): 1.94.0

Backward-compatible feature: the app can learn its sign-in realm from the API
server, and every existing install keeps the realm URL it typed and keeps
using it.

Claude-Session: https://claude.ai/code/session_01Gjxrtw5G2FQWgkFsdz7Vwi"
```

---

## Pull request notes

Three things belong in the description rather than in a comment, and `CONTRIBUTING.md` asks for the third explicitly.

1. **`ASVS 5.0.0 V10.2.2` is partially met.** The token-response `iss` check is implemented; the authorization-response leg (RFC 9207) is not, because requiring `iss` on the callback would break an older Keycloak and tolerating its absence is the bypass.
2. **First consumer of `RateLimitPolicies.Anonymous`.** The policy was registered at `RateLimitingExtensions.cs:74` and consumed nowhere, so this change also exercises wiring nothing had used. Two consequences of being first, both worth stating rather than discovering later:
   - A `429` reaches the app as "no realm advertised", because the probe collapses every non-answer to one (see Task 5's note). Narrow in practice — a returning rider falls back to the stored issuer — but real for a first sign-in on a saturated shared address.
   - `RateLimitSettings.cs:36-46` justifies the bucket's size with "the legacy server capped auth attempts at 10 per 5 minutes per address". The first actual consumer is not an auth attempt, and the policy is one shared bucket per IP across every endpoint that later carries it. When an auth-adjacent anonymous endpoint arrives, benign discovery probes and brute-force attempts will compete for a budget sized for the latter. Worth splitting the policy at that point, not now.
   - `HealthController` deliberately does **not** carry the policy: it is polled by load balancers and uptime monitors on a fixed cadence, and this budget would throttle the one caller that must never be throttled. That reasoning is recorded in `CapabilitiesController`'s own doc so the divergence does not read as an oversight.
3. **This is security-relevant code.** `CONTRIBUTING.md`'s pull-request section asks that credential-storage and auth changes be called out for a closer look. State the trust delta plainly: a compromised API server previously received the rider's tokens, and can now also choose the page the rider types their password into.

Also worth saying: `iosApp/` changes were not compiled locally, because Xcode is unavailable in the devcontainer and `shared`'s iOS targets cannot be built on Linux. The iOS workflow is the gate.

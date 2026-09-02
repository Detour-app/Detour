# Read X-Forwarded-* behind a trusted proxy — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the API honour `X-Forwarded-For` / `X-Forwarded-Proto` when — and only when — the operator has named a trusted proxy, so per-IP rate limits stop collapsing into one bucket behind a reverse proxy.

**Architecture:** A `ForwardedHeadersSettings` class binds the `ForwardedHeaders` config section that `docker-compose.yml` and `INSTALL.md` already document. Two extension methods configure `ForwardedHeadersOptions` and add the framework's `UseForwardedHeaders` middleware **only when a proxy is configured**. When nothing is configured, no middleware is added at all — the safe default is the absence of the middleware, not middleware told to trust nobody.

**Tech Stack:** .NET 10 (`net10.0`), ASP.NET Core, xunit + AwesomeAssertions, `Microsoft.AspNetCore.TestHost`. All `dotnet` commands run through `devcontainer-exec`.

**Spec:** `docs/superpowers/specs/2026-09-02-forwarded-headers-design.md`

**Working directory:** `/home/andre/Projects/Detour/.claude/worktrees/fix-forwarded-headers`

---

## Background the implementer needs

The API never reads `X-Forwarded-*`. There is no `UseForwardedHeaders()` call and nothing binds a `ForwardedHeaders` section. `ResolveClientIp` (`backend/Shared/Api.RateLimiting/RateLimitingExtensions.cs:134-142`) reads `context.Connection.RemoteIpAddress`, which behind a proxy is the proxy's address for every caller. So every per-IP rate limit is one shared bucket for the whole deployment.

### The framework defaults that make the naive fix wrong

These are the whole reason this plan is longer than "call UseForwardedHeaders". From the ASP.NET Core `ForwardedHeadersOptions` documentation:

| Option | Default | Why it matters |
|---|---|---|
| `ForwardedHeaders` | `None` | **Must be set explicitly.** Left alone, the middleware runs and does nothing. The bug would look fixed and stay open. |
| `KnownProxies` | `IPAddress.IPv6Loopback` (`::1`) | **Not empty.** Configuring with empty lists does not mean "trust nobody" — it leaves `::1` trusted. |
| `KnownIPNetworks` | empty | The `net10.0` property. `KnownNetworks` is the obsolete pre-.NET-8 form — do not use it. |
| `ForwardLimit` | `1` | Correct for a single proxy. Leave it alone. `null` means unlimited and is documented as dangerous. |

Also: `KnownProxies` is `IList<IPAddress>` and `KnownIPNetworks` is `IList<IPNetwork>`. Neither binds from a configuration string, so binding the section directly onto `ForwardedHeadersOptions` silently yields empty lists. That is why a settings class of `string[]` plus explicit parsing is required.

### Why "no middleware" rather than "middleware trusting nobody"

Trusting forwarded headers unconditionally would let any caller reset its own limiter bucket per request by spoofing a header — worse than the shared bucket being fixed, because it turns a coarse limit into no limit. Given the `::1` default above, "ignore forwarded headers entirely" cannot be expressed by configuring empty lists. Not adding the middleware is the only formulation that is safe by construction.

### Conventions to follow

- Namespaces mirror directories: `backend/Shared/Api/…` is namespace `Shared.Api`, so `backend/Shared/Api/ForwardedHeaders/` is `Shared.Api.ForwardedHeaders`.
- `backend/Shared/Api/Shared.Api.csproj` already has `<FrameworkReference Include="Microsoft.AspNetCore.App" />`, so `Microsoft.AspNetCore.HttpOverrides` is available with no package addition.
- Tests: xunit `[Fact]`, AwesomeAssertions (`value.Should().Be(...)`), method names in `Sentence_case_with_underscores`. `Xunit` and `AwesomeAssertions` are global usings in `backend/Detour/Detour.InfraTests/GlobalUsings.cs` — do not re-import them.
- CI runs `dotnet format style --severity info --verify-no-changes`. Formatting is a gate.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `backend/Shared/Api/ForwardedHeaders/ForwardedHeadersSettings.cs` | Create | The bound config section; knows whether a proxy is configured |
| `backend/Shared/Api/ForwardedHeaders/ForwardedHeadersExtensions.cs` | Create | Parse + configure options; conditionally add the middleware |
| `backend/Detour/Detour.InfraTests/Api/ForwardedHeadersTests.cs` | Create | The five cases, with no database dependency |
| `backend/Detour/Detour.Api/Configuration/ApiConfiguration.cs` | Modify | Add the `ForwardedHeaders` property |
| `backend/Detour/Detour.Api/Startup.cs` | Modify | Register the service and put the middleware first |
| `backend/Shared/Api.RateLimiting/RateLimitingExtensions.cs:134-142` | Modify | Correct the comment that asserts this already worked |
| `backend/INSTALL.md` | Modify | Note that a malformed address fails startup |
| `docs/BACKEND_SPEC.md` §15.2 | Modify | Record the single-hop (`ForwardLimit = 1`) assumption |

---

### Task 1: The settings class

**Files:**
- Create: `backend/Shared/Api/ForwardedHeaders/ForwardedHeadersSettings.cs`

- [ ] **Step 1: Write the file**

```csharp
namespace Shared.Api.ForwardedHeaders;

/// <summary>
/// Which upstream hops may be believed when they claim, via <c>X-Forwarded-For</c>, to be
/// relaying somebody else's request. Bind from <c>appsettings.json</c> section
/// <c>ForwardedHeaders</c>.
/// </summary>
/// <remarks>
/// Both lists empty is the safe default and means forwarded headers are ignored entirely —
/// see <see cref="IsConfigured"/>. The property names are the ones
/// <c>docker/prod/docker-compose.yml</c> and <c>backend/INSTALL.md</c> already tell operators
/// to set, so they are deliberately not renamed to match the framework's own
/// <c>KnownIPNetworks</c>.
/// </remarks>
public sealed class ForwardedHeadersSettings
{
    public const string SectionName = "ForwardedHeaders";

    /// <summary>Exact proxy addresses to trust, e.g. <c>172.18.0.5</c>.</summary>
    public string[] KnownProxies { get; set; } = [];

    /// <summary>Proxy networks in CIDR form, e.g. <c>172.18.0.0/16</c>.</summary>
    public string[] KnownNetworks { get; set; } = [];

    /// <summary>
    /// Whether the operator named a proxy. False means the middleware is never added, which is
    /// the only way to ignore forwarded headers entirely: the framework's own default for
    /// <c>KnownProxies</c> is the IPv6 loopback address rather than an empty list, so
    /// configuring the options with nothing in them still trusts a loopback peer.
    /// </summary>
    public bool IsConfigured => KnownProxies.Length > 0 || KnownNetworks.Length > 0;
}
```

- [ ] **Step 2: Build**

```bash
devcontainer-exec dotnet build backend/Detour.slnx
```
Expected: `Build succeeded.` Two pre-existing `NU1903` warnings about `SSH.NET` are expected and are not yours — do not try to fix them.

- [ ] **Step 3: Commit**

```bash
git add backend/Shared/Api/ForwardedHeaders/ForwardedHeadersSettings.cs
git commit -m "Add the ForwardedHeaders settings section (#119)"
```

---

### Task 2: The extensions, test-first

**Files:**
- Create: `backend/Shared/Api/ForwardedHeaders/ForwardedHeadersExtensions.cs`
- Test: `backend/Detour/Detour.InfraTests/Api/ForwardedHeadersTests.cs`

These tests deliberately do **not** use `DetourApiFactory` or `PostgresFixture`. Every other file in that directory does, because it exercises an endpoint. This one exercises the middleware pipeline's resolution of the client address, which needs no database and must not wait for a container.

- [ ] **Step 1: Write the failing tests**

Create `backend/Detour/Detour.InfraTests/Api/ForwardedHeadersTests.cs`:

```csharp
using System.Net;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Shared.Api.ForwardedHeaders;

namespace Detour.InfraTests.Api;

/// <summary>
/// What the rate limiter ends up keying on. Asserted through
/// <c>HttpContext.Connection.RemoteIpAddress</c> because that is exactly the value
/// <c>RateLimitingExtensions.ResolveClientIp</c> returns, so these are tests of the limiter's
/// partition key without reaching into a private method.
/// </summary>
public class ForwardedHeadersTests
{
    /// <summary>
    /// A host carrying only the middleware under test. The peer address is set by a middleware
    /// registered ahead of it, since a TestServer connection has none of its own.
    /// </summary>
    private static async Task<IHost> HostWith(ForwardedHeadersSettings settings, IPAddress peer)
    {
        return await new HostBuilder()
            .ConfigureWebHost(web => web
                .UseTestServer()
                .ConfigureServices(services => services.AddTrustedProxies(settings))
                .Configure(app =>
                {
                    app.Use(async (context, next) =>
                    {
                        context.Connection.RemoteIpAddress = peer;
                        await next();
                    });
                    app.UseTrustedProxies(settings);
                    app.Run(context =>
                        context.Response.WriteAsync(
                            context.Connection.RemoteIpAddress?.ToString() ?? "none"));
                }))
            .StartAsync();
    }

    private static async Task<string> ResolvedAddress(
        ForwardedHeadersSettings settings,
        string peer,
        string? forwardedFor)
    {
        using var host = await HostWith(settings, IPAddress.Parse(peer));
        var client = host.GetTestClient();
        if (forwardedFor is not null)
            client.DefaultRequestHeaders.Add("X-Forwarded-For", forwardedFor);

        return await client.GetStringAsync("/");
    }

    [Fact]
    public async Task A_spoofed_forwarded_header_is_ignored_when_no_proxy_is_trusted()
    {
        // The safe default, and the regression guard for it. If someone later "simplifies" the
        // conditional registration away, this is the test that fails — and it has to, because
        // trusting the header unconditionally lets any caller reset its own rate-limit bucket
        // per request, which is worse than the shared bucket this change fixes.
        var resolved = await ResolvedAddress(
            new ForwardedHeadersSettings(), peer: "10.0.0.9", forwardedFor: "203.0.113.7");

        resolved.Should().Be("10.0.0.9");
    }

    [Fact]
    public async Task A_forwarded_header_from_a_trusted_proxy_is_honoured()
    {
        var resolved = await ResolvedAddress(
            new ForwardedHeadersSettings { KnownProxies = ["10.0.0.9"] },
            peer: "10.0.0.9",
            forwardedFor: "203.0.113.7");

        resolved.Should().Be("203.0.113.7");
    }

    [Fact]
    public async Task A_forwarded_header_from_an_untrusted_hop_is_ignored()
    {
        var resolved = await ResolvedAddress(
            new ForwardedHeadersSettings { KnownProxies = ["10.0.0.9"] },
            peer: "10.0.0.250",
            forwardedFor: "203.0.113.7");

        resolved.Should().Be("10.0.0.250");
    }

    [Fact]
    public async Task A_trusted_proxy_can_be_named_as_a_network()
    {
        // The form docker-compose.yml documents, and the one an operator on a Docker bridge
        // actually needs, since the container's address is assigned rather than fixed.
        var resolved = await ResolvedAddress(
            new ForwardedHeadersSettings { KnownNetworks = ["10.0.0.0/24"] },
            peer: "10.0.0.9",
            forwardedFor: "203.0.113.7");

        resolved.Should().Be("203.0.113.7");
    }

    [Fact]
    public void A_malformed_proxy_address_fails_at_startup()
    {
        // Loudly, rather than degrading to an untrusted proxy — which would silently reinstate
        // the shared-bucket bug on a deployment whose operator believes they configured this.
        var settings = new ForwardedHeadersSettings { KnownProxies = ["not-an-address"] };

        var act = () => new ServiceCollection().AddTrustedProxies(settings);

        act.Should().Throw<FormatException>();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
devcontainer-exec dotnet test backend/Detour/Detour.InfraTests --filter "FullyQualifiedName~ForwardedHeadersTests" --logger 'console;verbosity=minimal'
```

Expected: a **compile** failure — `AddTrustedProxies` and `UseTrustedProxies` do not exist yet. That is the correct failure. If it fails any other way, STOP and report.

- [ ] **Step 3: Write the extensions**

Create `backend/Shared/Api/ForwardedHeaders/ForwardedHeadersExtensions.cs`:

```csharp
using System.Net;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.Extensions.DependencyInjection;

namespace Shared.Api.ForwardedHeaders;

/// <summary>
/// Wiring for <c>X-Forwarded-*</c>, opt-in by construction.
/// </summary>
public static class ForwardedHeadersExtensions
{
    /// <summary>
    /// Configures the forwarded-headers middleware, when — and only when — the operator named
    /// a proxy.
    /// </summary>
    /// <remarks>
    /// Every framework default here is overridden deliberately rather than inherited:
    /// <list type="bullet">
    /// <item><c>ForwardedHeaders</c> defaults to <c>None</c>, so the middleware would run and
    /// do nothing.</item>
    /// <item><c>KnownProxies</c> defaults to the IPv6 loopback address rather than an empty
    /// list, so the defaults are cleared before the configured entries are added.</item>
    /// <item><c>ForwardLimit</c> is left at its default of 1: one proxy, one hop. A chained
    /// deployment needs a deliberate change here, not a silently larger number.</item>
    /// </list>
    /// Parsing is strict and a malformed value throws at startup. A mistyped CIDR must be a
    /// boot failure rather than a quietly untrusted proxy, which would look like a working
    /// deployment while every caller shared one rate-limit bucket.
    /// </remarks>
    public static IServiceCollection AddTrustedProxies(
        this IServiceCollection services,
        ForwardedHeadersSettings settings)
    {
        if (!settings.IsConfigured) return services;

        var proxies = settings.KnownProxies.Select(IPAddress.Parse).ToList();
        var networks = settings.KnownNetworks.Select(IPNetwork.Parse).ToList();

        services.Configure<ForwardedHeadersOptions>(options =>
        {
            options.ForwardedHeaders =
                Microsoft.AspNetCore.HttpOverrides.ForwardedHeaders.XForwardedFor
                | Microsoft.AspNetCore.HttpOverrides.ForwardedHeaders.XForwardedProto;

            options.KnownProxies.Clear();
            options.KnownIPNetworks.Clear();

            foreach (var proxy in proxies) options.KnownProxies.Add(proxy);
            foreach (var network in networks) options.KnownIPNetworks.Add(network);
        });

        return services;
    }

    /// <summary>
    /// Adds the forwarded-headers middleware when a proxy is configured, and nothing at all
    /// when one is not.
    /// </summary>
    /// <remarks>
    /// Nothing at all, rather than middleware configured to trust an empty list, because the
    /// framework's <c>KnownProxies</c> default is the IPv6 loopback address — so "configured
    /// with nothing" still believes a loopback peer's header. Absence is the only formulation
    /// that ignores forwarded headers by construction.
    /// </remarks>
    public static IApplicationBuilder UseTrustedProxies(
        this IApplicationBuilder app,
        ForwardedHeadersSettings settings) =>
        settings.IsConfigured ? app.UseForwardedHeaders() : app;
}
```

Two name clashes to expect here, both real and both worth knowing before you fight the compiler:

1. **`ForwardedHeaders`** — the namespace `Shared.Api.ForwardedHeaders` shadows the enum name, so the short form does not compile. Hence the fully-qualified use above. An alias (`using ForwardedHeadersEnum = Microsoft.AspNetCore.HttpOverrides.ForwardedHeaders;`) is equally fine — either is acceptable, but it must compile and read clearly.
2. **`IPNetwork`** — this type exists in *both* `System.Net` (the .NET 8+ one, which `KnownIPNetworks` is a list of) and `Microsoft.AspNetCore.HttpOverrides` (the obsolete one). With both namespaces imported the bare name is ambiguous and will not compile. Write `System.Net.IPNetwork.Parse` explicitly, or alias it. The list you are adding to is `IList<System.Net.IPNetwork>`, so that is the one you want.

If either of these turns out differently in practice, report what the compiler actually said rather than working around it silently.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
devcontainer-exec dotnet test backend/Detour/Detour.InfraTests --filter "FullyQualifiedName~ForwardedHeadersTests" --logger 'console;verbosity=minimal'
```

Expected: 5 passed, 0 failed. If `A_malformed_proxy_address_fails_at_startup` fails because the exception type differs, report the actual type — do not loosen the assertion to `Exception` without saying so.

- [ ] **Step 5: Commit**

```bash
git add backend/Shared/Api/ForwardedHeaders/ForwardedHeadersExtensions.cs backend/Detour/Detour.InfraTests/Api/ForwardedHeadersTests.cs
git commit -m "Honour X-Forwarded-* only when a proxy is trusted (#119)"
```

---

### Task 3: Wire it into the API

**Files:**
- Modify: `backend/Detour/Detour.Api/Configuration/ApiConfiguration.cs`
- Modify: `backend/Detour/Detour.Api/Startup.cs`

- [ ] **Step 1: Add the section to `ApiConfiguration`**

In `backend/Detour/Detour.Api/Configuration/ApiConfiguration.cs`, add a using for `Shared.Api.ForwardedHeaders;` and add this property to the `ApiConfiguration` class, after `Cors`:

```csharp
    public ForwardedHeadersSettings ForwardedHeaders { get; set; } = new();
```

- [ ] **Step 2: Register the service**

In `Startup.ConfigureServices`, next to the other `services.Configure<…>` calls (just after the `IdpSettings` registration is a good spot):

```csharp
        services.AddTrustedProxies(MappedConfiguration.ForwardedHeaders);
```

Add `using Shared.Api.ForwardedHeaders;` to the file's usings, in alphabetical position among the existing `Shared.*` usings.

- [ ] **Step 3: Put the middleware first in the pipeline**

In `Startup.Configure`, the method currently begins:

```csharp
        app.UseCors();
        app.UseRequestLocalization();
```

Insert above `app.UseCors();` so it is the first middleware in the pipeline:

```csharp
        // First, because everything below reads either the client address or the scheme.
        // UseRateLimiter's per-IP partitions need the rewritten address, and
        // UseHttpsRedirection needs the forwarded scheme — without it, a request that reached
        // the proxy over https is answered with a redirect to http. Adds nothing at all
        // unless a proxy is configured; see ForwardedHeadersSettings.
        app.UseTrustedProxies(MappedConfiguration.ForwardedHeaders);

```

- [ ] **Step 4: Build and run the full backend test suite**

```bash
devcontainer-exec dotnet build backend/Detour.slnx
devcontainer-exec dotnet test backend/Detour/Detour.Domain.Tests --logger 'console;verbosity=minimal'
devcontainer-exec dotnet test backend/Detour/Detour.InfraTests --logger 'console;verbosity=minimal'
```

Expected: build succeeds; both suites pass. The InfraTests suite starts a Postgres container via Testcontainers and takes a while — that is normal. If Docker is unavailable inside the devcontainer and the Postgres-backed tests cannot run, say so explicitly in your report and confirm that at least the `ForwardedHeadersTests` filter above passes. Do not silently skip them.

- [ ] **Step 5: Commit**

```bash
git add backend/Detour/Detour.Api/Configuration/ApiConfiguration.cs backend/Detour/Detour.Api/Startup.cs
git commit -m "Read X-Forwarded-* at the front of the pipeline (#119)"
```

---

### Task 4: Correct the comment that asserted this already worked

**Files:**
- Modify: `backend/Shared/Api.RateLimiting/RateLimitingExtensions.cs:134-142`

- [ ] **Step 1: Reword**

The method currently reads:

```csharp
    private static string ResolveClientIp(HttpContext context)
    {
        // Prefer the immediate connection IP. Proxies are configured via ForwardedHeaders
        // middleware upstream so RemoteIpAddress reflects the real client — the legacy server's
        // TRUST_CF_HEADER switch exists for exactly this reason and must not be reintroduced as
        // an unconditional header read.
        var ip = context.Connection.RemoteIpAddress?.ToString();
        return string.IsNullOrWhiteSpace(ip) ? "unknown" : ip;
    }
```

The second sentence stated a fact that was not true until now. Replace the comment with:

```csharp
        // The connection IP, and only ever that. Behind a proxy it is the forwarded-headers
        // middleware that rewrites RemoteIpAddress before this runs — see
        // Shared.Api.ForwardedHeaders, which installs it only when the operator has named a
        // trusted proxy. Reading a header here instead would be the legacy server's
        // TRUST_CF_HEADER switch, which let any caller pick its own partition key, and must
        // not be reintroduced.
```

Leave the two lines of code unchanged.

- [ ] **Step 2: Build**

```bash
devcontainer-exec dotnet build backend/Detour.slnx
```
Expected: `Build succeeded.`

- [ ] **Step 3: Commit**

```bash
git add backend/Shared/Api.RateLimiting/RateLimitingExtensions.cs
git commit -m "Correct the client-IP comment now that the middleware exists (#119)"
```

---

### Task 5: Documentation

**Files:**
- Modify: `backend/INSTALL.md` (the **Behind a reverse proxy** section)
- Modify: `docs/BACKEND_SPEC.md` (§15.2)

Both documents already describe the intended behaviour correctly — they stop being wrong when this lands, so most of the text needs no change. Two additions only.

- [ ] **Step 1: `INSTALL.md`**

In the **Behind a reverse proxy** section, after the JSON example block and before the paragraph beginning "Do not reach for the usual container advice", add:

```markdown
Both values are parsed at startup and a malformed address or CIDR fails the boot
with a `FormatException`. That is deliberate: a typo that was quietly ignored
would leave the proxy untrusted and every caller sharing one rate-limit bucket,
on a deployment whose operator believes this is configured.

Only one proxy hop is trusted (`ForwardLimit` is 1). If you run a chain — a CDN
in front of your own reverse proxy — the address you get is the one the nearest
trusted hop reported, which is what you want unless you have deliberately
decided otherwise.
```

- [ ] **Step 2: `docs/BACKEND_SPEC.md` §15.2**

First read the section to match its voice:

```bash
grep -n "15.2" -A 20 docs/BACKEND_SPEC.md
```

It currently says the client address is taken from a proxy header only when the deployment is explicitly configured to trust one — which is now true rather than aspirational. Add one sentence recording the single-hop assumption and naming the configuration section (`ForwardedHeaders:KnownProxies` / `KnownNetworks`). Match the surrounding numbering and prose style; do not restructure the section.

- [ ] **Step 3: Commit**

```bash
git add backend/INSTALL.md docs/BACKEND_SPEC.md
git commit -m "Document the single-hop assumption and strict parsing (#119)"
```

---

### Task 6: Full verification

- [ ] **Step 1: The exact gates CI runs**

```bash
devcontainer-exec dotnet build backend/Detour.slnx --configuration Release
devcontainer-exec dotnet format style backend/Detour.slnx --severity info --verify-no-changes --exclude '**/Migrations/**'
devcontainer-exec dotnet test backend/Detour/Detour.Domain.Tests --logger 'console;verbosity=minimal'
devcontainer-exec dotnet test backend/Detour/Detour.InfraTests --logger 'console;verbosity=minimal'
```

All four must pass. `dotnet format` is a CI gate and will fail the build on whitespace — if it reports changes, apply them and amend.

- [ ] **Step 2: Prove the safe default is really the default**

Confirm by reading, not by assuming, that with no `ForwardedHeaders` configuration the middleware is genuinely absent: `ApiConfiguration.ForwardedHeaders` defaults to `new()`, both arrays default to `[]`, `IsConfigured` is false, `AddTrustedProxies` returns early and `UseTrustedProxies` returns `app` untouched. State this chain explicitly in your report.

- [ ] **Step 3: Confirm the diff**

```bash
git diff origin/main --stat
```

Expected files: the spec, this plan, and the eight listed in the File Structure table. Report anything else.

- [ ] **Step 4: Report**

Paste the actual test counts and the `dotnet format` result. Do not claim success without the output.

---

## Notes for the implementer

- **Do not use `ASPNETCORE_FORWARDEDHEADERS_ENABLED`.** It clears both trust lists, trusting every upstream IP — the exact vulnerability this change exists to avoid.
- **Do not bind the config section directly onto `ForwardedHeadersOptions`.** `IPAddress` and `IPNetwork` do not bind from configuration strings; you would get empty lists and a silent no-op.
- **Do not use `KnownNetworks` on the framework options object.** That is the obsolete pre-.NET-8 property. Use `KnownIPNetworks`. The *settings* class keeps the name `KnownNetworks` because that is the operator-facing key already documented in `docker-compose.yml`.
- **Do not set `ForwardLimit`.** The default of 1 is correct here.
- **Do not add the `Anonymous` rate-limit policy to any endpoint.** This change unblocks that; doing it is a separate issue.
- The two `NU1903` `SSH.NET` warnings are pre-existing. Leave them.
- All `dotnet` commands go through `devcontainer-exec`. File edits and `git` stay on the host.

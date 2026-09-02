# Read X-Forwarded-* when, and only when, a proxy is trusted

Issue: #119. Branch: `fix/forwarded-headers`.

## The defect

The API never reads `X-Forwarded-*`. There is no `UseForwardedHeaders()` call and
nothing binds a `ForwardedHeaders` configuration section. Verified on `8f803d1`:

```
$ grep -rn "UseForwardedHeaders" --include="*.cs" backend/
(no matches)
```

`ResolveClientIp` (`backend/Shared/Api.RateLimiting/RateLimitingExtensions.cs:134-142`)
reads `context.Connection.RemoteIpAddress` and nothing rewrites it. Its comment
asserts the opposite:

> Proxies are configured via ForwardedHeaders middleware upstream so
> `RemoteIpAddress` reflects the real client

Two documents describe the same absent behaviour as though it worked:

- `docker/prod/docker-compose.yml:57-58` sets `ForwardedHeaders__KnownNetworks`
  and `ForwardedHeaders__KnownProxies`. Nothing binds that section, so both are
  inert.
- `backend/INSTALL.md:74` and its **Behind a reverse proxy** section tell
  operators to set one of them, and `docs/BACKEND_SPEC.md` §15.2 states the
  intended rule. Accurate about intent, wrong about the implementation.

### What it costs

`docker/prod/docker-compose.yml` publishes only loopback ports and expects a
proxy in front, so every documented deployment is affected. Every per-IP rate
limit collapses to one bucket keyed on the proxy's address, across the entire
user base: the `Ip` global partition (400 tokens / 150 per 10s) and the
`Anonymous` named policy (20 tokens / 10 per 60s).

The global tier is generous enough that a shared bucket rarely trips, which is
why this went unnoticed. `Anonymous` is not — 10 sign-in probes per minute for
a whole deployment. This issue therefore gates applying
`RateLimitPolicies.Anonymous` to anything; it is registered
(`RateLimitingExtensions.cs:74`) and consumed nowhere, having been taken off
`/api/capabilities` in #118 rather than shipping on top of this.

### Correction to the issue text

The issue says HTTPS redirection is unaffected. It is not: `app.UseHttpsRedirection()`
(`Startup.cs:156`) runs in non-development environments and, without forwarded
headers, sees `http` on requests that arrived at the proxy over `https`.
`INSTALL.md`'s own text already says so. Placing the middleware first in the
pipeline fixes both, and this design forwards `XForwardedProto` for that reason.

## Security grounding

Checked against the OWASP corpus (ASVS 5.0.0, cheat sheets `20260805`).

- **ASVS 5.0.0 V2.4.1 (L2)** — anti-automation controls must actually protect
  against rate-limit breaches and resource exhaustion. A limiter whose partition
  key is constant for every caller does not.
- `Bot_Management_and_Anti-Automation_Cheat_Sheet#rate-limiting-and-quotas`
  describes per-IP limiting as "coarse … but still useful as a floor". Restoring
  the floor is the whole of this change; it does not claim to be more.

The corpus has nothing on proxy-header handling specifically, so the dimensions
below come from the ASP.NET Core documentation rather than from OWASP, and are
labelled as such. **This is the part that would otherwise have been missed** —
the naive wiring is a silent no-op.

Per the `ForwardedHeadersOptions` table in the ASP.NET Core Kestrel
security-considerations documentation:

| Option | Framework default | Consequence here |
|---|---|---|
| `ForwardedHeaders` | `None` | **Must be set explicitly.** Left alone, `UseForwardedHeaders()` is registered, runs, and does nothing — the bug would look fixed and stay open. |
| `KnownProxies` | `IPAddress.IPv6Loopback` (`::1`) | **Not empty.** A config section with empty lists does not produce "trust nobody"; it leaves `::1` trusted. |
| `KnownIPNetworks` | empty | `KnownNetworks` is the obsolete pre-.NET-8 form. This project targets `net10.0` (`backend/Directory.Build.props:4`), so `KnownIPNetworks` is the property to use. |
| `ForwardLimit` | `1` | Correct for one proxy. `null` means unlimited and is documented as dangerous. Left at the default. |

The documentation also warns that the `ASPNETCORE_FORWARDEDHEADERS_ENABLED`
environment variable clears both trust lists — trusting every upstream IP. This
design does not use that switch, and `INSTALL.md` already tells operators not to
reach for the equivalent advice.

A fourth dimension, from the framework rather than a table: `ForwardedHeadersOptions.KnownProxies`
is `IList<IPAddress>` and `KnownIPNetworks` is `IList<IPNetwork>`. Neither binds
from a configuration string, so `services.Configure<ForwardedHeadersOptions>(section)`
silently yields empty lists. The section must bind to a settings class of
`string[]` and be parsed.

## The safe default is the requirement, not a nicety

With both lists unset, forwarded headers must continue to be **ignored
entirely**. Trusting them unconditionally lets any caller reset its own limiter
bucket per request by spoofing a header — strictly worse than the shared bucket
this fixes, because it converts a coarse limit into no limit. That property is
what `RateLimitingExtensions.cs:136`'s comment was protecting; what is missing is
the opt-in, not the default.

Given the `::1` default above, "ignored entirely" cannot be achieved by
configuring the options with empty lists. **When neither list is configured, the
middleware is not added to the pipeline at all.** That is the only formulation
that is safe by construction rather than by a correctly-guessed default.

## Approaches considered

**A — bind the section straight onto `ForwardedHeadersOptions` and always call
`app.UseForwardedHeaders()`.** What the issue's "proposed fix" literally
describes. Rejected on all three framework dimensions above: `ForwardedHeaders`
would stay `None` (no-op), the `::1` default would survive an empty config, and
the `IPAddress` lists would not bind at all.

**B — a settings class, explicit parsing, and the middleware added only when a
proxy is actually configured.** Chosen. Every dimension above becomes an
explicit line of code rather than an inherited default, and the safe case is the
absence of middleware rather than middleware configured to trust nothing.

**C — keep reading `RemoteIpAddress` and add a bespoke `X-Forwarded-For` parser
in `ResolveClientIp`.** Rejected: it is the `TRUST_CF_HEADER` switch the existing
comment explicitly says must not come back, it would fix the limiter while
leaving `UseHttpsRedirection` broken, and it reimplements a framework middleware.

## The change

### 1. `backend/Shared/Api/ForwardedHeaders/ForwardedHeadersSettings.cs` (new)

```csharp
public class ForwardedHeadersSettings
{
    public const string SectionName = "ForwardedHeaders";

    /// <summary>Exact proxy addresses to trust, e.g. "172.18.0.5".</summary>
    public string[] KnownProxies { get; set; } = [];

    /// <summary>Proxy networks in CIDR form, e.g. "172.18.0.0/16".</summary>
    public string[] KnownNetworks { get; set; } = [];

    public bool IsConfigured => KnownProxies.Length > 0 || KnownNetworks.Length > 0;
}
```

The public name stays `KnownNetworks`, matching what `docker-compose.yml` and
`INSTALL.md` already tell operators to set. It maps onto the framework's
`KnownIPNetworks`. Renaming the operator-facing key to match a framework
rename would break the documented deployment for no gain.

### 2. `backend/Shared/Api/ForwardedHeaders/ForwardedHeadersExtensions.cs` (new)

Two members:

- `AddTrustedProxies(this IServiceCollection, ForwardedHeadersSettings)` —
  configures `ForwardedHeadersOptions` when `IsConfigured`, setting
  `ForwardedHeaders = XForwardedFor | XForwardedProto`, clearing the framework's
  loopback defaults from both lists, then adding the parsed entries. Leaves
  `ForwardLimit` at its default of 1.
- `UseTrustedProxies(this IApplicationBuilder, ForwardedHeadersSettings)` —
  calls `app.UseForwardedHeaders()` **only when** `IsConfigured`, and otherwise
  does nothing at all.

Parsing is strict: `IPAddress.Parse` and `IPNetwork.Parse` both throw on a
malformed value, and that exception is allowed to propagate at startup. A
mistyped CIDR must be a boot failure, not a silently untrusted proxy that
degrades to the shared-bucket bug this fixes. This matches `ApiConfiguration`'s
documented stance that "a missing or malformed section is a boot failure rather
than a null reference on the first request that needs it".

### 3. `ApiConfiguration` gains the section

```csharp
public ForwardedHeadersSettings ForwardedHeaders { get; set; } = new();
```

### 4. `Startup.cs`

`ConfigureServices`: `services.AddTrustedProxies(MappedConfiguration.ForwardedHeaders);`

`Configure`: first line of the method, above `app.UseCors()`:

```csharp
// First: everything below reads either the client address or the scheme.
// UseRateLimiter's per-IP partitions need the rewritten address, and
// UseHttpsRedirection needs the forwarded scheme or it answers a redirect to
// http for a request that arrived over https. Does nothing unless a proxy is
// configured — see ForwardedHeadersSettings.
app.UseTrustedProxies(MappedConfiguration.ForwardedHeaders);
```

### 5. Correct the comment that started this

`RateLimitingExtensions.cs:136` currently asserts the middleware exists. Reword
so it states the dependency rather than a false fact, keeping the
`TRUST_CF_HEADER` warning, which is still the right warning.

## Testing

Three cases, exactly the ones the issue asks for, plus the no-op guard.

Note the existing `Detour.InfraTests` API tests all run against a real Postgres
via `DetourApiFactory`/`PostgresFixture`. These need no database and must not
take that dependency: the unit under test is the middleware pipeline's
resolution of the client address, not any endpoint. They build a minimal host
with `Microsoft.AspNetCore.TestHost` containing `UseTrustedProxies` and a
terminal endpoint that echoes `HttpContext.Connection.RemoteIpAddress`, and set
the peer address with a small middleware registered ahead of it.

Asserting on `RemoteIpAddress` is deliberate: that is the value `ResolveClientIp`
returns, so a test on it is a test of the limiter's partition key without
reaching into a private method.

| # | Configuration | Request | Expected |
|---|---|---|---|
| 1 | neither list set | peer `10.0.0.9`, `X-Forwarded-For: 203.0.113.7` | `10.0.0.9` — the spoof is ignored |
| 2 | `KnownProxies: ["10.0.0.9"]` | peer `10.0.0.9`, `X-Forwarded-For: 203.0.113.7` | `203.0.113.7` — honoured |
| 3 | `KnownProxies: ["10.0.0.9"]` | peer `10.0.0.250`, `X-Forwarded-For: 203.0.113.7` | `10.0.0.250` — untrusted hop, ignored |
| 4 | `KnownNetworks: ["10.0.0.0/24"]` | peer `10.0.0.9`, `X-Forwarded-For: 203.0.113.7` | `203.0.113.7` — CIDR form works |
| 5 | neither list set | any | `UseTrustedProxies` adds no middleware |

Case 1 is the regression guard for the safe default and is the one that fails if
someone later "simplifies" the conditional registration away.

## Documentation

`backend/INSTALL.md` and `docker/prod/docker-compose.yml` describe the intended
behaviour accurately and need no correction — they stop being wrong when this
lands. Two additions:

- `INSTALL.md`'s **Behind a reverse proxy** section gains a line stating that a
  malformed address or CIDR fails startup, since that is now observable.
- `docs/BACKEND_SPEC.md` §15.2 gains the `ForwardLimit = 1` single-hop
  assumption, which is a real constraint on a chained-proxy deployment.

## Not in scope

- Applying `RateLimitPolicies.Anonymous` to `/api/capabilities`. This issue
  unblocks it; putting the attribute back is #118's follow-up and deserves its
  own change.
- `Idp:RequireHttpsMetadata`, genuinely unaffected.
- Per-ASN or per-identity limiting, which the Bot Management cheat sheet
  recommends layering on top. Out of scope here.

## Versioning

None. `versionName` in `app/build.gradle.kts` versions the Android app; this
change is entirely under `backend/` and does not touch it.

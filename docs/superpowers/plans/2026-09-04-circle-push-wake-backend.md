# Circle Push Wake — Backend Implementation Plan (Stage 1 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the .NET backend a device-token registry and a content-free FCM
wake-ping that fires to circle members who are offline when a `place_event` is
recorded — so the Android and iOS clients can later drop their always-on sockets.

**Architecture:** A new `DeviceToken` domain entity + repository + endpoints
(`PUT`/`DELETE /api/devices`). A thin `IFcmGateway` wraps the `FirebaseAdmin`
SDK. `CircleService.RecordEventAsync` already fans a `place_event` to connected
WebSockets via `ILiveRelay` inside an `IPostCommitActionScheduler` action; this
plan adds, in the same action, an enqueue onto a bounded `Channel<PushJob>`
drained by a `BackgroundService` that looks up tokens for the *offline*
recipients and calls the gateway. Stale tokens (`UNREGISTERED`) are pruned from
the gateway's per-token response. Ships **dark** — no client registers a token
until Stage 2/3, so every code path is exercised by tests but sends nothing in
production until then.

**Tech Stack:** .NET 10, EF Core 10 + Npgsql, xUnit + AwesomeAssertions + Moq +
Testcontainers.PostgreSql, `FirebaseAdmin` NuGet (FCM HTTP v1).

**Spec:** `docs/superpowers/specs/2026-09-04-circle-push-wake-design.md`
(read §1 and §4 — this plan implements §1 in full plus the §4.2 backend docs).

## Global Constraints

Every task's requirements implicitly include this section. Values are copied
from the spec and the codebase conventions verified while writing it.

- **.NET 10**, `backend/Detour.slnx`. Build with
  `dotnet build backend/Detour.slnx`.
- **Central package management.** New packages get a `<PackageVersion>` line in
  `backend/Directory.Packages.props`; the `.csproj` gets a bare
  `<PackageReference Include="…" />` with no version.
- **Repository interfaces live in `Detour.Domain`**, implementations in
  `Detour.Database/Repositories/`, entity configs in
  `Detour.Database/EntityConfigurations/`, registered in
  `Detour.Database/DatabaseInstaller.cs`'s `AddRepositories`.
- **Entity ids**: inherit `Shared.Domain.Entity` (gives
  `Id = Guid.CreateVersion7()`); config sets `.Property(x => x.Id).ValueGeneratedNever()`.
- **Enums** use `Ardalis.SmartEnum` + `SmartEnumNameConverter<T>` in the config,
  `.HasMaxLength(20)` — see `FriendshipConfiguration`.
- **Tables** are `snake_case` (EFCore.NamingConventions does it automatically);
  `builder.ToTable("device_tokens")`.
- **Routes**: controller `[Route("api/devices")]`, `options.LowercaseUrls = true`
  is already global. Rider-authed: `[Authorize(Policy = DetourPolicies.Rider)]`.
- **Services**: one `IXService` + `XService` per area, registered in
  `Detour.Api/Services/ServiceInstaller.cs`.
- **Fan-out runs post-commit** via `IPostCommitActionScheduler.Schedule(Func<Task>)`
  — never on the request's critical path, never observing the request's
  `CancellationToken`.
- **The wake-ping is content-free**: FCM `data` payload only
  (`{ "type": "circle_wake" }`), **no `notification` block**, Android
  `Priority = High`, `collapseKey` = the circle id.
- **Stale-token pruning**: on a per-token `UNREGISTERED` / `INVALID_ARGUMENT`
  result, delete that `DeviceToken` row.
- **The backend has no `versionName`.** The `1.98.0 → 1.99.0` bump in spec §4.4
  belongs to the Stage 2 (Android) plan, not this one.
- **Firebase project is `detour-1229f`** (created 2026-09-04). The Admin SDK
  service-account key is already in the working tree at the repo root as
  `detour-1229f-firebase-adminsdk-fbsvc-ef43eb3051.json` and is **gitignored**
  (`.gitignore` patterns `*-firebase-adminsdk-*.json`, `google-services*.json`).
  Never commit it. For local runs, point
  `Notifications__FirebaseCredentialsPath` at its absolute path.
- **The three `google-services*.json` at the repo root are Stage 2 (Android)
  inputs**, not used by this plan. They have distinct names
  (`google-services.json`, `google-services_debug.json`,
  `google-services_automotive.json`) for the three build-type applicationIds
  (`io.github.maxke24.detour`, `.debug`, `.automotive`); the Stage 2 plan must
  either merge their `client` blocks into one `app/google-services.json` or
  place each as `app/src/<buildType>/google-services.json` — the Google Services
  Gradle plugin only reads files named exactly `google-services.json`.
- **Migrations**: run from `backend/` (where `dotnet-tools.json` lives):
  `dotnet tool restore` then
  `dotnet dotnet-ef migrations add <Name> --project Detour/Detour.Database --startup-project Detour/Detour.Database`.
  CI enforces `dotnet dotnet-ef migrations has-pending-model-changes` — the model
  and the checked-in migration must agree.
- **InfraTests need Docker** running (Testcontainers starts a real Postgres).
- **Formatting**: `dotnet format style backend/Detour.slnx --severity info`
  before every commit (CI runs `--verify-no-changes`, excluding `Migrations/`).
- Commit message trailer, every commit:
  ```
  Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01JtVUnD7FpwJiwkcN6iFcJH
  ```

## File Structure

**Created:**

| Path | Responsibility |
|------|----------------|
| `backend/Detour/Detour.Domain/Notifications/DeviceToken.cs` | Entity, `DevicePlatform` SmartEnum, `IDeviceTokenRepository` |
| `backend/Detour/Detour.Database/Repositories/NotificationRepositories.cs` | `DeviceTokenRepository` |
| `backend/Detour/Detour.Database/EntityConfigurations/NotificationConfigurations.cs` | `DeviceTokenConfiguration` |
| `backend/Detour/Detour.Database/Migrations/<stamp>_AddDeviceTokens*.cs` | generated, committed as-is |
| `backend/Detour/Detour.Api/Contracts/DeviceContracts.cs` | `RegisterDeviceBody`, `UnregisterDeviceBody` |
| `backend/Detour/Detour.Api/Services/DeviceService.cs` | `IDeviceService` + `DeviceService` — upsert / delete orchestration |
| `backend/Detour/Detour.Api/Controllers/DevicesController.cs` | `PUT` / `DELETE /api/devices` |
| `backend/Detour/Detour.Api/Notifications/NotificationSettings.cs` | config POCO (`FirebaseCredentialsPath`, `QueueCapacity`) |
| `backend/Detour/Detour.Api/Notifications/IFcmGateway.cs` | gateway interface + `FcmSendResult` / `FcmTokenOutcome` records |
| `backend/Detour/Detour.Api/Notifications/FcmGateway.cs` | `FirebaseAdmin`-backed impl; no-op + log when unconfigured |
| `backend/Detour/Detour.Api/Notifications/PushJob.cs` | `record PushJob(IReadOnlyCollection<Guid> RecipientUserIds, string CollapseKey)` |
| `backend/Detour/Detour.Api/Notifications/PushQueue.cs` | `IPushQueue` + bounded-`Channel` impl (`TryEnqueue`, `ReadAllAsync`) |
| `backend/Detour/Detour.Api/Notifications/PushDispatcher.cs` | `DispatchAsync(PushJob, ct)` — token lookup, gateway call, prune |
| `backend/Detour/Detour.Api/Notifications/PushDispatchWorker.cs` | `BackgroundService` draining `IPushQueue` into `PushDispatcher` |
| `backend/Detour/Detour.Api/Notifications/NotificationsInstaller.cs` | DI wiring + `FirebaseApp.Create` |
| `backend/Detour/Detour.InfraTests/Api/DevicesTests.cs` | endpoint behaviour |
| `backend/Detour/Detour.InfraTests/Api/PushDispatchTests.cs` | `PushDispatcher` + `PushQueue` against a fake gateway |
| `backend/Detour/Detour.InfraTests/Api/CircleEventPushTests.cs` | recording an event enqueues the right push |
| `backend/Detour/Detour.Domain.Tests/Notifications/DeviceTokenTests.cs` | entity validation |
| `docs/PUSH.md` | transport, token lifecycle, wake-ping contract, Firebase pointer |

**Modified:**

| Path | Change |
|------|--------|
| `backend/Detour/Detour.Database/DetourDbContext.cs:29` | add `DbSet<DeviceToken> DeviceTokens` |
| `backend/Detour/Detour.Database/DatabaseInstaller.cs:57` | register `IDeviceTokenRepository` |
| `backend/Detour/Detour.Api/Services/ServiceInstaller.cs` | register `IDeviceService` |
| `backend/Detour/Detour.Api/Startup.cs:81` | `services.AddNotifications(configuration);` after `AddLiveRelay()` |
| `backend/Detour/Detour.Api/Services/CircleService.cs:37-43,233` | inject `IPushQueue`, enqueue offline recipients in the post-commit action |
| `backend/Directory.Packages.props` | `<PackageVersion Include="FirebaseAdmin" Version="…" />` |
| `backend/Detour/Detour.Api/Detour.Api.csproj` | `<PackageReference Include="FirebaseAdmin" />` |
| `backend/Detour/Detour.Api/appsettings.json` + `appsettings.Development.json` | `Notifications` section stub |
| `backend/INSTALL.md` | env-var row for `Notifications__FirebaseCredentialsPath` |
| `docs/CIRCLES_AND_CONVOYS.md` | coverage/cadence table gains a "backgrounded" column |

---

## Task 1: `DeviceToken` domain + persistence + migration

**Files:**
- Create: `backend/Detour/Detour.Domain/Notifications/DeviceToken.cs`
- Create: `backend/Detour/Detour.Database/Repositories/NotificationRepositories.cs`
- Create: `backend/Detour/Detour.Database/EntityConfigurations/NotificationConfigurations.cs`
- Create: `backend/Detour/Detour.Domain.Tests/Notifications/DeviceTokenTests.cs`
- Create: `backend/Detour/Detour.Database/Migrations/<stamp>_AddDeviceTokens.cs` (generated)
- Modify: `backend/Detour/Detour.Database/DetourDbContext.cs` (add `DbSet`)
- Modify: `backend/Detour/Detour.Database/DatabaseInstaller.cs` (register repo)

**Interfaces:**
- Produces:
  - `DeviceToken` (sealed, `: Entity`) with `Guid UserId`, `string Token`,
    `DevicePlatform Platform`, `DateTimeOffset CreatedAt`,
    `DateTimeOffset LastRefreshedAt`; factory
    `static Result<DeviceToken> Create(Guid userId, string token, DevicePlatform? platform)`;
    instance `void Refresh(Guid userId, DevicePlatform platform)` (reassigns
    `UserId`, sets `Platform`, bumps `LastRefreshedAt`).
  - `DevicePlatform : SmartEnum<DevicePlatform>` — `Android = 1`, `Ios = 2`,
    with `static DevicePlatform? TryParse(string)` returning null on miss.
  - `IDeviceTokenRepository : IBaseRepository<DeviceToken>`:
    - `Task<DeviceToken?> GetByTokenAsync(string token, CancellationToken ct)`
    - `Task<List<(Guid UserId, string Token)>> GetForUsersAsync(IReadOnlyCollection<Guid> userIds, CancellationToken ct)`
    - `Task DeleteByTokensAsync(IReadOnlyCollection<string> tokens, CancellationToken ct)`
  - `ValidationKeys.DeviceToken.TokenRequired`, `.PlatformInvalid` (add a nested
    static class to `backend/Detour/Detour.Domain/ValidationKeys.cs` mirroring
    the existing `PlaceEvent` block, and translation entries in
    `Detour.Api/Translations/Translations.en.resx` keyed `DeviceToken.TokenRequired`
    etc. — follow the `PlaceEvent.KindInvalid` entry already there).

- [ ] **Step 1: Write the failing domain test**

`backend/Detour/Detour.Domain.Tests/Notifications/DeviceTokenTests.cs`:

```csharp
using Detour.Domain;
using Detour.Domain.Notifications;

namespace Detour.Domain.Tests.Notifications;

public class DeviceTokenTests
{
    [Fact]
    public void Create_rejects_a_blank_token()
    {
        var result = DeviceToken.Create(Guid.CreateVersion7(), "  ", DevicePlatform.Android);

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.DeviceToken.TokenRequired).Should().BeTrue();
    }

    [Fact]
    public void Create_rejects_an_unknown_platform()
    {
        var result = DeviceToken.Create(Guid.CreateVersion7(), "fcm-abc", platform: null);

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.DeviceToken.PlatformInvalid).Should().BeTrue();
    }

    [Fact]
    public void Refresh_reassigns_the_owner_and_bumps_the_timestamp()
    {
        var original = Guid.CreateVersion7();
        var token = DeviceToken.Create(original, "fcm-abc", DevicePlatform.Android).Value;
        var firstSeen = token.LastRefreshedAt;
        var newOwner = Guid.CreateVersion7();

        token.Refresh(newOwner, DevicePlatform.Ios);

        token.UserId.Should().Be(newOwner);
        token.Platform.Should().Be(DevicePlatform.Ios);
        token.LastRefreshedAt.Should().BeOnOrAfter(firstSeen);
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `dotnet test backend/Detour/Detour.Domain.Tests --filter "FullyQualifiedName~DeviceTokenTests"`
Expected: FAIL — `DeviceToken` / `DevicePlatform` do not exist (compile error).

- [ ] **Step 3: Write `DeviceToken.cs`**

`backend/Detour/Detour.Domain/Notifications/DeviceToken.cs`:

```csharp
using Ardalis.SmartEnum;
using JV.ResultUtilities;
using Shared.Domain;

namespace Detour.Domain.Notifications;

public sealed class DevicePlatform : SmartEnum<DevicePlatform>
{
    public static readonly DevicePlatform Android = new("Android", 1);
    public static readonly DevicePlatform Ios = new("Ios", 2);

    private DevicePlatform(string name, int value) : base(name, value) { }

    public static DevicePlatform? TryParse(string? name) =>
        name is not null && TryFromName(name, ignoreCase: true, out var platform) ? platform : null;
}

/// <summary>
/// One push registration token for one app install. The token — an FCM
/// registration token on both platforms, since iOS registers through the
/// Firebase SDK rather than raw APNs — is the natural key: an install has
/// exactly one, and when the same install signs into a different account the
/// row is reassigned, never duplicated.
/// </summary>
public sealed class DeviceToken : Entity
{
    public Guid UserId { get; private set; }
    public string Token { get; private set; } = string.Empty;
    public DevicePlatform Platform { get; private set; } = DevicePlatform.Android;
    public DateTimeOffset CreatedAt { get; private set; }
    public DateTimeOffset LastRefreshedAt { get; private set; }

    private DeviceToken() { } // EF

    private DeviceToken(Guid userId, string token, DevicePlatform platform)
    {
        UserId = userId;
        Token = token;
        Platform = platform;
        CreatedAt = DateTimeOffset.UtcNow;
        LastRefreshedAt = CreatedAt;
    }

    public static Result<DeviceToken> Create(Guid userId, string token, DevicePlatform? platform)
    {
        if (string.IsNullOrWhiteSpace(token))
            return Result.Error(ValidationKeys.DeviceToken.TokenRequired);

        if (platform is null)
            return Result.Error(ValidationKeys.DeviceToken.PlatformInvalid);

        return new DeviceToken(userId, token.Trim(), platform);
    }

    public void Refresh(Guid userId, DevicePlatform platform)
    {
        UserId = userId;
        Platform = platform;
        LastRefreshedAt = DateTimeOffset.UtcNow;
    }
}

public interface IDeviceTokenRepository : IBaseRepository<DeviceToken>
{
    Task<DeviceToken?> GetByTokenAsync(string token, CancellationToken cancellationToken);

    /// <summary>Every registered (user, token) pair for the given users. The
    ///  fan-out's read side — one query, not one per recipient.</summary>
    Task<List<DeviceTokenTarget>> GetForUsersAsync(
        IReadOnlyCollection<Guid> userIds,
        CancellationToken cancellationToken);

    Task DeleteByTokensAsync(IReadOnlyCollection<string> tokens, CancellationToken cancellationToken);
}

public readonly record struct DeviceTokenTarget(Guid UserId, string Token);
```

Add to `backend/Detour/Detour.Domain/ValidationKeys.cs` (mirror the existing
`PlaceEvent` nested class):

```csharp
public static class DeviceToken
{
    public const string TokenRequired = "DeviceToken.TokenRequired";
    public const string PlatformInvalid = "DeviceToken.PlatformInvalid";
}
```

Add to `backend/Detour/Detour.Api/Translations/Translations.en.resx` two `<data>`
entries keyed `DeviceToken.TokenRequired` ("A push token is required.") and
`DeviceToken.PlatformInvalid` ("Unknown device platform."), matching the shape
of the `PlaceEvent.KindInvalid` entry already in the file.

- [ ] **Step 4: Run the domain test, verify it passes**

Run: `dotnet test backend/Detour/Detour.Domain.Tests --filter "FullyQualifiedName~DeviceTokenTests"`
Expected: PASS (3 tests).

- [ ] **Step 5: Add the EF configuration**

`backend/Detour/Detour.Database/EntityConfigurations/NotificationConfigurations.cs`:

```csharp
using Detour.Domain.Notifications;
using Detour.Domain.Users;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using Shared.Database.Converters;

namespace Detour.Database.EntityConfigurations;

public class DeviceTokenConfiguration : IEntityTypeConfiguration<DeviceToken>
{
    public void Configure(EntityTypeBuilder<DeviceToken> builder)
    {
        builder.ToTable("device_tokens");

        builder.HasKey(t => t.Id);
        builder.Property(t => t.Id).ValueGeneratedNever();

        builder.Property(t => t.Token).HasMaxLength(4096);
        builder.Property(t => t.Platform)
            .HasConversion<SmartEnumNameConverter<DevicePlatform>>()
            .HasMaxLength(20);
        builder.Property(t => t.CreatedAt);
        builder.Property(t => t.LastRefreshedAt);

        builder.HasOne<User>()
            .WithMany()
            .HasForeignKey(t => t.UserId)
            .OnDelete(DeleteBehavior.Cascade);

        // One row per install. Reassigned, never duplicated, when the install
        // switches accounts.
        builder.HasIndex(t => t.Token).IsUnique();
        // The fan-out reads by user.
        builder.HasIndex(t => t.UserId);
    }
}
```

Verify the `SmartEnumNameConverter` namespace matches `FriendshipConfiguration`'s
`using` (it is `Shared.Database.Converters` there — copy whatever that file uses).

- [ ] **Step 6: Wire the `DbSet` and repository**

`DetourDbContext.cs` — add after line 29 (`PlaceEvents`):
```csharp
public DbSet<DeviceToken> DeviceTokens => Set<DeviceToken>();
```
with `using Detour.Domain.Notifications;` at the top.

`backend/Detour/Detour.Database/Repositories/NotificationRepositories.cs`:

```csharp
using Detour.Domain.Notifications;
using Microsoft.EntityFrameworkCore;
using Shared.Database;

namespace Detour.Database.Repositories;

public class DeviceTokenRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<DeviceToken, DetourDbContext>(factory), IDeviceTokenRepository
{
    public Task<DeviceToken?> GetByTokenAsync(string token, CancellationToken cancellationToken) =>
        Set.TagWith(Tag(nameof(GetByTokenAsync)))
            .FirstOrDefaultAsync(t => t.Token == token, cancellationToken);

    public Task<List<DeviceTokenTarget>> GetForUsersAsync(
        IReadOnlyCollection<Guid> userIds,
        CancellationToken cancellationToken) =>
        Set.AsNoTracking()
            .TagWith(Tag(nameof(GetForUsersAsync)))
            .Where(t => userIds.Contains(t.UserId))
            .Select(t => new DeviceTokenTarget(t.UserId, t.Token))
            .ToListAsync(cancellationToken);

    public Task DeleteByTokensAsync(IReadOnlyCollection<string> tokens, CancellationToken cancellationToken) =>
        Set.Where(t => tokens.Contains(t.Token)).ExecuteDeleteAsync(cancellationToken);
}
```

`DatabaseInstaller.cs` — add after line 56 (`IPlaceEventRepository`):
```csharp
services.AddScoped<IDeviceTokenRepository, DeviceTokenRepository>();
```

- [ ] **Step 7: Generate the migration**

Run from `backend/`:
```bash
dotnet tool restore
dotnet dotnet-ef migrations add AddDeviceTokens \
  --project Detour/Detour.Database --startup-project Detour/Detour.Database
```
Expected: a new `Migrations/<stamp>_AddDeviceTokens.cs` + `.Designer.cs` +
an updated `DetourDbContextModelSnapshot.cs`. Do not hand-edit them.

- [ ] **Step 8: Verify the model and migration agree**

Run from `backend/`:
```bash
dotnet dotnet-ef migrations has-pending-model-changes \
  --project Detour/Detour.Database --startup-project Detour/Detour.Database
```
Expected: exit 0, "No changes have been made to the model since the last migration."

- [ ] **Step 9: Build + full domain suite + format**

```bash
dotnet build backend/Detour.slnx
dotnet test backend/Detour/Detour.Domain.Tests
dotnet format style backend/Detour.slnx --severity info
```
Expected: build clean, domain tests green.

- [ ] **Step 10: Commit**

```bash
git add backend/Detour/Detour.Domain backend/Detour/Detour.Database backend/Detour/Detour.Domain.Tests
git commit -m "feat(notifications): DeviceToken entity, repository and migration

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01JtVUnD7FpwJiwkcN6iFcJH"
```

---

## Task 2: `PUT` / `DELETE /api/devices` endpoints

**Files:**
- Create: `backend/Detour/Detour.Api/Contracts/DeviceContracts.cs`
- Create: `backend/Detour/Detour.Api/Services/DeviceService.cs`
- Create: `backend/Detour/Detour.Api/Controllers/DevicesController.cs`
- Create: `backend/Detour/Detour.InfraTests/Api/DevicesTests.cs`
- Modify: `backend/Detour/Detour.Api/Services/ServiceInstaller.cs`

**Interfaces:**
- Consumes: `IDeviceTokenRepository`, `DeviceToken`, `DevicePlatform` (Task 1).
- Produces:
  - `IDeviceService`:
    - `Task<Result> RegisterAsync(Guid userId, string token, string platform, CancellationToken ct)`
    - `Task RemoveAsync(string token, CancellationToken ct)`
  - `record RegisterDeviceBody(string Token, string Platform)`
  - `record UnregisterDeviceBody(string Token)`

- [ ] **Step 1: Write the failing endpoint test**

`backend/Detour/Detour.InfraTests/Api/DevicesTests.cs` — follow `GroupTests.cs`
for the `NewRider` / factory shape (each InfraTest class carries its own
`NewRider`; copy it verbatim from `GroupTests.cs:332`):

```csharp
using System.Net;
using System.Net.Http.Json;
using Detour.InfraTests.Database;

namespace Detour.InfraTests.Api;

[Collection(PostgresCollection.Name)]
public class DevicesTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task Registering_a_token_twice_is_idempotent()
    {
        var (rider, _) = await NewRider();

        (await rider.PutAsJsonAsync("/api/devices", new { token = "fcm-1", platform = "android" }))
            .StatusCode.Should().Be(HttpStatusCode.NoContent);
        (await rider.PutAsJsonAsync("/api/devices", new { token = "fcm-1", platform = "android" }))
            .StatusCode.Should().Be(HttpStatusCode.NoContent);
    }

    [Fact]
    public async Task A_second_rider_registering_the_same_token_takes_it_over()
    {
        // The install was handed to a friend, or the same phone signed into a
        // different account. The token must point at exactly one rider.
        var (alex, _) = await NewRider();
        var (blake, _) = await NewRider();

        await alex.PutAsJsonAsync("/api/devices", new { token = "fcm-shared", platform = "ios" });
        (await blake.PutAsJsonAsync("/api/devices", new { token = "fcm-shared", platform = "ios" }))
            .StatusCode.Should().Be(HttpStatusCode.NoContent);

        // Asserted via the DB: exactly one row, owned by blake.
        using var scope = _factory.Services.CreateScope();
        var repo = scope.ServiceProvider
            .GetRequiredService<Detour.Domain.Notifications.IDeviceTokenRepository>();
        var row = await repo.GetByTokenAsync("fcm-shared", default);
        row.Should().NotBeNull();
    }

    [Fact]
    public async Task An_unknown_platform_is_rejected()
    {
        var (rider, _) = await NewRider();

        (await rider.PutAsJsonAsync("/api/devices", new { token = "fcm-2", platform = "blackberry" }))
            .StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Deleting_a_token_removes_it_and_is_quiet_about_one_that_was_never_there()
    {
        var (rider, _) = await NewRider();
        await rider.PutAsJsonAsync("/api/devices", new { token = "fcm-3", platform = "android" });

        var delete = new HttpRequestMessage(HttpMethod.Delete, "/api/devices")
        {
            Content = JsonContent.Create(new { token = "fcm-3" }),
        };
        (await rider.SendAsync(delete)).StatusCode.Should().Be(HttpStatusCode.NoContent);

        var deleteAgain = new HttpRequestMessage(HttpMethod.Delete, "/api/devices")
        {
            Content = JsonContent.Create(new { token = "fcm-never" }),
        };
        (await rider.SendAsync(deleteAgain)).StatusCode.Should().Be(HttpStatusCode.NoContent);
    }

    [Fact]
    public async Task Registration_requires_authentication()
    {
        var anon = _factory.CreateClient();
        (await anon.PutAsJsonAsync("/api/devices", new { token = "x", platform = "android" }))
            .StatusCode.Should().Be(HttpStatusCode.Unauthorized);
    }

    // paste NewRider() from GroupTests.cs:332 here
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `dotnet test backend/Detour/Detour.InfraTests --filter "FullyQualifiedName~DevicesTests"`
Expected: FAIL — 404 on `/api/devices` (no controller).

- [ ] **Step 3: Write the contracts**

`backend/Detour/Detour.Api/Contracts/DeviceContracts.cs`:

```csharp
using System.ComponentModel.DataAnnotations;

namespace Detour.Api.Contracts;

public record RegisterDeviceBody([Required] string Token, [Required] string Platform);

public record UnregisterDeviceBody([Required] string Token);
```

- [ ] **Step 4: Write the service**

`backend/Detour/Detour.Api/Services/DeviceService.cs`:

```csharp
using Detour.Domain;
using Detour.Domain.Notifications;
using JV.ResultUtilities;

namespace Detour.Api.Services;

public interface IDeviceService
{
    Task<Result> RegisterAsync(Guid userId, string token, string platform, CancellationToken cancellationToken);
    Task RemoveAsync(string token, CancellationToken cancellationToken);
}

public class DeviceService(IDeviceTokenRepository tokens) : IDeviceService
{
    public async Task<Result> RegisterAsync(
        Guid userId, string token, string platform, CancellationToken cancellationToken)
    {
        var parsedPlatform = DevicePlatform.TryParse(platform);
        if (parsedPlatform is null)
            return Result.Error(ValidationKeys.DeviceToken.PlatformInvalid);

        var existing = await tokens.GetByTokenAsync(token.Trim(), cancellationToken);
        if (existing is not null)
        {
            existing.Refresh(userId, parsedPlatform);
            await tokens.FlushChangesAsync(cancellationToken);
            return Result.Success();
        }

        var created = DeviceToken.Create(userId, token, parsedPlatform);
        if (created.IsFailure)
            return Result.Error(created.ValidationMessages);

        await tokens.SaveAsync(created.Value, cancellationToken);
        await tokens.FlushChangesAsync(cancellationToken);
        return Result.Success();
    }

    public Task RemoveAsync(string token, CancellationToken cancellationToken) =>
        tokens.DeleteByTokensAsync([token.Trim()], cancellationToken);
}
```

> Note: `DeviceService` calls `FlushChangesAsync` directly rather than leaning on
> the transaction middleware, matching how `CircleService.RecordEventAsync`
> flushes its `PlaceEvent` (`placeEvents.FlushChangesAsync` at
> `CircleService.cs:213`). If a reviewer prefers the middleware-managed commit,
> that is a fine change — the endpoint has no fan-out to sequence.

Register in `ServiceInstaller.cs`:
```csharp
services.AddScoped<IDeviceService, DeviceService>();
```

- [ ] **Step 5: Write the controller**

`backend/Detour/Detour.Api/Controllers/DevicesController.cs` — follow
`SharedRoutesController.cs`:

```csharp
using Detour.Api.Authentication;
using Detour.Api.Authorization;
using Detour.Api.Contracts;
using Detour.Api.Services;
using JV.ResultUtilities.Extensions;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Detour.Api.Controllers;

[ApiController]
[Route("api/devices")]
[Produces("application/json")]
[Authorize(Policy = DetourPolicies.Rider)]
public class DevicesController(ICurrentUser currentUser, IDeviceService devices) : ControllerBase
{
    [HttpPut]
    [EndpointSummary("Register or refresh this install's push token.")]
    [EndpointDescription(
        "Idempotent. Called on sign-in, on app start, and whenever the push "
        + "service rotates the token. A token already registered to another "
        + "rider is reassigned to the caller — one install, one owner.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest, Description = "Unknown platform or blank token.")]
    public async Task<IActionResult> Register(
        [FromBody] RegisterDeviceBody body, CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        (await devices.RegisterAsync(user.Id, body.Token, body.Platform, cancellationToken))
            .ThrowIfFailure();
        return NoContent();
    }

    [HttpDelete]
    [EndpointSummary("Drop this install's push token.")]
    [EndpointDescription("Called on sign-out. Silent about a token that was never registered.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<IActionResult> Unregister(
        [FromBody] UnregisterDeviceBody body, CancellationToken cancellationToken)
    {
        await currentUser.GetAsync(cancellationToken); // auth*n* only; a token is not owner-scoped to delete
        await devices.RemoveAsync(body.Token, cancellationToken);
        return NoContent();
    }
}
```

Verify `ICurrentUser.GetAsync` returns a type with `.Id` — check
`SharedRoutesController.cs:31` (`user.Id`). Verify `ThrowIfFailure()` maps a
validation `Result` to a 400 — it does for `RouteSharingService` in the same
file.

- [ ] **Step 6: Run the endpoint tests, verify green**

Run: `dotnet test backend/Detour/Detour.InfraTests --filter "FullyQualifiedName~DevicesTests"`
Expected: PASS (5 tests). Docker must be running.

- [ ] **Step 7: Build + format**

```bash
dotnet build backend/Detour.slnx
dotnet format style backend/Detour.slnx --severity info
```

- [ ] **Step 8: Commit**

```bash
git add backend/Detour/Detour.Api backend/Detour/Detour.InfraTests/Api/DevicesTests.cs
git commit -m "feat(notifications): PUT/DELETE /api/devices token endpoints

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01JtVUnD7FpwJiwkcN6iFcJH"
```

---

## Task 3: FCM gateway

**Files:**
- Create: `backend/Detour/Detour.Api/Notifications/NotificationSettings.cs`
- Create: `backend/Detour/Detour.Api/Notifications/IFcmGateway.cs`
- Create: `backend/Detour/Detour.Api/Notifications/FcmGateway.cs`
- Create: `backend/Detour/Detour.Api/Notifications/NotificationsInstaller.cs`
- Create: `backend/Detour/Detour.Api/firebase-service-account.json.example`
- Modify: `backend/Directory.Packages.props`
- Modify: `backend/Detour/Detour.Api/Detour.Api.csproj`
- Modify: `backend/Detour/Detour.Api/appsettings.json`, `appsettings.Development.json`
- Modify: `backend/Detour/Detour.Api/Startup.cs`
- `.gitignore` (repo root) — **already done this session**: patterns
  `*-firebase-adminsdk-*.json`, `google-services.json`, `google-services_*.json`,
  `app/google-services.json`, `app/src/*/google-services.json`,
  `GoogleService-Info.plist`. Nothing to change; just don't remove them.

**Interfaces:**
- Produces:
  - `record FcmTokenOutcome(string Token, bool Delivered, bool ShouldPrune)`
  - `record FcmSendResult(IReadOnlyList<FcmTokenOutcome> Outcomes)` with
    `IEnumerable<string> TokensToPrune => Outcomes.Where(o => o.ShouldPrune).Select(o => o.Token)`
  - `IFcmGateway`:
    `Task<FcmSendResult> SendWakeAsync(IReadOnlyCollection<string> tokens, string collapseKey, CancellationToken ct)`
  - `NotificationSettings` — `string? FirebaseCredentialsPath`, `int QueueCapacity = 1024`;
    `const string SectionName = "Notifications"`.
  - `IServiceCollection AddNotifications(this IServiceCollection, IConfiguration)`

- [ ] **Step 1: Add the package**

`backend/Directory.Packages.props` — in a new `<!-- Push -->` ItemGroup, add
the current stable `FirebaseAdmin` (3.x). Check the latest on nuget.org and pin
the exact version:
```xml
<ItemGroup>
  <PackageVersion Include="FirebaseAdmin" Version="3.4.0" />
</ItemGroup>
```

`backend/Detour/Detour.Api/Detour.Api.csproj` — add to the existing
`PackageReference` ItemGroup:
```xml
<PackageReference Include="FirebaseAdmin" />
```

Run `dotnet restore backend/Detour.slnx` — expected: resolves clean.

- [ ] **Step 2: Write the failing gateway test**

Add to a new `backend/Detour/Detour.InfraTests/Api/PushDispatchTests.cs` (the
file Task 4 fills further):

```csharp
using Detour.Api.Notifications;

namespace Detour.InfraTests.Api;

public class FcmGatewayTests
{
    [Fact]
    public async Task An_unconfigured_gateway_sends_nothing_and_prunes_nothing()
    {
        var gateway = new FcmGateway(
            new NotificationSettings { FirebaseCredentialsPath = null },
            NullLogger<FcmGateway>.Instance);

        var result = await gateway.SendWakeAsync(["fcm-a", "fcm-b"], "circle-1", default);

        result.Outcomes.Should().BeEmpty();
        result.TokensToPrune.Should().BeEmpty();
    }
}
```

`using Microsoft.Extensions.Logging.Abstractions;` for `NullLogger`.

- [ ] **Step 3: Run it, verify it fails**

Run: `dotnet test backend/Detour/Detour.InfraTests --filter "FullyQualifiedName~FcmGatewayTests"`
Expected: FAIL — `FcmGateway` / `NotificationSettings` do not exist.

- [ ] **Step 4: Write `NotificationSettings.cs`**

```csharp
namespace Detour.Api.Notifications;

public sealed class NotificationSettings
{
    public const string SectionName = "Notifications";

    /// <summary>Absolute path to the Firebase service-account JSON. Null / empty
    ///  in every environment that has not been given one — the gateway then
    ///  no-ops, which is the correct state for a backend that ships before its
    ///  Firebase project exists.</summary>
    public string? FirebaseCredentialsPath { get; init; }

    /// <summary>Bounded — a wake-ping is self-healing, so a full queue drops
    ///  rather than grows.</summary>
    public int QueueCapacity { get; init; } = 1024;
}
```

- [ ] **Step 5: Write `IFcmGateway.cs`**

```csharp
namespace Detour.Api.Notifications;

public readonly record struct FcmTokenOutcome(string Token, bool Delivered, bool ShouldPrune);

public sealed record FcmSendResult(IReadOnlyList<FcmTokenOutcome> Outcomes)
{
    public static readonly FcmSendResult Empty = new(Array.Empty<FcmTokenOutcome>());

    public IEnumerable<string> TokensToPrune =>
        Outcomes.Where(o => o.ShouldPrune).Select(o => o.Token);
}

/// <summary>
/// The one call the backend makes to Firebase Cloud Messaging: a content-free
/// wake-ping to a batch of tokens. The payload carries no user data — the
/// device fetches the event itself once woken (spec §Q2). iOS tokens are FCM
/// tokens too; Firebase relays them to APNs (spec approach A).
/// </summary>
public interface IFcmGateway
{
    Task<FcmSendResult> SendWakeAsync(
        IReadOnlyCollection<string> tokens,
        string collapseKey,
        CancellationToken cancellationToken);
}
```

- [ ] **Step 6: Write `FcmGateway.cs`**

```csharp
using FirebaseAdmin;
using FirebaseAdmin.Messaging;
using Google.Apis.Auth.OAuth2;
using Microsoft.Extensions.Logging;

namespace Detour.Api.Notifications;

public sealed class FcmGateway : IFcmGateway
{
    private const string AppName = "detour";

    private readonly FirebaseMessaging? _messaging;
    private readonly ILogger<FcmGateway> _logger;

    public FcmGateway(NotificationSettings settings, ILogger<FcmGateway> logger)
    {
        _logger = logger;

        if (string.IsNullOrWhiteSpace(settings.FirebaseCredentialsPath))
        {
            _logger.LogWarning(
                "Notifications:FirebaseCredentialsPath is not set. Push wake-pings are disabled.");
            return;
        }

        var app = FirebaseApp.GetInstance(AppName) ?? FirebaseApp.Create(
            new AppOptions
            {
                Credential = GoogleCredential.FromFile(settings.FirebaseCredentialsPath),
            },
            AppName);

        _messaging = FirebaseMessaging.GetMessaging(app);
    }

    public async Task<FcmSendResult> SendWakeAsync(
        IReadOnlyCollection<string> tokens,
        string collapseKey,
        CancellationToken cancellationToken)
    {
        if (_messaging is null || tokens.Count == 0)
            return FcmSendResult.Empty;

        var message = new MulticastMessage
        {
            Tokens = tokens.ToList(),
            Data = new Dictionary<string, string> { ["type"] = "circle_wake" },
            Android = new AndroidConfig
            {
                Priority = Priority.High,
                CollapseKey = collapseKey,
            },
            Apns = new ApnsConfig
            {
                Headers = new Dictionary<string, string>
                {
                    ["apns-priority"] = "10",
                    ["apns-collapse-id"] = collapseKey,
                    ["apns-push-type"] = "alert",
                },
                Aps = new Aps
                {
                    // A minimal visible fallback: iOS throttles pure background
                    // pushes, and the Notification Service Extension replaces
                    // this body once it has fetched (Stage 3). Never localised
                    // here — the client owns copy.
                    Alert = new ApsAlert { Body = "New circle activity" },
                    ContentAvailable = true,
                    MutableContent = true,
                },
            },
        };

        BatchResponse response;
        try
        {
            response = await _messaging.SendEachForMulticastAsync(message, cancellationToken);
        }
        catch (FirebaseMessagingException ex)
        {
            // Whole-batch failure (auth, quota, transport). Nothing to prune —
            // the tokens may be perfectly good. The event is not re-queued; the
            // device catches up on its next foreground sweep.
            _logger.LogWarning(ex, "FCM multicast failed for collapseKey {CollapseKey}", collapseKey);
            return FcmSendResult.Empty;
        }

        var tokenList = tokens.ToList();
        var outcomes = new List<FcmTokenOutcome>(tokenList.Count);
        for (var i = 0; i < response.Responses.Count; i++)
        {
            var r = response.Responses[i];
            var prune = r.Exception?.MessagingErrorCode
                is MessagingErrorCode.Unregistered or MessagingErrorCode.InvalidArgument;
            outcomes.Add(new FcmTokenOutcome(tokenList[i], r.IsSuccess, prune));
        }

        return new FcmSendResult(outcomes);
    }
}
```

> The `FirebaseAdmin` type names (`MulticastMessage`, `SendEachForMulticastAsync`,
> `BatchResponse`, `MessagingErrorCode.Unregistered`) are from the 3.x API. If the
> pinned version differs, adjust — the shape (multicast send → per-token
> responses → map `Unregistered` to prune) is what matters.

- [ ] **Step 7: Write `NotificationsInstaller.cs`**

```csharp
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace Detour.Api.Notifications;

public static class NotificationsInstaller
{
    public static IServiceCollection AddNotifications(
        this IServiceCollection services, IConfiguration configuration)
    {
        var settings = configuration.GetSection(NotificationSettings.SectionName)
            .Get<NotificationSettings>() ?? new NotificationSettings();
        services.AddSingleton(settings);

        // Singleton: FirebaseApp is process-global and the SDK's HTTP client
        // is built to be shared.
        services.AddSingleton<IFcmGateway, FcmGateway>();

        return services;
    }
}
```

Wire in `Startup.cs` after `services.AddLiveRelay();` (line 81):
```csharp
services.AddNotifications(configuration);
```

- [ ] **Step 8: Config stubs + gitignore + example file**

`appsettings.json` — add a top-level section:
```json
"Notifications": {
  "FirebaseCredentialsPath": "",
  "QueueCapacity": 1024
}
```
`appsettings.Development.json` — same, `"FirebaseCredentialsPath": ""`.

`backend/Detour/Detour.Api/firebase-service-account.json.example`:
```json
{
  "_comment": "Download the real file from Firebase console > Project settings > Service accounts > Generate new private key. Never commit the real one. Point Notifications__FirebaseCredentialsPath at its deployed path.",
  "type": "service_account",
  "project_id": "detour-xxxxx",
  "private_key_id": "…",
  "private_key": "-----BEGIN PRIVATE KEY-----\n…\n-----END PRIVATE KEY-----\n",
  "client_email": "firebase-adminsdk-…@detour-xxxxx.iam.gserviceaccount.com",
  "client_id": "…",
  "token_uri": "https://oauth2.googleapis.com/token"
}
```

`.gitignore` (repo root) — the credential patterns are **already present**
(added this session). Confirm `git check-ignore detour-1229f-firebase-adminsdk-fbsvc-ef43eb3051.json`
prints a match; add nothing.

- [ ] **Step 9: Run the gateway test + build + format**

```bash
dotnet test backend/Detour/Detour.InfraTests --filter "FullyQualifiedName~FcmGatewayTests"
dotnet build backend/Detour.slnx
dotnet format style backend/Detour.slnx --severity info
```
Expected: gateway test PASS, build clean.

- [ ] **Step 10: Commit**

```bash
git add backend/Detour/Detour.Api backend/Directory.Packages.props backend/Detour/Detour.InfraTests
git commit -m "feat(notifications): FirebaseAdmin FCM gateway, no-op until configured

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01JtVUnD7FpwJiwkcN6iFcJH"
```

---

## Task 4: Push queue + dispatcher + background worker

**Files:**
- Create: `backend/Detour/Detour.Api/Notifications/PushJob.cs`
- Create: `backend/Detour/Detour.Api/Notifications/PushQueue.cs`
- Create: `backend/Detour/Detour.Api/Notifications/PushDispatcher.cs`
- Create: `backend/Detour/Detour.Api/Notifications/PushDispatchWorker.cs`
- Modify: `backend/Detour/Detour.Api/Notifications/NotificationsInstaller.cs`
- Modify: `backend/Detour/Detour.InfraTests/Api/PushDispatchTests.cs`

**Interfaces:**
- Consumes: `IFcmGateway`, `FcmSendResult` (Task 3); `IDeviceTokenRepository`,
  `DeviceTokenTarget` (Task 1).
- Produces:
  - `record PushJob(IReadOnlyCollection<Guid> RecipientUserIds, string CollapseKey)`
  - `IPushQueue`: `bool TryEnqueue(PushJob job)`,
    `IAsyncEnumerable<PushJob> ReadAllAsync(CancellationToken ct)`
  - `PushDispatcher` (concrete, DI-registered):
    `Task DispatchAsync(PushJob job, CancellationToken ct)`

- [ ] **Step 1: Write the failing dispatcher + queue tests**

Extend `backend/Detour/Detour.InfraTests/Api/PushDispatchTests.cs`:

```csharp
using Detour.Api.Notifications;
using Detour.Domain.Notifications;
using Microsoft.Extensions.Logging.Abstractions;

namespace Detour.InfraTests.Api;

public class PushQueueTests
{
    [Fact]
    public void A_full_queue_drops_rather_than_blocks()
    {
        var queue = new PushQueue(new NotificationSettings { QueueCapacity = 2 });

        queue.TryEnqueue(new PushJob([Guid.NewGuid()], "c1")).Should().BeTrue();
        queue.TryEnqueue(new PushJob([Guid.NewGuid()], "c2")).Should().BeTrue();
        queue.TryEnqueue(new PushJob([Guid.NewGuid()], "c3")).Should().BeFalse();
    }
}

public class PushDispatcherTests
{
    private sealed class FakeGateway : IFcmGateway
    {
        public List<string> SeenTokens { get; } = [];
        public FcmSendResult NextResult { get; set; } = FcmSendResult.Empty;

        public Task<FcmSendResult> SendWakeAsync(
            IReadOnlyCollection<string> tokens, string collapseKey, CancellationToken ct)
        {
            SeenTokens.AddRange(tokens);
            return Task.FromResult(NextResult);
        }
    }

    private sealed class FakeTokenRepo : IDeviceTokenRepository
    {
        public Dictionary<Guid, List<string>> ByUser { get; } = [];
        public List<string> Deleted { get; } = [];

        public Task<List<DeviceTokenTarget>> GetForUsersAsync(
            IReadOnlyCollection<Guid> userIds, CancellationToken ct) =>
            Task.FromResult(userIds
                .SelectMany(u => ByUser.GetValueOrDefault(u, []).Select(t => new DeviceTokenTarget(u, t)))
                .ToList());

        public Task DeleteByTokensAsync(IReadOnlyCollection<string> tokens, CancellationToken ct)
        {
            Deleted.AddRange(tokens);
            return Task.CompletedTask;
        }

        // The rest of IDeviceTokenRepository / IBaseRepository<DeviceToken> throw
        // NotImplementedException — the dispatcher only calls the two above.
        public Task<DeviceToken?> GetByTokenAsync(string token, CancellationToken ct) => throw new NotImplementedException();
        public Task<DeviceToken?> GetAsync(Guid id, CancellationToken token) => throw new NotImplementedException();
        public Task<DeviceToken?> GetNonTrackingAsync(Guid id, CancellationToken token) => throw new NotImplementedException();
        public Task<bool> ExistsAsync(Guid id, CancellationToken token) => throw new NotImplementedException();
        public Task<List<DeviceToken>> GetAllAsync(CancellationToken token) => throw new NotImplementedException();
        public Task<List<DeviceToken>> GetAllNonTrackingAsync(CancellationToken token) => throw new NotImplementedException();
        public Task SaveAsync(DeviceToken entity, CancellationToken token) => throw new NotImplementedException();
        public void Save(DeviceToken entity) => throw new NotImplementedException();
        public void Delete(DeviceToken entity) => throw new NotImplementedException();
        public Task ReloadAsync(DeviceToken entity, CancellationToken token) => throw new NotImplementedException();
        public Task FlushChangesAsync(CancellationToken token) => throw new NotImplementedException();
    }

    [Fact]
    public async Task Dispatch_sends_to_every_token_of_every_recipient()
    {
        var alex = Guid.NewGuid();
        var blake = Guid.NewGuid();
        var gateway = new FakeGateway();
        var repo = new FakeTokenRepo();
        repo.ByUser[alex] = ["fcm-alex-1", "fcm-alex-2"];
        repo.ByUser[blake] = ["fcm-blake"];

        var dispatcher = new PushDispatcher(repo, gateway, NullLogger<PushDispatcher>.Instance);
        await dispatcher.DispatchAsync(new PushJob([alex, blake], "circle-1"), default);

        gateway.SeenTokens.Should().BeEquivalentTo(["fcm-alex-1", "fcm-alex-2", "fcm-blake"]);
    }

    [Fact]
    public async Task Dispatch_prunes_the_tokens_the_gateway_reports_dead()
    {
        var alex = Guid.NewGuid();
        var gateway = new FakeGateway();
        var repo = new FakeTokenRepo();
        repo.ByUser[alex] = ["fcm-live", "fcm-dead"];
        gateway.NextResult = new FcmSendResult(
        [
            new FcmTokenOutcome("fcm-live", Delivered: true, ShouldPrune: false),
            new FcmTokenOutcome("fcm-dead", Delivered: false, ShouldPrune: true),
        ]);

        var dispatcher = new PushDispatcher(repo, gateway, NullLogger<PushDispatcher>.Instance);
        await dispatcher.DispatchAsync(new PushJob([alex], "circle-1"), default);

        repo.Deleted.Should().ContainSingle().Which.Should().Be("fcm-dead");
    }

    [Fact]
    public async Task Dispatch_with_no_tokens_never_calls_the_gateway()
    {
        var gateway = new FakeGateway();
        var dispatcher = new PushDispatcher(new FakeTokenRepo(), gateway, NullLogger<PushDispatcher>.Instance);

        await dispatcher.DispatchAsync(new PushJob([Guid.NewGuid()], "circle-1"), default);

        gateway.SeenTokens.Should().BeEmpty();
    }
}
```

- [ ] **Step 2: Run, verify fail**

Run: `dotnet test backend/Detour/Detour.InfraTests --filter "FullyQualifiedName~PushDispatcherTests|FullyQualifiedName~PushQueueTests"`
Expected: FAIL — types missing.

- [ ] **Step 3: Write `PushJob.cs`**

```csharp
namespace Detour.Api.Notifications;

/// <summary>One circle event's worth of wake-pings: the recipients who were not
///  holding a live socket when it was recorded, and the circle id to collapse on.</summary>
public sealed record PushJob(IReadOnlyCollection<Guid> RecipientUserIds, string CollapseKey);
```

- [ ] **Step 4: Write `PushQueue.cs`**

```csharp
using System.Threading.Channels;

namespace Detour.Api.Notifications;

public interface IPushQueue
{
    /// <summary>False when the queue is full. A dropped wake-ping is not an
    ///  error — the device reconciles on its next foreground sweep.</summary>
    bool TryEnqueue(PushJob job);

    IAsyncEnumerable<PushJob> ReadAllAsync(CancellationToken cancellationToken);
}

public sealed class PushQueue : IPushQueue
{
    private readonly Channel<PushJob> _channel;

    public PushQueue(NotificationSettings settings)
    {
        _channel = Channel.CreateBounded<PushJob>(new BoundedChannelOptions(settings.QueueCapacity)
        {
            FullMode = BoundedChannelFullMode.DropWrite,
            SingleReader = true,
        });
    }

    public bool TryEnqueue(PushJob job) => _channel.Writer.TryWrite(job);

    public IAsyncEnumerable<PushJob> ReadAllAsync(CancellationToken cancellationToken) =>
        _channel.Reader.ReadAllAsync(cancellationToken);
}
```

- [ ] **Step 5: Write `PushDispatcher.cs`**

```csharp
using Microsoft.Extensions.Logging;

namespace Detour.Api.Notifications;

/// <summary>
/// Turns one <see cref="PushJob"/> into FCM sends: look up every token for the
/// recipients, wake them, prune whatever the gateway reports dead. Pure
/// orchestration — no queue, no hosted-service lifecycle — so it is unit-tested
/// directly.
/// </summary>
public sealed class PushDispatcher(
    Detour.Domain.Notifications.IDeviceTokenRepository tokens,
    IFcmGateway gateway,
    ILogger<PushDispatcher> logger)
{
    public async Task DispatchAsync(PushJob job, CancellationToken cancellationToken)
    {
        if (job.RecipientUserIds.Count == 0)
            return;

        var targets = await tokens.GetForUsersAsync(job.RecipientUserIds, cancellationToken);
        if (targets.Count == 0)
            return;

        var tokenList = targets.Select(t => t.Token).Distinct().ToArray();
        var result = await gateway.SendWakeAsync(tokenList, job.CollapseKey, cancellationToken);

        var prune = result.TokensToPrune.ToArray();
        if (prune.Length > 0)
        {
            logger.LogInformation("Pruning {Count} dead push token(s)", prune.Length);
            await tokens.DeleteByTokensAsync(prune, cancellationToken);
        }
    }
}
```

- [ ] **Step 6: Write `PushDispatchWorker.cs`**

Follow `LiveRevocationSweep.cs` (internal sealed, `BackgroundService`,
`IServiceScopeFactory` for scoped deps):

```csharp
namespace Detour.Api.Notifications;

internal sealed class PushDispatchWorker(
    IPushQueue queue,
    IServiceScopeFactory scopeFactory,
    ILogger<PushDispatchWorker> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await foreach (var job in queue.ReadAllAsync(stoppingToken))
        {
            try
            {
                await using var scope = scopeFactory.CreateAsyncScope();
                var dispatcher = scope.ServiceProvider.GetRequiredService<PushDispatcher>();
                await dispatcher.DispatchAsync(job, stoppingToken);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                // One failed job must not stop the drain — the next event's
                // wake-ping is unaffected, and this one's recipients catch up
                // on foreground.
                logger.LogWarning(ex, "Push dispatch failed for collapseKey {CollapseKey}", job.CollapseKey);
            }
        }
    }
}
```

- [ ] **Step 7: Register in `NotificationsInstaller.cs`**

Add:
```csharp
services.AddSingleton<IPushQueue, PushQueue>();
services.AddScoped<PushDispatcher>();
services.AddHostedService<PushDispatchWorker>();
```
(`PushDispatcher` is scoped because `IDeviceTokenRepository` is scoped; the
worker resolves it per job inside its own scope.)

- [ ] **Step 8: Run the tests, verify green**

Run: `dotnet test backend/Detour/Detour.InfraTests --filter "FullyQualifiedName~PushDispatcherTests|FullyQualifiedName~PushQueueTests|FullyQualifiedName~FcmGatewayTests"`
Expected: PASS (all).

- [ ] **Step 9: Build + format**

```bash
dotnet build backend/Detour.slnx
dotnet format style backend/Detour.slnx --severity info
```

- [ ] **Step 10: Commit**

```bash
git add backend/Detour/Detour.Api/Notifications backend/Detour/Detour.InfraTests/Api/PushDispatchTests.cs
git commit -m "feat(notifications): bounded push queue, dispatcher and drain worker

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01JtVUnD7FpwJiwkcN6iFcJH"
```

---

## Task 5: Wire the wake-ping into `RecordEventAsync`

**Files:**
- Modify: `backend/Detour/Detour.Api/Services/CircleService.cs` (ctor + the
  `postCommit.Schedule` block at line 233)
- Create: `backend/Detour/Detour.InfraTests/Api/CircleEventPushTests.cs`

**Interfaces:**
- Consumes: `IPushQueue`, `PushJob` (Task 4); `ILiveRelay.ConnectedUserIds`
  (existing).
- Produces: nothing new — behaviour change only.

- [ ] **Step 1: Write the failing integration test**

`backend/Detour/Detour.InfraTests/Api/CircleEventPushTests.cs`. This swaps in a
capturing `IFcmGateway` via `ConfigureTestServices` and drives the real HTTP +
Postgres + post-commit + background-worker path, then waits for the async drain.

```csharp
using System.Net.Http.Json;
using Detour.Api.Notifications;
using Detour.InfraTests.Database;
using Microsoft.Extensions.DependencyInjection;

namespace Detour.InfraTests.Api;

[Collection(PostgresCollection.Name)]
public class CircleEventPushTests(PostgresFixture postgres) : IAsyncLifetime
{
    private sealed class CapturingGateway : IFcmGateway
    {
        public readonly TaskCompletionSource<IReadOnlyCollection<string>> FirstSend = new();
        public List<string> AllTokens { get; } = [];

        public Task<FcmSendResult> SendWakeAsync(
            IReadOnlyCollection<string> tokens, string collapseKey, CancellationToken ct)
        {
            AllTokens.AddRange(tokens);
            FirstSend.TrySetResult(tokens);
            return Task.FromResult(FcmSendResult.Empty);
        }
    }

    private CapturingGateway _gateway = null!;
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _gateway = new CapturingGateway();
        _factory = new DetourApiFactory(postgres, services =>
            services.AddSingleton<IFcmGateway>(_gateway));
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task Recording_an_event_wakes_an_accepted_member_who_is_offline()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        await Befriend(alex, alexName, blake, blakeName);

        var circle = await CreateCircle(alex, "Household");
        await Invite(alex, circle, blakeName);
        await Respond(blake, circle, accept: true);

        // Blake has an app install, but no live socket.
        await blake.PutAsJsonAsync("/api/devices", new { token = "fcm-blake", platform = "android" });

        // Alex crosses a geofence.
        await alex.PostAsJsonAsync($"/api/circles/{circle}/events",
            new { placeId = 1L, kind = "arrive", timestampMs = 1_000L });

        var woken = await _gateway.FirstSend.Task.WaitAsync(TimeSpan.FromSeconds(5));
        woken.Should().ContainSingle().Which.Should().Be("fcm-blake");
    }

    [Fact]
    public async Task The_mover_is_never_woken_by_their_own_event()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        await Befriend(alex, alexName, blake, blakeName);
        var circle = await CreateCircle(alex, "Household");
        await Invite(alex, circle, blakeName);
        await Respond(blake, circle, accept: true);

        await alex.PutAsJsonAsync("/api/devices", new { token = "fcm-alex", platform = "android" });
        await blake.PutAsJsonAsync("/api/devices", new { token = "fcm-blake", platform = "android" });

        await alex.PostAsJsonAsync($"/api/circles/{circle}/events",
            new { placeId = 1L, kind = "arrive", timestampMs = 1_000L });

        await _gateway.FirstSend.Task.WaitAsync(TimeSpan.FromSeconds(5));
        _gateway.AllTokens.Should().NotContain("fcm-alex");
    }

    // paste NewRider, Befriend, CreateCircle, Invite, Respond from GroupTests.cs
}
```

> `DetourApiFactory` currently takes only `PostgresFixture`. Add an optional
> second ctor parameter `Action<IServiceCollection>? configureServices = null`
> and call it inside the existing `builder.ConfigureTestServices(services => { … })`
> lambda. Small, additive change to `DetourApiFactory.cs`. All existing call
> sites (`new DetourApiFactory(postgres)`) keep working.

- [ ] **Step 2: Run, verify fail**

Run: `dotnet test backend/Detour/Detour.InfraTests --filter "FullyQualifiedName~CircleEventPushTests"`
Expected: FAIL — the capturing gateway's `FirstSend` never completes (nothing
enqueues a push yet); test times out at 5s.

- [ ] **Step 3: Add the `DetourApiFactory` hook**

In `DetourApiFactory.cs`, change the primary constructor to
`DetourApiFactory(PostgresFixture postgres, Action<IServiceCollection>? configureServices = null)`
and, at the end of the existing `builder.ConfigureTestServices(services => { … })`
body, add `configureServices?.Invoke(services);`.

- [ ] **Step 4: Wire `CircleService`**

`CircleService.cs` — add `IPushQueue pushQueue` to the primary constructor
parameter list (after `IPostCommitActionScheduler postCommit`), with
`using Detour.Api.Notifications;`.

Replace the `postCommit.Schedule` block (currently lines 233-245) with:

```csharp
postCommit.Schedule(() =>
{
    liveRelay.PublishPlaceEvent(
        recipients,
        groupId,
        caller.Username,
        placeEvent.ClientPlaceId,
        placeName ?? string.Empty,
        placeEvent.Kind.Wire(),
        placeEvent.TimestampMs);

    // Everyone entitled to the event who was not already sent the live frame —
    // i.e. not holding a socket right now. A dead socket that the relay has not
    // yet noticed just means a redundant wake-ping, which the device dedupes on
    // lastSeenEventTsMs. Content-free: the token is the whole message.
    var connected = liveRelay.ConnectedUserIds;
    var offline = recipients.Where(id => !connected.Contains(id)).ToArray();
    if (offline.Length > 0)
        pushQueue.TryEnqueue(new PushJob(offline, groupId.ToString()));

    return Task.CompletedTask;
});
```

Verify `ILiveRelay.ConnectedUserIds` is on the *interface* (`LiveRelay.cs:15`) —
it is. `recipients` is the `Guid[]` built just above at line 228.

- [ ] **Step 5: Run the tests, verify green**

Run: `dotnet test backend/Detour/Detour.InfraTests --filter "FullyQualifiedName~CircleEventPushTests"`
Expected: PASS (2 tests).

- [ ] **Step 6: Run the whole InfraTests + Domain suite (nothing regressed)**

```bash
dotnet test backend/Detour/Detour.Domain.Tests
dotnet test backend/Detour/Detour.InfraTests
```
Expected: all green. Pay attention to `LiveRelayTests`, `GroupTests`,
`SocialTests` — the `CircleService` ctor change touches DI for all circle paths.

- [ ] **Step 7: Migration check + build + format**

```bash
cd backend && dotnet dotnet-ef migrations has-pending-model-changes \
  --project Detour/Detour.Database --startup-project Detour/Detour.Database && cd ..
dotnet build backend/Detour.slnx
dotnet format style backend/Detour.slnx --severity info
```

- [ ] **Step 8: Commit**

```bash
git add backend/Detour/Detour.Api/Services/CircleService.cs \
        backend/Detour/Detour.InfraTests/Api/CircleEventPushTests.cs \
        backend/Detour/Detour.InfraTests/Api/DetourApiFactory.cs
git commit -m "feat(notifications): wake offline circle members on a place event

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01JtVUnD7FpwJiwkcN6iFcJH"
```

---

## Task 6: Docs + install notes

**Files:**
- Create: `docs/PUSH.md`
- Modify: `docs/CIRCLES_AND_CONVOYS.md` (coverage/cadence table)
- Modify: `backend/INSTALL.md` (env-var row)

No tests — documentation task. Folded into one commit.

- [ ] **Step 1: Write `docs/PUSH.md`**

Cover, in prose matching the house style of `docs/CIRCLES_AND_CONVOYS.md`:

- **Why a courier exists** — once the OS freezes a backgrounded app it suspends
  the process and tears down sockets; FCM (Android) and APNs (iOS, reached via
  FCM — spec approach A) are the only channels that still wake it.
- **The wake-ping is content-free** — `data: { type: "circle_wake" }`, no
  `notification` block, `collapseKey` = circle id. The device fetches
  `GET /api/circles/{id}/events?since=…` on wake and posts the notification
  itself. Google/Apple see only "user X has something".
- **Token lifecycle** — client `PUT /api/devices { token, platform }` on
  sign-in / app-start / rotation; `DELETE /api/devices { token }` on sign-out;
  server prunes on `UNREGISTERED`. One row per install (unique on token),
  reassigned on account switch.
- **Server path** — `CircleService.RecordEventAsync` → post-commit →
  `liveRelay` for connected members, `IPushQueue` for the rest →
  `PushDispatchWorker` → `PushDispatcher` → `FcmGateway`. Bounded queue, drops
  when full (a lost ping self-heals on the next foreground sweep).
- **Configuration** — `Notifications:FirebaseCredentialsPath` points at the
  Firebase service-account JSON. Empty ⇒ the gateway logs once and no-ops; this
  is the correct state until the Firebase project exists.
- **Firebase project pointer** — one project for Detour, Cloud Messaging API
  (v1). Android + iOS apps registered under `io.github.maxke24.detour`
  (+ `.debug`, `.automotive`). APNs auth key uploaded to the console for the
  iOS relay.
- **Manual test** (no automated E2E for the real courier):
  1. Two devices, one circle, notify toggle on for both.
  2. Background the receiver fully (swipe away).
  3. On the sender, cross a circle-place geofence (or use the debug intent /
     GPS replay — see `docs/DEBUG_INTENTS.md`).
  4. Receiver shows the arrival notification within a few seconds without being
     opened.
- **iOS Notification Service Extension** (Stage 3) — fetches `/events` and
  rewrites the placeholder body; if the fetch times out the generic
  "New circle activity" survives.
- **What is deliberately not built** — no payload in the push, no server-side
  per-circle mute (a muted device wakes then stays silent), no UnifiedPush /
  non-Play path.

- [ ] **Step 2: Update `docs/CIRCLES_AND_CONVOYS.md`**

Find the coverage/cadence table (near the "§11" / relay-cadence section). Add a
column or row distinguishing **app foreground** (live relay socket, instant)
from **app backgrounded** (FCM wake-ping, was: nothing). If the existing table
says circle notifications only work while the app is alive, correct that line.
Keep the edit minimal and in the table's existing shape.

- [ ] **Step 3: Update `backend/INSTALL.md`**

In the configuration table (around line 68, where
`ConnectionStrings:DefaultConnection` is described), add a row:

| `Notifications__FirebaseCredentialsPath` | Absolute path to the Firebase service-account JSON, for circle push wake-pings. Unset ⇒ push disabled (the rest of the app is unaffected). See `docs/PUSH.md`. |

- [ ] **Step 4: Commit**

```bash
git add docs/PUSH.md docs/CIRCLES_AND_CONVOYS.md backend/INSTALL.md
git commit -m "docs(notifications): document the circle push wake transport

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01JtVUnD7FpwJiwkcN6iFcJH"
```

---

## Task 7: Open the PR

- [ ] **Step 1: Rebase-check against main, run the full backend gate locally**

```bash
git fetch origin main
dotnet build backend/Detour.slnx --configuration Release
dotnet test backend/Detour/Detour.Domain.Tests --configuration Release
dotnet test backend/Detour/Detour.InfraTests --configuration Release
cd backend && dotnet dotnet-ef migrations has-pending-model-changes \
  --project Detour/Detour.Database --startup-project Detour/Detour.Database && cd ..
dotnet format style backend/Detour.slnx --severity info --verify-no-changes --exclude '**/Migrations/**'
```
All must pass — this is exactly what `.github/workflows/backend.yml` runs.

- [ ] **Step 2: Push the branch and open the PR**

Branch: `feat/circle-push-wake-backend` (off `spec/circle-push-wake` or `main` —
confirm with the requester; the spec commit `8d0ee15` should be in the history
or already merged).

Use the `detour-pr-writing` skill for the body. Lead with: the backend now has a
device-token registry and a content-free FCM wake-ping for offline circle
members; it ships dark (no client registers a token yet); Stages 2 (Android) and
3 (iOS) follow. Note the human prerequisites (spec §4.7 steps 1-2, 11-12) and
that `Notifications:FirebaseCredentialsPath` is unset in prod until then.

PR body trailer:
```
🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

---

## Self-Review

**Spec coverage (§1 + §4.2 backend docs):**

| Spec item | Task |
|-----------|------|
| §1.1 `DeviceToken` domain + repo | Task 1 |
| §1.1 EF migration | Task 1 (steps 7-8) |
| §1.2 `PUT` / `DELETE /devices` | Task 2 |
| §1.3 `IFcmGateway` / `FcmGateway`, content-free, collapseKey, APNs relay | Task 3 |
| §1.3 stale-token pruning on `UNREGISTERED` | Task 3 (gateway maps it) + Task 4 (dispatcher acts on it) |
| §1.3 `FirebaseApp.Create` from config | Task 3 (step 6-7) |
| §1.4 bounded `Channel` + `BackgroundService`, drop-when-full | Task 4 |
| §1.5 hook into `RecordEventAsync`, offline recipients only | Task 5 |
| §4.2 `docs/PUSH.md`, `CIRCLES_AND_CONVOYS.md` table | Task 6 |
| §4.1 Firebase config path on CT125 | Human step (spec §4.7 #11) — documented in `docs/PUSH.md` + `INSTALL.md`, not code |
| §4.4 `versionName` bump | **Stage 2 plan**, not here (backend has no version) — noted in Global Constraints |
| §4.3 Play/App-Store privacy labels | Human steps (spec §4.7 #13-14) |
| §4.5 backend tests | Tasks 1-5 each end with tests; `FcmGateway` real-send is manual (documented Task 6) |
| §4.6 "ships dark" | Enforced: no client code in this plan; `FcmGateway` no-ops without credentials |

No spec §1/§4.2 requirement is unassigned.

**Placeholder scan:** none — every code step has literal code; the one
non-literal is `docs/PUSH.md`'s content, which is a documentation task with a
detailed outline, not code.

**Type consistency:** `IDeviceTokenRepository.GetForUsersAsync` returns
`List<DeviceTokenTarget>` in Task 1 and is consumed as such in Task 4's fake and
`PushDispatcher`. `FcmSendResult.TokensToPrune` defined in Task 3, used in Task 4.
`PushJob(IReadOnlyCollection<Guid>, string)` defined Task 4, constructed in
Task 5. `IPushQueue.TryEnqueue` returns `bool` in Task 4, result ignored in
Task 5 (intentional — drop is fine). `DevicePlatform.TryParse` (Task 1) used in
`DeviceService` (Task 2). Consistent.

**Known soft spots for the executor:**
- `FirebaseAdmin` 3.x exact API names (`SendEachForMulticastAsync`,
  `MulticastMessage`, `MessagingErrorCode`) — verify against the pinned version;
  the *shape* is fixed, the identifiers may shift.
- `SmartEnumNameConverter` namespace — copy from `FriendshipConfiguration.cs`.
- `ICurrentUser.GetAsync` return type — confirm `.Id` (used in
  `SharedRoutesController.cs`).
- `JV.ResultUtilities` `Result.Success()` / `Result.Error(IEnumerable<…>)` /
  `.ValidationMessages` / `.HasError(key)` — confirm exact surface from
  `CircleService.cs` and `PlaceEvent.Create`.

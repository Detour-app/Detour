# Rider Identity Keyed on an Id — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every rider identity comparison in Detour key on the server's account `Guid` instead of the mutable `preferred_username` handle, across the HTTP API, the live WebSocket relay, `shared/`, Android and iOS.

**Architecture:** The backend already stores every relationship against a `Guid` — nothing in the database changes and there is no migration. The work is entirely at the boundary: contracts and relay frames carry the id, the handle becomes a display label resolved from group membership, and the clients compare ids. Taken as one breaking release because nothing is deployed, so `docs/BACKEND_SPEC.md` §15.5's additive-compatibility promise is spent once, deliberately.

**Tech Stack:** .NET 10 / ASP.NET Core / EF Core / Postgres (`backend/`), Kotlin Multiplatform (`shared/`), Jetpack Compose + Android Auto (`app/`), SwiftUI (`iosApp/`), xUnit + FluentAssertions (C# tests), kotlin.test (Kotlin tests).

## Global Constraints

- **Branch:** `feat/rider-id-identity`, already cut off `main` at `ccc1235`. Design spec: `docs/superpowers/specs/2026-09-03-rider-id-identity-design.md`.
- **Every toolchain command runs in the devcontainer.** Prefix with `devcontainer-exec`. Never run a bare `./gradlew` or `dotnet` on the host — the host JDK is 26 and has no Android SDK.
- **No `Co-Authored-By:` or `Claude-Session:` trailer on any commit.** Conventional-commits subject, optional body, no trailers.
- **`versionName` in `app/build.gradle.kts` → `2.0.0`** (currently `1.97.2`). Task 12 only. Never touch `versionCode` — CI stamps it.
- **`CapabilitiesResponse.SchemaVersion` → `2`** (currently `1`, `backend/Detour/Detour.Api/Contracts/CapabilityContracts.cs`).
- **Identity type on the wire is `Guid`, serialised as a canonical 36-character string.** Not `sub`, not base64url.
- **Username stays on the wire only where it is a display label or a lookup input.** Never as an identity to compare.
- **Section numbers in `docs/BACKEND_SPEC.md` and `docs/CIRCLES_AND_CONVOYS.md` are load-bearing** — code comments cite them. Append, never renumber.
- **Comments explain why, not what.** Keep existing why-comments that are still true; do not delete one because the line beside it changed.
- **Test scope:** plain JUnit4/kotlin.test over Android-free logic only. No Robolectric. Compose and SwiftUI edits are verified by hand, not by test.
- **`.github/local-workflows/` does not exist in this repo yet.** `local-ci-act` will refuse. Before the first push on this branch, either invoke `c7-github-workflow:authoring-local-workflows` or open the PR as a draft — a push to a ready PR bills every runner.

## File Structure

**Backend — new files**

| File | Responsibility |
|---|---|
| `backend/Detour/Detour.Api/Contracts/RiderRef.cs` | The one definition of how a rider appears on the wire: `RiderRef(Guid Id, string Username)`. Nothing else defines that pair. |
| `backend/Detour/Detour.Domain/Friendships/FriendRelation.cs` | `SmartEnum` for `friend` / `incoming` / `outgoing`, so `FriendsResponse` stops encoding the relation by array position. |
| `backend/Detour/Detour.Domain/Friendships/RespondOutcome.cs` | `Accepted` / `Declined`. Distinct from `FriendshipStatus` because a decline is the absence of a friendship, not a state of one. |

**Backend — modified**

| File | Change |
|---|---|
| `Detour.Api/Contracts/SocialContracts.cs` | `FriendsResponse` → one `IReadOnlyList<FriendEntry>`; `SharedRouteResponse.From` → `RiderRef`. |
| `Detour.Api/Contracts/RiderResponses.cs` | `FriendStatsResponse.Username` → `RiderRef Rider`. |
| `Detour.Api/Contracts/GroupContracts.cs` | `GroupMemberResponse` gains `Guid Id`; `MemberPositionResponse`, `CirclePlaceResponse`, `PlaceEventResponse` swap the handle for an id. |
| `Detour.Api/Contracts/CapabilityContracts.cs` | `SchemaVersion` → 2. |
| `Detour.Api/Controllers/FriendsController.cs` | Two routes `{username}` → `{id:guid}`. |
| `Detour.Api/Services/FriendshipService.cs` | Typed returns, `Guid` parameters, relation-tagged list. |
| `Detour.Api/Services/CircleService.cs` | Stops projecting `caller.Username` into place events and place owners. |
| `Detour.Api/Services/GroupService.cs` | Member mapping carries the id. |
| `Detour.Domain/Groups/IGroupRepository.cs` | `MemberFixView` and the place-event view drop `Username`. |
| `Detour.Database/Repositories/GroupRepositories.cs` | Two `join Context.Users` clauses deleted — they existed only to fetch the handle. |
| `Detour.Api/Live/LiveFrames.cs` | Five frames carry `Guid`. |
| `Detour.Api/Live/LiveConnection.cs`, `LiveRelay.cs`, `LiveLocationService.cs`, `LiveController.cs` | Stamp and dedupe on the id. |

**shared/ — new**

| File | Responsibility |
|---|---|
| `shared/src/commonMain/kotlin/com/jellemax/detour/data/RiderId.kt` | `value class RiderId(val value: String)`. The type that makes an id/handle swap a compile error. |

**shared/ — modified:** `Groups.kt`, `CircleFixes.kt`, `CircleEvents.kt`, `CirclePlaces.kt`, `CircleNotifyPolicy.kt`, `CirclePresence.kt`, `Social.kt`, `FriendsStore.kt`, `RouteShare.kt`, `Settings.kt`, `Auth.kt`, `CredentialMigration.kt`, `drive/RelayProtocol.kt`, `drive/ConvoyRelay.kt`, `CirclesStore.kt`, `ConvoysStore.kt`.

**app/ — modified:** `ui/FriendsScreen.kt`, `ui/CirclesScreen.kt`, `ui/MapScreen.kt`, `ui/MapLibreMap.kt`, `ui/CandidatesCard.kt`, `notif/CircleNotifyService.kt`, `notif/PlaceNotifications.kt`, `net/ConvoyLiveClient.kt`, `audio/PushToTalk.kt`, `car/CarMapRenderer.kt`.

**iosApp/ — modified:** `CirclesScreen.swift`, `FriendsScreen.swift`, `MapScreen.swift`, `ConvoyLiveClient.swift`, `ConvoyBar.swift`, `RoutesScreen.swift`, `MapView.swift`, `CircleNotifications.swift`.

## Spec refinement found while planning

The spec's §5 says unknown-id → name resolution lives in the stores. That is right for the **relay** path, where positions arrive push-style with no roster. It is unnecessary for the **circle fixes** path: `CircleFixes.othersFixes` (`CircleFixes.kt:54-62`) already fetches `Groups.list("circle")` and the fixes in the same function, so both halves are in hand and the join happens there with no extra request and no unknown-id window. Task 5 does it that way; Task 6 keeps the store-level refresh for the relay.

Second: `MemberFixView` (`IGroupRepository.cs:38-45`) and the place-event projection (`GroupRepositories.cs:150`) each carry a `join Context.Users` that exists *only* to fetch the handle. Dropping the name from those payloads removes both joins. Task 2 does that.

## Deliberately not changed

Named here so a reader does not mistake them for gaps:

- **`AdminContracts.cs:11-12` and `MeResponse`** already carry `Guid Id` + `string Username`. They are the convention this change follows, not something it changes. `AdminTests.cs:52` locating a rider by handle stays valid, because the admin payload still carries one.
- **`AccountScope`** (`AccountScope.kt:77-124`) keys on-disk buckets, already prefers `sub`, and its username fallback is for a provider issuing no `sub` at all. `AccountScopeTest.kt` — including the hard-coded `"bd01b0b648c2c64e"` hash at `:61` — must stay green untouched. A failure there means a task reached too far.
- **`AuthUsernameFallbackTest.kt`** asserts `Auth.carriedUsername` does not carry a departed rider's handle into a new session. Still the right question, still about the handle, unchanged.
- **`PttAudio.swift:138-141`** takes the speaker and ignores it, where Android keys an `AudioTrack` on them. That divergence predates this change; Task 10 retypes the parameter and leaves the behaviour.
- **Request, invite and share still take a handle.** `FriendRequestBody.Username`, `InviteBody.Username`, `ShareRouteBody.To` — riders find each other by name, and it is resolved to an id at the boundary.

---

### Task 1: Friend contracts, typed statuses, id-addressed endpoints

**Files:**
- Create: `backend/Detour/Detour.Api/Contracts/RiderRef.cs`
- Create: `backend/Detour/Detour.Domain/Friendships/FriendRelation.cs`
- Create: `backend/Detour/Detour.Domain/Friendships/RespondOutcome.cs`
- Modify: `backend/Detour/Detour.Api/Contracts/SocialContracts.cs:7-10,24,47-52`
- Modify: `backend/Detour/Detour.Api/Contracts/RiderResponses.cs:49-53`
- Modify: `backend/Detour/Detour.Api/Services/FriendshipService.cs:11-24,33-56,95-134,140-162`
- Modify: `backend/Detour/Detour.Api/Controllers/FriendsController.cs:45-59,61-72`
- Modify: `backend/Detour/Detour.Api/Services/RouteSharingService.cs` (whichever line builds `SharedRouteResponse`)
- Test: `backend/Detour/Detour.InfraTests/Api/SocialTests.cs`

**Interfaces:**
- Consumes: nothing — first task.
- Produces:
  - `RiderRef(Guid Id, string Username)` in namespace `Detour.Api.Contracts`
  - `FriendEntry(RiderRef Rider, string Relation)`
  - `FriendsResponse(IReadOnlyList<FriendEntry> Riders)`
  - `FriendRelation` SmartEnum with members `Friend`, `Incoming`, `Outgoing` (wire: `friend`, `incoming`, `outgoing`)
  - `RespondOutcome` enum with `Accepted`, `Declined`
  - `IFriendshipService.RespondAsync(User caller, Guid targetId, bool accept, CancellationToken ct) -> Task<Result<RespondOutcome>>`
  - `IFriendshipService.RemoveAsync(User caller, Guid targetId, CancellationToken ct) -> Task<Result>`
  - `IFriendshipService.RequestAsync(User caller, string username, CancellationToken ct) -> Task<Result<FriendshipStatus>>` (parameter stays a handle — it is a lookup)
  - `FriendStatsResponse(RiderRef Rider, RiderStatsResponse Stats, IReadOnlyDictionary<string, long> Badges)`

- [ ] **Step 1: Write the failing test**

Add to `backend/Detour/Detour.InfraTests/Api/SocialTests.cs`. Replace the existing in/outbox assertions at `:34-35` and the accepted-friends assertion at `:63` with the id-bearing shape:

```csharp
[Fact]
public async Task Friends_list_carries_a_stable_id_and_a_relation_per_rider()
{
    var alex = await Factory.SignInAsync();
    var blake = await Factory.SignInAsync();

    await alex.PostAsJsonAsync("/api/friends/requests", new { username = blake.Username });

    var alexView = await alex.GetFromJsonAsync<JsonElement>("/api/friends");
    var alexRiders = alexView.GetProperty("riders").EnumerateArray().ToList();

    alexRiders.Should().HaveCount(1);
    alexRiders[0].GetProperty("relation").GetString().Should().Be("outgoing");
    alexRiders[0].GetProperty("rider").GetProperty("id").GetGuid().Should().Be(blake.UserId);
    alexRiders[0].GetProperty("rider").GetProperty("username").GetString().Should().Be(blake.Username);

    var blakeView = await blake.GetFromJsonAsync<JsonElement>("/api/friends");
    var blakeRiders = blakeView.GetProperty("riders").EnumerateArray().ToList();

    blakeRiders.Should().HaveCount(1);
    blakeRiders[0].GetProperty("relation").GetString().Should().Be("incoming");
    blakeRiders[0].GetProperty("rider").GetProperty("id").GetGuid().Should().Be(alex.UserId);
}

[Fact]
public async Task Responding_is_addressed_by_id_and_reports_a_lowercase_outcome()
{
    var alex = await Factory.SignInAsync();
    var blake = await Factory.SignInAsync();

    await alex.PostAsJsonAsync("/api/friends/requests", new { username = blake.Username });

    var declined = await blake.PostAsJsonAsync(
        $"/api/friends/requests/{alex.UserId}/respond", new { accept = false });

    declined.StatusCode.Should().Be(HttpStatusCode.OK);
    var body = await declined.Content.ReadFromJsonAsync<JsonElement>();
    body.GetProperty("status").GetString().Should().Be("declined");
}

[Fact]
public async Task Removing_a_friend_is_addressed_by_id()
{
    var alex = await Factory.SignInAsync();
    var blake = await Factory.SignInAsync();

    await alex.PostAsJsonAsync("/api/friends/requests", new { username = blake.Username });
    await blake.PostAsJsonAsync($"/api/friends/requests/{alex.UserId}/respond", new { accept = true });

    var removed = await alex.DeleteAsync($"/api/friends/{blake.UserId}");

    removed.StatusCode.Should().Be(HttpStatusCode.NoContent);
    var after = await alex.GetFromJsonAsync<JsonElement>("/api/friends");
    after.GetProperty("riders").EnumerateArray().Should().BeEmpty();
}
```

The harness helper needs a `UserId`. `DetourApiFactory.cs:71-103` mints the `preferred_username` claim; it must also expose the `sub` it minted and the local `Guid` the API provisioned for it. Add to the signed-in client type it returns:

```csharp
// Fetched once on sign-in, from the endpoint that already returns it.
public Guid UserId { get; private set; }

internal async Task ResolveUserIdAsync()
{
    var me = await Client.GetFromJsonAsync<JsonElement>("/api/me");
    UserId = me.GetProperty("id").GetGuid();
}
```

and call `ResolveUserIdAsync()` at the end of `SignInAsync`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `devcontainer-exec dotnet test backend/Detour/Detour.InfraTests --filter "FullyQualifiedName~SocialTests"`

Expected: FAIL. `Friends_list_carries_a_stable_id_and_a_relation_per_rider` fails on a missing `riders` property; the other two fail with 404, because `{username}` does not match a `Guid` route constraint.

- [ ] **Step 3: Add the three new types**

`backend/Detour/Detour.Api/Contracts/RiderRef.cs`:

```csharp
using System.ComponentModel.DataAnnotations;

namespace Detour.Api.Contracts;

/// <summary>
/// How a rider appears on the wire, everywhere one is named.
///
/// <see cref="Id"/> is the identity — the local account id, stable for the life of the
/// account and unaffected by a rename. <see cref="Username"/> is a display label and a
/// search key, and is never what a client compares to decide "is this me" or "do I own
/// this". See docs/superpowers/specs/2026-09-03-rider-id-identity-design.md for why both
/// travel together rather than the client resolving one from the other.
/// </summary>
public record RiderRef(
    [Required] Guid Id,
    [Required] string Username);
```

`backend/Detour/Detour.Domain/Friendships/FriendRelation.cs`:

```csharp
using Ardalis.SmartEnum;

namespace Detour.Domain.Friendships;

/// <summary>
/// Which of the three sets a rider falls into on the caller's friend list.
///
/// An enum rather than the list a rider appears in: the response used to carry three
/// arrays and encode this by position, so nothing could type-check that a rider appeared
/// in exactly one. A member here is also where a future declined or blocked relation
/// (#139) lands, instead of a fourth array.
/// </summary>
public sealed class FriendRelation : SmartEnum<FriendRelation>
{
    public static readonly FriendRelation Friend = new("Friend", 1);
    public static readonly FriendRelation Incoming = new("Incoming", 2);
    public static readonly FriendRelation Outgoing = new("Outgoing", 3);

    private FriendRelation(string name, int value) : base(name, value) { }
}
```

`backend/Detour/Detour.Domain/Friendships/RespondOutcome.cs`:

```csharp
using Ardalis.SmartEnum;

namespace Detour.Domain.Friendships;

/// <summary>
/// What answering a pending request did.
///
/// Deliberately not a <see cref="FriendshipStatus"/> member: declining deletes the row, so
/// "declined" is the absence of a friendship rather than a state of one, and widening that
/// enum would make it queryable when no row exists. This type reports what just happened to
/// the caller and persists nothing. #139 covers making a decline durable.
/// </summary>
public sealed class RespondOutcome : SmartEnum<RespondOutcome>
{
    public static readonly RespondOutcome Accepted = new("Accepted", 1);
    public static readonly RespondOutcome Declined = new("Declined", 2);

    private RespondOutcome(string name, int value) : base(name, value) { }
}
```

- [ ] **Step 4: Reshape the friend contracts**

In `SocialContracts.cs`, replace lines 7-10:

```csharp
/// <summary>
/// Everyone the caller has a friendship row with, accepted or pending, each tagged with
/// which direction it points. One list rather than three: three arrays encoded the relation
/// by position, so nothing checked that a rider appeared in exactly one.
/// </summary>
public record FriendsResponse([Required] IReadOnlyList<FriendEntry> Riders);

public record FriendEntry(
    [Required] RiderRef Rider,
    [Required] string Relation);
```

Replace line 47-52's `SharedRouteResponse`:

```csharp
public record SharedRouteResponse(
    [Required] Guid Id,
    [Required] RiderRef From,
    [Required] long CreatedAtMs,
    [Required] string Name,
    [Required] JsonElement Route);
```

In `RiderResponses.cs`, replace lines 49-53:

```csharp
/// <summary>Another rider, as a friend sees them. Aggregates only — never their rides.</summary>
public record FriendStatsResponse(
    [Required] RiderRef Rider,
    [Required] RiderStatsResponse Stats,
    [Required] IReadOnlyDictionary<string, long> Badges);
```

- [ ] **Step 5: Retype the service**

In `FriendshipService.cs`, replace the interface at lines 11-24:

```csharp
public interface IFriendshipService
{
    Task<FriendsResponse> ListAsync(Guid userId, CancellationToken cancellationToken);

    /// <summary>Handle, not id, on purpose: this is the one place a rider is looked up by
    /// the name they typed. Resolved to an id immediately below.</summary>
    Task<Result<FriendshipStatus>> RequestAsync(User caller, string username, CancellationToken cancellationToken);

    Task<Result<RespondOutcome>> RespondAsync(User caller, Guid targetId, bool accept, CancellationToken cancellationToken);

    Task<Result> RemoveAsync(User caller, Guid targetId, CancellationToken cancellationToken);

    Task<Result<IReadOnlyList<FriendStatsResponse>>> GetFriendStatsAsync(Guid userId, CancellationToken cancellationToken);

    Task<SharedFogResponse> GetSharedFogAsync(User caller, CancellationToken cancellationToken);
}
```

Replace `ListAsync` (lines 33-56):

```csharp
public async Task<FriendsResponse> ListAsync(Guid userId, CancellationToken cancellationToken)
{
    var rows = await friendships.GetForUserAsync(userId, cancellationToken);
    if (rows.Count == 0)
        return new FriendsResponse([]);

    var others = rows.Select(f => f.OtherThan(userId)).ToArray();
    var names = await ResolveUsernamesAsync(others, cancellationToken);

    List<FriendEntry> entries = [];
    foreach (var friendship in rows)
    {
        var otherId = friendship.OtherThan(userId);
        if (!names.TryGetValue(otherId, out var name))
            continue;

        var relation = friendship.IsAccepted
            ? FriendRelation.Friend
            : friendship.RequestedByUserId == userId
                ? FriendRelation.Outgoing
                : FriendRelation.Incoming;

        entries.Add(new FriendEntry(new RiderRef(otherId, name), relation.Wire()));
    }

    return new FriendsResponse(entries);
}
```

Replace the `RequestAsync` return statements (lines 74, 84, 92) so they return the enum rather than its wire string: `Result.Ok(FriendshipStatus.Accepted)`, `Result.Ok(FriendshipStatus.Pending)`, `Result.Ok(FriendshipStatus.Pending)`. Change the signature's return type to `Task<Result<FriendshipStatus>>`.

Replace `RespondAsync` (lines 95-117):

```csharp
public async Task<Result<RespondOutcome>> RespondAsync(
    User caller,
    Guid targetId,
    bool accept,
    CancellationToken cancellationToken)
{
    var friendship = await friendships.GetForPairAsync(caller.Id, targetId, cancellationToken);
    if (friendship is null || friendship.IsAccepted)
        return Result.Error(ValidationKeys.Friendship.NoPendingRequest);

    if (!accept)
    {
        friendships.Delete(friendship);
        return Result.Ok(RespondOutcome.Declined);
    }

    var result = friendship.Accept(caller.Id);
    return result.IsFailure ? result : Result.Ok(RespondOutcome.Accepted);
}
```

Note what disappears: the `GetByUsernameAsync`-then-null-check preamble. An id that names nobody now yields no friendship row, which is the same `NoPendingRequest` answer — and per spec §15.3, conflating "no such rider" with "no pending request" is the behaviour that stops the endpoint enumerating accounts.

Replace `RemoveAsync` (lines 119-134):

```csharp
public async Task<Result> RemoveAsync(User caller, Guid targetId, CancellationToken cancellationToken)
{
    var friendship = await friendships.GetForPairAsync(caller.Id, targetId, cancellationToken);
    if (friendship is not null)
        friendships.Delete(friendship);

    // A shared route is places you have been, so losing the friendship takes it back — in
    // both directions, not only what you received.
    await sharedRoutes.DeleteBetweenAsync(caller.Id, targetId, cancellationToken);

    return Result.Ok();
}
```

Replace the `FriendStatsResponse` construction in `GetFriendStatsAsync` (lines 151-159):

```csharp
var response = friends
    .Select(friend => new FriendStatsResponse(
        new RiderRef(friend.Id, friend.Username),
        RiderStatsResponse.Map(friend.Stats),
        awards.TryGetValue(friend.Id, out var earned)
            ? earned.ToDictionary(b => b.BadgeId, b => b.EarnedAtMs)
            : []))
    .OrderByDescending(f => f.Stats.TotalDistanceMeters)
    .ToList();
```

- [ ] **Step 6: Re-address the two routes**

In `FriendsController.cs`, replace lines 45-59:

```csharp
[HttpPost("requests/{id:guid}/respond")]
[EndpointSummary("Accept or decline a pending request.")]
[EndpointDescription("Only the side that did not send the request may answer it.")]
[ProducesResponseType<FriendshipStatusResponse>(StatusCodes.Status200OK)]
[ProducesResponseType(StatusCodes.Status400BadRequest, Description = "No pending request from that rider.")]
public async Task<ActionResult<FriendshipStatusResponse>> Respond(
    Guid id,
    [FromBody] FriendRespondBody body,
    CancellationToken cancellationToken)
{
    var user = await currentUser.GetAsync(cancellationToken);
    var result = await friendships.RespondAsync(user, id, body.Accept, cancellationToken);
    result.ThrowIfFailure();
    return Ok(new FriendshipStatusResponse(result.Value.Wire()));
}
```

and lines 61-72:

```csharp
[HttpDelete("{id:guid}")]
[EndpointSummary("End a friendship.")]
[EndpointDescription(
    "Also deletes every route shared between the two riders, in both directions. A route is "
    + "places you have been, so losing the friendship takes it back.")]
[ProducesResponseType(StatusCodes.Status204NoContent)]
public async Task<IActionResult> Remove(Guid id, CancellationToken cancellationToken)
{
    var user = await currentUser.GetAsync(cancellationToken);
    (await friendships.RemoveAsync(user, id, cancellationToken)).ThrowIfFailure();
    return NoContent();
}
```

`SendRequest` at line 42 becomes `Ok(new FriendshipStatusResponse(result.Value.Wire()))` for the same reason.

- [ ] **Step 7: Fix the shared-route sender**

`SharedRouteResponse.From` is now a `RiderRef`, so `RouteSharingService` must supply the sender's id alongside the handle. Find its construction site:

Run: `devcontainer-exec grep -n "new SharedRouteResponse" backend/Detour/Detour.Api/Services/RouteSharingService.cs`

The sender's `User` row is already loaded there (it is what `DeleteBetweenAsync` and the inbox query key on). Pass `new RiderRef(sender.Id, sender.Username)` in place of the bare handle. If the method only holds a `Guid` and not the row, resolve the handle the same way `FriendshipService.ResolveUsernamesAsync` does — `users.GetManyAsync` over the distinct sender ids, one query for the whole inbox, never one per row.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `devcontainer-exec dotnet test backend/Detour/Detour.InfraTests --filter "FullyQualifiedName~SocialTests"`

Expected: PASS, all three new tests plus the existing ones in the class. The route-inbox assertion at the old `SocialTests.cs:193` needs updating in the same run — `from` is now an object, so `.GetProperty("from").GetString()` becomes `.GetProperty("from").GetProperty("username").GetString()`.

- [ ] **Step 9: Verify the domain suite still passes**

Run: `devcontainer-exec dotnet test backend/Detour/Detour.Domain.Tests`

Expected: PASS. `GroupTests.cs:55,127-128` already use `ownerId` Guids and need no change; `SharingLimitsTests.cs:314-328` assert handle *validation*, which is untouched.

- [ ] **Step 10: Commit**

```bash
git add backend/Detour/Detour.Api/Contracts/RiderRef.cs \
        backend/Detour/Detour.Domain/Friendships/FriendRelation.cs \
        backend/Detour/Detour.Domain/Friendships/RespondOutcome.cs \
        backend/Detour/Detour.Api/Contracts/SocialContracts.cs \
        backend/Detour/Detour.Api/Contracts/RiderResponses.cs \
        backend/Detour/Detour.Api/Services/FriendshipService.cs \
        backend/Detour/Detour.Api/Services/RouteSharingService.cs \
        backend/Detour/Detour.Api/Controllers/FriendsController.cs \
        backend/Detour/Detour.InfraTests/Api/SocialTests.cs \
        backend/Detour/Detour.InfraTests/Api/DetourApiFactory.cs
git commit -m "feat(api)!: key friendships on the account id, not the handle (#133)"
```

---

### Task 2: Group and circle contracts carry ids, and two joins disappear

**Files:**
- Modify: `backend/Detour/Detour.Api/Contracts/GroupContracts.cs:21-24,42-47,64-70,83-89,93-110`
- Modify: `backend/Detour/Detour.Domain/Groups/IGroupRepository.cs:38-45` and the place-event view
- Modify: `backend/Detour/Detour.Database/Repositories/GroupRepositories.cs:55-70,145-158`
- Modify: `backend/Detour/Detour.Api/Services/CircleService.cs:99-104,161-172,230-255,268-275`
- Modify: `backend/Detour/Detour.Api/Services/GroupService.cs:63,77-80`
- Test: `backend/Detour/Detour.InfraTests/Api/GroupTests.cs`

**Interfaces:**
- Consumes: `RiderRef` from Task 1.
- Produces:
  - `GroupMemberResponse(Guid Id, string Username, string Status, bool? Sharing)`
  - `MemberPositionResponse(Guid RiderId, double Latitude, double Longitude, double? AccuracyMeters, long TimestampMs)`
  - `CirclePlaceResponse(Guid Id, Guid OwnerId, string Name, double RadiusMeters, long CreatedAtMs, JsonElement Place)`
  - `PlaceEventResponse(Guid Id, long PlaceId, string PlaceName, Guid RiderId, string Kind, long TimestampMs)`
  - `MemberFixView(Guid UserId, double Latitude, double Longitude, double? AccuracyMeters, long TimestampMs)` — `Username` removed
  - `GroupResponseMapper.Map(Group group, Guid callerId, IReadOnlyDictionary<Guid, string> usernames)` — signature unchanged, output gains the id

- [ ] **Step 1: Write the failing test**

Replace the three handle assertions in `GroupTests.cs` (`:38`, `:156`, `:281`) with id assertions:

```csharp
[Fact]
public async Task Circle_members_carry_an_id_beside_the_display_handle()
{
    var alex = await Factory.SignInAsync();
    var circle = await alex.CreateCircleAsync("Sunday run");

    var view = await alex.GetFromJsonAsync<JsonElement>("/api/circles");
    var members = view.EnumerateArray().First().GetProperty("members").EnumerateArray().ToList();

    members[0].GetProperty("id").GetGuid().Should().Be(alex.UserId);
    members[0].GetProperty("username").GetString().Should().Be(alex.Username);
}

[Fact]
public async Task Circle_positions_identify_the_rider_and_carry_no_handle()
{
    var alex = await Factory.SignInAsync();
    var blake = await Factory.SignInAsync();
    var circle = await alex.CreateCircleWithMemberAsync(blake);

    await blake.PostAsJsonAsync($"/api/circles/{circle}/positions",
        new { latitude = 51.2, longitude = 4.4, timestampMs = 1_760_000_000_000L });

    var view = await alex.GetFromJsonAsync<JsonElement>($"/api/circles/{circle}/positions");
    var fix = view.GetProperty("fixes").EnumerateArray().Single();

    fix.GetProperty("riderId").GetGuid().Should().Be(blake.UserId);
    fix.TryGetProperty("username", out _).Should().BeFalse(
        "the handle is membership data; a position frame carries identity only");
}

[Fact]
public async Task A_recorded_place_event_identifies_the_rider_by_id()
{
    var alex = await Factory.SignInAsync();
    var circle = await alex.CreateCircleAsync("Sunday run");
    await alex.SharePlaceAsync(circle, placeId: 7, name: "Home", radiusMeters: 120);

    var recorded = await alex.PostAsJsonAsync($"/api/circles/{circle}/events",
        new { placeId = 7, kind = "arrive", timestampMs = 1_760_000_000_000L });

    var body = await recorded.Content.ReadFromJsonAsync<JsonElement>();
    body.GetProperty("riderId").GetGuid().Should().Be(alex.UserId);
    body.TryGetProperty("username", out _).Should().BeFalse();
}
```

`CreateCircleAsync`, `CreateCircleWithMemberAsync` and `SharePlaceAsync` are the existing harness helpers at `GroupTests.cs:332-369` — they invite by handle today and keep doing so, because invitation is a lookup.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `devcontainer-exec dotnet test backend/Detour/Detour.InfraTests --filter "FullyQualifiedName~GroupTests"`

Expected: FAIL on missing `id` / `riderId` properties.

- [ ] **Step 3: Reshape the contracts**

In `GroupContracts.cs`, replace lines 17-24:

```csharp
/// <summary>
/// <c>sharing</c> is present only for circles. A convoy connection <em>is</em> sharing, so
/// there is nothing meaningful to show on that screen.
///
/// <c>id</c> is the identity; <c>username</c> is the label the screens draw. Membership is
/// the only payload that carries both, which is what lets positions, places and events
/// carry the id alone.
/// </summary>
public record GroupMemberResponse(
    [Required] Guid Id,
    [Required] string Username,
    [Required] string Status,
    bool? Sharing);
```

Replace lines 42-47:

```csharp
public record MemberPositionResponse(
    [Required] Guid RiderId,
    [Required] double Latitude,
    [Required] double Longitude,
    double? AccuracyMeters,
    [Required] long TimestampMs);
```

Replace lines 64-70:

```csharp
public record CirclePlaceResponse(
    [Required] Guid Id,
    [Required] Guid OwnerId,
    [Required] string Name,
    [Required] double RadiusMeters,
    [Required] long CreatedAtMs,
    [Required] JsonElement Place);
```

Replace lines 83-89:

```csharp
public record PlaceEventResponse(
    [Required] Guid Id,
    [Required] long PlaceId,
    [Required] string PlaceName,
    [Required] Guid RiderId,
    [Required] string Kind,
    [Required] long TimestampMs);
```

Replace the mapper body at lines 100-106:

```csharp
var members = group.Members
    .Where(m => usernames.ContainsKey(m.UserId))
    .Select(m => new GroupMemberResponse(
        m.UserId,
        usernames[m.UserId],
        m.Status.Wire(),
        group.Kind.SupportsPause ? m.IsSharing : null))
    .ToList();
```

- [ ] **Step 4: Drop `Username` from the two repository views**

In `IGroupRepository.cs`, replace `MemberFixView` at lines 38-45:

```csharp
public readonly record struct MemberFixView(
    Guid UserId,
    double Latitude,
    double Longitude,
    double? AccuracyMeters,
    long TimestampMs);
```

Do the same to the place-event view in the same file — remove its `string Username` member.

In `GroupRepositories.cs`, delete `join user in Context.Users on fix.UserId equals user.Id` (line 57) and the `user.Username` argument (line 62) from `GetSharingFixesAsync`. The join existed only to fetch the handle; the query keeps its `MemberFixes`/`GroupMembers` join, which is what enforces the accepted-and-sharing rule.

Do the same in `GetSinceAsync` around line 150 — remove the `Users` join and the `user.Username` projection argument.

- [ ] **Step 5: Stop projecting the handle in the services**

In `CircleService.cs`, replace lines 99-104:

```csharp
return new CircleFixesResponse(
[
    .. fixes.Select(f => new MemberPositionResponse(
        f.UserId, f.Latitude, f.Longitude, f.AccuracyMeters, f.TimestampMs))
]);
```

Replace the owner resolution at lines 161-172 — the `users.GetManyAsync` lookup goes away entirely, because the response now carries the owner's id and the client resolves the name from membership:

```csharp
var rows = await circlePlaces.GetForGroupAsync(groupId, cancellationToken);
if (rows.Count == 0)
    return new CirclePlacesResponse([]);

return new CirclePlacesResponse(
[
    .. rows.Select(p => new CirclePlaceResponse(
        p.Id,
        p.OwnerId,
        p.Name,
        p.RadiusMeters,
```

Replace `caller.Username` with `caller.Id` at lines 238 and 251, and `e.Username` with `e.UserId` at line 273.

In `GroupService.cs`, line 63's `[caller.Id] = caller.Username` and lines 77-80's dictionary are still needed — `GroupResponseMapper` takes the same `IReadOnlyDictionary<Guid, string>` and now emits both halves. No change beyond confirming it compiles.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `devcontainer-exec dotnet test backend/Detour/Detour.InfraTests --filter "FullyQualifiedName~GroupTests"`

Expected: PASS.

- [ ] **Step 7: Run the full backend suite**

Run: `devcontainer-exec dotnet test backend/Detour/Detour.Domain.Tests && devcontainer-exec dotnet test backend/Detour/Detour.InfraTests`

Expected: PASS except `LiveEndpointTests`, `LiveResilienceTests` and `LiveRelayTests`, which Task 3 fixes. If any of those fail here, leave them — do not patch a relay test from this task.

- [ ] **Step 8: Check formatting**

Run: `devcontainer-exec dotnet format backend/Detour/Detour.slnx style --verify-no-changes`

Expected: no changes reported. CI runs this.

- [ ] **Step 9: Commit**

```bash
git add backend/Detour/Detour.Api/Contracts/GroupContracts.cs \
        backend/Detour/Detour.Domain/Groups/IGroupRepository.cs \
        backend/Detour/Detour.Database/Repositories/GroupRepositories.cs \
        backend/Detour/Detour.Api/Services/CircleService.cs \
        backend/Detour/Detour.Api/Services/GroupService.cs \
        backend/Detour/Detour.InfraTests/Api/GroupTests.cs
git commit -m "feat(api)!: circle payloads identify riders by id, dropping two user joins (#133)"
```

---

### Task 3: Relay frames carry the id

**Files:**
- Modify: `backend/Detour/Detour.Api/Live/LiveFrames.cs:44-52,85-88,107-120,131-134`
- Modify: `backend/Detour/Detour.Api/Live/LiveConnection.cs:17,55-57,134`
- Modify: `backend/Detour/Detour.Api/Live/LiveLocationService.cs:12,123`
- Modify: `backend/Detour/Detour.Api/Live/LiveRelay.cs:22-29,92-95,129-140`
- Modify: `backend/Detour/Detour.Api/Live/LiveController.cs:65,272,312,335`
- Modify: `backend/Detour/Detour.Api/Controllers/MeController.cs:69`
- Modify: `backend/Detour/Detour.Api/Services/CircleService.cs:238`
- Test: `backend/Detour/Detour.InfraTests/Api/LiveEndpointTests.cs`, `LiveResilienceTests.cs`, `LiveRelayTests.cs`

**Interfaces:**
- Consumes: `MemberPositionResponse` shape from Task 2 (the HTTP sibling of `PeerPosition`).
- Produces:
  - `PeerPosition(Guid User /* json "u" */, double Latitude, double Longitude, double? HeadingDegrees, double? SpeedKmh, long TimestampMs, int TtlSeconds)`
  - `LeftFrame(Guid User)`, `DestinationOfferFrame(Guid User, …)`, `DestinationVoteFrame(Guid User, …)`, `PlaceEventFrame(Guid User, …)`
  - `LiveRider(Guid Id)` — `Username` removed
  - `LiveConnection(Guid userId, WebSocket socket)` — `username` parameter removed
  - `ILiveRelay.PublishPlaceEvent(IReadOnlyCollection<Guid> recipients, Guid groupId, Guid riderId, long placeId, string placeName, string kind, long timestampMs)`

- [ ] **Step 1: Write the failing test**

Replace the frame identity assertions in `LiveEndpointTests.cs` (`:136,166,168,217,224,343`) with id assertions. The representative one:

```csharp
[Fact]
public async Task A_positions_frame_identifies_each_peer_by_id()
{
    var alex = await Factory.SignInAsync();
    var blake = await Factory.SignInAsync();
    var circle = await alex.CreateCircleWithMemberAsync(blake);

    using var blakeSocket = await blake.ConnectLiveAsync();
    await blakeSocket.JoinAsync(circle);

    using var alexSocket = await alex.ConnectLiveAsync();
    await alexSocket.JoinAsync(circle);
    await alexSocket.SendLocationAsync(51.2, 4.4);

    var frame = await blakeSocket.ReadFrameAsync("positions");
    var peer = frame.GetProperty("peers").EnumerateArray().Single();

    peer.GetProperty("u").GetGuid().Should().Be(alex.UserId);
}

[Fact]
public async Task A_left_frame_identifies_the_departed_rider_by_id()
{
    var alex = await Factory.SignInAsync();
    var blake = await Factory.SignInAsync();
    var circle = await alex.CreateCircleWithMemberAsync(blake);

    using var blakeSocket = await blake.ConnectLiveAsync();
    await blakeSocket.JoinAsync(circle);

    var alexSocket = await alex.ConnectLiveAsync();
    await alexSocket.JoinAsync(circle);
    alexSocket.Dispose();

    var frame = await blakeSocket.ReadFrameAsync("left");
    frame.GetProperty("user").GetGuid().Should().Be(alex.UserId);
}
```

The `_tokens[username]` harness maps (`LiveEndpointTests.cs:399-416`, `LiveResilienceTests.cs:436-451`) key on a handle. Rekey them on the signed-in client object itself so the handle is no longer a lookup key anywhere in the harness.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `devcontainer-exec dotnet test backend/Detour/Detour.InfraTests --filter "FullyQualifiedName~Live"`

Expected: FAIL — `GetGuid()` throws on a handle string.

- [ ] **Step 3: Retype the frames**

In `LiveFrames.cs`, replace `PeerPosition` at lines 44-52 (keeping the whole doc comment above it, which explains `TtlSeconds` and is still true):

```csharp
public sealed record PeerPosition(
    [property: JsonPropertyName("u")] Guid User,
    [property: JsonPropertyName("lat")] double Latitude,
    [property: JsonPropertyName("lon")] double Longitude,
    [property: JsonPropertyName("h")] double? HeadingDegrees,
    [property: JsonPropertyName("s")] double? SpeedKmh,
    [property: JsonPropertyName("ts")] long TimestampMs,
    [property: JsonPropertyName("ttl")] int TtlSeconds) : LiveOutbound;
```

Add to the type's doc comment, above the existing text:

```csharp
/// <c>u</c> is the rider's account id. It is the identity and nothing else — a peer's
/// display handle comes from the group's membership, which the client already holds, so it
/// is not repeated on a frame that goes out several times a minute per peer.
```

Change `User` from `string` to `Guid` on `LeftFrame` (`:87`), `DestinationOfferFrame` (`:109`), `DestinationVoteFrame` (`:119`) and `PlaceEventFrame` (`:133`). Their `JsonPropertyName("user")` keys stay — the key is the protocol and it is documented in `docs/CIRCLES_AND_CONVOYS.md` §6.3.

- [ ] **Step 4: Retype the connection and the relay**

In `LiveConnection.cs`, drop the `username` constructor parameter and the `Username` property (`:17,55,57`). Replace the dedupe at line 134:

```csharp
// Ids, so two fixes from one rider always collapse. This compared handles until #133, and
// an ordinal compare on a value the database stores as citext could see one rider's two
// spellings as two riders.
pending.RemoveAll(p => p.User == position.User);
```

In `LiveLocationService.cs`, replace line 12 with `internal readonly record struct LiveRider(Guid Id);` and line 123's `caller.Username` with `caller.Id`.

In `LiveRelay.cs`, change `PublishPlaceEvent`'s `string username` parameter to `Guid riderId` (`:22-29`, `:129-140`) and line 92's `new LeftFrame(connection.Username)` to `new LeftFrame(connection.UserId)`.

In `LiveController.cs`: line 65 becomes `new LiveConnection(user.Id, socket)`; line 272 becomes `new LiveRider(connection.UserId)`; lines 312 and 335 pass `connection.UserId` instead of `connection.Username`.

In `MeController.cs`, line 69 becomes `new LiveRider(user.Id)`.

In `CircleService.cs`, line 238 becomes `caller.Id` (Task 2 may already have done this — confirm rather than repeat).

- [ ] **Step 5: Run the tests to verify they pass**

Run: `devcontainer-exec dotnet test backend/Detour/Detour.InfraTests --filter "FullyQualifiedName~Live"`

Expected: PASS.

- [ ] **Step 6: Run the whole backend suite and the formatter**

Run: `devcontainer-exec dotnet test backend/Detour/Detour.Domain.Tests && devcontainer-exec dotnet test backend/Detour/Detour.InfraTests && devcontainer-exec dotnet format backend/Detour/Detour.slnx style --verify-no-changes`

Expected: PASS, no formatting changes. This is the whole backend green — the last backend task.

- [ ] **Step 7: Commit**

```bash
git add backend/Detour/Detour.Api/Live/ \
        backend/Detour/Detour.Api/Controllers/MeController.cs \
        backend/Detour/Detour.Api/Services/CircleService.cs \
        backend/Detour/Detour.InfraTests/Api/LiveEndpointTests.cs \
        backend/Detour/Detour.InfraTests/Api/LiveResilienceTests.cs \
        backend/Detour/Detour.InfraTests/Api/LiveRelayTests.cs
git commit -m "feat(api)!: live relay frames identify riders by id (#133)"
```

---

### Task 4: `RiderId` and the shared payload models

**Files:**
- Create: `shared/src/commonMain/kotlin/com/jellemax/detour/data/RiderId.kt`
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Groups.kt:7,88-95`
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/CircleFixes.kt:9-15,84-90`
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/CircleEvents.kt:15-25,80-90,100-118`
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/CirclePlaces.kt:13-20,60-70`
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Social.kt:52-64,74-114`
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/RouteShare.kt:15,60-70`
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/drive/RelayProtocol.kt:36,47,95,106,109,125,160-250`
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/data/GroupsTest.kt`, `shared/src/commonTest/kotlin/com/jellemax/detour/drive/RelayProtocolTest.kt`

**Interfaces:**
- Consumes: the JSON shapes produced by Tasks 1–3.
- Produces:
  - `RiderId(val value: String)` — `@JvmInline value class`, in package `com.jellemax.detour.data`
  - `GroupMember(val id: RiderId, val username: String, val status: String, val sharing: Boolean)`
  - `MemberFix(val riderId: RiderId, val lat: Double, val lon: Double, val accuracyM: Double, val tsMs: Long)`
  - `PlaceEvent(..., val riderId: RiderId, ...)` — `username` removed
  - `CirclePlace(..., val ownerId: RiderId, ...)` — `owner` removed
  - `FriendStats(val rider: RiderRef, val stats: RiderStats, val badgeIds: List<String>)` where `RiderRef(val id: RiderId, val username: String)`
  - `FriendLists(val friends: List<RiderRef>, val incoming: List<RiderRef>, val outgoing: List<RiderRef>)`
  - `SharedRoute(..., val from: RiderRef, ...)`
  - `FriendPosition(val riderId: RiderId, ...)`, `IncomingAudioChunk(val riderId: RiderId, ...)`, `RelayEvent.Left(val riderId: RiderId)`, `.PttStart`, `.PttEnd`, `.SpinVote` likewise

- [ ] **Step 1: Write the failing test**

Replace `GroupsTest.kt:40` and add a parse test. Full replacement for the member-parse case:

```kotlin
@Test
fun group_member_parses_an_id_and_a_display_handle() {
    val json = jsonObjectOf(
        """
        {
          "id": "8f14e45f-ceea-467a-9a3b-1b2c3d4e5f60",
          "name": "Sunday run",
          "status": "accepted",
          "members": [
            { "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
              "username": "alice", "status": "accepted", "sharing": true }
          ]
        }
        """.trimIndent()
    )

    val group = groupFromJson(json, kind = "circle")

    assertEquals(RiderId("3fa85f64-5717-4562-b3fc-2c963f66afa6"), group.members[0].id)
    assertEquals("alice", group.members[0].username)
}
```

And in `RelayProtocolTest.kt`, replace the `:248` peer assertion:

```kotlin
@Test
fun a_positions_frame_parses_each_peer_id() {
    val frame = """
        {"type":"positions","peers":[
          {"u":"3fa85f64-5717-4562-b3fc-2c963f66afa6",
           "lat":51.2,"lon":4.4,"ts":1760000000000,"ttl":90}]}
    """.trimIndent()

    val event = parseRelayFrame(frame)

    val peers = (event as RelayEvent.Positions).peers
    assertEquals(listOf(RiderId("3fa85f64-5717-4562-b3fc-2c963f66afa6")), peers.map { it.riderId })
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*GroupsTest*' --tests '*RelayProtocolTest*'`

Expected: FAIL — `RiderId` is unresolved.

- [ ] **Step 3: Add `RiderId`**

`shared/src/commonMain/kotlin/com/jellemax/detour/data/RiderId.kt`:

```kotlin
package com.jellemax.detour.data

/**
 * A rider's identity, as the server issues it.
 *
 * A `value class` and not a `String` so that handing a handle to something
 * expecting an identity is a compile error rather than a comparison that is
 * simply false. That is not hypothetical: every `isMe`, ownership and
 * self-filter check in this app compared handles until #133, and the server
 * stores the same handle in a case-insensitive column while Kotlin's `==` is
 * case-sensitive — so the two could disagree with nothing renamed and nobody
 * at fault.
 *
 * Opaque on purpose. It is a UUID string today and nothing here reads it as
 * anything but a key to compare and a value to put back in a path, the same
 * contract [Group.id] already has.
 */
@JvmInline
value class RiderId(val value: String)

/**
 * A rider as every payload that names one carries them: the identity, and the
 * handle to draw. Mirrors the server's `RiderRef`.
 */
data class RiderRef(val id: RiderId, val username: String)
```

Note: `@JvmInline` is required on a `value class` for the JVM target and is harmless on Kotlin/Native, which `commonMain` also compiles to.

- [ ] **Step 4: Retype the models and parsers**

`Groups.kt` line 7:

```kotlin
data class GroupMember(
    val id: RiderId,
    val username: String,
    val status: String,
    val sharing: Boolean,
)
```

and in `groupFromJson` (around line 90):

```kotlin
GroupMember(
    id = RiderId(m.optString("id")),
    username = m.optString("username"),
    status = m.optString("status"),
    // Absent for a convoy: being connected to one already is
    // sharing, there's no separate flag for the server to send.
    sharing = if (m.has("sharing")) m.optBoolean("sharing") else true,
)
```

`CircleFixes.kt` lines 9-15 and `memberFixFromJson`:

```kotlin
/** A circle member's last known position, as `GET /api/circles/{id}/positions`
 *  returns it. Identity only — the handle to draw comes from the group's
 *  membership, which [CircleFixes.othersFixes] fetches in the same breath. */
data class MemberFix(
    val riderId: RiderId,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val tsMs: Long,
)
```

```kotlin
internal fun memberFixFromJson(f: JsonObject): MemberFix = MemberFix(
    riderId = RiderId(f.optString("riderId")),
    lat = f.optDouble("latitude"),
    lon = f.optDouble("longitude"),
    // Null when the platform reported no accuracy; the map treats a
    // non-positive radius as "no circle to draw" already.
    accuracyM = f.optDouble("accuracyMeters", 0.0),
    tsMs = f.optLong("timestampMs"),
)
```

`CircleEvents.kt`: `PlaceEvent.username: String` becomes `riderId: RiderId`; the HTTP parse at `:83` reads `RiderId(e.optString("riderId"))`; the relay parse at `:103` reads `RiderId(o.optString("user"))`, keeping its non-empty guard. The notification text at `:129,132` interpolates a handle it no longer has — that becomes a parameter the caller supplies from membership, wired in Task 8.

`CirclePlaces.kt`: `CirclePlace.owner: String` becomes `ownerId: RiderId`; the parse at `:66` reads `RiderId(entry.optString("ownerId"))`.

`Social.kt` lines 52-64:

```kotlin
/** A friend's aggregate numbers. Never their trips or traces — the server
 *  doesn't send those, and this type has nowhere to put them. */
data class FriendStats(
    val rider: RiderRef,
    val stats: RiderStats,
    val badgeIds: List<String>,
)

data class FriendLists(
    val friends: List<RiderRef>,
    val incoming: List<RiderRef>,
    val outgoing: List<RiderRef>,
)
```

`Friends.lists()` now partitions one array instead of reading three:

```kotlin
@Throws(Exception::class)
suspend fun lists(): FriendLists {
    val entries = Api.requestJson("GET", "/friends").optArray("riders")?.objects().orEmpty()
    // The wire carries one list tagged with a relation; the screens want three.
    // Partitioning here rather than server-side keeps the contract from encoding
    // the relation by array position, which is what let a rider appear in two.
    val byRelation = entries.groupBy({ it.optString("relation") }) { riderRefFromJson(it.optObject("rider")!!) }
    return FriendLists(
        friends = byRelation["friend"].orEmpty(),
        incoming = byRelation["incoming"].orEmpty(),
        outgoing = byRelation["outgoing"].orEmpty(),
    )
}

internal fun riderRefFromJson(o: JsonObject): RiderRef =
    RiderRef(RiderId(o.optString("id")), o.optString("username"))
```

`respond` and `remove` become id-addressed, and the handle-safety comment at `:93-94` no longer applies — a UUID needs no escaping either, but for a different reason, so replace it:

```kotlin
@Throws(Exception::class)
suspend fun respond(riderId: RiderId, accept: Boolean) {
    // An account id is a UUID, so there is nothing to encode in a path segment.
    Api.request(
        "POST", "/friends/requests/${riderId.value}/respond",
        buildJsonObject { put("accept", accept) },
    )
}

suspend fun remove(riderId: RiderId) {
    Api.request("DELETE", "/friends/${riderId.value}")
}
```

`request(username: String)` is unchanged — it is the lookup.

`stats()` builds the new `FriendStats`:

```kotlin
@Throws(Exception::class)
suspend fun stats(): List<FriendStats> =
    jsonArrayOf(Api.request("GET", "/friends/stats")).objects().map { o ->
        val badges = o.optObject("badges")
        FriendStats(
            rider = riderRefFromJson(o.optObject("rider") ?: jsonObjectOf("{}")),
            stats = riderStatsFromJson(o.optObject("stats") ?: jsonObjectOf("{}")),
            badgeIds = badges?.keys?.toList().orEmpty(),
        )
    }
```

`stringList` at `:116-119` has no callers left. Delete it.

`RouteShare.kt`: `SharedRoute.from: String` becomes `from: RiderRef`, parsed with `riderRefFromJson(o.optObject("from")!!)` at `:67`. `share(username, route)` keeps its handle — a lookup.

`RelayProtocol.kt`: rename the identity field to `riderId: RiderId` on `FriendPosition` (`:36`), `IncomingAudioChunk` (`:47`), `RelayEvent.Left` (`:95`), `PttStart` (`:106`), `PttEnd` (`:109`) and `SpinVote` (`:125`). Every parse site wraps: `:166,168,169` and `:204,210,236,239` read `RiderId(o.optString("user"))`; `:181,184,187` read `RiderId(p.optString("u"))`. Keep every blank-rejection guard exactly as it is — a blank id is still not a rider, and `ConvoyRelayTest.kt:611,642` asserts that.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*GroupsTest*' --tests '*RelayProtocolTest*'`

Expected: PASS for the two new cases. Other tests in those classes will still fail — Tasks 5 and 6 own them.

- [ ] **Step 6: Type-check `commonMain` against the common intersection**

Run: `devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata`

Expected: BUILD SUCCESSFUL. This is the check that catches a `java.*` import that would compile on Android and fail only on the iOS targets — run it before committing anything in `shared/`, per CONTRIBUTING.md.

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/RiderId.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/Groups.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/CircleFixes.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/CircleEvents.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/CirclePlaces.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/Social.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/RouteShare.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/drive/RelayProtocol.kt \
        shared/src/commonTest/
git commit -m "feat(shared)!: RiderId, and payload models that carry it (#133)"
```

---

### Task 5: The shared identity rules compare ids

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/CircleFixes.kt:50-80`
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/CircleNotifyPolicy.kt:54-60`
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/CirclePresence.kt:157,166,216-228`
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/FriendsStore.kt:146,163,174,204`
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/data/GroupsTest.kt:83-92`, `CircleNotifyPolicyTest.kt`, `CirclePresenceTest.kt`

**Interfaces:**
- Consumes: `RiderId`, `RiderRef`, `MemberFix`, `GroupMember`, `PlaceEvent`, `FriendStats` from Task 4.
- Produces:
  - `newestPerOtherMember(fixes: List<MemberFix>, selfId: RiderId): List<MemberFix>`
  - `CircleFixes.othersFixes(selfId: RiderId): List<NamedMemberFix>` where `NamedMemberFix(val fix: MemberFix, val username: String)`
  - `CircleNotifyPolicy.planCatchUp(events: List<PlaceEvent>, myId: RiderId, nowMs: Long)`
  - `CirclePresence.sharingCircles(circles: List<Group>, myId: RiderId): List<Group>`
  - `FriendsStore.refreshOwn(rider: RiderRef)`, `FriendsStore.respond(riderId: RiderId, accept: Boolean)`
  - `List<GroupMember>.handleFor(riderId: RiderId): String` — the id-to-label lookup every screen needs, defined once here because Tasks 8 and 10 each want it three times

- [ ] **Step 1: Write the failing test**

Replace `GroupsTest.kt:83-92` and the `CirclePresenceTest.kt:99-114` cases:

```kotlin
private val me = RiderId("me")
private val bob = RiderId("bob")

@Test
fun own_fix_is_dropped_and_a_double_membership_collapses_to_the_newest() {
    val fixes = listOf(
        MemberFix(me, 51.0, 4.0, 0.0, 100L),
        MemberFix(bob, 51.1, 4.1, 0.0, 100L),
        MemberFix(bob, 51.2, 4.2, 0.0, 200L),
    )

    val drawn = newestPerOtherMember(fixes, selfId = me)

    assertEquals(listOf(bob), drawn.map { it.riderId })
    assertEquals(200L, drawn.single().tsMs)
}

@Test
fun a_member_whose_handle_casing_differs_is_still_recognised_as_self() {
    // The server stores the handle in a citext column and only renames on a
    // case-insensitive difference, so its stored spelling can differ from the
    // token's. This test is the reason the comparison moved to an id: it was
    // unrepresentable before, because both sides were the same string by
    // construction.
    val circles = listOf(
        group(
            members = listOf(
                GroupMember(id = me, username = "Andre", status = "accepted", sharing = true),
            )
        )
    )

    assertEquals(circles, CirclePresence.sharingCircles(circles, myId = me))
}
```

`RiderId` wraps any string, so use readable test ids rather than UUIDs — the parser does not validate the shape and a fixture is clearer without one:

```kotlin
private val me = RiderId("me")
private val bob = RiderId("bob")
```

Then the two test bodies above read with `me` and `bob` directly, and `group(members = …)` is the fixture helper `CirclePresenceTest.kt` already has.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*GroupsTest*' --tests '*CirclePresenceTest*'`

Expected: FAIL — `newestPerOtherMember` still takes a `selfUsername: String`.

- [ ] **Step 3: Re-key the rules**

`CircleFixes.kt`: `newestPerOtherMember` and `othersFixes`. `othersFixes` already fetches both halves, so it joins them here rather than leaving an unknown-id window:

```kotlin
/** A fix, plus the handle to draw beside it, resolved from the membership this
 *  call already fetched. Identity and label arrive together at exactly one
 *  place, which is why no other layer needs an id-to-name lookup. */
data class NamedMemberFix(val fix: MemberFix, val username: String)

    /**  Both the phone map and the car map read this, so they can't drift
     *  apart on which members count. */
    @Throws(Exception::class)
    suspend fun othersFixes(selfId: RiderId): List<NamedMemberFix> {
        val circles = Groups.list("circle").filter { it.status == "accepted" }
        val names = circles.flatMap { it.members }.associate { it.id to it.username }
        return newestPerOtherMember(circles.flatMap { fixes(it.id) }, selfId)
            .map { NamedMemberFix(it, names[it.riderId].orEmpty()) }
    }
```

```kotlin
internal fun newestPerOtherMember(
    fixes: List<MemberFix>,
    selfId: RiderId,
): List<MemberFix> = fixes
    .filter { it.riderId != selfId }
    .groupBy { it.riderId }
    .map { (_, forUser) -> forUser.maxBy { it.tsMs } }
```

`CircleNotifyPolicy.kt`: `myUsername: String` → `myId: RiderId` at `:54`, and `:60` becomes `.filter { it.riderId != myId }`.

`CirclePresence.kt`: line 157 becomes `val myId = Account.riderId.value` (Task 7 adds `Account.riderId`), threaded through `planTick` at `:166,216,218`, and `:227-228` becomes:

```kotlin
circles.filter { c -> c.members.find { it.id == myId }?.sharing == true }
```

`FriendsStore.kt`: `refreshOwn(username: String)` → `refreshOwn(rider: RiderRef)`. Its blank guard at `:163` becomes `if (rider.username.isBlank() || rider.id.value.isBlank()) return` — keep the existing why-comment above it, which explains the iOS mirror lag that makes a blank reachable; it is still true and now covers one more field. Line 174 becomes `FriendStats(rider, riderStats, badgeIds)`. Line 204's `respond` takes a `RiderId`.

- [ ] **Step 3b: Define the id-to-label lookup once**

Six sites across the two platforms need "given an id, what handle do I draw" — `FriendsScreen.kt:584`, `CandidatesCard.kt:113` and `CirclesScreen.kt:536` on Android, and their three twins in Task 10. CONTRIBUTING.md's rule applies: a policy earns the core when it is written more than once, and a change landing only in `app/` silently makes iOS diverge. So it goes in `Groups.kt`, next to the type it reads:

```kotlin
/**
 * The handle to draw for a rider, from the membership this screen already has.
 *
 * Exists because payloads stopped carrying a handle beside every id (#133):
 * positions, places and events identify a rider, and membership names them, so
 * every label is one lookup away rather than repeated on the wire. Empty for an
 * id no membership knows — a peer who joined since the last reload — which the
 * caller draws as a placeholder rather than treating as an error.
 */
fun List<GroupMember>.handleFor(riderId: RiderId): String =
    firstOrNull { it.id == riderId }?.username.orEmpty()
```

`CircleFixes.othersFixes` builds a map instead of calling this per fix, because it resolves a whole list at once and a linear scan per element would be quadratic. Both are correct; the map is for the batch, this is for the single lookup a screen does while drawing one row.

- [ ] **Step 3c: Update the fixture file that will not compile**

`StoresTest.kt:30,418,428` builds identity from bare handles (`stats(name)`, `owner = "ada"`, `username = "ada"`). It is a fixture file, so it breaks compilation for the whole `commonTest` source set rather than failing a test. Update the three fixtures to the new shapes: `stats(RiderRef(RiderId("ada"), "ada"))`, `ownerId = RiderId("ada")`, `riderId = RiderId("ada")`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*GroupsTest*' --tests '*CirclePresenceTest*' --tests '*CircleNotifyPolicyTest*'`

Expected: PASS. Update the remaining `myUsername = "me"` call sites in `CircleNotifyPolicyTest.kt` (`:35,38,47-48,58-59,68,79,100-104`) and `CirclePresenceTest.kt` (`:55,63,76-77,90`) to `myId = me` in this step — they are mechanical, and the suite will not go green until they are.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/CircleFixes.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/CircleNotifyPolicy.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/CirclePresence.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/FriendsStore.kt \
        shared/src/commonTest/
git commit -m "feat(shared)!: self-filters and sharing checks compare ids (#133)"
```

---

### Task 6: The convoy relay's live state and its vote quorum

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/drive/ConvoyRelay.kt:247-257,890-950,1015-1025,1140-1195`
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/CirclesStore.kt`, `ConvoysStore.kt` — add the unknown-id refresh
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/drive/ConvoyRelayTest.kt:379-392,416-447,511-532,611-665`

**Interfaces:**
- Consumes: `RiderId`, `FriendPosition`, `RelayEvent` from Task 4.
- Produces:
  - `ConvoyRelay.peers: StateFlow<Map<RiderId, FriendPosition>>`
  - `ConvoyRelay.talking: StateFlow<Set<RiderId>>`
  - `ConvoyRelay.spinVotes: StateFlow<Map<RiderId, Int>>`
  - `ConvoyRelay.spinRoundOutcome(myId: RiderId): SpinOutcome?`
  - `ConvoyRelay.sendSpinVote(myId: RiderId, index: Int)`
  - `leadingSpinIndex(votes: Map<RiderId, Int>, …)`
  - `resolveSpinRound(votes: Map<RiderId, Int>, expected: Set<RiderId>, …)`

- [ ] **Step 1: Write the failing test**

Replace `ConvoyRelayTest.kt:379` and `:416`:

```kotlin
@Test
fun peers_are_keyed_on_the_rider_id() {
    val relay = ConvoyRelay()
    relay.onFrame(
        """{"type":"positions","peers":[{"u":"fresh","lat":51.0,"lon":4.0,"ts":1,"ttl":90}]}"""
    )

    assertEquals(setOf(RiderId("fresh")), relay.peers.value.keys)
}

@Test
fun a_round_resolves_when_every_expected_id_has_voted() {
    val relay = ConvoyRelay()
    relay.onFrame("""{"type":"positions","peers":[{"u":"ada","lat":51.0,"lon":4.0,"ts":1,"ttl":90}]}""")
    relay.onFrame("""{"type":"spin_vote","user":"ada","index":1}""")
    relay.onFrame("""{"type":"spin_vote","user":"dave","index":1}""")

    val outcome = relay.spinRoundOutcome(myId = RiderId("dave"))

    assertEquals(1, outcome?.index)
}
```

Add the case the id makes representable, which is the point of the whole change:

```kotlin
@Test
fun a_vote_resolves_even_when_the_voter_reports_a_different_handle_casing() {
    // Before #133 the quorum was a set of handles, and `expected` mixed the
    // locally stored spelling with the relay's. One casing difference and the
    // round never closed. Keyed on ids there is nothing left to differ.
    val relay = ConvoyRelay()
    relay.onFrame("""{"type":"positions","peers":[{"u":"ada","lat":51.0,"lon":4.0,"ts":1,"ttl":90}]}""")
    relay.onFrame("""{"type":"spin_vote","user":"ada","index":2}""")
    relay.onFrame("""{"type":"spin_vote","user":"dave","index":2}""")

    assertEquals(2, relay.spinRoundOutcome(myId = RiderId("dave"))?.index)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*ConvoyRelayTest*'`

Expected: FAIL — `peers.value.keys` is a `Set<String>`.

- [ ] **Step 3: Re-key the three collections**

In `ConvoyRelay.kt`, lines 247-257:

```kotlin
private val _peers = MutableStateFlow<Map<RiderId, FriendPosition>>(emptyMap())
val peers: StateFlow<Map<RiderId, FriendPosition>> = _peers.asStateFlow()

private val _talking = MutableStateFlow<Set<RiderId>>(emptySet())
val talking: StateFlow<Set<RiderId>> = _talking.asStateFlow()

private val _spinVotes = MutableStateFlow<Map<RiderId, Int>>(emptyMap())
val spinVotes: StateFlow<Map<RiderId, Int>> = _spinVotes.asStateFlow()
```

Then every downstream site, mechanically: `:897-898` (`it - event.riderId` on both), `:904` (`associateBy { p -> p.riderId }`), `:906-907` (`it + event.riderId` / `it - event.riderId`), `:917` (`it + (event.riderId to event.index)`), `:946-948` (`expected = _peers.value.keys + setOfNotNull(myId)`), `:1020` (`sendSpinVote(myId, index)`), `:1147` (`leadingSpinIndex(votes: Map<RiderId, Int>, …)`), `:1179-1190` (`resolveSpinRound`, whose `containsAll(expected)` is now a set of ids).

Add above the `resolveSpinRound` quorum check:

```kotlin
// Ids, not handles. `expected` unions the relay's peer keys with this device's
// own identity, and until #133 those were two independently-sourced spellings
// of a handle — the server's stored casing and the token's. One difference and
// this never returned, so a convoy simply stopped agreeing on a destination.
if (!votes.keys.containsAll(expected)) return null
```

The TTL prune at `:931` touches no keys and needs no change.

- [ ] **Step 4: Add the unknown-id refresh for the relay path**

`ConvoyRelay` stays free of network — it is a parser and a state holder. The lookup belongs where the member lists already live. In `ConvoysStore` (and `CirclesStore` for the circle case), add:

```kotlin
/**
 * Reloads the member list when a relay frame names an id this device has never
 * seen, which happens for a peer who joined since the last reload. The relay
 * cannot answer it: positions arrive push-style with no roster, and adding one
 * would make a second id-to-name source alongside this list.
 *
 * Debounced to one reload in flight, and an id still unknown after a completed
 * reload is not retried — it means the peer left between the frame and the
 * response, which the relay's own TTL expires.
 */
suspend fun resolveIfUnknown(riderId: RiderId) {
    if (state.value.groups.any { g -> g.members.any { it.id == riderId } }) return
    if (!refreshGate.tryLock()) return
    try {
        reload()
    } finally {
        refreshGate.unlock()
    }
}
```

`refreshGate` is a `Mutex()` field, matching the `writeLock` pattern already in `Coverage.kt:149`. Follow whichever `reload`/`loaded` convention the store already uses rather than introducing a second one — `CirclesStore.kt:290` and `ConvoysStore.kt:114` show the existing `block()` shape.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*ConvoyRelayTest*'`

Expected: PASS. The remaining cases in the class (`:386-392`, `:442-447`, `:511-532`, `:611-665`) need their `"bob"`/`"dave"`/`"zoe"` string literals wrapped in `RiderId(...)` and `myUsername =` renamed to `myId =` — do that in this step.

- [ ] **Step 6: Run the full shared suite and the metadata check**

Run: `devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata && devcontainer-exec ./gradlew :shared:testDebugUnitTest`

Expected: BUILD SUCCESSFUL, all shared tests PASS. `AccountScopeTest.kt` and `AuthUsernameFallbackTest.kt` must pass untouched — they are about on-disk bucketing and session carry-over, are already `sub`-keyed, and a failure there means something in this task reached further than it should.

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/drive/ConvoyRelay.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/CirclesStore.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/ConvoysStore.kt \
        shared/src/commonTest/kotlin/com/jellemax/detour/drive/ConvoyRelayTest.kt
git commit -m "feat(shared)!: convoy peers, PTT and the vote quorum key on ids (#133)"
```

---

### Task 7: The client learns its own id

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Social.kt:40-50` (`Account`)
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Auth.kt:54,440-470`
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt:194-195,310,438-443`
- Modify: `shared/src/commonMain/kotlin/com/jellemax/detour/data/CredentialMigration.kt:64`
- Modify: `shared/src/iosMain/kotlin/com/jellemax/detour/data/FlowWatcher.kt:285,345`
- Test: `shared/src/commonTest/kotlin/com/jellemax/detour/data/CredentialMigrationTest.kt`

**Interfaces:**
- Consumes: `RiderId` from Task 4.
- Produces:
  - `Account.riderId: StateFlow<RiderId>` — `RiderId("")` until `/me` has answered
  - `Rider.me(): RiderRef` — the `/me` fetch
  - `Settings.authRiderId: StateFlow<String>`, persisted under the secure-store key `auth_rider_id`
  - `FlowWatcher.authRiderId()` for Swift

- [ ] **Step 1: Write the failing test**

In `CredentialMigrationTest.kt`, extend the existing assertions at `:49` and `:166`:

```kotlin
@Test
fun the_rider_id_migrates_out_of_plaintext_preferences() {
    val plain = FakePrefs(mapOf("auth_rider_id" to "3fa85f64-5717-4562-b3fc-2c963f66afa6"))
    val secure = FakeSecureStore()

    CredentialMigration.run(plain, secure)

    assertEquals("3fa85f64-5717-4562-b3fc-2c963f66afa6", secure.string("auth_rider_id"))
    assertNull(plain.string("auth_rider_id"))
}
```

Match the fake types the file already uses rather than the names above if they differ — read `CredentialMigrationTest.kt:20-60` first and follow it.

- [ ] **Step 2: Run the test to verify it fails**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*CredentialMigrationTest*'`

Expected: FAIL — `auth_rider_id` is not in the migration's key list.

- [ ] **Step 3: Persist the id**

`CredentialMigration.kt:64`, alongside the existing `auth_username` entry:

```kotlin
SecretKey("auth_rider_id", SecretType.Text),
```

`Settings.kt`: mirror `authUsername` exactly — a `_authRiderId` `MutableStateFlow` plus its public `StateFlow` at `:194-195`, a `secure.string("auth_rider_id")` read in the loader at `:310`, and a write in `setSession` beside `:442`. Follow the surrounding code rather than inventing a second shape for the same job.

`Auth.kt`: after a successful `exchangeCode`/refresh writes the session, fetch the account id. It goes here and not in a screen because `Auth` is the one place that knows a session just became valid:

```kotlin
/**
 * The local account id, which only the server knows.
 *
 * Not derivable from the token: `sub` identifies the rider to the realm, and
 * this backend keys on its own row. #133 chose the local id over `sub` so a
 * circle member list does not broadcast every rider's identity-provider
 * subject to every peer, and this request is the price of that choice.
 *
 * A failure is not fatal and is not retried here. Everything that compares an
 * id fails closed while it is blank — no delete affordance, no self-filter —
 * which is the harmless direction, and the next successful session fills it.
 */
private suspend fun resolveRiderId() {
    val id = runCatching { Rider.me().id.value }.getOrDefault("")
    if (id.isNotEmpty()) Settings.setRiderId(id)
}
```

Add `Rider.me()` beside `Friends` in `Social.kt`:

```kotlin
/** The caller's own account, as `GET /api/me` returns it. */
object Rider {
    @Throws(Exception::class)
    suspend fun me(): RiderRef = riderRefFromJson(Api.requestJson("GET", "/me"))
}
```

`Settings` holds the flow as a `RiderId` directly, not as a `String` mapped at the edge. `commonMain` has no `Dispatchers` to `stateIn` on (CONTRIBUTING.md, and `Platform.kt`'s three-thing ceiling), and `authUsername` at `Settings.kt:194-195` is a plain `MutableStateFlow` for exactly that reason — so this mirrors it:

```kotlin
// Settings.kt, beside _authUsername
private val _authRiderId = MutableStateFlow(RiderId(""))
val authRiderId: StateFlow<RiderId> = _authRiderId.asStateFlow()
```

The secure store holds the raw string under `auth_rider_id`; the wrap happens in the loader and the setter, so nothing downstream handles a bare `String` identity.

`Account` then just re-exposes it, the same way it already does for `username`:

```kotlin
object Account {

    val username: StateFlow<String> = Auth.username

    /** This device's own identity, as the server issues it. `RiderId("")` until
     *  `/me` has answered — see [Auth.resolveRiderId] for why a blank is the
     *  safe value and not an error. */
    val riderId: StateFlow<RiderId> = Settings.authRiderId

    val signedIn: Boolean get() = Auth.signedIn

    @Throws(Exception::class)
    suspend fun signOut() = Auth.signOut()
}
```

`Auth.clear()` must blank it in the same write that blanks `refreshToken` and `auth_scope_key` — the reason is `AccountScope.kt:99-108`: a signed-out install that keeps a departed rider's identity is the defect #73 closed, and this is one more field with the same hazard.

`FlowWatcher.kt:285,345`: add an `authRiderId()` watcher and value bridge beside the existing `authUsername` pair, so SwiftUI can observe it.

- [ ] **Step 4: Run the test to verify it passes**

Run: `devcontainer-exec ./gradlew :shared:testDebugUnitTest --tests '*CredentialMigrationTest*'`

Expected: PASS.

- [ ] **Step 5: Run the full shared suite and the metadata check**

Run: `devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata && devcontainer-exec ./gradlew :shared:testDebugUnitTest`

Expected: BUILD SUCCESSFUL, all PASS — including `AuthEpochTest.kt`, whose `:92` fixture (`FriendStats(username = "previous-rider")`) becomes `FriendStats(RiderRef(RiderId("previous-rider"), "previous-rider"), …)`. Its assertion is that a stale row does not land under a new session, which is unchanged and must stay green.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/jellemax/detour/data/Social.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/Auth.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt \
        shared/src/commonMain/kotlin/com/jellemax/detour/data/CredentialMigration.kt \
        shared/src/iosMain/kotlin/com/jellemax/detour/data/FlowWatcher.kt \
        shared/src/commonTest/ shared/src/androidUnitTest/
git commit -m "feat(shared)!: the client resolves and persists its own account id (#133)"
```

---

### Task 8: Android screens and services

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/ui/FriendsScreen.kt:238,296-297,330-333,584,616`
- Modify: `app/src/main/java/com/jellemax/detour/ui/CirclesScreen.kt:102,219,268,442,446,541`
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapScreen.kt:229,663,669,1051-1058`
- Modify: `app/src/main/java/com/jellemax/detour/ui/MapLibreMap.kt:307-308,327-328`
- Modify: `app/src/main/java/com/jellemax/detour/ui/CandidatesCard.kt:113`
- Modify: `app/src/main/java/com/jellemax/detour/notif/CircleNotifyService.kt:162,194`
- Modify: `app/src/main/java/com/jellemax/detour/notif/PlaceNotifications.kt:66,97-98`
- Modify: `app/src/main/java/com/jellemax/detour/net/ConvoyLiveClient.kt:208,216`
- Modify: `app/src/main/java/com/jellemax/detour/audio/PushToTalk.kt:99,128-129`
- Modify: `app/src/main/java/com/jellemax/detour/ui/RoutesScreen.kt:388-392`
- Test: `app/src/test/java/com/jellemax/detour/notif/CircleNotifyDeliveryOrderTest.kt:35,49,71`

**Interfaces:**
- Consumes: everything from Tasks 4–7, including `List<GroupMember>.handleFor(riderId)` from Task 5.
- Produces: `LeaderboardEntry(val friend: FriendStats, val isMe: Boolean)` in `FriendsScreen.kt`, private to that file.

**REQUIRED READING before Step 3:** `MapScreen.kt:1051` is `LaunchedEffect(accountUsername)` and this task changes its key. Read the `detour-compose-state-hazards` skill first — an effect key change is the class of edit that compiles clean and only fails in the field.

- [ ] **Step 1: Write the failing test**

`CircleNotifyDeliveryOrderTest.kt` is the only `app/` test touching identity. Replace its `myUsername = "me"` calls (`:49,71`) with `myId = RiderId("me")`, and its `:35` helper:

```kotlin
private fun deliveredOrder(plan: CatchUpPlan): List<RiderId> =
    plan.individual.asReversed().map { it.riderId }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `devcontainer-exec ./gradlew :app:testDebugUnitTest --tests '*CircleNotifyDeliveryOrderTest*'`

Expected: FAIL — `planCatchUp` no longer takes `myUsername`.

- [ ] **Step 3: The leaderboard carries `isMe` instead of re-deriving it**

`FriendsScreen.kt`, replacing lines 330-333. This is the site that needs no id at all:

```kotlin
// The own row arrives as its own typed field, so which row is "me" is known
// before the sort. Concatenating into a bare list and recovering it with a
// comparison afterwards is what #133 found here: information discarded and
// then guessed at. Carrying the flag is correct offline and cannot disagree.
val ranked = (
    state.leaderboard.map { LeaderboardEntry(it, isMe = false) } +
        listOfNotNull(state.own?.let { LeaderboardEntry(it, isMe = true) })
    ).sortedByDescending { it.friend.stats.totalDistanceMeters }
ranked.forEachIndexed { i, entry ->
    LeaderboardRow(rank = i + 1, friend = entry.friend, isMe = entry.isMe)
}
```

and add beside `LeaderboardRow`:

```kotlin
/** A leaderboard row and whether it is the signed-in rider's own, kept
 *  together through the sort. */
private data class LeaderboardEntry(val friend: FriendStats, val isMe: Boolean)
```

Line 418's `friend.username + if (isMe) " (you)" else ""` becomes `friend.rider.username + …`.

- [ ] **Step 4: Re-key the remaining Android sites**

Each is a one-line change. Exact current text and its replacement:

| Site | From | To |
|---|---|---|
| `FriendsScreen.kt:238` | `FriendsStore.refreshOwn(username)` | `FriendsStore.refreshOwn(RiderRef(riderId, username))` |
| `FriendsScreen.kt:296-297` | `FriendsStore.respond(name, …)` | `FriendsStore.respond(rider.id, …)` |
| `FriendsScreen.kt:584` | `livePeers.keys.sorted()` | `livePeers.keys.map { convoy.members.handleFor(it) }.sorted()` (Task 5's helper) |
| `FriendsScreen.kt:616` | `ConvoysStore.invite(convoy.id, target)` | unchanged — invitation is by handle |
| `CirclesScreen.kt:102,219` | `Account.username` | `Account.riderId` (keep `Account.username` where it is only drawn) |
| `CirclesScreen.kt:268` | `CirclesStore.invite(c.id, target)` | unchanged — by handle |
| `CirclesScreen.kt:442` | `circle.members.find { it.username == username }` | `circle.members.find { it.id == riderId }` |
| `CirclesScreen.kt:446` | `isMe = member.username == username` | `isMe = member.id == riderId` |
| `CirclesScreen.kt:541` | `if (place.owner == username)` | `if (place.ownerId == riderId)` |
| `CirclesScreen.kt:536` | `"Shared by ${place.owner} · …"` | `"Shared by ${circle.members.handleFor(place.ownerId)} · …"` |
| `MapScreen.kt:229` | `accountUsername` from `Account.username` | `accountRiderId` from `Account.riderId` |
| `MapScreen.kt:663,669` | `spinRoundOutcome(accountUsername)` | `spinRoundOutcome(accountRiderId)` |
| `MapScreen.kt:1051` | `LaunchedEffect(accountUsername)` | `LaunchedEffect(accountRiderId)` |
| `MapScreen.kt:1052,1058` | blank-handle clear, `othersFixes(accountUsername)` | blank-id clear, `othersFixes(accountRiderId)` |
| `MapLibreMap.kt:307-308,327-328` | `f.username` | `f.username` from `NamedMemberFix` — the label now arrives joined, so the property path changes but the drawn string does not |
| `CandidatesCard.kt:113` | `convoyVotes.filterValues { it == index }.keys.sorted()` | `.keys.map { members.handleFor(it) }.sorted()` — the card takes `members` as a new parameter; it has no store access of its own and should not gain one |
| `CircleNotifyService.kt:162` | `relay.event.username == Account.username.value` | `relay.event.riderId == Account.riderId.value` |
| `CircleNotifyService.kt:194` | `planCatchUp(events, Account.username.value, …)` | `planCatchUp(events, Account.riderId.value, …)` |
| `PlaceNotifications.kt:66,97-98` | `event.username` as the dedupe salt | `event.riderId.value` — a stable salt is strictly better here, since a rename previously split one rider's notifications into two ids |
| `ConvoyLiveClient.kt:208` | `relay.sendSpinVote(Settings.authUsername.value, index)` | `relay.sendSpinVote(Account.riderId.value, index)` |
| `ConvoyLiveClient.kt:216` | `spinRoundOutcome(myUsername)` | `spinRoundOutcome(myId)` |
| `PushToTalk.kt:99,128-129` | `tracks.getOrPut(username)` | `tracks.getOrPut(chunk.riderId)` — a rename previously orphaned a live AudioTrack |
| `RoutesScreen.kt:388-392` | handle as share target | unchanged — sharing is by handle |

Display-only sites keep drawing a handle: `CirclesScreen.kt:349,569,632`, `FriendsScreen.kt:411,669`, `HubScreen.kt:243,251`, `MapChrome.kt:159`, `SettingsScreen.kt:182,218-219,688`. Where the handle previously came off a payload that no longer carries it, resolve it from the group's membership.

- [ ] **Step 5: Run the test to verify it passes**

Run: `devcontainer-exec ./gradlew :app:testDebugUnitTest`

Expected: PASS, whole `app/` unit suite.

- [ ] **Step 6: Build the app**

Run: `devcontainer-exec ./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Verify by hand on a device**

The Compose edits are not covered by any test in this repo. Use `detour-adb` to install and `detour-gps-replay` to drive a circle and a convoy, and confirm:

- your own row shows "(you)" on the leaderboard and no other row does
- your own pin does not appear among circle members on the map
- the delete affordance appears on a place you shared and not on one you did not
- a member's handle is drawn beside their pin
- a convoy destination vote closes when everyone has voted

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/ app/src/test/java/com/jellemax/detour/
git commit -m "feat(app)!: Android identity checks compare ids (#133)"
```

---

### Task 9: Android Auto

**Files:**
- Modify: `app/src/main/java/com/jellemax/detour/car/CarMapRenderer.kt:144-148,390`

**Interfaces:**
- Consumes: `Account.riderId` (Task 7), `CircleFixes.othersFixes` (Task 5).
- Produces: nothing consumed downstream.

- [ ] **Step 1: Re-key the car renderer**

Lines 144-148:

```kotlin
val me = Account.riderId.value
// Same fixes the phone map draws, from the same shared rule, so the two
// cannot disagree about which members count.
val fixes = CircleFixes.othersFixes(me)
```

Line 390's redraw gate becomes `Account.riderId.value.value.isNotBlank()`. That double `.value` is ugly — if the file reads better with a local, add one; do not add an `isBlank()` helper to `RiderId` for one call site.

- [ ] **Step 2: Build**

Run: `devcontainer-exec ./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify by hand**

The car surface has no test coverage and needs a head unit or the DHU. Confirm circle members draw with their handles and that your own position is not among them.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/jellemax/detour/car/CarMapRenderer.kt
git commit -m "feat(app)!: Android Auto circle members key on ids (#133)"
```

---

### Task 10: iOS

**Files:**
- Modify: `iosApp/Detour/CirclesScreen.swift:226,237,253,304,322,353,372,378-379,459,464,499`
- Modify: `iosApp/Detour/FriendsScreen.swift:122,127,132,161-166,200,224,283,452,458,480,523-524`
- Modify: `iosApp/Detour/MapScreen.swift:104-105,120,131,406-407,420`
- Modify: `iosApp/Detour/ConvoyLiveClient.swift:62,63,65,283,300-301`
- Modify: `iosApp/Detour/ConvoyBar.swift:35-37,43`
- Modify: `iosApp/Detour/RoutesScreen.swift:292,315,319`
- Modify: `iosApp/Detour/MapView.swift:96`
- Modify: `iosApp/Detour/CircleNotifications.swift:105,142`

**Interfaces:**
- Consumes: `FlowWatcher.authRiderId()` (Task 7) and every shared type from Tasks 4–6, as exposed to Swift.
- Produces: nothing consumed downstream.

- [ ] **Step 1: Mirror the id alongside the handle**

`FriendsScreen.swift:452,458,480,523-524` and `CirclesScreen.swift:226,237,253,353` hold a `username` mirror fed by a `FlowWatcher`. Add a `riderId` mirror beside each, from `FlowWatcher.authRiderId()`, and keep the existing clear-rather-than-freeze behaviour — `FriendsStore.refreshOwn`'s doc names that iOS mirror lag as the reason its blank guard exists, and the new field has the same hazard.

- [ ] **Step 2: Re-key the comparisons**

| Site | From | To |
|---|---|---|
| `CirclesScreen.swift:372` | `circle.members.first { $0.username == username }` | `circle.members.first { $0.id == riderId }` |
| `CirclesScreen.swift:379` | `isMe: member.username == username` | `isMe: member.id == riderId` |
| `CirclesScreen.swift:464` | `if place.owner == username` | `if place.ownerId == riderId` |
| `CirclesScreen.swift:459` | `place.owner` drawn | resolve from `circle.members` |
| `FriendsScreen.swift:162` | `let isMe = friend.username == model.username` | carry it through `rankedLeaderboard`, exactly as Task 8 does on Android |
| `MapScreen.swift:104-105,120,131` | `.task(id: circleFixUsername.username)`, `othersFixes(selfUsername:)`, the stale guard | key the task on the id, pass the id, compare the id |
| `MapScreen.swift:406-407,420` | `authUsername` → `spinRoundIsReadyToClose`, voter handles | id, and resolve voter names from membership |
| `ConvoyLiveClient.swift:62,63,65` | `[String: FriendPosition]`, `Set<String>`, `[String: Int]` | keyed on the id type the shared module exposes |
| `ConvoyLiveClient.swift:283,300-301` | `sendSpinVote(username:)`, `spinRoundIsReadyToClose(myUsername:)` | id |
| `ConvoyBar.swift:35-37,43` | sort and `id:` on `username`, `talking.contains(peer.username)` | id for identity, handle for the label |
| `CircleNotifications.swift:105,142` | `authUsername` → `planCatchUp(myUsername:)` | id |
| `FriendsScreen.swift:127,132` | `FriendsStore.respond(username:)` | `respond(riderId:)` |
| `FriendsScreen.swift:224`, `CirclesScreen.swift:152`, `RoutesScreen.swift:315,319` | request / invite / share by handle | unchanged — all lookups |

`ForEach(id:)` moves off the handle at `CirclesScreen.swift:378`, `FriendsScreen.swift:122,161,200`, `ConvoyBar.swift:36` and `RoutesScreen.swift:292`. `MapView.swift:96`'s pin title keeps drawing the handle, resolved from membership.

`PttAudio.swift:138-141` takes the speaker and deliberately ignores it. Retype the parameter and leave the behaviour — do not "fix" it to match Android's keyed tracks; that difference is deliberate and undocumented here, so leave it alone rather than changing it in a commit about identity.

- [ ] **Step 3: Regenerate the Xcode project and build**

Without a Mac this cannot be built locally. Push the branch and let the iOS workflow build it on `macos-15` — it type-checks `commonMain`, runs the shared tests on both the JVM and Kotlin/Native, and boots the app in a simulator.

With a Mac:

```bash
cd iosApp && xcodegen && open Detour.xcodeproj
```

Never edit the `.xcodeproj` — it is generated from `project.yml` and not committed.

Expected: builds, and the simulator screenshot the workflow uploads shows the Friends screen rendering.

- [ ] **Step 4: Commit**

```bash
git add iosApp/Detour/
git commit -m "feat(ios)!: iOS identity checks compare ids (#133)"
```

---

### Task 11: Documentation

**Files:**
- Modify: `docs/BACKEND_SPEC.md` §6, §9, §10, §11.2, §15.5, §16
- Modify: `docs/CIRCLES_AND_CONVOYS.md` §6.2, §6.3
- Modify: `docker/prod/README.md:44-48`

**Interfaces:**
- Consumes: the final shapes from Tasks 1–3.
- Produces: nothing.

- [ ] **Step 1: Update the friend and group payload sections**

`docs/BACKEND_SPEC.md` §6 (line 164): the **List** bullet no longer returns three sets. Replace it:

```markdown
- **List** returns one set of riders, each carrying their account id, their handle and
  which relation it is — accepted friend, incoming request or outgoing request. The id is
  the identity; the handle is a label and a search key, never something a client compares
  to decide whose data it is looking at.
```

§9 and §10: state that member, position, place and event payloads identify a rider by their
account id, and that the display handle comes from group membership.

§11.2 (relay frames, line 312): the frame tables' identity field is an account id.

§15.5 (line 507): `schema` is now 2, and the compatibility paragraph needs an honest note.
Append after the two existing rules rather than rewriting them:

```markdown
Those two rules were spent once, deliberately, before any deployment existed: #133 changed
every payload that names a rider to carry an account id instead of a handle, which is a
breaking change to existing fields rather than an addition. `schema` moved to 2 for it. The
rules hold from here — the reason to break them cleanly once was that there was no client
and no self-hosted server to strand, and that will not be true again.
```

§16's Handle row keeps its pattern — the handle still exists and is still validated.

- [ ] **Step 2: Update the relay wire format**

`docs/CIRCLES_AND_CONVOYS.md` §6.2 and §6.3: the `u` key on `positions` and the `user` key on
`left`, `spin_offer`, `spin_vote` and `place_event` carry an account id. Note that `positions`
no longer carries a handle and why — one source for the label, and bytes on the hot path.

**Append, never renumber.** Both documents are cited by section number from code comments.

- [ ] **Step 3: Close #25's caveat**

`docker/prod/README.md:44-48` currently tells an operator to leave `editUsernameAllowed` off
and warns that `loginWithEmailAllowed: true` already means the handle is not what the system
keys on. That warning can now be replaced with the fact:

```markdown
6. `editUsernameAllowed` is Keycloak's default (off) and there is no longer a reason here to
   keep it that way: relationships key on the account id, so a rename changes the label
   riders see and nothing else. Turn it on if you want riders renaming themselves. Note that
   handles remain unique per realm, so a rename into a handle someone else holds is refused.
```

Verify that claim against `UserConfiguration.cs:55` before writing it — the unique index is
what makes the last sentence true, and if it ever goes, this paragraph is wrong.

- [ ] **Step 4: Check nothing else cites what moved**

Run: `devcontainer-exec grep -rn "preferred_username\|place.owner\|isMe" docs/`

Expected: any hit is either still accurate or updated in this task.

- [ ] **Step 5: Commit**

```bash
git add docs/BACKEND_SPEC.md docs/CIRCLES_AND_CONVOYS.md docker/prod/README.md
git commit -m "docs: rider identity is an account id, not a handle (#133)"
```

---

### Task 12: Version bump

**Files:**
- Modify: `app/build.gradle.kts:80`
- Modify: `backend/Detour/Detour.Api/Contracts/CapabilityContracts.cs` (`SchemaVersion`)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing.

- [ ] **Step 1: Bump `versionName`**

`app/build.gradle.kts:80`: `versionName = "1.97.2"` becomes `versionName = "2.0.0"`.

Breaking wire protocol, which is CONTRIBUTING.md's major row. Do not touch `versionCode` —
CI stamps it from the run number.

- [ ] **Step 2: Bump the schema**

`CapabilityContracts.cs`: `public const int SchemaVersion = 1;` becomes `= 2;`. Its doc comment
already says it moves only when an existing field changes meaning, which is exactly this.

- [ ] **Step 3: Confirm the whole tree builds**

Run: `devcontainer-exec ./gradlew :shared:compileCommonMainKotlinMetadata && devcontainer-exec ./gradlew :shared:testDebugUnitTest && devcontainer-exec ./gradlew :app:testDebugUnitTest && devcontainer-exec ./gradlew :app:assembleDebug && devcontainer-exec dotnet test backend/Detour/Detour.Domain.Tests && devcontainer-exec dotnet test backend/Detour/Detour.InfraTests && devcontainer-exec dotnet format backend/Detour/Detour.slnx style --verify-no-changes`

Expected: everything PASS, no formatting changes.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts backend/Detour/Detour.Api/Contracts/CapabilityContracts.cs
git commit -m "chore: 2.0.0 — breaking wire change for rider identity (#133)"
```

---

## Before the first push

`.github/local-workflows/` does not exist in this repository, so `local-ci-act` has nothing to
run and will refuse. Two acceptable routes, and the choice is the user's:

1. Invoke `c7-github-workflow:authoring-local-workflows` to create the local counterparts and
   `.claude/c7/github.yml`, then gate the push on `local-ci-act` as normal.
2. Open the pull request as a **draft** (`gh pr create --draft`), which starts no billed runs,
   and accept that the first real CI signal comes when it is marked ready.

Do not push to a ready pull request without one of the two. Every workflow in
`.github/workflows/` starts on paid runners, and this branch touches `backend/`, `shared/`,
`app/` and `iosApp/`, so it triggers all of them including the `macos-15` iOS job.

The PR description is `detour-pr-writing`'s job, not this plan's.

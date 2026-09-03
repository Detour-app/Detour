# Rider identity keyed on an id, not a handle

Closes #133. A breaking wire change, taken as one release rather than staged, because nothing is
deployed yet.

## The defect, in one line

Every payload that names a rider names them by their handle, and the clients then make decisions
by comparing that string:

```kotlin
// app/src/main/java/com/jellemax/detour/ui/CirclesScreen.kt:541
if (place.owner == username) {          // shows the delete affordance
```

`place.owner` is a handle the server put in the payload; `username` is the handle this device
read out of its own access token. Nothing guarantees the two spellings match, and one of them
is a mutable label.

## What the issue got wrong, and why it matters

The premise check (recorded as a comment on #133) found the server half already done. Worth
restating here because it halves the work and removes the scariest line from the issue's Scope
paragraph:

**Every relationship in the backend is already keyed on a `Guid`.**

```
Friendship.cs:17,19,23   LowUserId / HighUserId / RequestedByUserId
GroupMember.cs:18        UserId
Group.cs:22              OwnerId
CirclePlace.cs:23        OwnerId
SavedPlace.cs:17         UserId
PlaceEvent.cs:27         UserId
```

So: **no data migration**, and a rename does not detach a rider from their relationships today —
`CurrentUser.SyncFromToken` (`CurrentUser.cs:116`) calls `user.Rename()` when
`preferred_username` drifts and the `Guid` keys hold across it. Only two endpoints in the entire
API are name-addressed (`FriendsController.cs:45,61`); groups and circles are already
`{id:guid}`. And `MeResponse` (`RiderResponses.cs:8`) already returns the caller's `Id` — the
client simply never calls `/me`.

What is left is the wire and the clients. That half is bigger than the issue describes, for two
reasons.

### The live relay is a second wire

`/api/live` (`LiveController.cs:26`) is a WebSocket, one duplex socket per rider, and it carries
identity as the handle on five frames:

```
LiveFrames.cs:47    PeerPosition.User   (wire key "u" — several frames a minute per peer)
LiveFrames.cs:87    LeftFrame.User
LiveFrames.cs:109   DestinationOfferFrame.User
LiveFrames.cs:119   DestinationVoteFrame.User
LiveFrames.cs:133   PlaceEventFrame.User
```

The client keys three pieces of live state on that string — `ConvoyRelay.kt:247` (`_peers`),
`:250` (`_talking`), `:256` (`_spinVotes`) — which makes a convoy destination **commit** on a
set-of-handles quorum:

```kotlin
// ConvoyRelay.kt:1188
if (!votes.keys.containsAll(expected)) return null      // expected built at :946
```

### There is a live case-sensitivity defect underneath all of it

The server stores `Username` as `citext` (`UserConfiguration.cs:23-25`) but `SyncFromToken`
compares `OrdinalIgnoreCase` (`CurrentUser.cs:116`), so a case-only claim change never triggers
`Rename` and the stored casing drifts from the token's. Every client comparison is Kotlin
`String.==`, case-sensitive.

One IdP-side casing edit is then enough to make `isMe` false, stop a rider deleting their own
shared place (`CirclesScreen.kt:541`), stop the device posting circle fixes at all
(`CirclePresence.kt:228`), and leave that convoy quorum permanently unresolved — with
`editUsernameAllowed` still `false` and no attacker involved. Comparing on a `Guid` removes the
whole class rather than patching the comparison.

## Decisions taken

Five were put to the user, because each had a defensible alternative.

**Identity on the wire is `User.Id`, not `sub`.** The `{Guid Id, string Username}` shape already
exists in this codebase at `MeResponse` (`RiderResponses.cs:8-9`) and `AdminContracts.cs:11-12`,
so this is applying an existing convention rather than inventing one. `sub` was the cheaper
option — the client already decodes it locally (`Auth.subjectFrom`, used by `AccountScope`) so it
would need no `/me` call — and was rejected because it broadcasts every circle member's
identity-provider subject to every peer device. The cost of that choice is paid in
§"What this makes worse".

**Full breaking change, not an additive one.** `docs/BACKEND_SPEC.md` §15.5 promises "unknown is
ignored" and no coordination point with the app, precisely so self-hosters can update on their
own schedule. Nothing is deployed, so that promise is being spent deliberately to get the right
shape first time. §15.5 will say so rather than quietly contradicting itself.

**The relay ships in the same change.** Splitting HTTP from relay would mean two breaking
releases for one reason.

**`positions` carries the id only.** The display name is membership data and the client already
holds the member list. A `roster` frame was considered and rejected: it would create a *second*
id→name source alongside the member list, which is less normalised, not more.

**`FriendsResponse` becomes one list with an explicit relation**, not three lists of
`{id, username}`. Three arrays encode the relation positionally, so nothing type-checks that a
rider appears in exactly one, and `GroupMemberResponse:21` already solved the same problem with
an explicit `Status`.

## Design

### 1. The wire

One shared record for a rider reference, then applied across the social contracts:

```csharp
public record RiderRef(Guid Id, string Username);
```

| Contract | Now | After |
|---|---|---|
| `SocialContracts.cs:7-10` `FriendsResponse` | 3 × `IReadOnlyList<string>` | `IReadOnlyList<FriendEntry> Riders` |
| `SocialContracts.cs:49` `SharedRouteResponse.From` | `string` | `RiderRef` |
| `RiderResponses.cs:50` `FriendStatsResponse` | `string Username` | `RiderRef Rider` |
| `GroupContracts.cs:21` `GroupMemberResponse` | `string Username` | `+ Guid Id` |
| `GroupContracts.cs:42` `MemberPositionResponse` | `string Username` | `Guid Id`, name dropped |
| `GroupContracts.cs:66` `CirclePlaceResponse.Owner` | `string` | `Guid OwnerId` |
| `GroupContracts.cs:87` `PlaceEventResponse` | `string Username` | `Guid UserId` |

`FriendEntry` composes `RiderRef` and adds the relation the three arrays used to encode by
position — composed rather than flattened, so there is one definition of "how a rider appears on
the wire" and `FriendStatsResponse.Rider` is the same shape:

```csharp
public record FriendEntry(RiderRef Rider, string Relation);   // friend | incoming | outgoing
public record FriendsResponse(IReadOnlyList<FriendEntry> Riders);
```

`Relation` is a `SmartEnum` rendered through `WireNames.Wire()`, the same as `FriendshipStatus`
and `GroupMemberStatus`, so there is one lowercase vocabulary rather than two conventions. A
future `blocked` relation (#139) is then a new enum value, not a fourth array.

The last three rows drop the name entirely. Positions, places and events carry identity;
membership carries labels; one source each.

**Lookup inputs stay names.** `FriendRequestBody.Username`, `InviteBody.Username` and
`ShareRouteBody.To` are how riders find each other, and are resolved to an id at the boundary.
That is the issue's own Direction 1 and it is right — nobody types a Guid to add a friend.

### 2. Typed statuses

`IFriendshipService` returns `Task<Result<string>>` for `RequestAsync` and `RespondAsync`
(`FriendshipService.cs:15,17`), which is what permits this:

```csharp
// FriendshipService.cs:112
return Result.Ok("Declined");       // capitalised, and not a FriendshipStatus member
```

against `.Wire()` at `:74,84,116` producing `"pending"` / `"accepted"`, and against
`WireNames.cs:8-11`'s stated rule: *"One vocabulary, lowercase."* So the respond endpoint's
contract is today `"pending" | "accepted" | "Declined"`, and nothing notices because the client
does a bare `optString("status")` (`Social.kt:89`).

The service returns a typed value and the contract renders it, so a literal cannot compile.
Decline is the one case that is not a `FriendshipStatus` — it is the *absence* of a friendship,
not a state of one — so widening the enum would make "declined" queryable when no row exists.
Instead:

```csharp
public enum RespondOutcome { Accepted, Declined }

Task<Result<RespondOutcome>> RespondAsync(User caller, Guid targetId, bool accept, CancellationToken ct);
```

`FriendshipStatusResponse` renders it through `WireNames.Wire()`, giving `"accepted"` /
`"declined"` — both lowercase, which is the rule `"Declined"` broke. `RequestAsync` keeps
returning `FriendshipStatus`, since both of its outcomes genuinely are friendship states.

This stays deliberately separate from #139: `RespondOutcome.Declined` reports what just happened
to the caller, and persists nothing. If #139 lands, `Declined` becomes a `Relation` value in §1's
enum as well, and that is the change that makes it durable.

### 3. Endpoints

The only two name-addressed routes in the API:

```
POST   /api/friends/requests/{id:guid}/respond
DELETE /api/friends/{id:guid}
```

`RespondAsync`/`RemoveAsync` take a `Guid`, which deletes two of the three copy-pasted
`GetByUsernameAsync`-then-null-check preambles (`FriendshipService.cs:101-103,121-123`). The one
at `:63` stays — that is request-by-name, a lookup.

### 4. The relay

`PeerPosition.User` (`"u"`) and the four sibling frames carry the id. Canonical 36-character
Guid string rather than the 22-character base64url form: the difference is ~14 bytes per peer per
frame — at 15 peers and 4 frames a minute, ~13 KB/hour — against a hand-rolled codec needing to
agree across C# and Kotlin, and it stays readable in `bruno/`. `LiveFrames.cs`'s byte-starving
comment is an argument against waste, and this is not waste.

`LiveConnection.cs:134`'s in-batch dedupe becomes an id comparison:

```csharp
pending.RemoveAll(p => p.User == position.User);   // ordinal compare on a citext value today
```

which fixes a latent bug in passing — two positions differing only in stored casing do not
currently dedupe.

`ILiveRelay.PublishPlaceEvent(…, string username, …)` (`LiveRelay.cs:22-29`) takes a `Guid`;
its one caller (`CircleService.cs:238`) passes `caller.Id`.

Nothing changes in the relay's authority model: identity is still fixed at socket upgrade
(`LiveController.cs:65`) and inbound frames still carry no identity field at all, so a client
still cannot claim to be someone else.

### 5. shared/

A value class, so the compiler catches an id/handle swap rather than a reviewer:

```kotlin
@JvmInline value class RiderId(val value: String)
```

Models re-typed: `Groups.kt:7`, `CircleFixes.kt:10`, `CircleEvents.kt:21`, `CirclePlaces.kt:16`,
`Social.kt:55,60`, `RouteShare.kt:15`, and in `RelayProtocol.kt` `:36,47,95,106,109,125`.

Comparisons re-keyed: `CircleFixes.kt:78-79` (self-filter and duplicate collapse),
`CircleNotifyPolicy.kt:60`, `CirclePresence.kt:228`, `FriendsStore.kt:163,174`, and in
`ConvoyRelay.kt` the three collections at `:247,250,256` plus everything downstream —
`:897,904,906,917,946` and the quorum at `:1188`.

`FriendLists` is derived once here by partitioning `FriendsResponse.Riders` on `relation`, so
the screens keep the three-list shape they render without the wire encoding it positionally.

**Unknown-id resolution lives in the stores, not the relay.** `ConvoyRelay` stays a parser and
a state holder with no network of its own — it exposes peers keyed by `RiderId` and nothing else.
`CirclesStore` and `ConvoysStore` already own the member lists and already know how to reload
them, so the id→name lookup and the "this id is unknown, reload" trigger belong there, behind
the same `loaded`/refresh bookkeeping their other reloads use. A screen renders a peer with no
known name as a neutral placeholder for the one frame it takes to arrive.

Debounced: an unknown id must not turn a burst of position frames from a newly joined peer into
a burst of member-list requests. One refresh in flight at a time, and an id that is still unknown
after a completed refresh is not retried — it means the peer left the group between the frame and
the reload, which the relay's own TTL then expires.

### 6. The client's own id

The one genuine cost of choosing `User.Id` over `sub`: only the server knows it. So after token
exchange the client fetches `/me` and persists `auth_account_id` in the secure store beside
`auth_username` (`Settings.kt:194,310,438-443`), with the key added to
`CredentialMigration.kt:64`'s list.

Before that fetch lands — offline first run, or a failed `/me` — every `isMe` is false. That
fails closed: no delete affordance, no self-suppression, and a rider may briefly see their own
pin among the peers. Wrong in the harmless direction, and it clears on the next successful
`/me`.

`AccountScope` is untouched. Its `keyFrom(subject, username)` (`AccountScope.kt:77`) already
prefers `sub`, is about on-disk bucketing rather than peer identity, and its username fallback
is for a provider that issues no `sub` at all.

### 7. app/ and iosApp/

**Android**, beyond the five sites the issue cites: `PushToTalk.kt:128-129` (AudioTrack map
keyed on the speaker), `CandidatesCard.kt:113`, `FriendsScreen.kt:584`,
`PlaceNotifications.kt:66,97-98` (the handle is a dedupe salt inside a notification id),
`MapScreen.kt:669,1051,1058`, `ConvoyLiveClient.kt:208,216`, and Android Auto at
`CarMapRenderer.kt:144-148,390`.

`MapScreen.kt:1051` is `LaunchedEffect(accountUsername)` — an effect key change, so
`detour-compose-state-hazards` is read before that edit rather than after the regression.

**iOS**: `CirclesScreen.swift:372,379,464`, `FriendsScreen.swift:162`,
`MapScreen.swift:104-105,120,131,406-407`, `ConvoyLiveClient.swift:62,63,65,283,300-301`,
`ConvoyBar.swift:36,43`, and `ForEach(id: \.username)` moves off the handle in
`CirclesScreen.swift:378`, `FriendsScreen.swift:122,161,200`, `ConvoyBar.swift:36` and
`RoutesScreen.swift:292`.

Every display site keeps the username. It is just resolved from membership now, not from the
frame that carried it.

### 8. The leaderboard needs no id

`FriendsScreen.kt:333` is not really an identity problem. `FriendsState.own` is a separate typed
field — the code already knows which row is the rider's own — and both platforms discard that,
then recover it by string equality:

```kotlin
// FriendsScreen.kt:330-333
val ranked = (state.leaderboard + listOfNotNull(state.own))
    .sortedByDescending { it.stats.totalDistanceMeters }
ranked.forEachIndexed { i, friend ->
    LeaderboardRow(rank = i + 1, friend = friend, isMe = friend.username == username)
}
```

iOS is identical (`FriendsScreen.swift:149-153,162`), and its own doc comment says it mirrors
Android deliberately.

The merge carries the flag instead of dropping it — a `LeaderboardEntry(stats, isMe)` or
equivalent. That is correct with no id, no `/me` dependency, and right while offline, which is
strictly better than comparing ids here.

### 9. Docs

Both are cited by section number from code comments, so sections are appended, never renumbered
(CONTRIBUTING.md, "Documentation").

- `docs/BACKEND_SPEC.md` §6 (friends payloads), §9–§10 (group members, places, events), §11.2
  (relay frames), and §15.5 — `schema` → 2, plus an honest note that the no-coordination-point
  promise was spent once, deliberately, before launch.
- `docs/CIRCLES_AND_CONVOYS.md` §6.2–6.3, the relay's frame tables.
- `docker/prod/README.md:44` can now state that a rename is safe end-to-end, which is the real
  close of the caveat #25 documented.

## Tests

Roughly 60 assertions key on a handle as identity and move to an id.

**C#** — `SocialTests.cs:34-35,63,99,193`, `GroupTests.cs:38,156,281`,
`LiveEndpointTests.cs:136,166,168,217,224,343`, `LiveResilienceTests.cs:247,310,312`,
`AdminTests.cs:52`, plus the harness plumbing that keys on a handle:
`_tokens[username]` maps (`LiveEndpointTests.cs:399-416`, `LiveResilienceTests.cs:436-451`),
the `GroupOf` helpers, and `DetourApiFactory.cs:71-103`'s claim minting.

**Kotlin** — `GroupsTest.kt:40,68,83-92,109,150`, `CircleNotifyPolicyTest.kt` (all
`myUsername = "me"` cases), `CirclePresenceTest.kt:55-114`, `RelayProtocolTest.kt:137,162,201,248,257`,
`StoresTest.kt:30,418,428`, `AuthEpochTest.kt:92`, `CircleNotifyDeliveryOrderTest.kt:35,49,71`,
and `ConvoyRelayTest.kt`'s quorum block (`:379-392,416-447,511-532,611-665`) — the largest single
chunk, and the one that proves the vote resolves on ids.

**Untouched, deliberately** — `AccountScopeTest.kt` and `AuthUsernameFallbackTest.kt` are about
on-disk bucketing and carrying a handle across sessions, are already `sub`-keyed, and stay valid.
`CredentialMigrationTest.kt` gains the new secure-store key.

**New** — a test that a member whose stored casing differs from the token's is still recognised
as self, which is the defect in §"There is a live case-sensitivity defect" and is unrepresentable
once the comparison is on a `Guid`.

## Verification, and its limits

Per `detour-android-test-scope`, this repo runs plain JUnit4 over Android-free logic only, so
the shared partition, the relay parsing, the quorum and the policy filters are all directly
assertable. What is not: the Compose and SwiftUI edits, the `/me`-fetch ordering at sign-in, and
the unknown-id refresh path. Those are manual — `detour-gps-replay` drives a circle and a convoy
without driving, which covers the fix fan-out, the map markers and the vote round.

The Android Auto surface (`CarMapRenderer.kt`) needs a head unit or DHU and is verified by hand.

## What this makes worse, deliberately

**A `/me` round trip enters the sign-in path.** Choosing `User.Id` over `sub` means the client
cannot know its own identity from the token alone. One extra request, and an `isMe`-false window
before it lands.

**36 characters where 3–24 used to be, on the relay's hot path**, against a file whose comment
explicitly counts bytes there. Quantified in §4 and accepted; the 22-character form is the
fallback if it ever measures.

**A handle can no longer be read straight off a position frame.** Anything wanting a name now
needs the member list too. That is the correct normalisation, and it is also one more thing that
can be stale.

## Version

`versionName` `1.97.2` → **`2.0.0`** — wire protocol break, CONTRIBUTING.md's major row, and
there is no pre-launch exemption in it. `CapabilitiesResponse.SchemaVersion` 1 → 2, which is
exactly what that field reserves a bump for: an existing field changing meaning. `versionCode`
is untouched — CI stamps it.

## Commit order

```
1  feat(api)!:     contracts, typed statuses, id-addressed endpoints, relay frames + C# tests
2  feat(shared)!:  RiderId, models, partition, comparison sites + shared tests
3  feat(app)!:     Android + Android Auto sites, leaderboard merge carries isMe
4  feat(ios)!:     iOS sites, same merge fix
5  docs:           BACKEND_SPEC §6/§9-10/§11.2/§15.5, CIRCLES_AND_CONVOYS §6.2-6.3, prod README
6  chore:          versionName 2.0.0
```

Commits 2 and 4 both trip the iOS workflow (any change under `shared/` or `iosApp/`), so both
have to be green independently.

## Follow-ups this creates

- **#139** — declining a friend request deletes the row (`FriendshipService.cs:111`), so
  "declined" and "never asked" are indistinguishable and a requester can re-ask indefinitely.
  Filed from the same read, left out because it is a behaviour decision rather than a typing one.
  Its `blocked` state lands as a new `Relation` enum value in §1's contract.
- **A `roster` frame**, if the member-list refresh in §5 ever proves too coarse — the design
  rejected it as premature, not wrong.
- **The 22-character id encoding**, if the relay's byte budget ever measures as a problem.

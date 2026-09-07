using System.Text.Json;
using Detour.Api.Contracts;
using Detour.Api.Live;
using Detour.Api.Notifications;
using Detour.Domain;
using Detour.Domain.Circles;
using Detour.Domain.Friendships;
using Detour.Domain.Groups;
using Detour.Domain.Users;
using JV.ResultUtilities;
using Shared.Domain;

namespace Detour.Api.Services;

public interface ICircleService
{
    Task<Result> RecordPositionAsync(
        Guid callerId, Guid groupId, PositionBody body, CancellationToken cancellationToken);

    Task<Result<CircleFixesResponse>> GetPositionsAsync(
        Guid callerId, Guid groupId, CancellationToken cancellationToken);

    Task<Result> SharePlaceAsync(
        Guid callerId, Guid groupId, CirclePlacePayload place, CancellationToken cancellationToken);

    Task<Result<CirclePlacesResponse>> GetPlacesAsync(
        Guid callerId, Guid groupId, CancellationToken cancellationToken);

    Task<Result> DeletePlaceAsync(Guid callerId, Guid placeId, CancellationToken cancellationToken);

    Task<Result<PlaceEventResponse>> RecordEventAsync(
        User caller, Guid groupId, RecordEventBody body, CancellationToken cancellationToken);

    Task<Result<PlaceEventsResponse>> GetEventsAsync(
        Guid callerId, Guid groupId, long sinceMs, CancellationToken cancellationToken);
}

public class CircleService(
    IGroupService groupService,
    IMemberFixRepository memberFixes,
    ICirclePlaceRepository circlePlaces,
    IPlaceEventRepository placeEvents,
    IFriendshipRepository friendships,
    IUserRepository users,
    ILiveRelay liveRelay,
    IPushQueue pushQueue,
    IPostCommitActionScheduler postCommit) : ICircleService
{
    private static readonly JsonSerializerOptions PayloadOptions = new(JsonSerializerDefaults.Web);

    /// <summary>
    /// The low-cadence transport. Circles update on the order of minutes, which does not justify
    /// holding a stream open all day the way a convoy's second-by-second feed does.
    /// </summary>
    public async Task<Result> RecordPositionAsync(
        Guid callerId,
        Guid groupId,
        PositionBody body,
        CancellationToken cancellationToken)
    {
        var access = await groupService.RequireCircleMembershipAsync(callerId, groupId, cancellationToken);
        if (access.IsFailure)
            return Result.Error(access.ValidationMessages);

        // Server-side pause: read the membership fresh rather than trust that a client which
        // believes it stopped actually did. A stale build must not keep broadcasting.
        var membership = access.Value.FindMember(callerId)!;
        if (!membership.CanBroadcast)
            return Result.Ok();

        var timestamp = body.TimestampMs ?? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var existing = await memberFixes.GetForMemberAsync(groupId, callerId, cancellationToken);

        if (existing is not null)
        {
            // Overwritten in place. No history, no trail: a circle answers "where is everyone
            // now", not "where has everyone been".
            return existing.Update(body.Latitude, body.Longitude, body.AccuracyMeters, timestamp);
        }

        var (result, fix) = MemberFix.Create(
            groupId, callerId, body.Latitude, body.Longitude, body.AccuracyMeters, timestamp);
        if (result.IsFailure)
            return result;

        await memberFixes.SaveAsync(fix, cancellationToken);
        return Result.Ok();
    }

    public async Task<Result<CircleFixesResponse>> GetPositionsAsync(
        Guid callerId,
        Guid groupId,
        CancellationToken cancellationToken)
    {
        var access = await groupService.RequireCircleMembershipAsync(callerId, groupId, cancellationToken);
        if (access.IsFailure)
            return Result.Error(access.ValidationMessages);

        // Accepted and currently sharing only. A paused member is excluded here even though
        // their row still exists — the read-path half of the pause promise.
        var fixes = await memberFixes.GetSharingFixesAsync(groupId, cancellationToken);

        return new CircleFixesResponse(
        [
            .. fixes.Select(f => new MemberPositionResponse(
                f.UserId, f.Latitude, f.Longitude, f.AccuracyMeters, f.TimestampMs))
        ]);
    }

    public async Task<Result> SharePlaceAsync(
        Guid callerId,
        Guid groupId,
        CirclePlacePayload place,
        CancellationToken cancellationToken)
    {
        var access = await groupService.RequireCircleMembershipAsync(callerId, groupId, cancellationToken);
        if (access.IsFailure)
            return Result.Error(access.ValidationMessages);

        var document = JsonSerializer.Serialize(place, PayloadOptions);
        var existing = await circlePlaces.GetForOwnerPlaceAsync(groupId, callerId, place.Id, cancellationToken);

        if (existing is not null)
        {
            var replaced = existing.Replace(
                place.Name, place.RadiusMeters, document, place.Kind, place.Lat, place.Lon);
            if (replaced.IsFailure)
                return replaced;
        }
        else
        {
            var (created, circlePlace) = CirclePlace.Create(
                groupId, callerId, place.Id, place.Name, place.RadiusMeters, document,
                place.Kind, place.Lat, place.Lon);
            if (created.IsFailure)
                return created;

            await circlePlaces.SaveAsync(circlePlace, cancellationToken);
        }

        await circlePlaces.FlushChangesAsync(cancellationToken);

        // Write cap per (circle, owner), so one member cannot grow a circle's place list
        // without bound.
        var overflow = await circlePlaces.GetOverflowForOwnerAsync(
            groupId, callerId, DetourLimits.MaxCirclePlacesPerOwner, cancellationToken);

        foreach (var stale in overflow)
            circlePlaces.Delete(stale);

        return Result.Ok();
    }

    public async Task<Result<CirclePlacesResponse>> GetPlacesAsync(
        Guid callerId,
        Guid groupId,
        CancellationToken cancellationToken)
    {
        var access = await groupService.RequireCircleMembershipAsync(callerId, groupId, cancellationToken);
        if (access.IsFailure)
            return Result.Error(access.ValidationMessages);

        var rows = await circlePlaces.GetForGroupAsync(groupId, cancellationToken);
        if (rows.Count == 0)
            return new CirclePlacesResponse([]);

        // Owner usernames only for the home rows that will be masked — the caller is not the
        // owner and not family — so the label can read "{username}'s home".
        var maskedOwners = rows.Where(p => p.IsHome && p.OwnerId != callerId)
            .Select(p => p.OwnerId).Distinct().ToArray();
        var ownerNames = maskedOwners.Length == 0
            ? new Dictionary<Guid, string>()
            : (await users.GetManyAsync(maskedOwners, cancellationToken))
                .ToDictionary(u => u.Id, u => u.Username);

        var responses = new List<CirclePlaceResponse>(rows.Count);
        foreach (var p in rows)
        {
            // Only a home is ever masked, and only for a member who is neither the owner nor
            // family. The family lookup is skipped for every other row, so the common path is
            // one query per circle, not one per place.
            var coordsAllowed = !p.IsHome
                || p.OwnerId == callerId
                || await friendships.AreFamilyAsync(callerId, p.OwnerId, cancellationToken);
            responses.Add(CirclePlaceView(p, coordsAllowed, ownerNames.GetValueOrDefault(p.OwnerId, "")));
        }

        return new CirclePlacesResponse(responses);
    }

    /// <summary>
    /// The one place a shared place becomes a response, and the one place the home rule is
    /// applied — so a new endpoint returning a place cannot leak a home's coordinates by
    /// forgetting to mask. Default is to mask: a home discloses its coordinates only when
    /// [coordsAllowed] is explicitly true (the owner, or a family member).
    ///
    /// A non-home place is returned exactly as it was stored — byte-for-byte the payload the
    /// owner shared — because the rule touches homes only. A home is rebuilt from the promoted
    /// columns rather than the payload, so a withheld coordinate is one the response never
    /// held, not one a client is trusted to hide.
    /// </summary>
    private static CirclePlaceResponse CirclePlaceView(CirclePlace p, bool coordsAllowed, string ownerUsername)
    {
        if (!p.IsHome)
        {
            return new CirclePlaceResponse(
                p.Id, p.OwnerId, p.Name, p.RadiusMeters,
                p.CreatedAt.ToUnixTimeMilliseconds(),
                JsonSerializer.Deserialize<JsonElement>(p.Payload));
        }

        var name = HomeDisplayName(coordsAllowed, ownerUsername);
        var place = coordsAllowed && p is { Lat: not null, Lon: not null }
            ? JsonSerializer.SerializeToElement(
                new { id = p.ClientPlaceId, name, radiusMeters = p.RadiusMeters, lat = p.Lat, lon = p.Lon },
                PayloadOptions)
            : JsonSerializer.SerializeToElement(
                new { id = p.ClientPlaceId, name, radiusMeters = p.RadiusMeters },
                PayloadOptions);

        return new CirclePlaceResponse(
            p.Id, p.OwnerId, name, p.RadiusMeters, p.CreatedAt.ToUnixTimeMilliseconds(), place);
    }

    /// <summary>The name a home place shows: "Home" to the owner and family, "{username}'s home"
    /// to any other circle member. The single wording the place list, the events feed and the
    /// arrival fan-out all share, so the masked and open forms never drift apart.</summary>
    private static string HomeDisplayName(bool open, string ownerUsername) =>
        open ? "Home" : $"{ownerUsername}'s home";

    public async Task<Result> DeletePlaceAsync(Guid callerId, Guid placeId, CancellationToken cancellationToken)
    {
        var place = await circlePlaces.GetAsync(placeId, cancellationToken);

        // Only the owner. A caller who is not gets the same answer as one asking about a place
        // that does not exist.
        if (place is null || place.OwnerId != callerId)
            return Result.Error(ValidationKeys.CirclePlace.NotFound, placeId);

        circlePlaces.Delete(place);
        return Result.Ok();
    }

    /// <summary>
    /// Records a transition the device detected. Geofences are evaluated on-device; this
    /// backend never evaluates one, and a client cannot cause a fan-out by claiming a
    /// transition happened to somebody else — the event is always attributed to the caller.
    /// </summary>
    public async Task<Result<PlaceEventResponse>> RecordEventAsync(
        User caller,
        Guid groupId,
        RecordEventBody body,
        CancellationToken cancellationToken)
    {
        var access = await groupService.RequireCircleMembershipAsync(caller.Id, groupId, cancellationToken);
        if (access.IsFailure)
            return Result.Error(access.ValidationMessages);

        if (!PlaceEventKind.TryFromName(body.Kind, ignoreCase: true, out var kind))
            return Result.Error(ValidationKeys.PlaceEvent.KindInvalid);

        var (result, placeEvent) = PlaceEvent.Create(
            groupId, caller.Id, body.PlaceId, kind, body.TimestampMs);
        if (result.IsFailure)
            return result;

        await placeEvents.SaveAsync(placeEvent, cancellationToken);
        await placeEvents.FlushChangesAsync(cancellationToken);

        // Newest-N retention per circle, so one chatty member cannot grow the feed without
        // bound.
        var overflow = await placeEvents.GetOverflowAsync(
            groupId, DetourLimits.MaxPlaceEventsPerGroup, cancellationToken);

        foreach (var stale in overflow)
            placeEvents.Delete(stale);

        // An arrival at the caller's own home is the same disclosure as sharing it: the name
        // "Home" would tell a non-family member which shared place is a home and, over repeats,
        // where it is. So a home arrival is masked to non-family exactly as the place list is —
        // this is the arrival-event half of the rule, not a second one.
        var ownPlace = await circlePlaces.GetForOwnerPlaceAsync(
            groupId, caller.Id, body.PlaceId, cancellationToken);
        var isHome = ownPlace?.IsHome == true;

        // The name the owner and any family recipient may see; the masked name is what everyone
        // else in the circle gets for a home. A non-home resolves its name the way it always did.
        var openName = isHome
            ? HomeDisplayName(open: true, caller.Username)
            : await circlePlaces.ResolveNameAsync(groupId, body.PlaceId, cancellationToken) ?? string.Empty;
        var maskedName = isHome ? HomeDisplayName(open: false, caller.Username) : openName;

        // Fanned out only once the row is durable, so a peer that reacts to the frame by
        // re-reading the feed can never find nothing there. Scheduling it post-commit also means
        // a transaction that later rolls back never announces an arrival that did not happen.
        var recipients = access.Value.Members
            .Where(member => member.IsAccepted && member.UserId != caller.Id)
            .Select(member => member.UserId)
            .ToArray();

        // For a home, split recipients so family get "Home" and everyone else the masked label;
        // for any other place there is nothing to split and all recipients get the open name.
        var familyRecipients = recipients;
        var maskedRecipients = Array.Empty<Guid>();
        if (isHome && recipients.Length > 0)
        {
            var family = new List<Guid>();
            var masked = new List<Guid>();
            foreach (var recipient in recipients)
            {
                if (await friendships.AreFamilyAsync(caller.Id, recipient, cancellationToken))
                    family.Add(recipient);
                else
                    masked.Add(recipient);
            }

            familyRecipients = [.. family];
            maskedRecipients = [.. masked];
        }

        postCommit.Schedule(() =>
        {
            if (familyRecipients.Length > 0)
                liveRelay.PublishPlaceEvent(
                    familyRecipients, groupId, caller.Id, placeEvent.ClientPlaceId,
                    openName, placeEvent.Kind.Wire(), placeEvent.TimestampMs);

            if (maskedRecipients.Length > 0)
                liveRelay.PublishPlaceEvent(
                    maskedRecipients, groupId, caller.Id, placeEvent.ClientPlaceId,
                    maskedName, placeEvent.Kind.Wire(), placeEvent.TimestampMs);

            // Everyone entitled to the event who was not already sent the live frame —
            // i.e. not holding a socket right now. A dead socket the relay has not yet
            // noticed just means a redundant wake-ping, which the device dedupes on
            // lastSeenEventTsMs. Content-free: the token is the whole message.
            var connected = liveRelay.ConnectedUserIds;
            var offline = recipients.Where(id => !connected.Contains(id)).ToArray();
            if (offline.Length > 0)
                pushQueue.TryEnqueue(new PushJob(offline, groupId.ToString()));

            return Task.CompletedTask;
        });

        // The caller is the owner, so their own arrival always reads openName.
        return new PlaceEventResponse(
            placeEvent.Id,
            placeEvent.ClientPlaceId,
            openName,
            caller.Id,
            placeEvent.Kind.Wire(),
            placeEvent.TimestampMs);
    }

    public async Task<Result<PlaceEventsResponse>> GetEventsAsync(
        Guid callerId,
        Guid groupId,
        long sinceMs,
        CancellationToken cancellationToken)
    {
        var access = await groupService.RequireCircleMembershipAsync(callerId, groupId, cancellationToken);
        if (access.IsFailure)
            return Result.Error(access.ValidationMessages);

        // Includes the caller's own arrivals. That is a requirement of the feed, not an
        // oversight — a rider's own timeline is part of what a circle shows.
        var rows = await placeEvents.GetSinceAsync(groupId, sinceMs, cancellationToken);

        // The events feed resolves each event's place name, so a home arrival would name the
        // home to every member unless it is masked here too — the same rule the place list
        // applies, by the same wording (#270).
        var maskedOwners = rows
            .Where(e => e.PlaceKind == CirclePlace.HomeKind && e.PlaceOwnerId != callerId)
            .Select(e => e.PlaceOwnerId).Distinct().ToArray();
        var ownerNames = maskedOwners.Length == 0
            ? new Dictionary<Guid, string>()
            : (await users.GetManyAsync(maskedOwners, cancellationToken))
                .ToDictionary(u => u.Id, u => u.Username);

        var events = new List<PlaceEventResponse>(rows.Count);
        foreach (var e in rows)
        {
            var name = e.PlaceName;
            if (e.PlaceKind == CirclePlace.HomeKind)
            {
                var open = e.PlaceOwnerId == callerId
                    || await friendships.AreFamilyAsync(callerId, e.PlaceOwnerId, cancellationToken);
                name = HomeDisplayName(open, ownerNames.GetValueOrDefault(e.PlaceOwnerId, ""));
            }

            events.Add(new PlaceEventResponse(e.Id, e.ClientPlaceId, name, e.UserId, e.Kind.Wire(), e.TimestampMs));
        }

        return new PlaceEventsResponse(events);
    }
}

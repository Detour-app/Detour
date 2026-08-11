using System.Text.Json;
using Detour.Api.Contracts;
using Detour.Domain;
using Detour.Domain.Circles;
using Detour.Domain.Groups;
using Detour.Domain.Users;
using JV.ResultUtilities;

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
    IUserRepository users) : ICircleService
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
                f.Username, f.Latitude, f.Longitude, f.AccuracyMeters, f.TimestampMs))
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
            var replaced = existing.Replace(place.Name, place.RadiusMeters, document);
            if (replaced.IsFailure)
                return replaced;
        }
        else
        {
            var (created, circlePlace) = CirclePlace.Create(
                groupId, callerId, place.Id, place.Name, place.RadiusMeters, document);
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

        var owners = (await users.GetManyAsync([.. rows.Select(p => p.OwnerId).Distinct()], cancellationToken))
            .ToDictionary(u => u.Id, u => u.Username);

        return new CirclePlacesResponse(
        [
            .. rows.Select(p => new CirclePlaceResponse(
                p.Id,
                owners.GetValueOrDefault(p.OwnerId, string.Empty),
                p.Name,
                p.RadiusMeters,
                p.CreatedAt.ToUnixTimeMilliseconds(),
                JsonSerializer.Deserialize<JsonElement>(p.Payload)))
        ]);
    }

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

        var placeName = await circlePlaces.ResolveNameAsync(groupId, body.PlaceId, cancellationToken);

        return new PlaceEventResponse(
            placeEvent.Id,
            placeEvent.ClientPlaceId,
            placeName ?? string.Empty,
            caller.Username,
            placeEvent.Kind.Name,
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

        return new PlaceEventsResponse(
        [
            .. rows.Select(e => new PlaceEventResponse(
                e.Id, e.ClientPlaceId, e.PlaceName, e.Username, e.Kind, e.TimestampMs))
        ]);
    }
}

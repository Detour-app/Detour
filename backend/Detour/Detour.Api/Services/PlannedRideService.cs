using System.Text.Json;
using Detour.Api.Contracts;
using Detour.Domain;
using Detour.Domain.Friendships;
using Detour.Domain.Groups;
using Detour.Domain.PlannedRides;
using Detour.Domain.Users;
using JV.ResultUtilities;

namespace Detour.Api.Services;

public interface IPlannedRideService
{
    Task<Result<PlannedRideResponse>> CreateAsync(
        User caller, PlannedRideBody body, CancellationToken cancellationToken);

    Task<PlannedRidesResponse> ListAsync(Guid callerId, CancellationToken cancellationToken);

    Task<Result<PlannedRideResponse>> GetAsync(Guid callerId, Guid rideId, CancellationToken cancellationToken);

    Task<Result<PlannedRideResponse>> UpdateAsync(
        Guid callerId, Guid rideId, PlannedRideBody body, CancellationToken cancellationToken);

    Task<Result> DeleteAsync(Guid callerId, Guid rideId, CancellationToken cancellationToken);

    /// <summary>Owner only. Every invitee must be an accepted friend of the owner, or an
    /// accepted member of a circle the owner is an accepted member of.</summary>
    Task<Result<PlannedRideResponse>> InviteAsync(
        Guid callerId, Guid rideId, PlannedRideInviteBody body, CancellationToken cancellationToken);

    Task<Result<string>> AnswerAsync(
        Guid callerId, Guid rideId, string answer, CancellationToken cancellationToken);
}

public class PlannedRideService(
    IPlannedRideRepository rides,
    IFriendshipRepository friendships,
    IGroupRepository groups,
    IUserRepository users) : IPlannedRideService
{
    private static readonly JsonSerializerOptions PayloadOptions = new(JsonSerializerDefaults.Web);

    public async Task<Result<PlannedRideResponse>> CreateAsync(
        User caller,
        PlannedRideBody body,
        CancellationToken cancellationToken)
    {
        var (result, ride) = PlannedRide.Create(caller.Id, ToDetails(body));
        if (result.IsFailure)
            return result;

        await rides.SaveAsync(ride, cancellationToken);
        return await MapAsync(ride, caller.Id, cancellationToken);
    }

    public async Task<PlannedRidesResponse> ListAsync(Guid callerId, CancellationToken cancellationToken)
    {
        var rows = await rides.GetForUserAsync(callerId, cancellationToken);
        if (rows.Count == 0)
            return new PlannedRidesResponse([]);

        var names = await ResolveUsernamesAsync(rows, cancellationToken);
        return new PlannedRidesResponse([.. rows.Select(r => Map(r, callerId, names))]);
    }

    public async Task<Result<PlannedRideResponse>> GetAsync(
        Guid callerId,
        Guid rideId,
        CancellationToken cancellationToken)
    {
        var ride = await rides.GetWithRsvpsAsync(rideId, cancellationToken);
        if (ride is null || !ride.IsVisibleTo(callerId))
            return Result.Error(ValidationKeys.PlannedRide.NotFound);

        return await MapAsync(ride, callerId, cancellationToken);
    }

    public async Task<Result<PlannedRideResponse>> UpdateAsync(
        Guid callerId,
        Guid rideId,
        PlannedRideBody body,
        CancellationToken cancellationToken)
    {
        var (owned, ride) = await RequireOwnerAsync(callerId, rideId, cancellationToken);
        if (owned.IsFailure)
            return owned;

        var updated = ride.Update(ToDetails(body));
        if (updated.IsFailure)
            return updated;

        return await MapAsync(ride, callerId, cancellationToken);
    }

    public async Task<Result> DeleteAsync(Guid callerId, Guid rideId, CancellationToken cancellationToken)
    {
        var (owned, ride) = await RequireOwnerAsync(callerId, rideId, cancellationToken);
        if (owned.IsFailure)
            return owned;

        rides.Delete(ride);
        return Result.Ok();
    }

    public async Task<Result<PlannedRideResponse>> InviteAsync(
        Guid callerId,
        Guid rideId,
        PlannedRideInviteBody body,
        CancellationToken cancellationToken)
    {
        var (owned, ride) = await RequireOwnerAsync(callerId, rideId, cancellationToken);
        if (owned.IsFailure)
            return owned;

        // Every circle the owner is an accepted member of, read once: it answers both "may this
        // circle be invited" and "is this rider a circle member of the owner".
        var ownerCircles = (await groups.GetForUserAsync(callerId, GroupKind.Circle, cancellationToken))
            .Where(c => c.IsAcceptedMember(callerId))
            .ToDictionary(c => c.Id);

        HashSet<Guid> invitees = [];
        foreach (var circleId in body.CircleIds ?? [])
        {
            // A circle the owner is not in answers the same way whether or not it exists.
            if (!ownerCircles.TryGetValue(circleId, out var circle))
                return Result.Error(ValidationKeys.Group.NotAMember);

            invitees.UnionWith(circle.Members.Where(m => m.IsAccepted).Select(m => m.UserId));
        }

        var riderIds = body.RiderIds ?? [];
        if (riderIds.Count > 0)
        {
            // Re-checked on every invite, not once at some earlier point: an invitation must not
            // reach someone unfriended, or who left the circle, a moment ago.
            HashSet<Guid> allowed =
            [
                .. await friendships.GetAcceptedFriendIdsAsync(callerId, cancellationToken),
                .. ownerCircles.Values.SelectMany(c => c.Members.Where(m => m.IsAccepted).Select(m => m.UserId)),
            ];

            // All-or-nothing: one rider the owner may not invite refuses the whole request,
            // rather than inviting some and leaving the client to work out which.
            if (riderIds.Any(id => id != callerId && !allowed.Contains(id)))
                return Result.Error(ValidationKeys.PlannedRide.InviteeNotAFriendOrCircleMember);

            invitees.UnionWith(riderIds);
        }

        foreach (var inviteeId in invitees)
            ride.Invite(inviteeId);

        return await MapAsync(ride, callerId, cancellationToken);
    }

    public async Task<Result<string>> AnswerAsync(
        Guid callerId,
        Guid rideId,
        string answer,
        CancellationToken cancellationToken)
    {
        var ride = await rides.GetWithRsvpsAsync(rideId, cancellationToken);
        if (ride is null || !ride.IsVisibleTo(callerId))
            return Result.Error(ValidationKeys.PlannedRide.NotFound);

        // The owner can see the ride but holds no invitation to answer.
        var rsvp = ride.FindRsvp(callerId);
        if (rsvp is null)
            return Result.Error(ValidationKeys.PlannedRide.NotInvited);

        var parsed = RsvpAnswer.TryParseAnswer(answer);
        if (parsed is null)
            return Result.Error(ValidationKeys.PlannedRide.AnswerInvalid);

        var result = rsvp.AnswerWith(parsed);
        return result.IsFailure ? result : Result.Ok(rsvp.Answer.Wire());
    }

    /// <summary>
    /// The gate every owner-only action shares. A rider who cannot see the ride gets
    /// <c>NotFound</c>, the same as for an id that does not exist; an invitee, who can already
    /// see it, gets <c>NotTheOwner</c>.
    /// </summary>
    private async Task<Result<PlannedRide>> RequireOwnerAsync(
        Guid callerId,
        Guid rideId,
        CancellationToken cancellationToken)
    {
        var ride = await rides.GetWithRsvpsAsync(rideId, cancellationToken);
        if (ride is null || !ride.IsVisibleTo(callerId))
            return Result.Error(ValidationKeys.PlannedRide.NotFound);

        return ride.IsOwner(callerId) ? ride : Result.Error(ValidationKeys.PlannedRide.NotTheOwner);
    }

    private static PlannedRideDetails ToDetails(PlannedRideBody body) =>
        new(
            body.Title,
            DateTimeOffset.FromUnixTimeMilliseconds(body.StartsAtMs),
            body.MeetingPoint.Latitude,
            body.MeetingPoint.Longitude,
            body.MeetingPoint.Label,
            body.RouteId,
            body.Description,
            body.Route is { } route
                ? new PlannedRideRoute(JsonSerializer.Serialize(route, PayloadOptions), route.Stops?.Count ?? 0)
                : null);

    private async Task<PlannedRideResponse> MapAsync(
        PlannedRide ride,
        Guid callerId,
        CancellationToken cancellationToken) =>
        Map(ride, callerId, await ResolveUsernamesAsync([ride], cancellationToken));

    private static PlannedRideResponse Map(PlannedRide ride, Guid callerId, Dictionary<Guid, string> names) =>
        new(
            ride.Id,
            new RiderRef(ride.OwnerId, names.GetValueOrDefault(ride.OwnerId, string.Empty)),
            ride.Title,
            ride.StartsAt.ToUnixTimeMilliseconds(),
            new MeetingPointResponse(ride.MeetingLatitude, ride.MeetingLongitude, ride.MeetingLabel),
            ride.ClientRouteId,
            ride.Description,
            ride.CreatedAt.ToUnixTimeMilliseconds(),
            ride.RoutePayload is { } payload ? JsonSerializer.Deserialize<JsonElement>(payload) : null,
            ride.FindRsvp(callerId)?.Answer.Wire(),
            [
                .. ride.Rsvps
                    .OrderBy(r => r.InvitedAt)
                    .ThenBy(r => r.Id)
                    .Select(r => new PlannedRideInviteeResponse(
                        new RiderRef(r.UserId, names.GetValueOrDefault(r.UserId, string.Empty)),
                        r.Answer.Wire()))
            ]);

    private async Task<Dictionary<Guid, string>> ResolveUsernamesAsync(
        IEnumerable<PlannedRide> rows,
        CancellationToken cancellationToken)
    {
        var ids = rows.SelectMany(r => r.Rsvps.Select(v => v.UserId).Append(r.OwnerId)).Distinct().ToArray();
        var found = await users.GetManyAsync(ids, cancellationToken);
        return found.ToDictionary(u => u.Id, u => u.Username);
    }
}

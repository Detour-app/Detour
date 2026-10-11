using JV.ResultUtilities;
using Shared.Database;
using Shared.Domain;

namespace Detour.Domain.PlannedRides;

/// <summary>
/// A ride one rider plans ahead: when, where to meet, and who is asked along.
///
/// The invitation and the answer are one row per invitee (<see cref="PlannedRideRsvp"/>), so
/// "who was asked" and "who is coming" cannot drift apart. Being invited is the only thing that
/// makes a ride visible to anyone but its owner; an id the caller holds no row for answers the
/// same way as one that does not exist.
///
/// There is deliberately no time-window rule here (owner decision on #639): the owner may start
/// the ride whenever they like, and the start time is what the invitees are told, not a gate.
/// </summary>
public sealed class PlannedRide : Entity
{
    private readonly List<PlannedRideRsvp> _rsvps = [];

    public Guid OwnerId { get; private set; }

    public string Title { get; private set; }

    public DateTimeOffset StartsAt { get; private set; }

    public double MeetingLatitude { get; private set; }

    public double MeetingLongitude { get; private set; }

    public string? MeetingLabel { get; private set; }

    /// <summary>The owner's own identifier for a saved route. Meaningless outside their device.</summary>
    public long? ClientRouteId { get; private set; }

    public string? Description { get; private set; }

    public DateTimeOffset CreatedAt { get; private set; }

    public IReadOnlyCollection<PlannedRideRsvp> Rsvps => _rsvps;

    private PlannedRide(Guid ownerId, PlannedRideDetails details)
    {
        OwnerId = ownerId;
        Title = string.Empty;
        CreatedAt = DateTimeOffset.UtcNow;
        Apply(details);
    }

    public static Result<PlannedRide> Create(Guid ownerId, PlannedRideDetails details)
    {
        var validation = Validate(details);
        if (validation.IsFailure)
            return validation;

        return new PlannedRide(ownerId, details);
    }

    public Result Update(PlannedRideDetails details)
    {
        var validation = Validate(details);
        if (validation.IsFailure)
            return validation;

        Apply(details);
        return Result.Ok();
    }

    public bool IsOwner(Guid userId) => OwnerId == userId;

    /// <summary>The owner and every invitee, whatever they answered. Nobody else.</summary>
    public bool IsVisibleTo(Guid userId) => IsOwner(userId) || FindRsvp(userId) is not null;

    public PlannedRideRsvp? FindRsvp(Guid userId) => _rsvps.FirstOrDefault(r => r.UserId == userId);

    /// <summary>
    /// Adds an invitation. The caller has already checked that the invitee is a friend or a
    /// circle member of the owner — relationships this aggregate cannot see. Inviting someone
    /// already asked keeps their answer rather than resetting it to pending.
    /// </summary>
    public PlannedRideRsvp? Invite(Guid inviteeId)
    {
        if (IsOwner(inviteeId))
            return null;

        var existing = FindRsvp(inviteeId);
        if (existing is not null)
            return existing;

        var rsvp = PlannedRideRsvp.CreateInvited(Id, inviteeId);
        _rsvps.Add(rsvp);
        return rsvp;
    }

    private void Apply(PlannedRideDetails details)
    {
        Title = details.Title.Trim();
        StartsAt = details.StartsAt;
        MeetingLatitude = details.MeetingLatitude;
        MeetingLongitude = details.MeetingLongitude;
        MeetingLabel = NormalizeOptional(details.MeetingLabel);
        ClientRouteId = details.ClientRouteId;
        Description = NormalizeOptional(details.Description);
    }

    private static Result Validate(PlannedRideDetails details)
    {
        if (string.IsNullOrWhiteSpace(details.Title))
            return Result.Error(ValidationKeys.PlannedRide.TitleRequired);

        if (details.Title.Trim().Length > DetourLimits.PlannedRideTitleMaxLength)
            return Result.Error(ValidationKeys.PlannedRide.TitleTooLong, DetourLimits.PlannedRideTitleMaxLength);

        if (!GeoPoint.IsValid(details.MeetingLatitude, details.MeetingLongitude))
            return Result.Error(ValidationKeys.Location.CoordinatesOutOfRange);

        if (details.MeetingLabel?.Trim().Length > DetourLimits.LabelMaxLength)
            return Result.Error(ValidationKeys.PlannedRide.MeetingLabelTooLong, DetourLimits.LabelMaxLength);

        if (details.Description?.Trim().Length > DetourLimits.PlannedRideDescriptionMaxLength)
            return Result.Error(
                ValidationKeys.PlannedRide.DescriptionTooLong, DetourLimits.PlannedRideDescriptionMaxLength);

        return details.ClientRouteId == 0
            ? Result.Error(ValidationKeys.PlannedRide.RouteIdInvalid)
            : Result.Ok();
    }

    private static string? NormalizeOptional(string? value)
    {
        var trimmed = value?.Trim();
        return string.IsNullOrEmpty(trimmed) ? null : trimmed;
    }

    // EF materialisation.
    private PlannedRide()
    {
        Title = string.Empty;
    }
}

/// <summary>Everything the owner sets on a ride, on create and on every edit.</summary>
public sealed record PlannedRideDetails(
    string Title,
    DateTimeOffset StartsAt,
    double MeetingLatitude,
    double MeetingLongitude,
    string? MeetingLabel,
    long? ClientRouteId,
    string? Description);

public interface IPlannedRideRepository : IBaseRepository<PlannedRide>
{
    /// <summary>The ride with its RSVP rows loaded — the aggregate every mutation needs.</summary>
    Task<PlannedRide?> GetWithRsvpsAsync(Guid rideId, CancellationToken cancellationToken);

    /// <summary>Rides this rider owns or is invited to, RSVPs included, soonest start first.</summary>
    Task<List<PlannedRide>> GetForUserAsync(Guid userId, CancellationToken cancellationToken);

    /// <summary>
    /// Both directions — unfriending removes the invitation either side holds to the other's
    /// rides, the same rule shared routes follow.
    /// </summary>
    Task<int> DeleteRsvpsBetweenAsync(Guid a, Guid b, CancellationToken cancellationToken);
}

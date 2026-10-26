using System.Text;
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

    /// <summary>
    /// The route itself, as the owner's client encoded it (#757): opaque here like a shared
    /// route's payload, and what every invitee reads — <see cref="ClientRouteId"/> means nothing
    /// on their device, and a circle invitee who is not the owner's friend could not be sent a
    /// share. Null for a ride with no route.
    /// </summary>
    public string? RoutePayload { get; private set; }

    public string? Description { get; private set; }

    public DateTimeOffset CreatedAt { get; private set; }

    /// <summary>
    /// The <see cref="StartsAt"/> the last reminder went out for, or null if none has. Keyed on
    /// the start time rather than a flag, so rescheduling moves the reminder with no reset step:
    /// a new start time simply has not been reminded of yet.
    /// </summary>
    public DateTimeOffset? RemindedForStartsAt { get; private set; }

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

    /// <summary>
    /// Whether the reminder for the current start time is due at <paramref name="now"/>: the
    /// start is within <paramref name="lead"/> and still ahead, and this start time has not been
    /// reminded of. A ride created or moved inside the window is due at once; one already
    /// started is never reminded.
    /// </summary>
    public bool IsReminderDue(DateTimeOffset now, TimeSpan lead) =>
        StartsAt > now && StartsAt - lead <= now && RemindedForStartsAt != StartsAt;

    /// <summary>
    /// Records the reminder for the current start time as sent and returns who it goes to:
    /// every invitee who answered going or maybe. Pending and declined get none, and an
    /// invitation that ended with a friendship is already gone from <see cref="Rsvps"/>.
    /// </summary>
    public IReadOnlyCollection<Guid> MarkReminded()
    {
        RemindedForStartsAt = StartsAt;
        return
        [
            .. _rsvps
                .Where(r => r.Answer == RsvpAnswer.Going || r.Answer == RsvpAnswer.Maybe)
                .Select(r => r.UserId)
        ];
    }

    private void Apply(PlannedRideDetails details)
    {
        Title = details.Title.Trim();
        StartsAt = details.StartsAt;
        MeetingLatitude = details.MeetingLatitude;
        MeetingLongitude = details.MeetingLongitude;
        MeetingLabel = NormalizeOptional(details.MeetingLabel);
        ClientRouteId = details.ClientRouteId;
        RoutePayload = details.Route?.Payload;
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

        if (details.ClientRouteId == 0)
            return Result.Error(ValidationKeys.PlannedRide.RouteIdInvalid);

        return details.Route is { } route ? ValidateRoute(route) : Result.Ok();
    }

    /// <summary>The same floor and cap a shared route has: it is the same payload.</summary>
    private static Result ValidateRoute(PlannedRideRoute route)
    {
        if (route.StopCount < DetourLimits.MinRouteStops)
            return Result.Error(ValidationKeys.PlannedRide.RouteNotEnoughStops, DetourLimits.MinRouteStops);

        return Encoding.UTF8.GetByteCount(route.Payload) > DetourLimits.MaxRoutePayloadBytes
            ? Result.Error(ValidationKeys.PlannedRide.RoutePayloadTooLarge, DetourLimits.MaxRoutePayloadBytes)
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

/// <summary>
/// Everything the owner sets on a ride, on create and on every edit. A null
/// <paramref name="Route"/> on an edit detaches the route, the same as every other field.
/// </summary>
public sealed record PlannedRideDetails(
    string Title,
    DateTimeOffset StartsAt,
    double MeetingLatitude,
    double MeetingLongitude,
    string? MeetingLabel,
    long? ClientRouteId,
    string? Description,
    PlannedRideRoute? Route = null);

/// <summary>An attached route's stored document, and the stop count read from it before storing.</summary>
public sealed record PlannedRideRoute(string Payload, int StopCount);

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

    /// <summary>
    /// Tracked rides, RSVPs included, whose reminder is due at <paramref name="now"/> — the
    /// query form of <see cref="PlannedRide.IsReminderDue"/>.
    /// </summary>
    Task<List<PlannedRide>> GetDueForReminderAsync(
        DateTimeOffset now, TimeSpan lead, CancellationToken cancellationToken);
}

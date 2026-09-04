using Ardalis.SmartEnum;
using JV.ResultUtilities;
using Shared.Database;
using Shared.Domain;

namespace Detour.Domain.Circles;

public sealed class PlaceEventKind : SmartEnum<PlaceEventKind>
{
    public static readonly PlaceEventKind Arrive = new("Arrive", 1);
    public static readonly PlaceEventKind Depart = new("Depart", 2);

    private PlaceEventKind(string name, int value) : base(name, value) { }
}

/// <summary>
/// A record that a member entered or left a circle place.
///
/// Geofence transitions are decided on the device. This backend records the result and fans it
/// out — it never evaluates a geofence itself, and a client cannot cause a fan-out by claiming
/// one happened to someone else.
/// </summary>
public sealed class PlaceEvent : Entity
{
    public Guid GroupId { get; private set; }

    public Guid UserId { get; private set; }

    /// <summary>The owner-assigned place id, not a <c>CirclePlace</c> row id. See that type.</summary>
    public long ClientPlaceId { get; private set; }

    public PlaceEventKind Kind { get; private set; }

    /// <summary>Unix milliseconds, as reported by the device that detected the transition.</summary>
    public long TimestampMs { get; private set; }

    private PlaceEvent(Guid groupId, Guid userId, long clientPlaceId, PlaceEventKind kind, long timestampMs)
    {
        GroupId = groupId;
        UserId = userId;
        ClientPlaceId = clientPlaceId;
        Kind = kind;
        TimestampMs = timestampMs;
    }

    public static Result<PlaceEvent> Create(
        Guid groupId,
        Guid userId,
        long clientPlaceId,
        PlaceEventKind? kind,
        long? timestampMs)
    {
        if (clientPlaceId == 0)
            return Result.Error(ValidationKeys.CirclePlace.PlaceIdRequired);

        if (kind is null)
            return Result.Error(ValidationKeys.PlaceEvent.KindInvalid);

        var at = timestampMs is > 0 ? timestampMs.Value : DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        return new PlaceEvent(groupId, userId, clientPlaceId, kind, at);
    }

    // EF materialisation.
    private PlaceEvent()
    {
        Kind = PlaceEventKind.Arrive;
    }
}

public interface IPlaceEventRepository : IBaseRepository<PlaceEvent>
{
    /// <summary>
    /// A circle's events after <paramref name="sinceMs"/>, oldest first, including the caller's
    /// own — that is a requirement of the feed, not an oversight.
    /// </summary>
    Task<List<PlaceEventView>> GetSinceAsync(
        Guid groupId,
        long sinceMs,
        CancellationToken cancellationToken);

    /// <summary>Everything above the newest <paramref name="keep"/> for this circle.</summary>
    Task<List<PlaceEvent>> GetOverflowAsync(Guid groupId, int keep, CancellationToken cancellationToken);
}

public readonly record struct PlaceEventView(
    Guid Id,
    long ClientPlaceId,
    string PlaceName,
    Guid UserId,
    string Kind,
    long TimestampMs);

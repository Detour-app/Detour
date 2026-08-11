using JV.ResultUtilities;
using Shared.Domain;

namespace Detour.Domain.Groups;

/// <summary>
/// The latest known position of one member of one circle, overwritten in place.
///
/// No history and no trail: a circle answers "where is everyone now", not "where has everyone
/// been". A convoy never writes a row here — its live position stays relay-only, the same
/// spirit as fog: a live view between consenting members, not a record.
/// </summary>
public sealed class MemberFix : Entity
{
    public Guid GroupId { get; private set; }

    public Guid UserId { get; private set; }

    public double Latitude { get; private set; }

    public double Longitude { get; private set; }

    public double? AccuracyMeters { get; private set; }

    /// <summary>Unix milliseconds, as reported by the device.</summary>
    public long TimestampMs { get; private set; }

    private MemberFix(Guid groupId, Guid userId, double latitude, double longitude,
        double? accuracyMeters, long timestampMs)
    {
        GroupId = groupId;
        UserId = userId;
        Latitude = latitude;
        Longitude = longitude;
        AccuracyMeters = accuracyMeters;
        TimestampMs = timestampMs;
    }

    public static Result<MemberFix> Create(Guid groupId, Guid userId, double latitude, double longitude,
        double? accuracyMeters, long timestampMs)
    {
        if (!GeoPoint.IsValid(latitude, longitude))
            return Result.Error(ValidationKeys.Location.CoordinatesOutOfRange);

        return new MemberFix(groupId, userId, latitude, longitude,
            NormalizeAccuracy(accuracyMeters),
            timestampMs > 0 ? timestampMs : DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
    }

    public Result Update(double latitude, double longitude, double? accuracyMeters, long timestampMs)
    {
        if (!GeoPoint.IsValid(latitude, longitude))
            return Result.Error(ValidationKeys.Location.CoordinatesOutOfRange);

        Latitude = latitude;
        Longitude = longitude;
        AccuracyMeters = NormalizeAccuracy(accuracyMeters);
        TimestampMs = timestampMs > 0 ? timestampMs : DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        return Result.Ok();
    }

    /// <summary>An implausible radius is dropped rather than refused — the position is still useful.</summary>
    private static double? NormalizeAccuracy(double? accuracyMeters)
    {
        if (accuracyMeters is not { } accuracy)
            return null;

        return double.IsFinite(accuracy) && accuracy is >= 0 and <= 100_000 ? accuracy : null;
    }

    // EF materialisation.
    private MemberFix() { }
}

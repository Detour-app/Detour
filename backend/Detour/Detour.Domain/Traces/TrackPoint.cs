namespace Detour.Domain.Traces;

/// <summary>
/// A single recorded position with its instant and, where the device measured them, speed and
/// lean. Unpacked from trace lines on sync; the unit the read-only dashboard reads.
///
/// Deliberately not an <c>Entity</c>: this is the one table that grows per recorded sample
/// rather than per user action, and a surrogate Guid on every row would cost more than it buys.
/// Its key is (owner, instant), which is also the only join it has to a trip — a trace line
/// carries no trip reference, so a point belongs to whichever ride's window contains it.
/// </summary>
public sealed class TrackPoint
{
    public Guid UserId { get; private set; }

    /// <summary>Unix milliseconds.</summary>
    public long TimestampMs { get; private set; }

    public double Latitude { get; private set; }

    public double Longitude { get; private set; }

    public double? SpeedKmh { get; private set; }

    public double? LeanDegrees { get; private set; }

    private TrackPoint(Guid userId, long timestampMs, double latitude, double longitude,
        double? speedKmh, double? leanDegrees)
    {
        UserId = userId;
        TimestampMs = timestampMs;
        Latitude = latitude;
        Longitude = longitude;
        SpeedKmh = speedKmh;
        LeanDegrees = leanDegrees;
    }

    /// <summary>
    /// Null when the sample is unusable — out of range, non-finite, or with no instant to hang
    /// on. One broken reading must not cost the whole ride, so callers drop nulls and continue
    /// rather than failing the sync.
    /// </summary>
    public static TrackPoint? TryCreate(Guid userId, long timestampMs, double latitude, double longitude,
        double? speedKmh, double? leanDegrees)
    {
        if (timestampMs <= 0 || !GeoPoint.IsValid(latitude, longitude))
            return null;

        return new TrackPoint(
            userId,
            timestampMs,
            latitude,
            longitude,
            speedKmh is { } s && double.IsFinite(s) ? s : null,
            leanDegrees is { } l && double.IsFinite(l) ? l : null);
    }

    // EF materialisation.
    private TrackPoint()
    {
        UserId = Guid.Empty;
    }
}

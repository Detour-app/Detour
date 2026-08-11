namespace Detour.Domain.Users;

/// <summary>
/// The aggregate numbers a rider's own device computed and last synced.
///
/// The service does not derive these — it stores what the app reports and hands them to
/// friends. Only this fixed set of fields is kept, and only as finite numbers, so a friend's
/// app cannot push arbitrary content into a payload other people read.
///
/// Owned by <see cref="User"/>, mapped as columns rather than a JSON blob: the friend
/// leaderboard sorts on total distance, and sorting inside a blob is not worth the trade.
/// </summary>
public sealed record RiderStats(
    double TotalDistanceMeters,
    double TopSpeedKmh,
    double LongestTripMeters,
    double? MaxLeanDegrees,
    int MunicipalitiesVisited,
    double BestCoveragePercent,
    int TripCount)
{
    /// <summary>
    /// A fresh instance every call, deliberately — not a cached singleton. This is an owned
    /// entity, so EF tracks the instance itself as belonging to one rider; handing the same
    /// object to two of them makes the second one throw on Add ("part of a key and so cannot be
    /// modified"). Allocating a record here costs nothing next to that class of bug.
    /// </summary>
    public static RiderStats Empty => new(0, 0, 0, null, 0, 0, 0);

    /// <summary>
    /// Drops any value that is not finite. Infinity and NaN survive JSON parsing and would
    /// otherwise reach the database and every client that reads it back.
    /// </summary>
    public static RiderStats Sanitize(RiderStats? stats)
    {
        if (stats is null)
            return Empty;

        return new RiderStats(
            Finite(stats.TotalDistanceMeters),
            Finite(stats.TopSpeedKmh),
            Finite(stats.LongestTripMeters),
            stats.MaxLeanDegrees is { } lean && double.IsFinite(lean) ? lean : null,
            Math.Max(stats.MunicipalitiesVisited, 0),
            Finite(stats.BestCoveragePercent),
            Math.Max(stats.TripCount, 0));
    }

    private static double Finite(double value) => double.IsFinite(value) ? Math.Max(value, 0) : 0;
}

using Detour.Api.Contracts;
using Detour.Domain;
using Detour.Domain.Traces;
using Detour.Domain.Trips;
using Detour.Domain.Users;
using JV.ResultUtilities;

namespace Detour.Api.Services;

public interface IDashboardService
{
    Task<DashboardStatsResponse> GetStatsAsync(User owner, CancellationToken cancellationToken);

    Task<RidesResponse> GetRidesAsync(Guid ownerId, int limit, CancellationToken cancellationToken);

    Task<Result<RideTrackResponse>> GetTrackAsync(
        Guid ownerId, long? startMs, int toleranceMeters, int maxPoints, CancellationToken cancellationToken);

    Task<TracesResponse> GetTracesAsync(Guid ownerId, int every, CancellationToken cancellationToken);

    Task<CoverageResponse> GetCoverageAsync(
        Guid ownerId, int toleranceMeters, int maxPoints, CancellationToken cancellationToken);
}

/// <summary>
/// The read-only surface a home-automation dashboard polls.
///
/// Every method reads one owner's own data and nothing else. That is not enforced by a check
/// inside these methods but by the shape of them: the owner is a parameter, and no query here
/// takes a second identity.
/// </summary>
public class DashboardService(
    ITripRepository trips,
    ITraceRepository traces,
    ITrackPointRepository trackPoints,
    IBadgeAwardRepository badges) : IDashboardService
{
    /// <summary>Coordinates are rounded to about 10 cm; more digits is noise a map cannot draw.</summary>
    private const int CoordinateDecimals = 6;

    /// <summary>
    /// The longest window an unended ride is given. Capped at the next ride's start below, so a
    /// trip that never recorded an end cannot swallow the one after it — and its speed and lean
    /// peaks with it.
    /// </summary>
    private static readonly long OpenRideFallbackMs = (long)TimeSpan.FromHours(24).TotalMilliseconds;

    public async Task<DashboardStatsResponse> GetStatsAsync(User owner, CancellationToken cancellationToken)
    {
        var awards = await badges.GetForUserAsync(owner.Id, cancellationToken);
        var earned = awards.ToDictionary(b => b.BadgeId, b => b.EarnedAtMs);

        // Two corrections this backend is better placed to make than the device:
        // the ride count comes from the trips actually held, and the deepest lean is whichever
        // of the two figures is deeper — the points table only goes back as far as lean has
        // been recorded, and the device's own figure only counts rides it still holds.
        var rideCount = await trips.CountForUserAsync(owner.Id, cancellationToken);
        var recordedLean = await trackPoints.GetMaxLeanAsync(owner.Id, cancellationToken);

        var stats = owner.Stats with
        {
            TripCount = rideCount,
            MaxLeanDegrees = DeeperLean(recordedLean, owner.Stats.MaxLeanDegrees),
        };

        return new DashboardStatsResponse(
            RiderStatsResponse.Map(stats),
            rideCount,
            earned.Count,
            earned,
            [
                .. BadgeCatalogue.Score(stats, earned).Select(b => new BadgeProgressResponse(
                    b.Id, b.Kind, b.Title, b.Threshold, b.Value, b.EarnedAtMs, b.ProgressPercent))
            ]);
    }

    public async Task<RidesResponse> GetRidesAsync(Guid ownerId, int limit, CancellationToken cancellationToken)
    {
        var rows = await trips.GetRecentForUserAsync(ownerId, limit, cancellationToken);
        var rides = new List<RideSummaryResponse>(rows.Count);

        foreach (var trip in rows)
        {
            var end = await ResolveEndAsync(ownerId, trip, cancellationToken);
            var aggregate = await trackPoints.AggregateWindowAsync(
                ownerId, trip.StartTimeMs, end, cancellationToken);

            rides.Add(new RideSummaryResponse(
                trip.StartTimeMs,
                trip.EndTimeMs,
                trip.Mode,
                Math.Round(trip.DistanceMeters / 1000.0, 2),
                Math.Round(trip.TopSpeedKmh, 1),
                // Null, not zero, when nothing ever measured a lean: "never measured" and "rode
                // upright" are different answers and zero cannot say both.
                aggregate.MaxLeanDegrees is { } lean ? Math.Round(lean, 1) : null,
                trip.MaxGForce,
                aggregate.Count));
        }

        return new RidesResponse(rides);
    }

    public async Task<Result<RideTrackResponse>> GetTrackAsync(
        Guid ownerId,
        long? startMs,
        int toleranceMeters,
        int maxPoints,
        CancellationToken cancellationToken)
    {
        // No ride named means the newest one, so a polling sensor needs no second request to
        // find out what "latest" is.
        var trip = startMs is > 0
            ? await trips.GetByStartAsync(ownerId, startMs.Value, cancellationToken)
            : await trips.GetLatestAsync(ownerId, cancellationToken);

        if (trip is null)
        {
            return startMs is > 0
                ? Result.Error(ValidationKeys.Trip.NotFound)
                : new RideTrackResponse(null, null, null, 0, null, null, 0, 0, null, []);
        }

        var end = await ResolveEndAsync(ownerId, trip, cancellationToken);
        var points = await trackPoints.GetInWindowAsync(ownerId, trip.StartTimeMs, end, cancellationToken);
        var coordinates = points.Select(p => (p.Latitude, p.Longitude)).ToList();
        var bounds = Bounds(coordinates);

        var kept = TrackSimplifier.ThinTo(
            TrackSimplifier.Simplify(coordinates, toleranceMeters, bounds?.CentreLatitude ?? 0),
            maxPoints);

        // Read the peaks off the raw track, not off what survived thinning: dropping a sample
        // must not drop the 55-degree corner it was carrying.
        var leans = points.Where(p => p.LeanDegrees is not null).Select(p => Math.Abs(p.LeanDegrees!.Value)).ToList();
        var speeds = points.Where(p => p.SpeedKmh is not null).Select(p => p.SpeedKmh!.Value).ToList();

        return new RideTrackResponse(
            trip.StartTimeMs,
            trip.EndTimeMs,
            trip.Mode,
            Math.Round(trip.DistanceMeters / 1000.0, 2),
            speeds.Count > 0 ? Math.Round(speeds.Max(), 1) : null,
            leans.Count > 0 ? Math.Round(leans.Max(), 1) : null,
            points.Count,
            kept.Count,
            bounds,
            [.. kept.Select(i => Coordinate(coordinates[i]))]);
    }

    public async Task<TracesResponse> GetTracesAsync(Guid ownerId, int every, CancellationToken cancellationToken)
    {
        var rows = await traces.GetForUserAsync(ownerId, cancellationToken);
        var lines = new List<IReadOnlyList<IReadOnlyList<double>>>();

        foreach (var trace in rows)
        {
            var points = TraceLineReader.ReadPoints(ownerId, trace.Line);
            if (points.Count == 0)
                continue;

            var thinned = points
                .Where((_, index) => index % every == 0)
                .Select(p => Coordinate((p.Latitude, p.Longitude)))
                .ToList();

            if (thinned.Count > 0)
                lines.Add(thinned);
        }

        return new TracesResponse(lines);
    }

    /// <summary>
    /// Every trace as one aggressively-thinned geometry. Simplification runs per line so a
    /// single long trace cannot eat the whole budget; the point budget is then a total across
    /// all lines, not per line.
    /// </summary>
    public async Task<CoverageResponse> GetCoverageAsync(
        Guid ownerId,
        int toleranceMeters,
        int maxPoints,
        CancellationToken cancellationToken)
    {
        var rows = await traces.GetForUserAsync(ownerId, cancellationToken);

        var simplified = new List<List<(double Latitude, double Longitude)>>();
        var all = new List<(double Latitude, double Longitude)>();

        foreach (var trace in rows)
        {
            var points = TraceLineReader.ReadPoints(ownerId, trace.Line)
                .Select(p => (p.Latitude, p.Longitude))
                .ToList();

            if (points.Count < 2)
                continue;

            all.AddRange(points);
            var reference = points[0].Latitude;
            var kept = TrackSimplifier.Simplify(points, toleranceMeters, reference);
            simplified.Add([.. kept.Select(i => points[i])]);
        }

        var total = simplified.Sum(line => line.Count);
        if (total > maxPoints && total > 0)
        {
            // Share the budget in proportion to each line's length, so one long trace is thinned
            // hardest rather than a short one disappearing entirely.
            var scale = (double)maxPoints / total;
            simplified = [.. simplified.Select(line =>
                line.Count <= 2
                    ? line
                    : TrackSimplifier
                        .ThinTo([.. Enumerable.Range(0, line.Count)], Math.Max((int)(line.Count * scale), 2))
                        .Select(i => line[i])
                        .ToList())];
        }

        return new CoverageResponse(
            simplified.Count,
            simplified.Sum(line => line.Count),
            Bounds(all),
            [.. simplified.Select(line => (IReadOnlyList<IReadOnlyList<double>>)[.. line.Select(Coordinate)])]);
    }

    /// <summary>
    /// A ride that never recorded an end still has points. Give it the longest plausible window,
    /// but stop at the next ride's start — otherwise an unended ride swallows the one after it.
    /// </summary>
    private async Task<long> ResolveEndAsync(Guid ownerId, Trip trip, CancellationToken cancellationToken)
    {
        if (trip.EndTimeMs is { } end && end > trip.StartTimeMs)
            return end;

        var fallback = trip.StartTimeMs + OpenRideFallbackMs;
        var nextStart = await trips.GetNextStartAsync(ownerId, trip.StartTimeMs, cancellationToken);

        return nextStart is { } next ? Math.Min(next - 1, fallback) : fallback;
    }

    private static double? DeeperLean(double? recorded, double? reported)
    {
        var candidates = new[] { recorded, reported }.Where(v => v is > 0).Select(v => v!.Value).ToList();
        return candidates.Count > 0 ? Math.Round(candidates.Max(), 1) : null;
    }

    private static IReadOnlyList<double> Coordinate((double Latitude, double Longitude) point) =>
    [
        Math.Round(point.Latitude, CoordinateDecimals),
        Math.Round(point.Longitude, CoordinateDecimals),
    ];

    private static MapBounds? Bounds(IReadOnlyList<(double Latitude, double Longitude)> points)
    {
        if (points.Count == 0)
            return null;

        var minLatitude = points.Min(p => p.Latitude);
        var maxLatitude = points.Max(p => p.Latitude);
        var minLongitude = points.Min(p => p.Longitude);
        var maxLongitude = points.Max(p => p.Longitude);

        return new MapBounds(
            minLatitude,
            minLongitude,
            maxLatitude,
            maxLongitude,
            (minLatitude + maxLatitude) / 2,
            (minLongitude + maxLongitude) / 2);
    }
}

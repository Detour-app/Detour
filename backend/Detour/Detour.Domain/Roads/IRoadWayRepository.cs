using Shared.Database;

namespace Detour.Domain.Roads;

public interface IRoadWayRepository : IBaseRepository<RoadWay>
{
    /// <summary>Ways whose bbox overlaps the query box, optionally restricted to
    /// <paramref name="highwayClasses"/> (the raw OSM `highway` tag values) — null or empty
    /// means every drivable class this table holds.</summary>
    Task<List<RoadWay>> BboxAsync(
        double minLat, double minLon, double maxLat, double maxLon,
        IReadOnlyCollection<string>? highwayClasses, CancellationToken cancellationToken);

    /// <summary>Upserts by <see cref="RoadWay.SourceId"/>: a matching existing row is replaced
    /// in place (<see cref="RoadWay.ReplaceWith"/>), else <paramref name="incoming"/> is
    /// persisted as new.
    ///
    /// No missing-row deletion here, same reasoning as
    /// <see cref="ISpeedLimitWayRepository.UpsertAsync"/>'s doc: a per-region import run must not
    /// wipe every other region's previously imported rows.</summary>
    Task<RoadWay> UpsertAsync(RoadWay incoming, CancellationToken cancellationToken);

    /// <summary>Same upsert as <see cref="UpsertAsync"/> for a whole batch, then flushes it and
    /// clears the change tracker, so a bulk import stays linear in its row count (#561). A later
    /// entry in <paramref name="batch"/> with an earlier one's source id replaces it in place.
    ///
    /// Import-only: clearing the tracker detaches every entity this repository's context
    /// holds, not just the batch's.</summary>
    Task UpsertBatchAsync(IReadOnlyCollection<RoadWay> batch, CancellationToken cancellationToken);
}

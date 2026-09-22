using Shared.Database;

namespace Detour.Domain.Pois;

public interface IPoiRepository : IBaseRepository<Poi>
{
    /// <summary>POIs whose point falls within the query box, optionally restricted to
    /// <paramref name="kinds"/> (`"viewpoint"`/`"food"`/`"sight"`) — null or empty means every
    /// kind this table holds.</summary>
    Task<List<Poi>> BboxAsync(
        double minLat, double minLon, double maxLat, double maxLon,
        IReadOnlyCollection<string>? kinds, CancellationToken cancellationToken);

    /// <summary>Upserts by <see cref="Poi.SourceId"/>: a matching existing row is replaced in
    /// place (<see cref="Poi.ReplaceWith"/>), else <paramref name="incoming"/> is persisted as
    /// new.
    ///
    /// No missing-row deletion here, same reasoning as
    /// <see cref="Detour.Domain.Roads.IRoadWayRepository.UpsertAsync"/>'s doc: a per-region
    /// import run must not wipe every other region's previously imported rows.</summary>
    Task<Poi> UpsertAsync(Poi incoming, CancellationToken cancellationToken);
}

using Detour.Domain.Roads;
using Microsoft.EntityFrameworkCore;
using Shared.Database;

namespace Detour.Database.Repositories;

public class RoadWayRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<RoadWay, DetourDbContext>(factory), IRoadWayRepository
{
    public Task<List<RoadWay>> BboxAsync(
        double minLat, double minLon, double maxLat, double maxLon,
        IReadOnlyCollection<string>? highwayClasses, CancellationToken cancellationToken)
    {
        var query = Set.AsNoTracking()
            .TagWith(Tag(nameof(BboxAsync)))
            .Where(w => w.BboxMinLat <= maxLat && w.BboxMaxLat >= minLat)
            .Where(w => w.BboxMinLon <= maxLon && w.BboxMaxLon >= minLon);

        if (highwayClasses is { Count: > 0 })
            query = query.Where(w => highwayClasses.Contains(w.Highway));

        return query.ToListAsync(cancellationToken);
    }

    public async Task<RoadWay> UpsertAsync(RoadWay incoming, CancellationToken cancellationToken)
    {
        var existing = await Set
            .TagWith(Tag(nameof(UpsertAsync)))
            .FirstOrDefaultAsync(w => w.SourceId == incoming.SourceId, cancellationToken)
            // A row added earlier in this same import run is not yet flushed to the DB — same
            // reason CameraRepository.UpsertAsync/SpeedLimitWayRepository.UpsertAsync union in
            // Set.Local (#367).
            ?? Set.Local.FirstOrDefault(w => w.SourceId == incoming.SourceId);

        if (existing is null)
        {
            Save(incoming);
            return incoming;
        }

        existing.ReplaceWith(incoming);
        return existing;
    }
}

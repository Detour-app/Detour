using Detour.Domain.Pois;
using Microsoft.EntityFrameworkCore;
using Shared.Database;

namespace Detour.Database.Repositories;

public class PoiRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<Poi, DetourDbContext>(factory), IPoiRepository
{
    public Task<List<Poi>> BboxAsync(
        double minLat, double minLon, double maxLat, double maxLon,
        IReadOnlyCollection<string>? kinds, CancellationToken cancellationToken)
    {
        var query = Set.AsNoTracking()
            .TagWith(Tag(nameof(BboxAsync)))
            .Where(p => p.BboxMinLat <= maxLat && p.BboxMaxLat >= minLat)
            .Where(p => p.BboxMinLon <= maxLon && p.BboxMaxLon >= minLon);

        if (kinds is { Count: > 0 })
            query = query.Where(p => kinds.Contains(p.Kind));

        return query.ToListAsync(cancellationToken);
    }

    public async Task<Poi> UpsertAsync(Poi incoming, CancellationToken cancellationToken)
    {
        var existing = await Set
            .TagWith(Tag(nameof(UpsertAsync)))
            .FirstOrDefaultAsync(p => p.SourceId == incoming.SourceId, cancellationToken)
            // A row added earlier in this same import run is not yet flushed to the DB — same
            // reason CameraRepository.UpsertAsync/RoadWayRepository.UpsertAsync union in
            // Set.Local (#367).
            ?? Set.Local.FirstOrDefault(p => p.SourceId == incoming.SourceId);

        if (existing is null)
        {
            Save(incoming);
            return incoming;
        }

        existing.ReplaceWith(incoming);
        return existing;
    }
}

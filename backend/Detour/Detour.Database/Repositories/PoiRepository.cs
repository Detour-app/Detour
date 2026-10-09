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

    public async Task UpsertBatchAsync(IReadOnlyCollection<Poi> batch, CancellationToken cancellationToken)
    {
        // One query for the whole batch instead of UpsertAsync's per-row lookup plus Set.Local
        // scan: Set.Local runs DetectChanges over every tracked entity, so a run of n rows on one
        // context cost O(n²) and a country-sized extract never finished (#561).
        var sourceIds = batch.Select(p => p.SourceId).Distinct().ToList();
        var bySourceId = await Set
            .TagWith(Tag(nameof(UpsertBatchAsync)))
            .Where(p => sourceIds.Contains(p.SourceId))
            .ToDictionaryAsync(p => p.SourceId, cancellationToken);

        foreach (var incoming in batch)
        {
            if (bySourceId.TryGetValue(incoming.SourceId, out var existing))
            {
                existing.ReplaceWith(incoming);
                continue;
            }

            Set.Add(incoming);
            bySourceId[incoming.SourceId] = incoming;
        }

        await Context.SaveChangesAsync(cancellationToken);
        Context.ChangeTracker.Clear();
    }
}

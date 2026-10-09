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

    public async Task UpsertBatchAsync(IReadOnlyCollection<RoadWay> batch, CancellationToken cancellationToken)
    {
        // One query for the whole batch instead of UpsertAsync's per-row lookup plus Set.Local
        // scan: Set.Local runs DetectChanges over every tracked entity, so a run of n rows on one
        // context cost O(n²) and a country-sized extract never finished (#561).
        var sourceIds = batch.Select(w => w.SourceId).Distinct().ToList();
        var bySourceId = await Set
            .TagWith(Tag(nameof(UpsertBatchAsync)))
            .Where(w => sourceIds.Contains(w.SourceId))
            .ToDictionaryAsync(w => w.SourceId, cancellationToken);

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

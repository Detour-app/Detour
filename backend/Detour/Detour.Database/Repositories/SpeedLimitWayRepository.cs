using Detour.Domain.Roads;
using Microsoft.EntityFrameworkCore;
using Shared.Database;

namespace Detour.Database.Repositories;

public class SpeedLimitWayRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<SpeedLimitWay, DetourDbContext>(factory), ISpeedLimitWayRepository
{
    public Task<List<SpeedLimitWay>> BboxAsync(double minLat, double minLon, double maxLat, double maxLon, CancellationToken cancellationToken) =>
        Set.AsNoTracking()
            .TagWith(Tag(nameof(BboxAsync)))
            .Where(w => w.BboxMinLat <= maxLat && w.BboxMaxLat >= minLat)
            .Where(w => w.BboxMinLon <= maxLon && w.BboxMaxLon >= minLon)
            .ToListAsync(cancellationToken);

    public async Task<SpeedLimitWay> UpsertAsync(SpeedLimitWay incoming, CancellationToken cancellationToken)
    {
        var existing = await Set
            .TagWith(Tag(nameof(UpsertAsync)))
            .FirstOrDefaultAsync(w => w.SourceId == incoming.SourceId, cancellationToken)
            // A row added earlier on this context is not yet flushed to the DB when the caller
            // upserts several before one FlushChangesAsync, so without this a duplicate way id
            // would insert twice. Same reason
            // CameraRepository.UpsertAsync unions in Set.Local (#367).
            ?? Set.Local.FirstOrDefault(w => w.SourceId == incoming.SourceId);

        if (existing is null)
        {
            Save(incoming);
            return incoming;
        }

        existing.ReplaceWith(incoming);
        return existing;
    }

    public async Task UpsertBatchAsync(IReadOnlyCollection<SpeedLimitWay> batch, CancellationToken cancellationToken)
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

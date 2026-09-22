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
            // A row added earlier in this same import run is not yet flushed to the DB — the
            // import command does one FlushChangesAsync at the end, not one per entry — so
            // without this a same-run duplicate way id would insert twice. Same reason
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
}

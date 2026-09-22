using Detour.Domain.Boundaries;
using Microsoft.EntityFrameworkCore;
using Shared.Database;

namespace Detour.Database.Repositories;

public class MunicipalityBoundaryRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<MunicipalityBoundary, DetourDbContext>(factory), IMunicipalityBoundaryRepository
{
    public Task<List<MunicipalityBoundary>> CandidatesAsync(double lat, double lon, CancellationToken cancellationToken) =>
        Set.AsNoTracking()
            .TagWith(Tag(nameof(CandidatesAsync)))
            .Where(b => b.BboxMinLat <= lat && b.BboxMaxLat >= lat)
            .Where(b => b.BboxMinLon <= lon && b.BboxMaxLon >= lon)
            .ToListAsync(cancellationToken);

    public async Task<MunicipalityBoundary> UpsertAsync(MunicipalityBoundary incoming, CancellationToken cancellationToken)
    {
        var existing = await Set
            .TagWith(Tag(nameof(UpsertAsync)))
            .FirstOrDefaultAsync(b => b.SourceId == incoming.SourceId, cancellationToken)
            // A row added earlier in this same import run is not yet flushed to the DB — see
            // SpeedLimitWayRepository.UpsertAsync's identical comment (#367).
            ?? Set.Local.FirstOrDefault(b => b.SourceId == incoming.SourceId);

        if (existing is null)
        {
            Save(incoming);
            return incoming;
        }

        existing.ReplaceWith(incoming);
        return existing;
    }
}

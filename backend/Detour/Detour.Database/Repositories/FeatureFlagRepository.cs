using Detour.Domain.FeatureFlags;
using Microsoft.EntityFrameworkCore;
using Shared.Database;

namespace Detour.Database.Repositories;

public class FeatureFlagRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<FeatureFlag, DetourDbContext>(factory), IFeatureFlagRepository
{
    public Task<List<FeatureFlagOverride>> GetOverridesForUserAsync(
        Guid userId, CancellationToken cancellationToken) =>
        Context.FeatureFlagOverrides.AsNoTracking()
            .TagWith(Tag(nameof(GetOverridesForUserAsync)))
            .Where(o => o.UserId == userId)
            .ToListAsync(cancellationToken);
}

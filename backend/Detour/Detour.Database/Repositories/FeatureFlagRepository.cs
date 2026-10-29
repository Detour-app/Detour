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

    public Task<FeatureFlag?> GetByKeyAsync(string key, CancellationToken cancellationToken) =>
        Set.TagWith(Tag(nameof(GetByKeyAsync)))
            .FirstOrDefaultAsync(f => f.Key == key, cancellationToken);

    public Task<List<FeatureFlagOverrideListing>> GetAllOverridesNonTrackingAsync(
        CancellationToken cancellationToken) =>
        Context.FeatureFlagOverrides.AsNoTracking()
            .TagWith(Tag(nameof(GetAllOverridesNonTrackingAsync)))
            .Join(Context.Users, o => o.UserId, u => u.Id, (o, u) => new { o, u.Username })
            .OrderBy(x => x.o.FlagKey)
            .ThenBy(x => x.Username)
            .Select(x => new FeatureFlagOverrideListing(x.o.FlagKey, x.o.UserId, x.Username, x.o.Enabled))
            .ToListAsync(cancellationToken);

    public Task<FeatureFlagOverride?> GetOverrideAsync(
        string key, Guid userId, CancellationToken cancellationToken) =>
        Context.FeatureFlagOverrides
            .TagWith(Tag(nameof(GetOverrideAsync)))
            .FirstOrDefaultAsync(o => o.FlagKey == key && o.UserId == userId, cancellationToken);

    public void SaveOverride(FeatureFlagOverride flagOverride)
    {
        if (!Context.FeatureFlagOverrides.Local.Contains(flagOverride))
            Context.FeatureFlagOverrides.Add(flagOverride);
    }

    public void DeleteOverride(FeatureFlagOverride flagOverride) =>
        Context.FeatureFlagOverrides.Remove(flagOverride);
}

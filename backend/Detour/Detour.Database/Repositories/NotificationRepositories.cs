using Detour.Domain.Notifications;
using Microsoft.EntityFrameworkCore;
using Shared.Database;

namespace Detour.Database.Repositories;

public class DeviceTokenRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<DeviceToken, DetourDbContext>(factory), IDeviceTokenRepository
{
    public Task<DeviceToken?> GetByTokenAsync(string token, CancellationToken cancellationToken) =>
        Set.TagWith(Tag(nameof(GetByTokenAsync)))
            .FirstOrDefaultAsync(t => t.Token == token, cancellationToken);

    public async Task UpsertAsync(
        Guid userId, string token, DevicePlatform platform, CancellationToken cancellationToken)
    {
        var now = DateTimeOffset.UtcNow;
        // Raw SQL: EF Core has no upsert of its own, and this is the one place
        // that actually needs the atomicity - see the interface doc. `id` only
        // takes effect on the insert branch; EXCLUDED refers to the row the
        // INSERT would have written, which is Postgres' name for the proposed
        // row inside an ON CONFLICT clause.
        await Context.Database.ExecuteSqlInterpolatedAsync(
            $"""
             INSERT INTO detour.device_tokens (id, user_id, token, platform, created_at, last_refreshed_at)
             VALUES ({Guid.CreateVersion7()}, {userId}, {token}, {platform.Name}, {now}, {now})
             ON CONFLICT (token) DO UPDATE SET
                 user_id = EXCLUDED.user_id,
                 platform = EXCLUDED.platform,
                 last_refreshed_at = EXCLUDED.last_refreshed_at
             """,
            cancellationToken);
    }

    public Task<List<DeviceTokenTarget>> GetForUsersAsync(
        IReadOnlyCollection<Guid> userIds,
        CancellationToken cancellationToken) =>
        Set.AsNoTracking()
            .TagWith(Tag(nameof(GetForUsersAsync)))
            .Where(t => userIds.Contains(t.UserId))
            .Select(t => new DeviceTokenTarget(t.UserId, t.Token, t.Platform))
            .ToListAsync(cancellationToken);

    public Task DeleteByTokensAsync(IReadOnlyCollection<string> tokens, CancellationToken cancellationToken) =>
        Set.Where(t => tokens.Contains(t.Token)).ExecuteDeleteAsync(cancellationToken);
}

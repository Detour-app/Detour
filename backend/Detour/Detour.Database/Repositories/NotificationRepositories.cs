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
        //
        // Raw, but not unparameterized, and the distinction is the whole reason
        // this is allowed to exist. ExecuteSqlInterpolatedAsync takes a
        // FormattableString, so the holes below never become statement text:
        // EF Core lifts each one into a DbParameter and Npgsql sends the SQL
        // and the values separately. `token` is caller-supplied and
        // DeviceToken.Create filters no characters from it, so that binding is
        // the only thing standing between a hostile token and this statement -
        // which is what DeviceRegistrationTests pins with an actual injection
        // payload. Satisfies ASVS 5.0.0 V1.2.4 (CWE-89) by its Defense Option 1,
        // parameterized queries, not by escaping.
        //
        // The trap, and the reason EF Core renamed these methods in 3.0: the
        // Raw variants take a plain string, so hoisting this literal into a
        // `var sql = $"..."` first, or reaching for ExecuteSqlRawAsync, silently
        // interpolates the values into the SQL and loses all of that. The
        // `Interpolated` in the name is load-bearing.
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

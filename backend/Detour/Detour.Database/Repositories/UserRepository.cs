using Detour.Domain.Users;
using Microsoft.EntityFrameworkCore;
using Shared.Database;

namespace Detour.Database.Repositories;

public class UserRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<User, DetourDbContext>(factory), IUserRepository
{
    public Task<User?> GetBySubjectAsync(string subject, CancellationToken cancellationToken) =>
        Set.TagWith(Tag(nameof(GetBySubjectAsync)))
            .FirstOrDefaultAsync(u => u.Subject == subject, cancellationToken);

    public async Task<bool> InsertIfAbsentAsync(User user, CancellationToken cancellationToken)
    {
        // Raw SQL for the same reason as DeviceTokenRepository.UpsertAsync: EF Core has no
        // ON CONFLICT. No conflict target, so a clash on subject, handle or id all land in
        // DO NOTHING; the caller tells them apart by re-reading the subject. Interpolated, not
        // Raw: every hole below is sent as a parameter, never as statement text.
        var stats = user.Stats;
        var inserted = await Context.Database.ExecuteSqlInterpolatedAsync(
            $"""
             INSERT INTO detour.users (
                 id, subject, username, email, share_fog, is_administrator,
                 stats_total_distance_meters, stats_top_speed_kmh, stats_longest_trip_meters,
                 stats_max_lean_degrees, stats_municipalities_visited, stats_best_coverage_percent,
                 stats_trip_count, created_at, last_seen_at)
             VALUES (
                 {user.Id}, {user.Subject}, {user.Username}, {user.Email}, {user.ShareFog},
                 {user.IsAdministrator}, {stats.TotalDistanceMeters}, {stats.TopSpeedKmh},
                 {stats.LongestTripMeters}, {stats.MaxLeanDegrees}, {stats.MunicipalitiesVisited},
                 {stats.BestCoveragePercent}, {stats.TripCount}, {user.CreatedAt}, {user.LastSeenAt})
             ON CONFLICT DO NOTHING
             """,
            cancellationToken);
        return inserted > 0;
    }

    // Username is citext, so equality here is already case-insensitive at the database.
    public Task<User?> GetByUsernameAsync(string username, CancellationToken cancellationToken) =>
        Set.TagWith(Tag(nameof(GetByUsernameAsync)))
            .FirstOrDefaultAsync(u => u.Username == username, cancellationToken);

    public Task<bool> ExistsByUsernameAsync(string username, CancellationToken cancellationToken) =>
        Set.TagWith(Tag(nameof(ExistsByUsernameAsync)))
            .AnyAsync(u => u.Username == username, cancellationToken);

    public Task<List<User>> GetManyAsync(IReadOnlyCollection<Guid> ids, CancellationToken cancellationToken)
    {
        if (ids.Count == 0)
            return Task.FromResult(new List<User>());

        return Set.AsNoTracking()
            .TagWith(Tag(nameof(GetManyAsync)))
            .Where(u => ids.Contains(u.Id))
            .ToListAsync(cancellationToken);
    }
}

public class BadgeAwardRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<BadgeAward, DetourDbContext>(factory), IBadgeAwardRepository
{
    public Task<List<BadgeAward>> GetForUserAsync(Guid userId, CancellationToken cancellationToken) =>
        Set.TagWith(Tag(nameof(GetForUserAsync)))
            .Where(b => b.UserId == userId)
            .ToListAsync(cancellationToken);

    public async Task<Dictionary<Guid, List<BadgeAward>>> GetForUsersAsync(
        IReadOnlyCollection<Guid> userIds,
        CancellationToken cancellationToken)
    {
        if (userIds.Count == 0)
            return [];

        var rows = await Set.AsNoTracking()
            .TagWith(Tag(nameof(GetForUsersAsync)))
            .Where(b => userIds.Contains(b.UserId))
            .ToListAsync(cancellationToken);

        return rows.GroupBy(b => b.UserId).ToDictionary(g => g.Key, g => g.ToList());
    }

    public Task<int> CountForUserAsync(Guid userId, CancellationToken cancellationToken) =>
        Set.TagWith(Tag(nameof(CountForUserAsync)))
            .CountAsync(b => b.UserId == userId, cancellationToken);
}

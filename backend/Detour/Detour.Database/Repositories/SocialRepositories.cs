using Detour.Domain.Friendships;
using Detour.Domain.Routes;
using Microsoft.EntityFrameworkCore;
using Shared.Database;

namespace Detour.Database.Repositories;

public class FriendshipRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<Friendship, DetourDbContext>(factory), IFriendshipRepository
{
    public Task<Friendship?> GetForPairAsync(Guid a, Guid b, CancellationToken cancellationToken)
    {
        var (low, high) = Friendship.OrderPair(a, b);
        return Set.TagWith(Tag(nameof(GetForPairAsync)))
            .FirstOrDefaultAsync(f => f.LowUserId == low && f.HighUserId == high, cancellationToken);
    }

    public Task<List<Friendship>> GetForUserAsync(Guid userId, CancellationToken cancellationToken) =>
        Set.AsNoTracking()
            .TagWith(Tag(nameof(GetForUserAsync)))
            .Where(f => f.LowUserId == userId || f.HighUserId == userId)
            .ToListAsync(cancellationToken);

    public Task<List<Guid>> GetAcceptedFriendIdsAsync(Guid userId, CancellationToken cancellationToken) =>
        Set.AsNoTracking()
            .TagWith(Tag(nameof(GetAcceptedFriendIdsAsync)))
            .Where(f => f.Status == FriendshipStatus.Accepted
                        && (f.LowUserId == userId || f.HighUserId == userId))
            .Select(f => f.LowUserId == userId ? f.HighUserId : f.LowUserId)
            .ToListAsync(cancellationToken);

    public Task<bool> AreFriendsAsync(Guid a, Guid b, CancellationToken cancellationToken)
    {
        var (low, high) = Friendship.OrderPair(a, b);
        return Set.AsNoTracking()
            .TagWith(Tag(nameof(AreFriendsAsync)))
            .AnyAsync(
                f => f.LowUserId == low && f.HighUserId == high && f.Status == FriendshipStatus.Accepted,
                cancellationToken);
    }
}

public class SharedRouteRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<SharedRoute, DetourDbContext>(factory), ISharedRouteRepository
{
    public Task<SharedRoute?> GetForTripleAsync(
        Guid toUserId,
        Guid fromUserId,
        long clientRouteId,
        CancellationToken cancellationToken) =>
        Set.TagWith(Tag(nameof(GetForTripleAsync)))
            .FirstOrDefaultAsync(
                r => r.ToUserId == toUserId
                     && r.FromUserId == fromUserId
                     && r.ClientRouteId == clientRouteId,
                cancellationToken);

    public Task<List<SharedRoute>> GetInboxAsync(Guid toUserId, int limit, CancellationToken cancellationToken) =>
        Set.AsNoTracking()
            .TagWith(Tag(nameof(GetInboxAsync)))
            .Where(r => r.ToUserId == toUserId)
            .OrderByDescending(r => r.CreatedAt)
            .Take(limit)
            .ToListAsync(cancellationToken);

    public Task<List<SharedRoute>> GetOverflowForPairAsync(
        Guid toUserId,
        Guid fromUserId,
        int keep,
        CancellationToken cancellationToken) =>
        Set.TagWith(Tag(nameof(GetOverflowForPairAsync)))
            .Where(r => r.ToUserId == toUserId && r.FromUserId == fromUserId)
            .OrderByDescending(r => r.CreatedAt)
            .ThenByDescending(r => r.Id)
            .Skip(keep)
            .ToListAsync(cancellationToken);

    public Task<int> DeleteBetweenAsync(Guid a, Guid b, CancellationToken cancellationToken) =>
        Set.Where(r => (r.FromUserId == a && r.ToUserId == b) || (r.FromUserId == b && r.ToUserId == a))
            .ExecuteDeleteAsync(cancellationToken);
}

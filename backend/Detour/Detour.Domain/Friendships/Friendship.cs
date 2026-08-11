using JV.ResultUtilities;
using Shared.Database;
using Shared.Domain;

namespace Detour.Domain.Friendships;

/// <summary>
/// A symmetric relationship between two riders.
///
/// One row per pair, with the two ids stored in a fixed order so a pair can never be
/// represented twice. <see cref="RequestedByUserId"/> is what says who still has to accept —
/// without it, "pending" would not know which direction it points.
/// </summary>
public sealed class Friendship : Entity
{
    /// <summary>The numerically lower of the two ids. Ordering is what makes the pair unique.</summary>
    public Guid LowUserId { get; private set; }

    public Guid HighUserId { get; private set; }

    public FriendshipStatus Status { get; private set; }

    public Guid RequestedByUserId { get; private set; }

    public DateTimeOffset CreatedAt { get; private set; }

    public DateTimeOffset? AcceptedAt { get; private set; }

    private Friendship(Guid lowUserId, Guid highUserId, Guid requestedByUserId)
    {
        LowUserId = lowUserId;
        HighUserId = highUserId;
        RequestedByUserId = requestedByUserId;
        Status = FriendshipStatus.Pending;
        CreatedAt = DateTimeOffset.UtcNow;
    }

    public static Result<Friendship> Request(Guid requesterId, Guid targetId)
    {
        if (requesterId == targetId)
            return Result.Error(ValidationKeys.Friendship.CannotFriendYourself);

        var (low, high) = OrderPair(requesterId, targetId);
        return new Friendship(low, high, requesterId);
    }

    /// <summary>
    /// Only the side that did not send the request may accept it. The check lives here rather
    /// than in the caller because it is the invariant that makes "pending" mean anything.
    /// </summary>
    public Result Accept(Guid accepterId)
    {
        if (Status == FriendshipStatus.Accepted)
            return Result.Ok();

        if (accepterId == RequestedByUserId)
            return Result.Error(ValidationKeys.Friendship.CannotAcceptOwnRequest);

        Status = FriendshipStatus.Accepted;
        AcceptedAt = DateTimeOffset.UtcNow;
        return Result.Ok();
    }

    public bool IsAccepted => Status == FriendshipStatus.Accepted;

    public bool Involves(Guid userId) => LowUserId == userId || HighUserId == userId;

    /// <summary>The other party, from one side's point of view.</summary>
    public Guid OtherThan(Guid userId) => LowUserId == userId ? HighUserId : LowUserId;

    public static (Guid Low, Guid High) OrderPair(Guid a, Guid b) =>
        a.CompareTo(b) <= 0 ? (a, b) : (b, a);

    // EF materialisation.
    private Friendship()
    {
        Status = FriendshipStatus.Pending;
    }
}

public interface IFriendshipRepository : IBaseRepository<Friendship>
{
    Task<Friendship?> GetForPairAsync(Guid a, Guid b, CancellationToken cancellationToken);

    Task<List<Friendship>> GetForUserAsync(Guid userId, CancellationToken cancellationToken);

    /// <summary>Ids of everyone this rider has an accepted friendship with.</summary>
    Task<List<Guid>> GetAcceptedFriendIdsAsync(Guid userId, CancellationToken cancellationToken);

    Task<bool> AreFriendsAsync(Guid a, Guid b, CancellationToken cancellationToken);
}

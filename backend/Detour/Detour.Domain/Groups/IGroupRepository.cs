using Shared.Database;

namespace Detour.Domain.Groups;

public interface IGroupRepository : IBaseRepository<Group>
{
    /// <summary>The group with its member rows loaded — the aggregate every mutation needs.</summary>
    Task<Group?> GetWithMembersAsync(Guid groupId, CancellationToken cancellationToken);

    /// <summary>Groups of one kind this rider is invited to or a member of, members included.</summary>
    Task<List<Group>> GetForUserAsync(Guid userId, GroupKind kind, CancellationToken cancellationToken);

    /// <summary>
    /// The rider's accepted membership of this group, or null. Null must be answered the same
    /// way whether the group does not exist or the caller is simply not in it, so ids cannot be
    /// enumerated.
    /// </summary>
    Task<GroupMember?> GetAcceptedMembershipAsync(Guid groupId, Guid userId, CancellationToken cancellationToken);

    /// <summary>Ids of every accepted member — the live relay's fan-out list.</summary>
    Task<List<Guid>> GetAcceptedMemberIdsAsync(Guid groupId, CancellationToken cancellationToken);
}

public interface IMemberFixRepository : IBaseRepository<MemberFix>
{
    Task<MemberFix?> GetForMemberAsync(Guid groupId, Guid userId, CancellationToken cancellationToken);

    /// <summary>
    /// The latest fix of every accepted <em>and currently sharing</em> member. A paused member
    /// is excluded here even though their row still exists — the read-path half of the pause
    /// promise.
    /// </summary>
    Task<List<MemberFixView>> GetSharingFixesAsync(Guid groupId, CancellationToken cancellationToken);

    Task<int> DeleteForMemberAsync(Guid groupId, Guid userId, CancellationToken cancellationToken);
}

public readonly record struct MemberFixView(
    Guid UserId,
    string Username,
    double Latitude,
    double Longitude,
    double? AccuracyMeters,
    long TimestampMs);

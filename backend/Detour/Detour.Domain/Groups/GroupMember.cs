using JV.ResultUtilities;
using Shared.Domain;

namespace Detour.Domain.Groups;

/// <summary>
/// One rider's membership of one group.
///
/// <see cref="IsSharing"/> is the circle pause switch, and it lives on the membership rather
/// than on the rider: pausing is per person <em>per circle</em>. It is enforced server-side on
/// both paths — inbound frames are dropped and the paused member is excluded from reads — so a
/// stale client build cannot keep broadcasting after the rider believes they stopped.
/// </summary>
public sealed class GroupMember : Entity
{
    public Guid GroupId { get; private set; }

    public Guid UserId { get; private set; }

    public GroupMemberStatus Status { get; private set; }

    public DateTimeOffset JoinedAt { get; private set; }

    /// <summary>Circles only. Convoy rows leave it true and the relay ignores it for them.</summary>
    public bool IsSharing { get; private set; } = true;

    private GroupMember(Guid groupId, Guid userId, GroupMemberStatus status)
    {
        GroupId = groupId;
        UserId = userId;
        Status = status;
        JoinedAt = DateTimeOffset.UtcNow;
    }

    internal static GroupMember CreateOwner(Guid groupId, Guid userId) =>
        new(groupId, userId, GroupMemberStatus.Accepted);

    internal static GroupMember CreateInvited(Guid groupId, Guid userId) =>
        new(groupId, userId, GroupMemberStatus.Invited);

    public bool IsAccepted => Status == GroupMemberStatus.Accepted;

    public bool IsInvited => Status == GroupMemberStatus.Invited;

    public Result Accept()
    {
        if (Status == GroupMemberStatus.Accepted)
            return Result.Ok();

        if (Status != GroupMemberStatus.Invited)
            return Result.Error(ValidationKeys.Group.NoPendingInvite);

        Status = GroupMemberStatus.Accepted;
        JoinedAt = DateTimeOffset.UtcNow;
        return Result.Ok();
    }

    public void SetSharing(bool sharing) => IsSharing = sharing;

    /// <summary>Live traffic requires both an accepted membership and an unpaused switch.</summary>
    public bool CanBroadcast => IsAccepted && IsSharing;

    // EF materialisation.
    private GroupMember()
    {
        Status = GroupMemberStatus.Invited;
    }
}

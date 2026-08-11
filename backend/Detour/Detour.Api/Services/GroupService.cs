using Detour.Api.Contracts;
using Detour.Domain;
using Detour.Domain.Circles;
using Detour.Domain.Friendships;
using Detour.Domain.Groups;
using Detour.Domain.Users;
using JV.ResultUtilities;

namespace Detour.Api.Services;

public interface IGroupService
{
    Task<Result<GroupResponse>> CreateAsync(
        User caller, GroupKind kind, string name, CancellationToken cancellationToken);

    Task<IReadOnlyList<GroupResponse>> ListAsync(
        Guid callerId, GroupKind kind, CancellationToken cancellationToken);

    Task<Result<string>> InviteAsync(
        User caller, Guid groupId, string username, CancellationToken cancellationToken);

    Task<Result<string>> RespondAsync(
        Guid callerId, Guid groupId, bool accept, CancellationToken cancellationToken);

    Task<Result> LeaveAsync(Guid callerId, Guid groupId, CancellationToken cancellationToken);

    Task<Result<bool>> SetSharingAsync(
        Guid callerId, Guid groupId, bool sharing, CancellationToken cancellationToken);

    /// <summary>
    /// The gate every circle-only capability shares. Failure is always the same
    /// <c>NotAMember</c>, whether the group does not exist, is a convoy, or the caller simply is
    /// not in it — otherwise group ids can be enumerated by watching which answer comes back.
    /// </summary>
    Task<Result<Group>> RequireCircleMembershipAsync(
        Guid callerId, Guid groupId, CancellationToken cancellationToken);
}

public class GroupService(
    IGroupRepository groups,
    IFriendshipRepository friendships,
    IUserRepository users,
    ICirclePlaceRepository circlePlaces,
    IMemberFixRepository memberFixes) : IGroupService
{
    public async Task<Result<GroupResponse>> CreateAsync(
        User caller,
        GroupKind kind,
        string name,
        CancellationToken cancellationToken)
    {
        var (result, group) = Group.Create(kind, name, caller.Id);
        if (result.IsFailure)
            return result;

        await groups.SaveAsync(group, cancellationToken);
        return GroupResponseMapper.Map(group, caller.Id, new Dictionary<Guid, string>
        {
            [caller.Id] = caller.Username,
        });
    }

    public async Task<IReadOnlyList<GroupResponse>> ListAsync(
        Guid callerId,
        GroupKind kind,
        CancellationToken cancellationToken)
    {
        var rows = await groups.GetForUserAsync(callerId, kind, cancellationToken);
        if (rows.Count == 0)
            return [];

        var memberIds = rows.SelectMany(g => g.Members.Select(m => m.UserId)).Distinct().ToArray();
        var usernames = (await users.GetManyAsync(memberIds, cancellationToken))
            .ToDictionary(u => u.Id, u => u.Username);

        return [.. rows.Select(g => GroupResponseMapper.Map(g, callerId, usernames))];
    }

    public async Task<Result<string>> InviteAsync(
        User caller,
        Guid groupId,
        string username,
        CancellationToken cancellationToken)
    {
        var group = await groups.GetWithMembersAsync(groupId, cancellationToken);

        // Membership is checked before anything else exists, so a group id the caller does not
        // hold answers the same way whether or not it is real.
        if (group is null || !group.IsAcceptedMember(caller.Id))
            return Result.Error(ValidationKeys.Group.NotAMember);

        var invitee = await users.GetByUsernameAsync(username.Trim(), cancellationToken);
        if (invitee is null)
            return Result.Error(ValidationKeys.User.NotFoundByUsername, username);

        if (invitee.Id == caller.Id)
            return Result.Error(ValidationKeys.Group.AlreadyAMember);

        // Group membership can only ever come from an existing friendship. This is the chain
        // that makes "granted access" mean something rather than an open room.
        if (!await friendships.AreFriendsAsync(caller.Id, invitee.Id, cancellationToken))
            return Result.Error(ValidationKeys.Group.InviteeNotAFriend);

        var (result, member) = group.Invite(invitee.Id);
        return result.IsFailure ? result : Result.Ok(member.Status.Name);
    }

    public async Task<Result<string>> RespondAsync(
        Guid callerId,
        Guid groupId,
        bool accept,
        CancellationToken cancellationToken)
    {
        var group = await groups.GetWithMembersAsync(groupId, cancellationToken);
        var membership = group?.FindMember(callerId);
        if (group is null || membership is null || !membership.IsInvited)
            return Result.Error(ValidationKeys.Group.NoPendingInvite);

        if (!accept)
        {
            group.RemoveMember(membership);
            return Result.Ok("Declined");
        }

        var result = membership.Accept();
        return result.IsFailure ? result : Result.Ok(GroupMemberStatus.Accepted.Name);
    }

    public async Task<Result> LeaveAsync(Guid callerId, Guid groupId, CancellationToken cancellationToken)
    {
        var group = await groups.GetWithMembersAsync(groupId, cancellationToken);
        var membership = group?.FindMember(callerId);
        if (group is null || membership is null)
            return Result.Error(ValidationKeys.Group.NotAMember);

        group.RemoveMember(membership);

        // Places this member shared go with them — the same "revoked when the sharing
        // relationship ends" rule unfriending applies to routes. A convoy has no rows here, so
        // this is a no-op for one.
        await circlePlaces.DeleteForOwnerAsync(groupId, callerId, cancellationToken);
        await memberFixes.DeleteForMemberAsync(groupId, callerId, cancellationToken);

        // Read the kind's own policy rather than re-testing what it is. Getting this wrong
        // evaporates someone's circle the moment they are the last one left in it.
        if (group.ShouldBeDeletedWhenEmpty)
            groups.Delete(group);

        return Result.Ok();
    }

    public async Task<Result<bool>> SetSharingAsync(
        Guid callerId,
        Guid groupId,
        bool sharing,
        CancellationToken cancellationToken)
    {
        var group = await groups.GetWithMembersAsync(groupId, cancellationToken);
        var membership = group?.FindMember(callerId);

        // A convoy answers "not a member" rather than "not applicable", so this cannot become a
        // second way to ask what kind a group is.
        if (group is null || !group.Kind.SupportsPause || membership is null || !membership.IsAccepted)
            return Result.Error(ValidationKeys.Group.NotAMember);

        membership.SetSharing(sharing);
        return Result.Ok(sharing);
    }

    public async Task<Result<Group>> RequireCircleMembershipAsync(
        Guid callerId,
        Guid groupId,
        CancellationToken cancellationToken)
    {
        var group = await groups.GetWithMembersAsync(groupId, cancellationToken);
        if (group is null || group.Kind != GroupKind.Circle || !group.IsAcceptedMember(callerId))
            return Result.Error(ValidationKeys.Group.NotAMember);

        return group;
    }
}

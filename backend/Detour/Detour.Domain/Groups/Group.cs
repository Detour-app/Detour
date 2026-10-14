using JV.ResultUtilities;
using Shared.Domain;

namespace Detour.Domain.Groups;

/// <summary>
/// A convoy or a circle — one entity discriminated by <see cref="Kind"/>.
///
/// Membership is the only privacy gate for live location and voice: nothing is relayed to a
/// connection that is not an accepted member, and you can only be invited by someone who is
/// already an accepted friend. That chain is what makes "granted access" mean something
/// instead of an open room.
/// </summary>
public sealed class Group : Entity, INamedEntity
{
    private readonly List<GroupMember> _members = [];

    public GroupKind Kind { get; private set; }

    public string Name { get; private set; }

    public Guid OwnerId { get; private set; }

    public DateTimeOffset CreatedAt { get; private set; }

    public IReadOnlyCollection<GroupMember> Members => _members;

    private Group(GroupKind kind, string name, Guid ownerId)
    {
        Kind = kind;
        Name = name;
        OwnerId = ownerId;
        CreatedAt = DateTimeOffset.UtcNow;
    }

    /// <summary>The creator joins automatically, already accepted.</summary>
    public static Result<Group> Create(GroupKind kind, string name, Guid ownerId)
    {
        var validation = ValidateName(name);
        if (validation.IsFailure)
            return validation;

        var group = new Group(kind, name.Trim(), ownerId);
        group._members.Add(GroupMember.CreateOwner(group.Id, ownerId));
        return group;
    }

    public Result Rename(string name)
    {
        var validation = ValidateName(name);
        if (validation.IsFailure)
            return validation;

        Name = name.Trim();
        return Result.Ok();
    }

    /// <summary>
    /// Adds an invitation. The caller has already checked that the inviter is an accepted
    /// member and an accepted friend of the invitee — those are relationships this aggregate
    /// cannot see. What it does own is the size cap and the "already in" case.
    /// </summary>
    public Result<GroupMember> Invite(Guid inviteeId)
    {
        var existing = FindMember(inviteeId);
        if (existing is not null)
            return existing;

        if (Kind.MaxMembers is { } max && _members.Count >= max)
            return Result.Error(ValidationKeys.Group.CircleFull, max);

        var member = GroupMember.CreateInvited(Id, inviteeId);
        _members.Add(member);
        return member;
    }

    /// <summary>
    /// Admits a rider through an invite link, already accepted. The friendship chain
    /// <see cref="Invite"/> relies on is replaced by the host having handed out the link, so
    /// this is a convoy-only power: a circle is long-lived and keeps the friends-only rule.
    /// </summary>
    public Result<GroupMember> AdmitGuest(Guid userId)
    {
        if (Kind != GroupKind.Convoy)
            return Result.Error(ValidationKeys.Group.NotAConvoy);

        var existing = FindMember(userId);
        if (existing is not null)
        {
            var accepted = Accept(existing);
            return accepted.IsFailure ? accepted : existing;
        }

        var member = GroupMember.CreateGuest(Id, userId);
        _members.Add(member);
        FillVacantOwnership();
        return member;
    }

    /// <summary>
    /// Accepts a pending invitation. Goes through the group rather than the member because a
    /// newly accepted rider may be the one to take over an owner who already left.
    /// </summary>
    public Result Accept(GroupMember member)
    {
        var accepted = member.Accept();
        if (accepted.IsFailure)
            return accepted;

        FillVacantOwnership();
        return Result.Ok();
    }

    public GroupMember? FindMember(Guid userId) => _members.FirstOrDefault(m => m.UserId == userId);

    public bool IsAcceptedMember(Guid userId) => FindMember(userId)?.IsAccepted == true;

    /// <summary>
    /// Drops a membership. When the owner goes and others ride on, the group passes to the
    /// longest-standing accepted member: every host-only power (invite links, guest removal,
    /// the regroup pin) checks <see cref="OwnerId"/>, so a departed owner would leave nobody
    /// able to use them (#698).
    /// </summary>
    public void RemoveMember(GroupMember member)
    {
        _members.Remove(member);
        if (member.UserId == OwnerId)
            FillVacantOwnership();
    }

    /// <summary>
    /// Hands ownership to the longest-standing accepted member if the owner is no longer one.
    /// Called again whenever someone is accepted (an invitation or an invite link), because an
    /// owner who left a group of only pending invitees had nobody to hand it to at the time.
    /// </summary>
    private void FillVacantOwnership()
    {
        if (IsAcceptedMember(OwnerId))
            return;

        var heir = _members
            .Where(m => m.IsAccepted)
            .OrderBy(m => m.JoinedAt)
            .ThenBy(m => m.UserId)
            .FirstOrDefault();
        if (heir is not null)
            OwnerId = heir.UserId;
    }

    /// <summary>
    /// Read the kind's own policy rather than re-testing what it is. A convoy with nobody left
    /// is dead weight; evaporating someone's circle the moment they are alone in it is the bug
    /// this exists to prevent.
    /// </summary>
    public bool ShouldBeDeletedWhenEmpty => Kind.DropWhenEmpty && _members.Count == 0;

    private static Result ValidateName(string? name)
    {
        if (string.IsNullOrWhiteSpace(name))
            return Result.Error(ValidationKeys.Group.NameRequired);

        return name.Trim().Length > DetourLimits.GroupNameMaxLength
            ? Result.Error(ValidationKeys.Group.NameTooLong, DetourLimits.GroupNameMaxLength)
            : Result.Ok();
    }

    // EF materialisation.
    private Group()
    {
        Kind = GroupKind.Convoy;
        Name = string.Empty;
    }
}

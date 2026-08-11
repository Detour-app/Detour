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

    public GroupMember? FindMember(Guid userId) => _members.FirstOrDefault(m => m.UserId == userId);

    public bool IsAcceptedMember(Guid userId) => FindMember(userId)?.IsAccepted == true;

    public void RemoveMember(GroupMember member) => _members.Remove(member);

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

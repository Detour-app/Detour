using Detour.Domain;
using Detour.Domain.Groups;

namespace Detour.Domain.Tests;

public class GroupKindTests
{
    [Fact]
    public void Circle_never_allows_voice_or_destination_votes()
    {
        // The single highest-consequence rule in the convoy/circle merge: a circle must never
        // gain always-on voice between people who signed up for a dot on a map.
        GroupKind.Circle.AllowsVoice.Should().BeFalse();
        GroupKind.Circle.AllowsDestinationVote.Should().BeFalse();
    }

    [Fact]
    public void Convoy_allows_voice_and_destination_votes()
    {
        GroupKind.Convoy.AllowsVoice.Should().BeTrue();
        GroupKind.Convoy.AllowsDestinationVote.Should().BeTrue();
    }

    [Fact]
    public void Convoy_is_dropped_when_empty_and_a_circle_is_not()
    {
        GroupKind.Convoy.DropWhenEmpty.Should().BeTrue();
        GroupKind.Circle.DropWhenEmpty.Should().BeFalse();
    }

    [Fact]
    public void Only_a_circle_caps_membership_and_persists_a_last_fix()
    {
        GroupKind.Circle.MaxMembers.Should().Be(DetourLimits.MaxCircleMembers);
        GroupKind.Circle.PersistsLastFix.Should().BeTrue();
        GroupKind.Circle.SupportsPause.Should().BeTrue();

        GroupKind.Convoy.MaxMembers.Should().BeNull();
        GroupKind.Convoy.PersistsLastFix.Should().BeFalse();
        GroupKind.Convoy.SupportsPause.Should().BeFalse();
    }
}

public class GroupTests
{
    [Fact]
    public void Create_joins_the_owner_as_an_accepted_member()
    {
        var ownerId = Guid.CreateVersion7();

        var (result, group) = Group.Create(GroupKind.Circle, "Household", ownerId);

        result.IsFailure.Should().BeFalse();
        group.Members.Should().ContainSingle();
        group.IsAcceptedMember(ownerId).Should().BeTrue();
    }

    [Fact]
    public void Create_rejects_a_blank_name()
    {
        var (result, _) = Group.Create(GroupKind.Convoy, "   ", Guid.CreateVersion7());

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.Group.NameRequired).Should().BeTrue();
    }

    [Fact]
    public void Create_rejects_a_name_past_the_limit()
    {
        var name = new string('x', DetourLimits.GroupNameMaxLength + 1);

        var (result, _) = Group.Create(GroupKind.Convoy, name, Guid.CreateVersion7());

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.Group.NameTooLong).Should().BeTrue();
    }

    [Fact]
    public void Invite_returns_the_existing_membership_rather_than_duplicating_it()
    {
        var (_, group) = Group.Create(GroupKind.Convoy, "Sunday ride", Guid.CreateVersion7());
        var friendId = Guid.CreateVersion7();

        var (first, firstMember) = group.Invite(friendId);
        var (second, secondMember) = group.Invite(friendId);

        first.IsFailure.Should().BeFalse();
        second.IsFailure.Should().BeFalse();
        secondMember.Should().BeSameAs(firstMember);
        group.Members.Should().HaveCount(2);
    }

    [Fact]
    public void Invite_refuses_once_a_circle_is_full()
    {
        var (_, circle) = Group.Create(GroupKind.Circle, "Household", Guid.CreateVersion7());

        // The owner already occupies one slot.
        for (var i = 1; i < DetourLimits.MaxCircleMembers; i++)
            circle.Invite(Guid.CreateVersion7());

        var (result, _) = circle.Invite(Guid.CreateVersion7());

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.Group.CircleFull).Should().BeTrue();
    }

    [Fact]
    public void A_convoy_has_no_size_cap()
    {
        var (_, convoy) = Group.Create(GroupKind.Convoy, "Big ride", Guid.CreateVersion7());

        for (var i = 0; i < DetourLimits.MaxCircleMembers * 2; i++)
        {
            var (result, _) = convoy.Invite(Guid.CreateVersion7());
            result.IsFailure.Should().BeFalse();
        }
    }

    [Fact]
    public void An_emptied_convoy_is_deleted_and_an_emptied_circle_is_not()
    {
        var ownerId = Guid.CreateVersion7();
        var (_, convoy) = Group.Create(GroupKind.Convoy, "Sunday ride", ownerId);
        var (_, circle) = Group.Create(GroupKind.Circle, "Household", ownerId);

        convoy.RemoveMember(convoy.FindMember(ownerId)!);
        circle.RemoveMember(circle.FindMember(ownerId)!);

        convoy.ShouldBeDeletedWhenEmpty.Should().BeTrue();
        circle.ShouldBeDeletedWhenEmpty.Should().BeFalse();
    }
}

public class GroupMemberTests
{
    [Fact]
    public void An_invited_member_is_not_yet_allowed_to_broadcast()
    {
        var (_, group) = Group.Create(GroupKind.Circle, "Household", Guid.CreateVersion7());
        var (_, member) = group.Invite(Guid.CreateVersion7());

        member.CanBroadcast.Should().BeFalse();

        member.Accept().IsFailure.Should().BeFalse();

        member.CanBroadcast.Should().BeTrue();
    }

    [Fact]
    public void Pausing_stops_an_accepted_member_broadcasting()
    {
        var (_, group) = Group.Create(GroupKind.Circle, "Household", Guid.CreateVersion7());
        var (_, member) = group.Invite(Guid.CreateVersion7());
        member.Accept();

        member.SetSharing(false);

        member.CanBroadcast.Should().BeFalse();
        member.IsAccepted.Should().BeTrue("pausing is not leaving");
    }
}

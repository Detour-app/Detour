using Detour.Domain;
using Detour.Domain.Friendships;

namespace Detour.Domain.Tests;

public class FriendshipTests
{
    [Fact]
    public void Request_orders_the_pair_so_it_can_never_be_stored_twice()
    {
        var a = Guid.CreateVersion7();
        var b = Guid.CreateVersion7();

        var (_, forward) = Friendship.Request(a, b);
        var (_, backward) = Friendship.Request(b, a);

        forward.LowUserId.Should().Be(backward.LowUserId);
        forward.HighUserId.Should().Be(backward.HighUserId);
    }

    [Fact]
    public void Request_remembers_who_has_to_accept()
    {
        var requester = Guid.CreateVersion7();
        var target = Guid.CreateVersion7();

        var (_, friendship) = Friendship.Request(requester, target);

        friendship.RequestedByUserId.Should().Be(requester);
        friendship.IsAccepted.Should().BeFalse();
    }

    [Fact]
    public void Request_refuses_self_friendship()
    {
        var id = Guid.CreateVersion7();

        var (result, _) = Friendship.Request(id, id);

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.Friendship.CannotFriendYourself).Should().BeTrue();
    }

    [Fact]
    public void The_requester_cannot_accept_their_own_request()
    {
        var requester = Guid.CreateVersion7();
        var (_, friendship) = Friendship.Request(requester, Guid.CreateVersion7());

        var result = friendship.Accept(requester);

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.Friendship.CannotAcceptOwnRequest).Should().BeTrue();
        friendship.IsAccepted.Should().BeFalse();
    }

    [Fact]
    public void The_other_side_accepting_settles_the_friendship()
    {
        var requester = Guid.CreateVersion7();
        var target = Guid.CreateVersion7();
        var (_, friendship) = Friendship.Request(requester, target);

        var result = friendship.Accept(target);

        result.IsFailure.Should().BeFalse();
        friendship.IsAccepted.Should().BeTrue();
        friendship.AcceptedAt.Should().NotBeNull();
    }

    [Fact]
    public void Accepting_twice_is_a_no_op_rather_than_an_error()
    {
        var target = Guid.CreateVersion7();
        var (_, friendship) = Friendship.Request(Guid.CreateVersion7(), target);
        friendship.Accept(target);

        friendship.Accept(target).IsFailure.Should().BeFalse();
    }

    [Fact]
    public void OtherThan_reads_the_pair_from_either_side()
    {
        var a = Guid.CreateVersion7();
        var b = Guid.CreateVersion7();
        var (_, friendship) = Friendship.Request(a, b);

        friendship.OtherThan(a).Should().Be(b);
        friendship.OtherThan(b).Should().Be(a);
        friendship.Involves(Guid.CreateVersion7()).Should().BeFalse();
    }
}

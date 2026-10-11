using Detour.Domain;
using Detour.Domain.Groups;

namespace Detour.Domain.Tests;

public class ConvoyInviteTests
{
    private static readonly DateTimeOffset Now = new(2026, 10, 10, 12, 0, 0, TimeSpan.Zero);

    [Fact]
    public void Issue_stores_only_the_hash_and_mints_at_least_128_bits()
    {
        var (invite, token) = ConvoyInvite.Issue(Guid.CreateVersion7(), Guid.CreateVersion7(), Now);

        invite.TokenHash.Should().Be(ConvoyInvite.HashOf(token));
        invite.TokenHash.Should().NotContain(token);
        // Base64url without padding: 6 bits per character.
        (token.Length * 6).Should().BeGreaterThanOrEqualTo(128);
        token.Should().MatchRegex("^[A-Za-z0-9_-]+$");
    }

    [Fact]
    public void Two_links_never_share_a_token()
    {
        var groupId = Guid.CreateVersion7();
        var hostId = Guid.CreateVersion7();

        var (_, first) = ConvoyInvite.Issue(groupId, hostId, Now);
        var (_, second) = ConvoyInvite.Issue(groupId, hostId, Now);

        first.Should().NotBe(second);
    }

    [Fact]
    public void A_link_is_usable_until_its_hard_cap_or_revocation()
    {
        var (invite, _) = ConvoyInvite.Issue(Guid.CreateVersion7(), Guid.CreateVersion7(), Now);

        invite.IsUsable(Now).Should().BeTrue();
        invite.IsUsable(Now + DetourLimits.ConvoyInviteLifetime - TimeSpan.FromSeconds(1)).Should().BeTrue();
        invite.IsUsable(Now + DetourLimits.ConvoyInviteLifetime).Should().BeFalse();

        invite.Revoke(Now);
        invite.IsUsable(Now).Should().BeFalse();
    }

    [Fact]
    public void Admit_refuses_an_expired_link()
    {
        var (invite, _) = ConvoyInvite.Issue(Guid.CreateVersion7(), Guid.CreateVersion7(), Now);

        var (result, _) = invite.Admit(Guid.CreateVersion7(), Now + DetourLimits.ConvoyInviteLifetime);

        result.HasError(ValidationKeys.ConvoyInvite.Expired).Should().BeTrue();
        invite.Guests.Should().BeEmpty();
    }

    [Fact]
    public void A_removed_guest_cannot_be_admitted_again()
    {
        var (invite, _) = ConvoyInvite.Issue(Guid.CreateVersion7(), Guid.CreateVersion7(), Now);
        var riderId = Guid.CreateVersion7();
        var (_, guest) = invite.Admit(riderId, Now);

        guest.Remove(Now);
        var (again, _) = invite.Admit(riderId, Now);

        again.HasError(ValidationKeys.ConvoyInvite.GuestRemoved).Should().BeTrue();
    }

    [Fact]
    public void Admitting_the_same_rider_twice_keeps_one_row()
    {
        var (invite, _) = ConvoyInvite.Issue(Guid.CreateVersion7(), Guid.CreateVersion7(), Now);
        var riderId = Guid.CreateVersion7();

        invite.Admit(riderId, Now);
        invite.Admit(riderId, Now);

        invite.Guests.Should().ContainSingle();
    }
}

public class GroupAdmitGuestTests
{
    [Fact]
    public void A_guest_joins_a_convoy_already_accepted()
    {
        var (_, convoy) = Group.Create(GroupKind.Convoy, "Ride", Guid.CreateVersion7());
        var guestId = Guid.CreateVersion7();

        var (result, _) = convoy.AdmitGuest(guestId);

        result.IsFailure.Should().BeFalse();
        convoy.IsAcceptedMember(guestId).Should().BeTrue();
    }

    [Fact]
    public void A_circle_never_admits_a_guest()
    {
        var (_, circle) = Group.Create(GroupKind.Circle, "Household", Guid.CreateVersion7());

        var (result, _) = circle.AdmitGuest(Guid.CreateVersion7());

        result.HasError(ValidationKeys.Group.NotAConvoy).Should().BeTrue();
        circle.Members.Should().ContainSingle();
    }

    [Fact]
    public void A_pending_invitee_who_uses_the_link_is_accepted()
    {
        var (_, convoy) = Group.Create(GroupKind.Convoy, "Ride", Guid.CreateVersion7());
        var riderId = Guid.CreateVersion7();
        convoy.Invite(riderId);

        convoy.AdmitGuest(riderId);

        convoy.IsAcceptedMember(riderId).Should().BeTrue();
        convoy.Members.Should().HaveCount(2);
    }
}

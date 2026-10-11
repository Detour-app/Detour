using Detour.Api.Contracts;
using Detour.Api.Live;
using Detour.Domain;
using Detour.Domain.Groups;
using Detour.Domain.Users;
using JV.ResultUtilities;
using Shared.Domain;

namespace Detour.Api.Services;

public interface IConvoyInviteService
{
    /// <summary>
    /// Mints a fresh link, revoking any still-usable one: a convoy has at most one live link, so
    /// "revoke" has one meaning and a host who re-shares does not leave an older link working.
    /// </summary>
    Task<Result<ConvoyInviteResponse>> CreateAsync(
        Guid callerId, Guid groupId, CancellationToken cancellationToken);

    Task<Result> RevokeAsync(Guid callerId, Guid groupId, CancellationToken cancellationToken);

    Task<Result<ConvoyGuestsResponse>> ListGuestsAsync(
        Guid callerId, Guid groupId, CancellationToken cancellationToken);

    Task<Result> RemoveGuestAsync(
        Guid callerId, Guid groupId, Guid guestId, CancellationToken cancellationToken);

    Task<Result<GroupResponse>> JoinAsync(
        User caller, string token, string clientIp, CancellationToken cancellationToken);
}

public class ConvoyInviteService(
    IGroupRepository groups,
    IConvoyInviteRepository invites,
    IUserRepository users,
    ILiveRelay liveRelay,
    IPostCommitActionScheduler postCommit,
    ConvoyJoinThrottle throttle,
    TimeProvider time) : IConvoyInviteService
{
    public async Task<Result<ConvoyInviteResponse>> CreateAsync(
        Guid callerId,
        Guid groupId,
        CancellationToken cancellationToken)
    {
        var (gate, _) = await RequireHostAsync(callerId, groupId, cancellationToken);
        if (gate.IsFailure)
            return gate;

        var now = time.GetUtcNow();
        foreach (var previous in await invites.GetForGroupAsync(groupId, cancellationToken))
            previous.Revoke(now);

        var (invite, token) = ConvoyInvite.Issue(groupId, callerId, now);
        await invites.SaveAsync(invite, cancellationToken);
        return new ConvoyInviteResponse(token, invite.ExpiresAt.ToUnixTimeMilliseconds());
    }

    public async Task<Result> RevokeAsync(Guid callerId, Guid groupId, CancellationToken cancellationToken)
    {
        var (gate, _) = await RequireHostAsync(callerId, groupId, cancellationToken);
        if (gate.IsFailure)
            return gate;

        // Revoking stops new joins only. Guests already in stay until the convoy ends or the
        // host removes them one by one — pulling everyone at once is not what "stop sharing
        // the link" means to a host mid-ride.
        var now = time.GetUtcNow();
        foreach (var invite in await invites.GetForGroupAsync(groupId, cancellationToken))
            invite.Revoke(now);

        return Result.Ok();
    }

    public async Task<Result<ConvoyGuestsResponse>> ListGuestsAsync(
        Guid callerId,
        Guid groupId,
        CancellationToken cancellationToken)
    {
        var (gate, group) = await RequireHostAsync(callerId, groupId, cancellationToken);
        if (gate.IsFailure)
            return gate;

        var now = time.GetUtcNow();
        var all = await invites.GetForGroupAsync(groupId, cancellationToken);

        // Only guests still in the convoy. One who left on their own is not someone the host
        // can see on the map, so listing them would offer a removal that changes nothing visible.
        var guests = CurrentGuests(all, group).OrderBy(g => g.JoinedAt).ToList();

        var usernames = (await users.GetManyAsync([.. guests.Select(g => g.UserId)], cancellationToken))
            .ToDictionary(u => u.Id, u => u.Username);

        var usable = all.FirstOrDefault(i => i.IsUsable(now));
        return new ConvoyGuestsResponse(
            usable?.ExpiresAt.ToUnixTimeMilliseconds(),
            [.. guests
                .Where(g => usernames.ContainsKey(g.UserId))
                .Select(g => new ConvoyGuestResponse(
                    g.UserId, usernames[g.UserId], g.JoinedAt.ToUnixTimeMilliseconds()))]);
    }

    public async Task<Result> RemoveGuestAsync(
        Guid callerId,
        Guid groupId,
        Guid guestId,
        CancellationToken cancellationToken)
    {
        var (gate, group) = await RequireHostAsync(callerId, groupId, cancellationToken);
        if (gate.IsFailure)
            return gate;

        // Only a rider who came in by link. Friends invited the ordinary way are not the
        // host's to eject through this route, and the host cannot remove themselves.
        var rows = (await invites.GetForGroupAsync(groupId, cancellationToken))
            .Select(i => i.FindGuest(guestId))
            .OfType<ConvoyGuest>()
            .Where(g => !g.IsRemoved)
            .ToList();
        if (guestId == callerId || rows.Count == 0)
            return Result.Error(ValidationKeys.ConvoyInvite.GuestNotFound);

        var now = time.GetUtcNow();
        foreach (var row in rows)
            row.Remove(now);

        if (group.FindMember(guestId) is { } membership)
            group.RemoveMember(membership);

        // Same as leaving: the removed rider stops receiving the convoy's traffic before this
        // request returns, and only once the removal has committed.
        postCommit.Schedule(() => liveRelay.EvictAsync(guestId, groupId, CancellationToken.None));

        return Result.Ok();
    }

    public async Task<Result<GroupResponse>> JoinAsync(
        User caller,
        string token,
        string clientIp,
        CancellationToken cancellationToken)
    {
        var tokenHash = ConvoyInvite.HashOf(token.Trim());
        if (!throttle.TryAcquire(tokenHash, clientIp))
            return Result.Error(ValidationKeys.ConvoyInvite.TooManyAttempts);

        var invite = await invites.GetByHashAsync(tokenHash, cancellationToken);
        var group = invite is null
            ? null
            : await groups.GetWithMembersAsync(invite.GroupId, cancellationToken);
        if (invite is null || group is null)
            return Result.Error(ValidationKeys.ConvoyInvite.NotFound);

        // Already riding in it, by friendship or an earlier tap on the same link: nothing to
        // record, and not worth refusing just because the link has since expired.
        if (!group.IsAcceptedMember(caller.Id))
        {
            var guestsIn = CurrentGuests(await invites.GetForGroupAsync(group.Id, cancellationToken), group)
                .Count();
            if (guestsIn >= DetourLimits.MaxConvoyGuests)
                return Result.Error(ValidationKeys.ConvoyInvite.ConvoyFull, DetourLimits.MaxConvoyGuests);

            var (admitted, _) = invite.Admit(caller.Id, time.GetUtcNow());
            if (admitted.IsFailure)
                return admitted;

            var (joined, _) = group.AdmitGuest(caller.Id);
            if (joined.IsFailure)
                return joined;
        }

        var memberIds = group.Members.Select(m => m.UserId).ToArray();
        var usernames = (await users.GetManyAsync(memberIds, cancellationToken))
            .ToDictionary(u => u.Id, u => u.Username);
        return GroupResponseMapper.Map(group, caller.Id, usernames);
    }

    /// <summary>
    /// Link guests still in the convoy, one row per rider (their latest join): neither removed
    /// by the host nor gone on their own. What the host sees, and what the guest cap counts.
    /// </summary>
    private static IEnumerable<ConvoyGuest> CurrentGuests(IEnumerable<ConvoyInvite> invites, Group group) =>
        invites
            .SelectMany(i => i.Guests)
            .Where(g => !g.IsRemoved && group.IsAcceptedMember(g.UserId))
            .GroupBy(g => g.UserId)
            .Select(byUser => byUser.MaxBy(g => g.JoinedAt)!);

    /// <summary>
    /// The host gate. Anyone who is not an accepted member of a convoy gets the same
    /// <c>NotAMember</c> whether it exists or not, as on every group route; only a member is
    /// told they are not the host.
    /// </summary>
    private async Task<Result<Group>> RequireHostAsync(
        Guid callerId,
        Guid groupId,
        CancellationToken cancellationToken)
    {
        var group = await groups.GetWithMembersAsync(groupId, cancellationToken);
        if (group is null || group.Kind != GroupKind.Convoy || !group.IsAcceptedMember(callerId))
            return Result.Error(ValidationKeys.Group.NotAMember);

        return group.OwnerId == callerId ? group : Result.Error(ValidationKeys.Group.NotTheHost);
    }
}

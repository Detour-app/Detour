using Detour.Api.Contracts;
using Detour.Domain;
using Detour.Domain.Friendships;
using Detour.Domain.Routes;
using Detour.Domain.Traces;
using Detour.Domain.Users;
using JV.ResultUtilities;

namespace Detour.Api.Services;

public interface IFriendshipService
{
    Task<FriendsResponse> ListAsync(Guid userId, CancellationToken cancellationToken);

    Task<Result<string>> RequestAsync(User caller, string username, CancellationToken cancellationToken);

    Task<Result<string>> RespondAsync(User caller, string username, bool accept, CancellationToken cancellationToken);

    Task<Result> RemoveAsync(User caller, string username, CancellationToken cancellationToken);

    Task<Result<IReadOnlyList<FriendStatsResponse>>> GetFriendStatsAsync(Guid userId, CancellationToken cancellationToken);

    Task<SharedFogResponse> GetSharedFogAsync(User caller, CancellationToken cancellationToken);
}

public class FriendshipService(
    IFriendshipRepository friendships,
    IUserRepository users,
    IBadgeAwardRepository badges,
    ITraceRepository traces,
    ISharedRouteRepository sharedRoutes) : IFriendshipService
{
    public async Task<FriendsResponse> ListAsync(Guid userId, CancellationToken cancellationToken)
    {
        var rows = await friendships.GetForUserAsync(userId, cancellationToken);
        if (rows.Count == 0)
            return new FriendsResponse([], [], []);

        var names = await ResolveUsernamesAsync(rows.Select(f => f.OtherThan(userId)), cancellationToken);

        List<string> accepted = [], incoming = [], outgoing = [];
        foreach (var friendship in rows)
        {
            if (!names.TryGetValue(friendship.OtherThan(userId), out var name))
                continue;

            if (friendship.IsAccepted)
                accepted.Add(name);
            else if (friendship.RequestedByUserId == userId)
                outgoing.Add(name);
            else
                incoming.Add(name);
        }

        return new FriendsResponse(accepted, incoming, outgoing);
    }

    public async Task<Result<string>> RequestAsync(
        User caller,
        string username,
        CancellationToken cancellationToken)
    {
        var target = await users.GetByUsernameAsync(username.Trim(), cancellationToken);
        if (target is null)
            return Result.Error(ValidationKeys.User.NotFoundByUsername, username);

        if (target.Id == caller.Id)
            return Result.Error(ValidationKeys.Friendship.CannotFriendYourself);

        var existing = await friendships.GetForPairAsync(caller.Id, target.Id, cancellationToken);
        if (existing is not null)
        {
            if (existing.IsAccepted)
                return Result.Ok(FriendshipStatus.Accepted.Name);

            // They asked first; asking back is the same as accepting. Anything else would leave
            // two people who have each asked the other still not friends.
            if (existing.RequestedByUserId != caller.Id)
            {
                var accept = existing.Accept(caller.Id);
                return accept.IsFailure ? accept : Result.Ok(FriendshipStatus.Accepted.Name);
            }

            return Result.Ok(FriendshipStatus.Pending.Name);
        }

        var (result, friendship) = Friendship.Request(caller.Id, target.Id);
        if (result.IsFailure)
            return result;

        await friendships.SaveAsync(friendship, cancellationToken);
        return Result.Ok(FriendshipStatus.Pending.Name);
    }

    public async Task<Result<string>> RespondAsync(
        User caller,
        string username,
        bool accept,
        CancellationToken cancellationToken)
    {
        var target = await users.GetByUsernameAsync(username.Trim(), cancellationToken);
        if (target is null)
            return Result.Error(ValidationKeys.User.NotFoundByUsername, username);

        var friendship = await friendships.GetForPairAsync(caller.Id, target.Id, cancellationToken);
        if (friendship is null || friendship.IsAccepted)
            return Result.Error(ValidationKeys.Friendship.NoPendingRequest);

        if (!accept)
        {
            friendships.Delete(friendship);
            return Result.Ok("Declined");
        }

        var result = friendship.Accept(caller.Id);
        return result.IsFailure ? result : Result.Ok(FriendshipStatus.Accepted.Name);
    }

    public async Task<Result> RemoveAsync(User caller, string username, CancellationToken cancellationToken)
    {
        var target = await users.GetByUsernameAsync(username.Trim(), cancellationToken);
        if (target is null)
            return Result.Error(ValidationKeys.User.NotFoundByUsername, username);

        var friendship = await friendships.GetForPairAsync(caller.Id, target.Id, cancellationToken);
        if (friendship is not null)
            friendships.Delete(friendship);

        // A shared route is places you have been, so losing the friendship takes it back — in
        // both directions, not only what you received.
        await sharedRoutes.DeleteBetweenAsync(caller.Id, target.Id, cancellationToken);

        return Result.Ok();
    }

    /// <summary>
    /// The only capability that returns another rider's data, and it reads nothing but the
    /// aggregates their own device computed. No trip, trace, route or place is reachable here.
    /// </summary>
    public async Task<Result<IReadOnlyList<FriendStatsResponse>>> GetFriendStatsAsync(
        Guid userId,
        CancellationToken cancellationToken)
    {
        var friendIds = await friendships.GetAcceptedFriendIdsAsync(userId, cancellationToken);
        if (friendIds.Count == 0)
            return Result.Ok<IReadOnlyList<FriendStatsResponse>>([]);

        var friends = await users.GetManyAsync(friendIds, cancellationToken);
        var awards = await badges.GetForUsersAsync(friendIds, cancellationToken);

        var response = friends
            .Select(friend => new FriendStatsResponse(
                friend.Username,
                RiderStatsResponse.Map(friend.Stats),
                awards.TryGetValue(friend.Id, out var earned)
                    ? earned.ToDictionary(b => b.BadgeId, b => b.EarnedAtMs)
                    : new Dictionary<string, long>()))
            .OrderByDescending(f => f.Stats.TotalDistanceMeters)
            .ToList();

        return Result.Ok<IReadOnlyList<FriendStatsResponse>>(response);
    }

    /// <summary>
    /// The only capability that returns another rider's traces.
    ///
    /// Two conditions, both required, both checked here: the caller shares their own fog, and
    /// so does each friend whose lines are about to be handed over. A rider who turns sharing
    /// off therefore both stops contributing and stops receiving, which is what makes the trade
    /// legible rather than a one-way take.
    ///
    /// Lines come back unattributed. The union is a map, not a per-friend history.
    /// </summary>
    public async Task<SharedFogResponse> GetSharedFogAsync(User caller, CancellationToken cancellationToken)
    {
        if (!caller.ShareFog)
            return new SharedFogResponse(false, []);

        var friendIds = await friendships.GetAcceptedFriendIdsAsync(caller.Id, cancellationToken);
        if (friendIds.Count == 0)
            return new SharedFogResponse(true, []);

        // The friend's own flag is re-read on the row inside this query, so revoking takes
        // effect on the very next request rather than whenever a cache happens to expire.
        var lines = await traces.GetSharedLinesAsync(friendIds, cancellationToken);
        return new SharedFogResponse(true, lines);
    }

    private async Task<Dictionary<Guid, string>> ResolveUsernamesAsync(
        IEnumerable<Guid> ids,
        CancellationToken cancellationToken)
    {
        var distinct = ids.Distinct().ToArray();
        var rows = await users.GetManyAsync(distinct, cancellationToken);
        return rows.ToDictionary(u => u.Id, u => u.Username);
    }
}

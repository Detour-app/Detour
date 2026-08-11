using System.Text.Json;
using Detour.Api.Contracts;
using Detour.Domain;
using Detour.Domain.Friendships;
using Detour.Domain.Routes;
using Detour.Domain.Users;
using JV.ResultUtilities;

namespace Detour.Api.Services;

public interface IRouteSharingService
{
    Task<Result> ShareAsync(User caller, ShareRouteBody body, CancellationToken cancellationToken);

    Task<SharedRouteInboxResponse> GetInboxAsync(Guid userId, CancellationToken cancellationToken);

    Task<Result> DeleteAsync(Guid userId, Guid routeId, CancellationToken cancellationToken);
}

public class RouteSharingService(
    ISharedRouteRepository sharedRoutes,
    IFriendshipRepository friendships,
    IUserRepository users) : IRouteSharingService
{
    private static readonly JsonSerializerOptions PayloadOptions = new(JsonSerializerDefaults.Web);

    public async Task<Result> ShareAsync(User caller, ShareRouteBody body, CancellationToken cancellationToken)
    {
        var recipient = await users.GetByUsernameAsync(body.To.Trim(), cancellationToken);
        if (recipient is null)
            return Result.Error(ValidationKeys.User.NotFoundByUsername, body.To);

        if (recipient.Id == caller.Id)
            return Result.Error(ValidationKeys.SharedRoute.CannotShareWithYourself);

        // Re-checked on every share, not just once at some earlier point: a route must not be
        // pushable to someone unfriended a moment ago.
        if (!await friendships.AreFriendsAsync(caller.Id, recipient.Id, cancellationToken))
            return Result.Error(ValidationKeys.SharedRoute.RecipientNotAFriend);

        var document = JsonSerializer.Serialize(body.Route, PayloadOptions);
        var stopCount = body.Route.Stops?.Count ?? 0;

        var existing = await sharedRoutes.GetForTripleAsync(
            recipient.Id, caller.Id, body.Route.Id, cancellationToken);

        if (existing is not null)
        {
            // Replaces the recipient's copy and moves it to the top of their inbox, rather than
            // appearing twice with the same name.
            var replaced = existing.Replace(body.Route.Name, document, stopCount);
            if (replaced.IsFailure)
                return replaced;
        }
        else
        {
            var (created, route) = SharedRoute.Create(
                caller.Id, recipient.Id, body.Route.Id, body.Route.Name, document, stopCount);
            if (created.IsFailure)
                return created;

            await sharedRoutes.SaveAsync(route, cancellationToken);
        }

        await sharedRoutes.FlushChangesAsync(cancellationToken);

        // A write cap, not a display cap: the inbox limit alone would only hide the excess while
        // the table kept growing, one payload at a time. Scoped per (recipient, sender), so a
        // friend who shares constantly can only ever push out their own older shares — never
        // crowd a quieter friend's routes out of someone's inbox.
        var overflow = await sharedRoutes.GetOverflowForPairAsync(
            recipient.Id, caller.Id, DetourLimits.MaxSharedRoutesPerPair, cancellationToken);

        foreach (var stale in overflow)
            sharedRoutes.Delete(stale);

        return Result.Ok();
    }

    public async Task<SharedRouteInboxResponse> GetInboxAsync(Guid userId, CancellationToken cancellationToken)
    {
        var rows = await sharedRoutes.GetInboxAsync(userId, DetourLimits.RouteInboxLimit, cancellationToken);
        if (rows.Count == 0)
            return new SharedRouteInboxResponse([]);

        var senders = await users.GetManyAsync(
            [.. rows.Select(r => r.FromUserId).Distinct()], cancellationToken);
        var names = senders.ToDictionary(u => u.Id, u => u.Username);

        return new SharedRouteInboxResponse(
        [
            .. rows.Select(route => new SharedRouteResponse(
                route.Id,
                // Taken from the authenticated sender's account, never read out of the stored
                // document — the same reasoning that has fog sharing trust only the row it read.
                names.GetValueOrDefault(route.FromUserId, string.Empty),
                route.CreatedAt.ToUnixTimeMilliseconds(),
                route.Name,
                JsonSerializer.Deserialize<JsonElement>(route.Payload)))
        ]);
    }

    public async Task<Result> DeleteAsync(Guid userId, Guid routeId, CancellationToken cancellationToken)
    {
        var route = await sharedRoutes.GetAsync(routeId, cancellationToken);

        // Either side may drop it: the recipient clearing their inbox, or the sender
        // un-sharing. A caller who is neither gets the same answer as one asking about a route
        // that does not exist.
        if (route is null || !route.IsVisibleTo(userId))
            return Result.Error(ValidationKeys.SharedRoute.NotFound, routeId);

        sharedRoutes.Delete(route);
        return Result.Ok();
    }
}

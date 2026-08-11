using System.Text;
using JV.ResultUtilities;
using Shared.Database;
using Shared.Domain;

namespace Detour.Domain.Routes;

/// <summary>
/// A planned route one rider sent to a friend.
///
/// Stored opaquely like a trip. Keyed on (recipient, sender, the sender's own route id) so
/// re-sharing an edited route replaces the earlier copy rather than piling up duplicates — and
/// so two friends sharing routes that happen to carry the same client-side id do not collide.
///
/// A route is places you have been, so ending a friendship takes it back: the friendship
/// handler deletes every route between the pair in both directions.
/// </summary>
public sealed class SharedRoute : Entity
{
    public Guid FromUserId { get; private set; }

    public Guid ToUserId { get; private set; }

    /// <summary>The sender's own identifier for the route. Meaningless outside their device.</summary>
    public long ClientRouteId { get; private set; }

    public string Name { get; private set; }

    public string Payload { get; private set; }

    public DateTimeOffset CreatedAt { get; private set; }

    private SharedRoute(Guid fromUserId, Guid toUserId, long clientRouteId, string name, string payload)
    {
        FromUserId = fromUserId;
        ToUserId = toUserId;
        ClientRouteId = clientRouteId;
        Name = name;
        Payload = payload;
        CreatedAt = DateTimeOffset.UtcNow;
    }

    public static Result<SharedRoute> Create(
        Guid fromUserId,
        Guid toUserId,
        long clientRouteId,
        string? name,
        string payload,
        int stopCount)
    {
        if (fromUserId == toUserId)
            return Result.Error(ValidationKeys.SharedRoute.CannotShareWithYourself);

        var validation = Validate(clientRouteId, payload, stopCount);
        if (validation.IsFailure)
            return validation;

        return new SharedRoute(fromUserId, toUserId, clientRouteId, NormalizeName(name), payload);
    }

    /// <summary>Replaces the recipient's copy in place, and moves it to the top of their inbox.</summary>
    public Result Replace(string? name, string payload, int stopCount)
    {
        var validation = Validate(ClientRouteId, payload, stopCount);
        if (validation.IsFailure)
            return validation;

        Name = NormalizeName(name);
        Payload = payload;
        CreatedAt = DateTimeOffset.UtcNow;
        return Result.Ok();
    }

    /// <summary>Either side may drop it: the recipient clearing their inbox, or the sender un-sharing.</summary>
    public bool IsVisibleTo(Guid userId) => FromUserId == userId || ToUserId == userId;

    private static Result Validate(long clientRouteId, string? payload, int stopCount)
    {
        if (clientRouteId == 0)
            return Result.Error(ValidationKeys.SharedRoute.RouteIdRequired);

        if (stopCount < DetourLimits.MinRouteStops)
            return Result.Error(ValidationKeys.SharedRoute.NotEnoughStops, DetourLimits.MinRouteStops);

        if (string.IsNullOrWhiteSpace(payload))
            return Result.Error(ValidationKeys.SharedRoute.RouteIdRequired);

        return Encoding.UTF8.GetByteCount(payload) > DetourLimits.MaxRoutePayloadBytes
            ? Result.Error(ValidationKeys.SharedRoute.PayloadTooLarge, DetourLimits.MaxRoutePayloadBytes)
            : Result.Ok();
    }

    private static string NormalizeName(string? name)
    {
        var trimmed = (name ?? string.Empty).Trim();
        if (trimmed.Length == 0)
            return "Route";

        return trimmed.Length > DetourLimits.DisplayNameMaxLength
            ? trimmed[..DetourLimits.DisplayNameMaxLength]
            : trimmed;
    }

    // EF materialisation.
    private SharedRoute()
    {
        Name = string.Empty;
        Payload = string.Empty;
    }
}

public interface ISharedRouteRepository : IBaseRepository<SharedRoute>
{
    Task<SharedRoute?> GetForTripleAsync(
        Guid toUserId,
        Guid fromUserId,
        long clientRouteId,
        CancellationToken cancellationToken);

    /// <summary>Newest first, capped at the inbox limit.</summary>
    Task<List<SharedRoute>> GetInboxAsync(Guid toUserId, int limit, CancellationToken cancellationToken);

    /// <summary>
    /// Everything above the newest <paramref name="keep"/> for this (recipient, sender) pair.
    /// The write cap: without it the inbox limit would only hide the excess while the table
    /// kept growing, one payload at a time.
    /// </summary>
    Task<List<SharedRoute>> GetOverflowForPairAsync(
        Guid toUserId,
        Guid fromUserId,
        int keep,
        CancellationToken cancellationToken);

    /// <summary>Both directions — unfriending takes back what either side shared.</summary>
    Task<int> DeleteBetweenAsync(Guid a, Guid b, CancellationToken cancellationToken);
}

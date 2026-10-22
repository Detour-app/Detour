using JV.ResultUtilities;
using Shared.Domain;
using Shared.Database;

namespace Detour.Domain.Routes;

/// <summary>
/// A rider's own planned or kept route — the stops, and the geometry the device routed for them.
/// Private to its owner; the one a friend sends is <c>SharedRoute</c>, an inbox item with its
/// own lifetime.
///
/// Opaque payload keyed by the client's own identifier, as <c>SavedPlace</c> is: a rename or
/// re-route replaces the stored copy, and the merged union restores every route after a
/// reinstall (#592).
/// </summary>
public sealed class SavedRoute : Entity
{
    public Guid UserId { get; private set; }

    /// <summary>The client-assigned identifier. Unique per owner, meaningless across owners.</summary>
    public long ClientRouteId { get; private set; }

    public string Payload { get; private set; }

    public DateTimeOffset UpdatedAt { get; private set; }

    private SavedRoute(Guid userId, long clientRouteId, string payload)
    {
        UserId = userId;
        ClientRouteId = clientRouteId;
        Payload = payload;
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    public static Result<SavedRoute> Create(Guid userId, long clientRouteId, string payload)
    {
        var validation = Validate(clientRouteId, payload);
        if (validation.IsFailure)
            return validation;

        return new SavedRoute(userId, clientRouteId, payload);
    }

    public Result Replace(string payload)
    {
        var validation = Validate(ClientRouteId, payload);
        if (validation.IsFailure)
            return validation;

        Payload = payload;
        UpdatedAt = DateTimeOffset.UtcNow;
        return Result.Ok();
    }

    private static Result Validate(long clientRouteId, string? payload)
    {
        if (clientRouteId == 0)
            return Result.Error(ValidationKeys.SavedRoute.IdRequired);

        return string.IsNullOrWhiteSpace(payload)
            ? Result.Error(ValidationKeys.SavedRoute.PayloadRequired)
            : Result.Ok();
    }
}

public interface ISavedRouteRepository : IBaseRepository<SavedRoute>
{
    Task<List<SavedRoute>> GetForUserAsync(Guid userId, CancellationToken cancellationToken);

    Task<List<SavedRoute>> GetByClientIdsAsync(
        Guid userId,
        IReadOnlyCollection<long> clientRouteIds,
        CancellationToken cancellationToken);

    Task<int> DeleteByClientIdsAsync(
        Guid userId,
        IReadOnlyCollection<long> clientRouteIds,
        CancellationToken cancellationToken);
}

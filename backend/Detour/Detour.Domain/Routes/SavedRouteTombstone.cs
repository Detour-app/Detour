using JV.ResultUtilities;
using Shared.Database;
using Shared.Domain;

namespace Detour.Domain.Routes;

/// <summary>
/// The record that a rider deleted the saved route with <see cref="ClientRouteId"/> (#909).
///
/// Kept instead of only dropping the row, as <c>TripTombstone</c> is for rides: a route deleted in
/// the web planner (#636) or on one phone is still held by every other device, which uploads it
/// again on its next sync. With this row the upload is ignored, and the id goes back down in the
/// response so that device drops its copy too.
/// </summary>
public sealed class SavedRouteTombstone : Entity
{
    public Guid UserId { get; private set; }

    /// <summary>The deleted route's client-assigned identifier — the same key saved routes use.</summary>
    public long ClientRouteId { get; private set; }

    /// <summary>When the server first saw the deletion.</summary>
    public DateTimeOffset DeletedAt { get; private set; }

    private SavedRouteTombstone(Guid userId, long clientRouteId)
    {
        UserId = userId;
        ClientRouteId = clientRouteId;
        DeletedAt = DateTimeOffset.UtcNow;
    }

    public static Result<SavedRouteTombstone> Create(Guid userId, long clientRouteId)
    {
        if (clientRouteId == 0)
            return Result.Error(ValidationKeys.SavedRoute.IdRequired);

        return new SavedRouteTombstone(userId, clientRouteId);
    }
}

public interface ISavedRouteTombstoneRepository : IBaseRepository<SavedRouteTombstone>
{
    /// <summary>Every deleted route's client id, ascending.</summary>
    Task<List<long>> GetIdsForUserAsync(Guid userId, CancellationToken cancellationToken);
}

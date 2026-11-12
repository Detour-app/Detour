using System.Text.Json;
using Detour.Api.Contracts;
using Detour.Domain.Routes;
using Detour.Domain.Users;
using JV.ResultUtilities;

namespace Detour.Api.Services;

public interface ISavedRouteService
{
    Task<RouteListResponse> ListAsync(Guid userId, CancellationToken cancellationToken);

    Task<Result<RouteWriteOutcome>> PutAsync(
        Guid userId,
        long clientRouteId,
        RouteDocument body,
        CancellationToken cancellationToken);

    Task<Result> DeleteAsync(Guid userId, long clientRouteId, CancellationToken cancellationToken);
}

/// <summary>What a planner write did, for the controller to answer with.</summary>
public enum RouteWriteStatus
{
    /// <summary>The document is now the stored copy.</summary>
    Stored,

    /// <summary>The stored copy carries a newer edit and was kept.</summary>
    Stale,

    /// <summary>The rider deleted this route; it is not stored again.</summary>
    Deleted,
}

/// <param name="Document">The stored copy after the write; null when <see cref="RouteWriteStatus.Deleted"/>.</param>
public sealed record RouteWriteOutcome(RouteWriteStatus Status, JsonElement? Document);

/// <summary>
/// One route at a time, for the web planner (#912), over the same rows and rules as the
/// <c>/sync</c> merge: the newest edit wins (<see cref="SavedRoute.ReplaceIfNewer"/>), and a
/// deleted route stays deleted (<see cref="SavedRouteTombstone"/>), so a phone's next sync agrees
/// with whatever the planner did.
/// </summary>
public class SavedRouteService(
    ISavedRouteRepository savedRoutes,
    ISavedRouteTombstoneRepository tombstones,
    IRiderLock riderLock) : ISavedRouteService
{
    // The same options SyncService stores a route's document with, so the phone reads a planner
    // write exactly as it reads its own.
    private static readonly JsonSerializerOptions PayloadOptions = new(JsonSerializerDefaults.Web);

    public async Task<RouteListResponse> ListAsync(Guid userId, CancellationToken cancellationToken)
    {
        var routes = await savedRoutes.GetForUserAsync(userId, cancellationToken);
        return new RouteListResponse([.. routes.Select(r => JsonSerializer.Deserialize<JsonElement>(r.Payload))]);
    }

    public async Task<Result<RouteWriteOutcome>> PutAsync(
        Guid userId,
        long clientRouteId,
        RouteDocument body,
        CancellationToken cancellationToken)
    {
        var document = JsonSerializer.Serialize(body, PayloadOptions);
        var editedAtMs = body.EditedAtMs ?? 0;

        var validation = SavedRoute.ValidateEdit(
            clientRouteId, body.Id, editedAtMs, body.Stops?.Count ?? 0, body.Mode, document);
        if (validation.IsFailure)
            return validation;

        // Taken as /sync takes it, so a planner write and a phone's merge of the same route run
        // one after the other instead of racing to insert the same row.
        await riderLock.AcquireAsync(userId, cancellationToken);

        if (await IsDeletedAsync(userId, clientRouteId, cancellationToken))
            return new RouteWriteOutcome(RouteWriteStatus.Deleted, null);

        var stored = (await savedRoutes.GetByClientIdsAsync(userId, [clientRouteId], cancellationToken))
            .SingleOrDefault();

        if (stored is not null)
        {
            var status = stored.ReplaceIfNewer(document, editedAtMs) ? RouteWriteStatus.Stored : RouteWriteStatus.Stale;
            return new RouteWriteOutcome(status, JsonSerializer.Deserialize<JsonElement>(stored.Payload));
        }

        var (created, route) = SavedRoute.Create(userId, clientRouteId, document, editedAtMs);
        if (created.IsFailure)
            return created;

        await savedRoutes.SaveAsync(route, cancellationToken);
        return new RouteWriteOutcome(RouteWriteStatus.Stored, JsonSerializer.Deserialize<JsonElement>(document));
    }

    public async Task<Result> DeleteAsync(Guid userId, long clientRouteId, CancellationToken cancellationToken)
    {
        var (created, tombstone) = SavedRouteTombstone.Create(userId, clientRouteId);
        if (created.IsFailure)
            return created;

        await riderLock.AcquireAsync(userId, cancellationToken);

        await savedRoutes.DeleteByClientIdsAsync(userId, [clientRouteId], cancellationToken);

        // Idempotent: a second delete finds the tombstone and writes nothing.
        if (!await IsDeletedAsync(userId, clientRouteId, cancellationToken))
            await tombstones.SaveAsync(tombstone, cancellationToken);

        return Result.Ok();
    }

    private async Task<bool> IsDeletedAsync(Guid userId, long clientRouteId, CancellationToken cancellationToken) =>
        (await tombstones.GetIdsForUserAsync(userId, cancellationToken)).Contains(clientRouteId);
}

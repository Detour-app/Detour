using System.Text;
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
///
/// A copy can be edited off the phone (the web planner, #636), so a phone that never saw that
/// edit must not undo it on its next sync: see <see cref="ReplaceIfNewer"/>.
/// </summary>
public sealed class SavedRoute : Entity
{
    public Guid UserId { get; private set; }

    /// <summary>The client-assigned identifier. Unique per owner, meaningless across owners.</summary>
    public long ClientRouteId { get; private set; }

    public string Payload { get; private set; }

    public DateTimeOffset UpdatedAt { get; private set; }

    /// <summary>
    /// When the stored copy was last edited, Unix milliseconds, as the editor stamped it in the
    /// document's <c>editedAtMs</c>. 0 for a copy without a stamp.
    /// </summary>
    public long EditedAtMs { get; private set; }

    private SavedRoute(Guid userId, long clientRouteId, string payload, long editedAtMs)
    {
        UserId = userId;
        ClientRouteId = clientRouteId;
        Payload = payload;
        EditedAtMs = editedAtMs;
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    public static Result<SavedRoute> Create(Guid userId, long clientRouteId, string payload, long editedAtMs = 0)
    {
        var validation = Validate(clientRouteId, payload);
        if (validation.IsFailure)
            return validation;

        return new SavedRoute(userId, clientRouteId, payload, Stamp(editedAtMs));
    }

    /// <summary>
    /// Replaces the stored copy when the incoming one is at least as new: <c>editedAtMs</c> &gt;=
    /// <see cref="EditedAtMs"/>. A missing stamp counts as 0, so two unstamped copies keep
    /// last-upload-wins (a rename on the phone still propagates), and an unstamped upload from an
    /// app that predates stamps can never replace a stamped copy.
    ///
    /// Deliberately not <c>Trip.Accepts</c>, which lets an unstamped upload through: a trip has
    /// only ever been edited on a phone, while a route can be edited in the browser, and an old
    /// app re-uploading its stale copy would silently revert that edit. A tie replaces, unlike
    /// <c>TripTitle.KeepNewest</c>, so the unstamped case behaves as it did before stamps.
    /// Returns true when the stored copy changed.
    /// </summary>
    public bool ReplaceIfNewer(string payload, long editedAtMs)
    {
        var incoming = Stamp(editedAtMs);
        if (incoming < EditedAtMs || Validate(ClientRouteId, payload).IsFailure)
            return false;

        Payload = payload;
        EditedAtMs = incoming;
        UpdatedAt = DateTimeOffset.UtcNow;
        return true;
    }

    /// <summary>
    /// The rules for a route written one at a time, by the web planner (#912), on top of the ones
    /// every stored copy meets. Stricter than <c>/sync</c>, which takes whatever a phone holds:
    /// the planner is new, so it can be held to a document the phone will actually show.
    /// <list type="bullet">
    /// <item>the document's own id is the one it is stored under;</item>
    /// <item>it carries an edit stamp, or it could never be ordered against the phone's copy;</item>
    /// <item>at least <see cref="DetourLimits.MinRouteStops"/> stops, below which the app drops it;</item>
    /// <item>a mode — any name, since the app reads one it does not know as a car and a later
    /// app may add modes;</item>
    /// <item>at most <see cref="DetourLimits.MaxRoutePayloadBytes"/>, as a shared route.</item>
    /// </list>
    /// </summary>
    public static Result ValidateEdit(
        long clientRouteId,
        long documentId,
        long editedAtMs,
        int stopCount,
        string? mode,
        string payload)
    {
        var stored = Validate(clientRouteId, payload);
        if (stored.IsFailure)
            return stored;

        if (documentId != clientRouteId)
            return Result.Error(ValidationKeys.SavedRoute.IdMismatch);

        if (editedAtMs <= 0)
            return Result.Error(ValidationKeys.SavedRoute.EditStampRequired);

        if (stopCount < DetourLimits.MinRouteStops)
            return Result.Error(ValidationKeys.SavedRoute.NotEnoughStops, DetourLimits.MinRouteStops);

        if (string.IsNullOrWhiteSpace(mode))
            return Result.Error(ValidationKeys.SavedRoute.ModeRequired);

        return Encoding.UTF8.GetByteCount(payload) > DetourLimits.MaxRoutePayloadBytes
            ? Result.Error(ValidationKeys.SavedRoute.PayloadTooLarge, DetourLimits.MaxRoutePayloadBytes)
            : Result.Ok();
    }

    /// <summary>A negative stamp says nothing a missing one doesn't.</summary>
    private static long Stamp(long editedAtMs) => Math.Max(0, editedAtMs);

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

using JV.ResultUtilities;
using Shared.Database;
using Shared.Domain;

namespace Detour.Domain.Trips;

/// <summary>
/// The record that a rider deleted the ride starting at <see cref="TripStartTimeMs"/> (#778).
///
/// Kept instead of only dropping the trip row: a second device that still holds the ride uploads
/// it on its next sync, and without this row the server would take it back and hand it to every
/// device again. With it, the upload is ignored and the tombstone goes back down in the response,
/// so that device drops its copy too.
/// </summary>
public sealed class TripTombstone : Entity
{
    public Guid UserId { get; private set; }

    /// <summary>The deleted ride's start instant, Unix milliseconds — the same key trips use.</summary>
    public long TripStartTimeMs { get; private set; }

    /// <summary>When the server first saw the deletion.</summary>
    public DateTimeOffset DeletedAt { get; private set; }

    /// <summary>
    /// When the ride's location was dealt with (#895). Null only on rows buried before that
    /// shipped: their trace may still be on the server, and <c>DeletedRideTraceSweep</c> infers
    /// their end once. A tombstone made since is stamped at creation, because the sync that made
    /// it already removed what it had a window for.
    /// </summary>
    public DateTimeOffset? TraceSweptAt { get; private set; }

    private TripTombstone(Guid userId, long tripStartTimeMs)
    {
        UserId = userId;
        TripStartTimeMs = tripStartTimeMs;
        DeletedAt = DateTimeOffset.UtcNow;
        TraceSweptAt = DeletedAt;
    }

    public static Result<TripTombstone> Create(Guid userId, long tripStartTimeMs)
    {
        if (tripStartTimeMs <= 0)
            return Result.Error(ValidationKeys.Trip.StartTimeRequired);

        return new TripTombstone(userId, tripStartTimeMs);
    }

    /// <summary>Records that the inferred window, if any, was removed. Once: a stamped row is never inferred again.</summary>
    public void MarkTraceSwept(DateTimeOffset now) => TraceSweptAt ??= now;
}

public interface ITripTombstoneRepository : IBaseRepository<TripTombstone>
{
    /// <summary>Every deleted ride's start instant, ascending.</summary>
    Task<List<long>> GetStartsForUserAsync(Guid userId, CancellationToken cancellationToken);

    /// <summary>The earliest tombstoned start after <paramref name="afterMs"/>, if any.</summary>
    Task<long?> GetNextStartAsync(Guid userId, long afterMs, CancellationToken cancellationToken);

    /// <summary>Every rider with a tombstone whose trace was never swept (<see cref="TripTombstone.TraceSweptAt"/> null).</summary>
    Task<List<Guid>> GetUsersWithUnsweptAsync(CancellationToken cancellationToken);

    /// <summary>This rider's unswept tombstones, tracked, ascending by start.</summary>
    Task<List<TripTombstone>> GetUnsweptForUserAsync(Guid userId, CancellationToken cancellationToken);
}

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

    private TripTombstone(Guid userId, long tripStartTimeMs)
    {
        UserId = userId;
        TripStartTimeMs = tripStartTimeMs;
        DeletedAt = DateTimeOffset.UtcNow;
    }

    public static Result<TripTombstone> Create(Guid userId, long tripStartTimeMs)
    {
        if (tripStartTimeMs <= 0)
            return Result.Error(ValidationKeys.Trip.StartTimeRequired);

        return new TripTombstone(userId, tripStartTimeMs);
    }
}

public interface ITripTombstoneRepository : IBaseRepository<TripTombstone>
{
    /// <summary>Every deleted ride's start instant, ascending.</summary>
    Task<List<long>> GetStartsForUserAsync(Guid userId, CancellationToken cancellationToken);
}

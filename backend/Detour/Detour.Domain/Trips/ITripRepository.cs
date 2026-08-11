using Shared.Database;

namespace Detour.Domain.Trips;

public interface ITripRepository : IBaseRepository<Trip>
{
    Task<List<Trip>> GetForUserAsync(Guid userId, CancellationToken cancellationToken);

    /// <summary>Newest first, capped — the dashboard's ride list.</summary>
    Task<List<Trip>> GetRecentForUserAsync(Guid userId, int limit, CancellationToken cancellationToken);

    Task<Trip?> GetByStartAsync(Guid userId, long startTimeMs, CancellationToken cancellationToken);

    /// <summary>Every trip named by <paramref name="startTimes"/>, so one sync is one round trip.</summary>
    Task<List<Trip>> GetByStartsAsync(
        Guid userId,
        IReadOnlyCollection<long> startTimes,
        CancellationToken cancellationToken);

    Task<int> DeleteByStartsAsync(
        Guid userId,
        IReadOnlyCollection<long> startTimes,
        CancellationToken cancellationToken);

    Task<int> CountForUserAsync(Guid userId, CancellationToken cancellationToken);

    /// <summary>
    /// The start of the first trip after <paramref name="afterMs"/>, or null when there is none.
    /// Used to cap an unended ride's fallback window so it cannot swallow the ride after it.
    /// </summary>
    Task<long?> GetNextStartAsync(Guid userId, long afterMs, CancellationToken cancellationToken);

    Task<Trip?> GetLatestAsync(Guid userId, CancellationToken cancellationToken);
}

using Shared.Database;

namespace Detour.Domain.Traces;

public interface ITraceRepository : IBaseRepository<Trace>
{
    Task<List<Trace>> GetForUserAsync(Guid userId, CancellationToken cancellationToken);

    /// <summary>
    /// The hashes this user already holds, out of the candidates offered. One round trip per
    /// sync instead of one per line: every sync re-sends the whole history, so this is the hot
    /// path.
    /// </summary>
    Task<HashSet<string>> GetExistingHashesAsync(
        Guid userId,
        IReadOnlyCollection<string> hashes,
        CancellationToken cancellationToken);

    /// <summary>
    /// Trace lines belonging to users who are currently sharing fog. Unattributed on purpose —
    /// the union is a map, not a per-friend history — and filtered at read time so revoking
    /// sharing takes effect on the next request.
    /// </summary>
    Task<List<string>> GetSharedLinesAsync(
        IReadOnlyCollection<Guid> userIds,
        CancellationToken cancellationToken);

    Task<int> CountForUserAsync(Guid userId, CancellationToken cancellationToken);
}

public interface ITrackPointRepository
{
    Task AddRangeAsync(IReadOnlyCollection<TrackPoint> points, CancellationToken cancellationToken);

    /// <summary>
    /// Which instants in <paramref name="fromMs"/>..<paramref name="toMs"/> this user already has
    /// a sample for.
    ///
    /// Consecutive trace lines deliberately share their boundary sample — the device flushes its
    /// buffer mid-drive and re-emits the last point so the drawn trace has no break — and a
    /// point's key is (owner, instant). When the earlier line synced first it is skipped by the
    /// hash check, so its stored sample is invisible to the merge and re-inserting that instant
    /// violates the key. Bounded by a range rather than an IN list: a first full sync unpacks
    /// tens of thousands of points, and asking by value would send them all as parameters.
    /// </summary>
    Task<HashSet<long>> GetExistingTimestampsAsync(
        Guid userId,
        long fromMs,
        long toMs,
        CancellationToken cancellationToken);

    Task<List<TrackPoint>> GetInWindowAsync(
        Guid userId,
        long fromMs,
        long toMs,
        CancellationToken cancellationToken);

    Task<TrackPointAggregate> AggregateWindowAsync(
        Guid userId,
        long fromMs,
        long toMs,
        CancellationToken cancellationToken);

    /// <summary>
    /// Deepest lean ever recorded for this user, or null when nothing has ever measured one.
    /// Null and zero are different answers: "never measured" is not "rode upright".
    /// </summary>
    Task<double?> GetMaxLeanAsync(Guid userId, CancellationToken cancellationToken);

    Task<int> DeleteForUserAsync(Guid userId, CancellationToken cancellationToken);
}

public readonly record struct TrackPointAggregate(int Count, double? MaxLeanDegrees, double? TopSpeedKmh);

using Detour.Domain.Places;
using Detour.Domain.Traces;
using Detour.Domain.Trips;
using Microsoft.EntityFrameworkCore;
using Shared.Database;

namespace Detour.Database.Repositories;

public class TripRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<Trip, DetourDbContext>(factory), ITripRepository
{
    public Task<List<Trip>> GetForUserAsync(Guid userId, CancellationToken cancellationToken) =>
        Set.AsNoTracking()
            .TagWith(Tag(nameof(GetForUserAsync)))
            .Where(t => t.UserId == userId)
            .OrderByDescending(t => t.StartTimeMs)
            .ToListAsync(cancellationToken);

    public Task<List<Trip>> GetRecentForUserAsync(Guid userId, int limit, CancellationToken cancellationToken) =>
        Set.AsNoTracking()
            .TagWith(Tag(nameof(GetRecentForUserAsync)))
            .Where(t => t.UserId == userId)
            .OrderByDescending(t => t.StartTimeMs)
            .Take(limit)
            .ToListAsync(cancellationToken);

    public Task<Trip?> GetByStartAsync(Guid userId, long startTimeMs, CancellationToken cancellationToken) =>
        Set.TagWith(Tag(nameof(GetByStartAsync)))
            .FirstOrDefaultAsync(t => t.UserId == userId && t.StartTimeMs == startTimeMs, cancellationToken);

    public Task<List<Trip>> GetByStartsAsync(
        Guid userId,
        IReadOnlyCollection<long> startTimes,
        CancellationToken cancellationToken)
    {
        if (startTimes.Count == 0)
            return Task.FromResult(new List<Trip>());

        return Set.TagWith(Tag(nameof(GetByStartsAsync)))
            .Where(t => t.UserId == userId && startTimes.Contains(t.StartTimeMs))
            .ToListAsync(cancellationToken);
    }

    public Task<int> DeleteByStartsAsync(
        Guid userId,
        IReadOnlyCollection<long> startTimes,
        CancellationToken cancellationToken)
    {
        if (startTimes.Count == 0)
            return Task.FromResult(0);

        return Set.Where(t => t.UserId == userId && startTimes.Contains(t.StartTimeMs))
            .ExecuteDeleteAsync(cancellationToken);
    }

    public Task<int> CountForUserAsync(Guid userId, CancellationToken cancellationToken) =>
        Set.TagWith(Tag(nameof(CountForUserAsync)))
            .CountAsync(t => t.UserId == userId, cancellationToken);

    public async Task<long?> GetNextStartAsync(Guid userId, long afterMs, CancellationToken cancellationToken)
    {
        var starts = await Set.AsNoTracking()
            .TagWith(Tag(nameof(GetNextStartAsync)))
            .Where(t => t.UserId == userId && t.StartTimeMs > afterMs)
            .OrderBy(t => t.StartTimeMs)
            .Select(t => t.StartTimeMs)
            .Take(1)
            .ToListAsync(cancellationToken);

        return starts.Count == 0 ? null : starts[0];
    }

    public Task<Trip?> GetLatestAsync(Guid userId, CancellationToken cancellationToken) =>
        Set.AsNoTracking()
            .TagWith(Tag(nameof(GetLatestAsync)))
            .Where(t => t.UserId == userId)
            .OrderByDescending(t => t.StartTimeMs)
            .FirstOrDefaultAsync(cancellationToken);
}

public class TraceRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<Trace, DetourDbContext>(factory), ITraceRepository
{
    public Task<List<Trace>> GetForUserAsync(Guid userId, CancellationToken cancellationToken) =>
        Set.AsNoTracking()
            .TagWith(Tag(nameof(GetForUserAsync)))
            .Where(t => t.UserId == userId)
            .ToListAsync(cancellationToken);

    public async Task<HashSet<string>> GetExistingHashesAsync(
        Guid userId,
        IReadOnlyCollection<string> hashes,
        CancellationToken cancellationToken)
    {
        if (hashes.Count == 0)
            return [];

        var found = await Set.AsNoTracking()
            .TagWith(Tag(nameof(GetExistingHashesAsync)))
            .Where(t => t.UserId == userId && hashes.Contains(t.LineHash))
            .Select(t => t.LineHash)
            .ToListAsync(cancellationToken);

        return [.. found];
    }

    public async Task<List<string>> GetSharedLinesAsync(
        IReadOnlyCollection<Guid> userIds,
        CancellationToken cancellationToken)
    {
        if (userIds.Count == 0)
            return [];

        // The share flag is re-read here, on the row, rather than trusted from anything the
        // caller passed in — revoking sharing has to take effect on the very next request.
        return await Context.Traces.AsNoTracking()
            .TagWith(Tag(nameof(GetSharedLinesAsync)))
            .Where(t => userIds.Contains(t.UserId))
            .Join(Context.Users.Where(u => u.ShareFog), t => t.UserId, u => u.Id, (t, _) => t.Line)
            .ToListAsync(cancellationToken);
    }

    public Task<int> CountForUserAsync(Guid userId, CancellationToken cancellationToken) =>
        Set.TagWith(Tag(nameof(CountForUserAsync)))
            .CountAsync(t => t.UserId == userId, cancellationToken);
}

public class TrackPointRepository(ICustomDbContextFactory<DetourDbContext> factory) : ITrackPointRepository
{
    private DetourDbContext Context => factory.CreateDbContext();

    public async Task AddRangeAsync(IReadOnlyCollection<TrackPoint> points, CancellationToken cancellationToken)
    {
        if (points.Count == 0)
            return;

        await Context.TrackPoints.AddRangeAsync(points, cancellationToken);
    }

    public Task<List<TrackPoint>> GetInWindowAsync(
        Guid userId,
        long fromMs,
        long toMs,
        CancellationToken cancellationToken) =>
        Context.TrackPoints.AsNoTracking()
            .TagWith("TrackPointRepository.GetInWindowAsync")
            .Where(p => p.UserId == userId && p.TimestampMs >= fromMs && p.TimestampMs <= toMs)
            .OrderBy(p => p.TimestampMs)
            .ToListAsync(cancellationToken);

    public async Task<TrackPointAggregate> AggregateWindowAsync(
        Guid userId,
        long fromMs,
        long toMs,
        CancellationToken cancellationToken)
    {
        var rows = await Context.TrackPoints.AsNoTracking()
            .TagWith("TrackPointRepository.AggregateWindowAsync")
            .Where(p => p.UserId == userId && p.TimestampMs >= fromMs && p.TimestampMs <= toMs)
            .GroupBy(_ => 1)
            .Select(g => new
            {
                Count = g.Count(),
                // Max of the absolute value: a lean is signed, and the deepest one is the
                // deepest either way.
                MaxLean = g.Max(p => p.LeanDegrees == null ? (double?)null : Math.Abs(p.LeanDegrees.Value)),
                TopSpeed = g.Max(p => p.SpeedKmh)
            })
            .ToListAsync(cancellationToken);

        return rows.Count == 0
            ? new TrackPointAggregate(0, null, null)
            : new TrackPointAggregate(rows[0].Count, rows[0].MaxLean, rows[0].TopSpeed);
    }

    public async Task<double?> GetMaxLeanAsync(Guid userId, CancellationToken cancellationToken)
    {
        var values = await Context.TrackPoints.AsNoTracking()
            .TagWith("TrackPointRepository.GetMaxLeanAsync")
            .Where(p => p.UserId == userId && p.LeanDegrees != null)
            .Select(p => Math.Abs(p.LeanDegrees!.Value))
            .ToListAsync(cancellationToken);

        return values.Count == 0 ? null : values.Max();
    }

    public Task<int> DeleteForUserAsync(Guid userId, CancellationToken cancellationToken) =>
        Context.TrackPoints.Where(p => p.UserId == userId).ExecuteDeleteAsync(cancellationToken);
}

public class SavedPlaceRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<SavedPlace, DetourDbContext>(factory), ISavedPlaceRepository
{
    public Task<List<SavedPlace>> GetForUserAsync(Guid userId, CancellationToken cancellationToken) =>
        Set.AsNoTracking()
            .TagWith(Tag(nameof(GetForUserAsync)))
            .Where(p => p.UserId == userId)
            .OrderBy(p => p.ClientPlaceId)
            .ToListAsync(cancellationToken);

    public Task<List<SavedPlace>> GetByClientIdsAsync(
        Guid userId,
        IReadOnlyCollection<long> clientPlaceIds,
        CancellationToken cancellationToken)
    {
        if (clientPlaceIds.Count == 0)
            return Task.FromResult(new List<SavedPlace>());

        return Set.TagWith(Tag(nameof(GetByClientIdsAsync)))
            .Where(p => p.UserId == userId && clientPlaceIds.Contains(p.ClientPlaceId))
            .ToListAsync(cancellationToken);
    }
}

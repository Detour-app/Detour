using Detour.Domain.Traces;

namespace Detour.Api.Services;

/// <summary>
/// Takes deleted rides' location off the server (#780): the samples in each window, and every
/// line with a point in one. The one copy of that rule, shared by a sync
/// (<see cref="SyncService"/>) and the sweep of rides buried before windows existed
/// (<see cref="DeletedRideTraceSweep"/>).
/// </summary>
public interface IDeletedRideTraceRemover
{
    /// <summary>Removes every window's location for this rider. Returns how many samples went.</summary>
    Task<int> RemoveAsync(Guid userId, IReadOnlyList<TimeWindow> windows, CancellationToken cancellationToken);
}

public sealed class DeletedRideTraceRemover(
    ITraceRepository traces,
    ITraceTombstoneRepository traceTombstones,
    ITrackPointRepository trackPoints) : IDeletedRideTraceRemover
{
    /// <summary>
    /// A line is a flush of the tracker's buffer rather than a ride, so one can straddle a window;
    /// the points either side stay, as new lines. Each cut line leaves a trace tombstone, so a
    /// device still holding it cannot upload it again.
    /// </summary>
    public async Task<int> RemoveAsync(
        Guid userId,
        IReadOnlyList<TimeWindow> windows,
        CancellationToken cancellationToken)
    {
        if (windows.Count == 0)
            return 0;

        // The lookups and bulk deletes below go straight to the database, past anything still pending.
        await traces.FlushChangesAsync(cancellationToken);

        var removedPoints = 0;
        foreach (var window in windows)
            removedPoints += await trackPoints.DeleteInWindowAsync(userId, window.FromMs, window.ToMs, cancellationToken);

        var stored = await traces.GetForUserAsync(userId, cancellationToken);
        var cutHashes = new List<string>();
        var survivors = new Dictionary<string, string>();
        foreach (var trace in stored)
        {
            if (TraceLineReader.WithoutWindows(trace.Line, windows) is not { } runs)
                continue;

            cutHashes.Add(trace.LineHash);
            foreach (var run in runs)
                survivors[Trace.HashOf(run)] = run;
        }

        if (cutHashes.Count == 0)
            return removedPoints;

        await traces.DeleteByHashesAsync(userId, cutHashes, cancellationToken);

        foreach (var hash in cutHashes)
        {
            var (result, tombstone) = TraceTombstone.Create(userId, hash);
            if (result.IsFailure)
                continue; // a stored line always has a hash

            await traceTombstones.SaveAsync(tombstone, cancellationToken);
        }

        // The deleting device's own cut lines arrived in this request, so most survivors are
        // already held. Their samples are too: they sit outside the window, so none were removed.
        var held = stored.Select(t => t.LineHash).ToHashSet();
        var buried = await traceTombstones.GetExistingHashesAsync(userId, survivors.Keys, cancellationToken);
        foreach (var (hash, run) in survivors)
        {
            if (held.Contains(hash) || buried.Contains(hash))
                continue;

            var (result, trace) = Trace.Create(userId, run);
            if (result.IsFailure)
                continue; // a run holds at least two points, so it is never blank

            await traces.SaveAsync(trace, cancellationToken);
        }

        return removedPoints;
    }
}

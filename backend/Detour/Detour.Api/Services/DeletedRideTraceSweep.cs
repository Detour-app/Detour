using Detour.Database;
using Detour.Domain;
using Detour.Domain.Traces;
using Detour.Domain.Trips;
using Detour.Domain.Users;
using Microsoft.EntityFrameworkCore;
using Shared.Database;

namespace Detour.Api.Services;

/// <summary>
/// One pass over the rides deleted before the server removed their location (#895). Their row
/// is gone and no device sent a window, so the trace stayed, and friends with shared fog still
/// got the lines. Each such tombstone (<see cref="TripTombstone.TraceSweptAt"/> null) gets its
/// end inferred once by <see cref="DeletedRideWindow"/>, the location in that window goes through
/// <see cref="IDeletedRideTraceRemover"/>, and the tombstone is stamped either way.
///
/// Each rider runs in its own scope and transaction, under <see cref="IRiderLock"/> so a sync
/// cannot store a cut line again mid-pass. One rider failing rolls back that rider only; the
/// next pass retries them. Separate from <see cref="DeletedRideTraceSweepWorker"/>, which only
/// owns the timer, so a test can run a pass directly.
/// </summary>
public sealed class DeletedRideTraceSweep(
    IServiceScopeFactory scopeFactory,
    TimeProvider clock,
    ILogger<DeletedRideTraceSweep> logger)
{
    /// <summary>What one pass did, summed over every rider it finished.</summary>
    public sealed record Outcome(int TombstonesLookedAt, int WindowsRemoved, int PointsRemoved, int RidersFailed);

    public async Task<Outcome> RunAsync(CancellationToken cancellationToken)
    {
        List<Guid> riders;
        await using (var scope = scopeFactory.CreateAsyncScope())
        {
            riders = await scope.ServiceProvider.GetRequiredService<ITripTombstoneRepository>()
                .GetUsersWithUnsweptAsync(cancellationToken);
        }

        var total = new Outcome(0, 0, 0, 0);
        foreach (var userId in riders)
        {
            try
            {
                var rider = await SweepRiderAsync(userId, cancellationToken);
                total = total with
                {
                    TombstonesLookedAt = total.TombstonesLookedAt + rider.TombstonesLookedAt,
                    WindowsRemoved = total.WindowsRemoved + rider.WindowsRemoved,
                    PointsRemoved = total.PointsRemoved + rider.PointsRemoved,
                };
            }
            catch (Exception exception) when (exception is not OperationCanceledException)
            {
                // No user id: the log must not say whose ride this was.
                logger.LogWarning(exception, "Deleted-ride trace sweep failed for one rider; the next pass retries them");
                total = total with { RidersFailed = total.RidersFailed + 1 };
            }
        }

        logger.LogInformation(
            "Deleted-ride trace sweep: {Tombstones} tombstone(s) looked at, {Windows} window(s) removed, {Points} point(s) removed, {Failed} rider(s) failed",
            total.TombstonesLookedAt, total.WindowsRemoved, total.PointsRemoved, total.RidersFailed);
        return total;
    }

    private async Task<Outcome> SweepRiderAsync(Guid userId, CancellationToken cancellationToken)
    {
        await using var scope = scopeFactory.CreateAsyncScope();
        var services = scope.ServiceProvider;

        // The request transaction middleware doesn't wrap a background pass, so this opens its
        // own on the scope's context, which every repository below shares. Disposed uncommitted
        // on a throw, which rolls this rider back.
        var db = services.GetRequiredService<ICustomDbContextFactory<DetourDbContext>>().CreateDbContext();
        await using var transaction = await db.Database.BeginTransactionAsync(cancellationToken);
        await services.GetRequiredService<IRiderLock>().AcquireAsync(userId, cancellationToken);

        var tombstones = services.GetRequiredService<ITripTombstoneRepository>();
        var trips = services.GetRequiredService<ITripRepository>();
        var trackPoints = services.GetRequiredService<ITrackPointRepository>();

        // Read under the lock, so a pass that waited on a sync sees what it committed.
        var unswept = await tombstones.GetUnsweptForUserAsync(userId, cancellationToken);
        var windows = new List<TimeWindow>();
        foreach (var tombstone in unswept)
        {
            var start = tombstone.TripStartTimeMs;
            var upper = await UpperBoundAsync(userId, start, trips, tombstones, cancellationToken);
            var points = await trackPoints.GetInWindowAsync(userId, start, upper, cancellationToken);

            if (DeletedRideWindow.Infer(start, [.. points.Select(p => p.TimestampMs)], upper) is { } window)
                windows.Add(new TimeWindow(window.FromMs, window.ToMs));
        }

        var removedPoints = await services.GetRequiredService<IDeletedRideTraceRemover>()
            .RemoveAsync(userId, windows, cancellationToken);

        // Stamped whether anything went or not: a tombstone is inferred once, so a line that
        // lands in its old span later is not taken for this ride.
        var now = clock.GetUtcNow();
        foreach (var tombstone in unswept)
            tombstone.MarkTraceSwept(now);

        await db.SaveChangesAsync(cancellationToken);
        await transaction.CommitAsync(cancellationToken);

        return new Outcome(unswept.Count, windows.Count, removedPoints, 0);
    }

    /// <summary>
    /// The last instant the ride could reach: one before the next trip row or the next deleted
    /// ride starts, the convention <see cref="DashboardService"/> uses for an unended ride, capped
    /// at <see cref="DetourLimits.MaxDeletedRideWindow"/>.
    /// </summary>
    private static async Task<long> UpperBoundAsync(
        Guid userId,
        long startMs,
        ITripRepository trips,
        ITripTombstoneRepository tombstones,
        CancellationToken cancellationToken)
    {
        var upper = startMs + (long)DetourLimits.MaxDeletedRideWindow.TotalMilliseconds;

        if (await trips.GetNextStartAsync(userId, startMs, cancellationToken) is { } nextRow)
            upper = Math.Min(upper, nextRow - 1);
        if (await tombstones.GetNextStartAsync(userId, startMs, cancellationToken) is { } nextDeleted)
            upper = Math.Min(upper, nextDeleted - 1);

        return upper;
    }
}

/// <summary>
/// Runs <see cref="DeletedRideTraceSweep"/> once at startup, then hourly. Only rides deleted
/// before #895 deployed are ever swept, so after the first pass most are no-ops; the hourly
/// pass retries a rider whose pass failed.
/// </summary>
internal sealed class DeletedRideTraceSweepWorker(
    DeletedRideTraceSweep sweep,
    TimeProvider clock,
    ILogger<DeletedRideTraceSweepWorker> logger) : BackgroundService
{
    private static readonly TimeSpan Interval = TimeSpan.FromHours(1);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(Interval, clock);

        do
        {
            try
            {
                await sweep.RunAsync(stoppingToken);
            }
            catch (Exception exception) when (exception is not OperationCanceledException)
            {
                // Listing the riders failed; per-rider failures are caught inside the pass.
                logger.LogWarning(exception, "Deleted-ride trace sweep pass failed");
            }
        }
        while (await SafeWaitAsync(timer, stoppingToken));
    }

    private static async Task<bool> SafeWaitAsync(PeriodicTimer timer, CancellationToken stoppingToken)
    {
        try
        {
            return await timer.WaitForNextTickAsync(stoppingToken);
        }
        catch (OperationCanceledException)
        {
            return false;
        }
    }
}

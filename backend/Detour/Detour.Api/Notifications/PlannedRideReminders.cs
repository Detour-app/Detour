using Detour.Domain.PlannedRides;

namespace Detour.Api.Notifications;

/// <summary>
/// One pass of the planned-ride reminder (#759): every ride whose start is now within
/// <see cref="Lead"/> and not yet reminded of is marked reminded, and its going and maybe riders
/// get a <see cref="PushWake.RideReminder"/> wake-ping. The device fetches the ride and posts
/// the notification itself (<c>docs/PUSH.md</c> §11).
///
/// Separate from <see cref="PlannedRideReminderWorker"/>, which only owns the timer, so a test
/// can run a pass at a chosen instant.
/// </summary>
public sealed class PlannedRideReminders(
    IPlannedRideRepository rides,
    IPushQueue pushQueue,
    ILogger<PlannedRideReminders> logger)
{
    /// <summary>How long before the start the reminder goes out. One reminder per start time.</summary>
    public static readonly TimeSpan Lead = TimeSpan.FromHours(1);

    /// <summary>Returns how many rides were reminded of on this pass.</summary>
    public async Task<int> RunAsync(DateTimeOffset now, CancellationToken cancellationToken)
    {
        var due = await rides.GetDueForReminderAsync(now, Lead, cancellationToken);
        if (due.Count == 0)
            return 0;

        var jobs = due.Select(ride => new PushJob(ride.MarkReminded(), PushWake.RideReminder(ride.Id))).ToList();

        // Marked before anything is sent: a pass that fails to save sends nothing and the next
        // pass retries it, where sending first would remind again after every failed save. A
        // ping lost after the save is not retried — the ride is still on the Planned list.
        await rides.FlushChangesAsync(cancellationToken);

        foreach (var job in jobs.Where(j => j.RecipientUserIds.Count > 0))
            pushQueue.TryEnqueue(job);

        logger.LogInformation("Reminded {Count} planned ride(s)", due.Count);
        return due.Count;
    }
}

/// <summary>Runs <see cref="PlannedRideReminders"/> once a minute, each pass in its own scope.</summary>
internal sealed class PlannedRideReminderWorker(
    IServiceScopeFactory scopeFactory,
    TimeProvider clock,
    ILogger<PlannedRideReminderWorker> logger) : BackgroundService
{
    /// <summary>A reminder is at most this late. Its lead is an hour, so a minute is noise.</summary>
    private static readonly TimeSpan Interval = TimeSpan.FromMinutes(1);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(Interval, clock);

        while (await SafeWaitAsync(timer, stoppingToken))
        {
            try
            {
                await using var scope = scopeFactory.CreateAsyncScope();
                var reminders = scope.ServiceProvider.GetRequiredService<PlannedRideReminders>();
                await reminders.RunAsync(clock.GetUtcNow(), stoppingToken);
            }
            catch (Exception exception) when (exception is not OperationCanceledException)
            {
                // A failed pass sent nothing it had not saved; the next tick picks the same
                // rides up again.
                logger.LogWarning(exception, "Planned ride reminder pass failed");
            }
        }
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

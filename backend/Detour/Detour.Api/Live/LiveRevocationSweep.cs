using Detour.Domain.Groups;

namespace Detour.Api.Live;

/// <summary>
/// Re-validates every open connection against stored membership, so access revoked out of band
/// still ends the ride within seconds.
///
/// The relay drops a membership the moment it handles a leave itself, and every voting frame
/// re-checks the group it names. Neither covers a change this process never saw: an administrator
/// removing a member, or a command-line tool in another process. Spec §11 requires those to take
/// effect anyway, which is what this sweep is for — it is the backstop, not the primary path.
/// </summary>
internal sealed class LiveRevocationSweep(
    LiveRelay relay,
    IServiceScopeFactory scopeFactory,
    ILogger<LiveRevocationSweep> logger) : BackgroundService
{
    /// <summary>
    /// "Within seconds" per spec §11. Short enough that a revoked rider stops seeing peers before
    /// they would notice, long enough that an idle server with a handful of connections is not
    /// querying continuously.
    /// </summary>
    private static readonly TimeSpan Interval = TimeSpan.FromSeconds(15);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(Interval);

        while (await SafeWaitAsync(timer, stoppingToken))
        {
            try
            {
                await SweepAsync(stoppingToken);
            }
            catch (Exception exception) when (exception is not OperationCanceledException)
            {
                // A failed sweep must not take the relay down with it — every connection it would
                // have checked is simply re-checked on the next tick.
                logger.LogWarning(exception, "Live revocation sweep failed");
            }
        }
    }

    private async Task SweepAsync(CancellationToken cancellationToken)
    {
        var userIds = relay.ConnectedUserIds;
        if (userIds.Count == 0)
            return;

        await using var scope = scopeFactory.CreateAsyncScope();
        var groups = scope.ServiceProvider.GetRequiredService<IGroupRepository>();

        foreach (var userId in userIds)
        {
            foreach (var groupId in relay.GroupsFor(userId))
            {
                var membership = await groups.GetAcceptedMembershipAsync(groupId, userId, cancellationToken);
                if (membership is not null)
                    continue;

                // Each pairing is judged on its own: a connection can go stale in one group and
                // stay perfectly valid in another, and only losing the last one closes the socket.
                logger.LogDebug("Evicting {UserId} from {GroupId}: membership no longer valid", userId, groupId);
                await relay.EvictAsync(userId, groupId, cancellationToken);
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

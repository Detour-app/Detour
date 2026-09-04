namespace Detour.Api.Notifications;

internal sealed class PushDispatchWorker(
    IPushQueue queue,
    IServiceScopeFactory scopeFactory,
    ILogger<PushDispatchWorker> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await foreach (var job in queue.ReadAllAsync(stoppingToken))
        {
            try
            {
                await using var scope = scopeFactory.CreateAsyncScope();
                var dispatcher = scope.ServiceProvider.GetRequiredService<PushDispatcher>();
                await dispatcher.DispatchAsync(job, stoppingToken);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                // One failed job must not stop the drain — the next event's
                // wake-ping is unaffected, and this one's recipients catch up
                // on foreground.
                logger.LogWarning(ex, "Push dispatch failed for collapseKey {CollapseKey}", job.CollapseKey);
            }
        }
    }
}

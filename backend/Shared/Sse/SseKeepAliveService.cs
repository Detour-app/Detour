using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Shared.Sse;

/// <summary>
/// Sends periodic keep-alive events to prevent proxy/load-balancer timeouts on SSE connections.
/// </summary>
public class SseKeepAliveService(
    ISseEventBus eventBus,
    ILogger<SseKeepAliveService> logger) : BackgroundService
{
    private static readonly TimeSpan Interval = TimeSpan.FromSeconds(30);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(Interval);
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            try
            {
                if (eventBus is SseEventBus bus)
                {
                    foreach (var channel in bus.ActiveChannels)
                    {
                        await eventBus.PublishAsync(channel, new SseEvent("keepalive", ""), stoppingToken);
                    }
                }
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                logger.LogWarning(ex, "Error sending SSE keep-alive");
            }
        }
    }
}
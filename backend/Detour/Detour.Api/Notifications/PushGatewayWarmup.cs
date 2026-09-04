using Microsoft.Extensions.Hosting;

namespace Detour.Api.Notifications;

/// <summary>
/// Forces every <see cref="IPushGateway"/> singleton to be constructed at host
/// start rather than lazily on the first push job. Two reasons: each gateway's
/// "not configured" warning then lands at startup where an operator sees it, and a
/// credential that is set but unreadable (a bad <c>.p8</c> path) throws during
/// startup and fails the deploy instead of silently no-opping every wake-ping.
/// </summary>
internal sealed class PushGatewayWarmup(IEnumerable<IPushGateway> gateways) : IHostedService
{
    public Task StartAsync(CancellationToken cancellationToken)
    {
        // Resolving this hosted service resolved every gateway singleton with it —
        // enumerating is enough to be sure none was deferred.
        _ = gateways.Count();
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;
}

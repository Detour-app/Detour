using Microsoft.Extensions.Hosting;

namespace Detour.Api.Notifications;

/// <summary>
/// Forces the <see cref="IFcmGateway"/> singleton to be constructed at host start
/// rather than lazily on the first push job. Two reasons: the "credentials not
/// set" warning then lands at startup where an operator sees it, and a bad
/// credentials path throws during startup and fails the deploy (CT125) instead
/// of silently no-opping every wake-ping.
/// </summary>
internal sealed class FcmGatewayWarmup(IFcmGateway gateway) : IHostedService
{
    public Task StartAsync(CancellationToken cancellationToken)
    {
        // The injected gateway has already been constructed by the time this runs —
        // resolving this hosted service resolves it. Nothing else to do.
        _ = gateway;
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;
}

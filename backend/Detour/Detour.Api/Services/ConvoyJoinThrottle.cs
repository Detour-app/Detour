using System.Threading.RateLimiting;
using Detour.Domain;

namespace Detour.Api.Services;

/// <summary>
/// Caps join-by-link attempts per link and per address, on top of the global per-IP and
/// per-user buckets.
///
/// Not an <c>[EnableRateLimiting]</c> policy: an endpoint gets one policy and one partition key,
/// and this needs two — the address, so one host cannot sweep tokens, and the presented token
/// itself, read from the body the middleware never sees. 256-bit tokens make guessing hopeless
/// already; this keeps a leaked link from being hammered and bounds the cost of trying.
/// </summary>
public sealed class ConvoyJoinThrottle : IDisposable
{
    private readonly PartitionedRateLimiter<string> _perToken =
        FixedWindow(DetourLimits.ConvoyJoinsPerToken);

    private readonly PartitionedRateLimiter<string> _perIp =
        FixedWindow(DetourLimits.ConvoyJoinsPerIp);

    /// <summary>
    /// Spends one attempt from both buckets. The address is checked first so a flood of
    /// distinct made-up tokens is stopped before it creates a partition for each.
    /// </summary>
    public bool TryAcquire(string tokenHash, string clientIp)
    {
        using var ipLease = _perIp.AttemptAcquire(clientIp);
        if (!ipLease.IsAcquired)
            return false;

        using var tokenLease = _perToken.AttemptAcquire(tokenHash);
        return tokenLease.IsAcquired;
    }

    public void Dispose()
    {
        _perToken.Dispose();
        _perIp.Dispose();
    }

    private static PartitionedRateLimiter<string> FixedWindow(int permits) =>
        PartitionedRateLimiter.Create<string, string>(key =>
            RateLimitPartition.GetFixedWindowLimiter(key, _ => new FixedWindowRateLimiterOptions
            {
                PermitLimit = permits,
                Window = DetourLimits.ConvoyJoinWindow,
                QueueLimit = 0,
                AutoReplenishment = true,
            }));
}

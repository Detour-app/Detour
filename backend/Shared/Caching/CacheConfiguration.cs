namespace Shared.Caching;

public class CacheConfiguration
{
    public bool EnableLogger { get; set; }

    /// <summary>
    /// StackExchange.Redis connection string for the L2 cache and its backplane. Empty means
    /// memory-only, which is a correct single-instance deployment.
    /// </summary>
    public string? RedisConnectionString { get; set; }

    /// <summary>
    /// Default cache entry duration. Defaults to <see cref="CacheTimes.Short"/> (5 min).
    /// </summary>
    public TimeSpan Duration { get; set; } = CacheTimes.Short;

    /// <summary>
    /// Maximum duration a stale value is served when fail-safe activates.
    /// Defaults to <see cref="CacheTimes.Medium"/> (30 min).
    /// </summary>
    public TimeSpan FailSafeMaxDuration { get; set; } = CacheTimes.Medium;

    /// <summary>
    /// How long an expired value reused by fail-safe is considered non-expired,
    /// to avoid repeated database lookups. Defaults to 30 seconds.
    /// </summary>
    public TimeSpan FailSafeThrottleDuration { get; set; } = TimeSpan.FromSeconds(30);
}

using Microsoft.Extensions.Caching.Hybrid;
using Microsoft.Extensions.Caching.Memory;
using Microsoft.Extensions.Caching.StackExchangeRedis;
using Microsoft.Extensions.DependencyInjection;
using ZiggyCreatures.Caching.Fusion;
using ZiggyCreatures.Caching.Fusion.Backplane.StackExchangeRedis;
using ZiggyCreatures.Caching.Fusion.Serialization.SystemTextJson;

namespace Shared.Caching;

public static class CacheInstaller
{
    /// <summary>
    /// Registers the two-level cache. L1 is in-process memory; L2 is Redis when a connection
    /// string is configured, with a backplane so an eviction on one instance is not silently
    /// ignored by the others. With no connection string it degrades to memory-only — which is a
    /// correct single-instance deployment, not a broken one.
    /// </summary>
    public static IServiceCollection AddCaching(this IServiceCollection services, CacheConfiguration config)
    {
        // Idempotent — skip if HybridCache is already registered (e.g. by an earlier installer).
        if (services.Any(d => d.ServiceType == typeof(HybridCache)))
            return services;

        var fusionCacheBuilder = services.AddFusionCache()
            .WithDefaultEntryOptions(new FusionCacheEntryOptions
            {
                Duration = config.Duration,

                // FAIL-SAFE OPTIONS
                IsFailSafeEnabled = true,
                // How long a value stays usable after its logical expiration.
                FailSafeMaxDuration = config.FailSafeMaxDuration,
                // How long an expired value served by fail-safe is treated as temporarily
                // non-expired, so a cold backend isn't hit once per request.
                FailSafeThrottleDuration = config.FailSafeThrottleDuration
            })
            .WithSerializer(new FusionCacheSystemTextJsonSerializer())
            .WithMemoryCache(new MemoryCache(new MemoryCacheOptions()))
            .AsHybridCache();

        if (string.IsNullOrWhiteSpace(config.RedisConnectionString))
        {
            fusionCacheBuilder
                .WithoutDistributedCache()
                .WithoutBackplane();
        }
        else
        {
            fusionCacheBuilder
                .WithDistributedCache(new RedisCache(new RedisCacheOptions
                {
                    Configuration = config.RedisConnectionString
                }))
                .WithBackplane(new RedisBackplane(new RedisBackplaneOptions
                {
                    Configuration = config.RedisConnectionString
                }));
        }

        if (!config.EnableLogger)
            fusionCacheBuilder.WithoutLogger(); // enabling the logger adds significant overhead

        return services;
    }
}

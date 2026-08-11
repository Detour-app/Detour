using Microsoft.Extensions.Caching.Hybrid;

namespace Shared.Caching;

/// <summary>
/// Provides extension methods for the HybridCache class.
/// </summary>
public static class CacheExtensions
{
    /// <summary>
    /// Method to simplify cache retrieval operations without writing to cache.
    /// </summary>
    public static async ValueTask<TValue> GetAsync<TValue>(this HybridCache cache, string key,
        CancellationToken ct = default)
    {
        return await cache.GetOrCreateAsync<TValue>(key,
            static _ => new ValueTask<TValue>(),
            new HybridCacheEntryOptions
            {
                Flags = HybridCacheEntryFlags.DisableLocalCacheWrite |
                        HybridCacheEntryFlags.DisableDistributedCacheWrite
            },
            cancellationToken: ct);
    }
}
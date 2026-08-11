namespace Shared.Caching;

public static class CacheTimes
{
    public static readonly TimeSpan Short = TimeSpan.FromMinutes(5);
    public static readonly TimeSpan Medium = TimeSpan.FromMinutes(30);
    public static readonly TimeSpan Long = TimeSpan.FromHours(1);
    public static readonly TimeSpan Day = TimeSpan.FromDays(1);
    public static readonly TimeSpan Month = TimeSpan.FromDays(30);

    // Compiler API key authentication cache — argon2id verify is expensive (m=64MiB, t=3,
    // p=2 ≈ 300-500ms CPU) so we cache the verified key aggressively. Two minutes bounds the
    // window during which a revoked key would keep authenticating; the cache is keyed by
    // SHA-256(plaintext), so we can't invalidate by KeyId on revoke.
    public static readonly TimeSpan CompilerApiKeyAuth = TimeSpan.FromMinutes(2);
}
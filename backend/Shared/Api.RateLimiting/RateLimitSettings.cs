namespace Shared.Api.RateLimiting;

/// <summary>
/// Configurable token-bucket budgets for each rate-limit tier.
/// Bind from <c>appsettings.json</c> section <c>RateLimit</c>.
/// </summary>
public sealed class RateLimitSettings
{
    public RateLimitTier Ip { get; set; } = new()
    {
        TokenLimit = 400,
        TokensPerPeriod = 150,
        ReplenishmentPeriodSeconds = 10
    };

    public RateLimitTier User { get; set; } = new()
    {
        TokenLimit = 600,
        TokensPerPeriod = 200,
        ReplenishmentPeriodSeconds = 10
    };

    /// <summary>
    /// Per-API-key budget, keyed on the key's <c>jti</c> claim. Deliberately smaller than
    /// <see cref="User"/>: every key a user owns would otherwise share one bucket with that
    /// user's app session, so a runaway dashboard poll on one key throttled the owner's own
    /// phone and starved their other keys.
    /// </summary>
    public RateLimitTier ApiKey { get; set; } = new()
    {
        TokenLimit = 300,
        TokensPerPeriod = 100,
        ReplenishmentPeriodSeconds = 10
    };

    /// <summary>
    /// Per-IP budget applied on top of <see cref="Ip"/> to the endpoints an unauthenticated
    /// caller can reach. Deliberately tiny: the legacy server capped auth attempts at 10 per
    /// 5 minutes per address, and Keycloak's own brute-force detection is the other half of
    /// that promise. Applied by name via <c>[EnableRateLimiting(RateLimitPolicies.Anonymous)]</c>.
    /// </summary>
    public RateLimitTier Anonymous { get; set; } = new()
    {
        TokenLimit = 20,
        TokensPerPeriod = 10,
        ReplenishmentPeriodSeconds = 60
    };
}

public sealed class RateLimitTier
{
    public int TokenLimit { get; set; }
    public int TokensPerPeriod { get; set; }
    public int ReplenishmentPeriodSeconds { get; set; }

    internal TimeSpan ReplenishmentPeriod => TimeSpan.FromSeconds(ReplenishmentPeriodSeconds);
}

public static class RateLimitPolicies
{
    /// <summary>Named policy for endpoints reachable without a bearer token.</summary>
    public const string Anonymous = "anonymous";
}

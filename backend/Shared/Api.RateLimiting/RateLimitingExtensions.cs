using System.Globalization;
using System.Security.Claims;
using System.Threading.RateLimiting;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc.Infrastructure;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;

namespace Shared.Api.RateLimiting;

/// <summary>
/// Configures rate limiting for the API.
///
/// Design goals:
///   - Generous burst capacity so normal app usage never gets throttled.
///   - Fail fast (no queue) when a script hammers the API in a tight loop.
///   - Three chained global tiers so anonymous floods cannot exhaust everyone else's budget:
///       1. Per-IP token bucket (cheap first gate, and the only gate for anonymous traffic).
///       2. Per-user token bucket (only applies to authenticated requests).
///       3. Per-API-key token bucket (only applies when the principal was authenticated by the
///          <c>ApiKey</c> scheme; gives each dashboard key its own budget below the user tier
///          so a runaway poll on one key cannot starve the owner's phone).
///   - Plus a named <see cref="RateLimitPolicies.Anonymous"/> policy, opt-in per endpoint, for
///     the handful of routes an unauthenticated caller can reach.
///
/// Token bucket is the right primitive here: it allows short bursts up to TokenLimit and then
/// gates sustained throughput at TokensPerPeriod/ReplenishmentPeriod. With QueueLimit = 0 the
/// limiter rejects immediately instead of queuing — that is the "fail fast" behavior we want.
///
/// Pipeline note: <see cref="RateLimiterApplicationBuilderExtensions.UseRateLimiter(IApplicationBuilder)"/>
/// must run AFTER authentication so the user-tier partition key (sub claim) is populated.
/// </summary>
public static class RateLimitingExtensions
{
    /// <summary>
    /// Name of the API-key authentication scheme. Spelled out rather than referenced so this
    /// library keeps its zero-<c>ProjectReference</c> footprint.
    /// </summary>
    private const string DefaultApiKeyScheme = "ApiKey";

    /// <summary>Claim carrying the API key id. Fixed by RFC 7519.</summary>
    private const string ApiKeyIdClaim = "jti";

    /// <summary>
    /// Registers the chained global limiter (per-IP + per-user + per-API-key token buckets) plus
    /// the named anonymous policy. Call <c>UseRateLimiter()</c> on the pipeline after
    /// authentication middleware.
    ///
    /// Tier budgets are read from <see cref="RateLimitSettings"/> (bound from the <c>RateLimit</c>
    /// configuration section) and captured once at registration time — runtime config changes take
    /// effect on the next restart.
    /// </summary>
    public static IServiceCollection AddDefaultRateLimit(
        this IServiceCollection services,
        string apiKeyScheme = DefaultApiKeyScheme)
    {
        services.AddRateLimiter(options =>
        {
            options.AddDefaultOptions();

            var settings = services
                .BuildServiceProvider()
                .GetService<IOptions<RateLimitSettings>>()?.Value
                ?? new RateLimitSettings();

            options.GlobalLimiter = PartitionedRateLimiter.CreateChained(
                BuildIpPartition(settings.Ip),
                BuildUserPartition(settings.User),
                BuildApiKeyPartition(settings.ApiKey, apiKeyScheme));

            var anonymous = settings.Anonymous;
            options.AddPolicy(RateLimitPolicies.Anonymous, context =>
                RateLimitPartition.GetTokenBucketLimiter(
                    ResolveClientIp(context),
                    _ => new TokenBucketRateLimiterOptions
                    {
                        TokenLimit = anonymous.TokenLimit,
                        TokensPerPeriod = anonymous.TokensPerPeriod,
                        ReplenishmentPeriod = anonymous.ReplenishmentPeriod,
                        QueueLimit = 0,
                        AutoReplenishment = true,
                    }));
        });

        return services;
    }

    internal static PartitionedRateLimiter<HttpContext> BuildIpPartition(RateLimitTier tier) =>
        PartitionedRateLimiter.Create<HttpContext, string>(context =>
            RateLimitPartition.GetTokenBucketLimiter(
                ResolveClientIp(context),
                _ => Bucket(tier)));

    internal static PartitionedRateLimiter<HttpContext> BuildUserPartition(RateLimitTier tier) =>
        PartitionedRateLimiter.Create<HttpContext, string>(context =>
        {
            var userId = ResolveUserId(context);
            // Anonymous traffic is already gated by the per-IP partition; skip the user tier.
            return string.IsNullOrWhiteSpace(userId)
                ? RateLimitPartition.GetNoLimiter("anonymous")
                : RateLimitPartition.GetTokenBucketLimiter(userId, _ => Bucket(tier));
        });

    internal static PartitionedRateLimiter<HttpContext> BuildApiKeyPartition(
        RateLimitTier tier,
        string apiKeyScheme = DefaultApiKeyScheme) =>
        PartitionedRateLimiter.Create<HttpContext, string>(context =>
        {
            // Gate on the authenticating scheme, not on jti presence — Keycloak access tokens
            // carry a jti too (per spec). AuthenticationType comes from the handler's own
            // ClaimsIdentity and cannot be spoofed by token content.
            var isApiKey = context.User.Identities
                .Any(i => i.AuthenticationType == apiKeyScheme && i.IsAuthenticated);
            if (!isApiKey)
                return RateLimitPartition.GetNoLimiter("not-an-api-key");

            var keyId = context.User.FindFirstValue(ApiKeyIdClaim);
            return string.IsNullOrWhiteSpace(keyId)
                ? RateLimitPartition.GetNoLimiter("not-an-api-key")
                : RateLimitPartition.GetTokenBucketLimiter(keyId, _ => Bucket(tier));
        });

    private static TokenBucketRateLimiterOptions Bucket(RateLimitTier tier) => new()
    {
        TokenLimit = tier.TokenLimit,
        TokensPerPeriod = tier.TokensPerPeriod,
        ReplenishmentPeriod = tier.ReplenishmentPeriod,
        QueueLimit = 0,
        AutoReplenishment = true,
    };

    private static string ResolveClientIp(HttpContext context)
    {
        // The connection IP, and only ever that. Behind a proxy it is the forwarded-headers
        // middleware that rewrites RemoteIpAddress before this runs — see
        // Shared.Api.ForwardedHeaders, which installs it only when the operator has named a
        // trusted proxy. Reading a header here instead would be the legacy server's
        // TRUST_CF_HEADER switch, which let any caller pick its own partition key, and must
        // not be reintroduced.
        var ip = context.Connection.RemoteIpAddress?.ToString();
        return string.IsNullOrWhiteSpace(ip) ? "unknown" : ip;
    }

    private static string? ResolveUserId(HttpContext context) =>
        context.User.FindFirstValue(ClaimTypes.NameIdentifier)
        ?? context.User.FindFirstValue("sub");

    public static void AddDefaultOptions(this RateLimiterOptions options)
    {
        options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;

        options.OnRejected = async (context, token) =>
        {
            var problemDetailsFactory =
                context.HttpContext.RequestServices.GetRequiredService<ProblemDetailsFactory>();

            string detail;
            if (context.Lease.TryGetMetadata(MetadataName.RetryAfter, out TimeSpan retryAfter))
            {
                context.HttpContext.Response.Headers.RetryAfter =
                    retryAfter.TotalSeconds.ToString(CultureInfo.InvariantCulture);
                detail = $"Too many requests. Please try again after {retryAfter.TotalSeconds} seconds.";
            }
            else
            {
                detail = "Too many requests. Please slow down and try again shortly.";
            }

            var problemDetails = problemDetailsFactory.CreateProblemDetails(
                context.HttpContext,
                StatusCodes.Status429TooManyRequests,
                "Too many requests",
                detail: detail);

            await context.HttpContext.Response.WriteAsJsonAsync(problemDetails, cancellationToken: token);
        };
    }
}
